package io.github.originosfcmguardian

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.MAX_TARGET_SIGNING_HISTORY
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.targetCertificateDigests
import io.github.originosfcmguardian.core.isUserZeroTargetAppUid
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.StoppedObservationCodec
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.recovery.ExternalShizukuRecoveryTransport
import io.github.originosfcmguardian.recovery.IStoppedRecoveryJournal
import io.github.originosfcmguardian.recovery.RecoveryBindingLifecycle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in reads of one fixed rotation fixture. Never selects, grants, prepares or claims a write. */
@LargeTest
class SignerRotationReadDeviceTest {
    @Suppress("DEPRECATION")
    @Test(timeout = 60_000)
    fun captureInitialIdentity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guardianRunSignerRotationRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(FIXTURE_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(FIXTURE_PACKAGE, info.packageName)
        assertEquals(1L, info.longVersionCode)
        val application = requireNotNull(info.applicationInfo)
        assertEquals(FIXTURE_PACKAGE, application.packageName)
        require(isUserZeroTargetAppUid(application.uid) && info.firstInstallTime > 0)
        val signing = requireNotNull(info.signingInfo)
        assertFalse(signing.hasMultipleSigners())
        val certificates = requireNotNull(signing.apkContentsSigners)
        assertEquals(1, certificates.size)
        val current = requireNotNull(targetCertificateDigests(certificates.map { it.toByteArray() }, 1))
        val history = requireNotNull(signing.signingCertificateHistory)
        assertEquals(1, history.size)
        assertEquals(current, targetCertificateDigests(history.map { it.toByteArray() }, 1))
        val initial = SelectedTargetIdentity(FIXTURE_PACKAGE, application.uid, info.firstInstallTime, current)
        val probe = TargetPackageProbe(context)
        assertEquals(initial, probe.identity(FIXTURE_PACKAGE))
        assertTrue(probe.isRecoveryCandidate(initial))
        assertEquals(listOf(FIXTURE_PACKAGE), context.packageManager.getPackagesForUid(initial.uid)?.toList())
        // Public certificate/installation evidence only; the parent keeps these baseline arguments ephemeral.
        instrumentation.sendStatus(0, Bundle().apply {
            putString("guardian.rotation.baseline.signerSha256", current.single())
            putInt("guardian.rotation.baseline.uid", initial.uid)
            putLong("guardian.rotation.baseline.firstInstallTime", initial.firstInstallTime)
            putBoolean("guardian.rotation.baseline.singleSigner", true)
            putBoolean("guardian.rotation.write", false)
        })
    }

    @Suppress("DEPRECATION")
    @Test(timeout = 60_000)
    fun rotatedFixtureRetainsOnlyTheVerifiedExistingBaseline() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("guardianRunSignerRotationRead") == "true")
        val oldSigner = requireNotNull(arguments.getString("oldSignerSha256"))
        require(Regex("[0-9a-f]{64}").matches(oldSigner)) { "Invalid baseline signer argument" }
        val uidArgument = requireNotNull(arguments.getString("baselineUid"))
        require(Regex("[1-9][0-9]{0,4}").matches(uidArgument)) { "Invalid baseline UID argument" }
        val uid = requireNotNull(uidArgument.toIntOrNull())
        require(isUserZeroTargetAppUid(uid)) { "Invalid baseline UID argument" }
        val installArgument = requireNotNull(arguments.getString("baselineFirstInstallTime"))
        require(Regex("[1-9][0-9]{0,18}").matches(installArgument)) { "Invalid baseline install-time argument" }
        val installedAt = requireNotNull(installArgument.toLongOrNull())
        val expected = SelectedTargetIdentity(FIXTURE_PACKAGE, uid, installedAt, listOf(oldSigner))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = TargetPackageProbe(context)
        val before = requireNotNull(probe.packageSnapshot(FIXTURE_PACKAGE))
        assertEquals(uid, before.identity.uid)
        assertEquals(installedAt, before.identity.firstInstallTime)
        assertEquals(1, before.identity.signerSha256.size)
        assertNotEquals(expected, before.identity)
        val currentSigner = before.identity.signerSha256.single()
        assertNotEquals(oldSigner, currentSigner)

        // Independently inspect the OS response; the retained expected identity is not raw evidence.
        val info = context.packageManager.getPackageInfo(FIXTURE_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals(FIXTURE_PACKAGE, info.packageName)
        val application = requireNotNull(info.applicationInfo)
        assertEquals(FIXTURE_PACKAGE, application.packageName)
        assertEquals(uid, application.uid)
        assertEquals(installedAt, info.firstInstallTime)
        val signing = requireNotNull(info.signingInfo)
        assertFalse(signing.hasMultipleSigners())
        val certificates = requireNotNull(signing.apkContentsSigners)
        assertEquals(1, certificates.size)
        val currentDigests = requireNotNull(targetCertificateDigests(certificates.map { it.toByteArray() }, 1))
        assertEquals(before.identity.signerSha256, currentDigests)
        val history = requireNotNull(signing.signingCertificateHistory)
        require(history.size in 1..MAX_TARGET_SIGNING_HISTORY)
        val historyDigests = requireNotNull(targetCertificateDigests(history.map { it.toByteArray() }, MAX_TARGET_SIGNING_HISTORY))
        assertTrue(oldSigner in historyDigests && currentSigner in historyDigests)
        assertEquals(listOf(FIXTURE_PACKAGE), context.packageManager.getPackagesForUid(uid)?.toList())

        assertEquals(expected, probe.identity(expected))
        assertTrue(probe.isRecoveryCandidate(expected))
        val stopped = probe.stoppedSnapshot(expected)
        assertEquals(if (before.stopped) TargetStoppedStatus.STOPPED else TargetStoppedStatus.NOT_STOPPED, stopped.status)
        // At most 32 history entries: one of these 33 deterministic digests is absent from it.
        val wrongSigner = (0..MAX_TARGET_SIGNING_HISTORY).asSequence()
            .map { it.toString(16).padStart(64, '0') }
            .first { it != oldSigner && it != currentSigner && it !in historyDigests }
        val wrongExpected = expected.copy(signerSha256 = listOf(wrongSigner))
        assertEquals(before.identity, probe.identity(wrongExpected))
        assertNotEquals(wrongExpected, probe.identity(wrongExpected))
        assertFalse(probe.isRecoveryCandidate(wrongExpected))

        val live = AtomicBoolean(true)
        val claims = AtomicInteger(0)
        val callback = object : IStoppedRecoveryJournal.Stub() {
            override fun isSessionActive() = Binder.getCallingUid() == 2000 && live.get()
            override fun claim(requestId: Long): Boolean { claims.incrementAndGet(); return false }
        }
        val runtime = ShizukuRuntime(context)
        runtime.start()
        val lifecycle = RecoveryBindingLifecycle(ExternalShizukuRecoveryTransport(context).newBinding(),
            live::get, live::get, { live.set(false) }, { it() })
        try {
            val remote = lifecycle.bind()
            assertEquals(2000, remote.serviceUid)
            val raw = remote.observeTarget(1, TargetSelectionCodec.encode(listOf(expected)), callback)
            assertTrue(raw.startsWith("2|"))
            val observation = StoppedObservationCodec.decode(raw, 1, expected)
            assertTrue("Expected qualified fixture observation", observation is StoppedTargetObservation.Known)
            observation as StoppedTargetObservation.Known
            val after = requireNotNull(probe.packageSnapshot(FIXTURE_PACKAGE))
            assertEquals(before.identity, after.identity)
            assertEquals(before.stopped, after.stopped)
            assertEquals(expected, observation.identity)
            assertEquals(after.stopped, observation.stopped)
            assertTrue(observation.qualifiedRecipient && observation.exclusiveUidOwner)
            assertEquals(0, observation.currentUser)
            assertEquals(2000, observation.serviceUid)
            assertEquals(0, claims.get())
            instrumentation.sendStatus(0, Bundle().apply {
                putBoolean("guardian.rotation.rawSignerChanged", true)
                putBoolean("guardian.rotation.osLineageVerified", true)
                putBoolean("guardian.rotation.appBaselineMatched", true)
                putBoolean("guardian.rotation.wrongSignerRejected", true)
                putBoolean("guardian.rotation.shellBaselineMatched", true)
                putBoolean("guardian.rotation.qualifiedExclusiveOwner", true)
                putBoolean("guardian.rotation.shellUserZero", observation.currentUser == 0)
                putBoolean("guardian.rotation.shellUidVerified", observation.serviceUid == 2000)
                putBoolean("guardian.rotation.noClaims", claims.get() == 0)
                putBoolean("guardian.rotation.write", false)
            })
        } finally {
            live.set(false)
            lifecycle.close()
            runtime.close()
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "io.github.originosfcmguardian.signerrotationfixture"
    }
}
