package io.github.originosfcmguardian

import android.os.Binder
import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.StoppedObservationCodec
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.recovery.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in. Reads one fixed test recipient; never claims or prepares a write. */
@LargeTest
class RecipientIdentityReadDeviceTest {
    @Test(timeout = 60_000)
    fun recipientObservationMatchesCurrentInstallation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guardianRunRecipientIdentityRead") == "true")
        observeFixedRecipient("fr.smarquis.fcm")
    }

    @Test(timeout = 60_000)
    fun telegramRecipientObservationMatchesCurrentInstallation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guardianRunTelegramRecipientIdentityRead") == "true")
        observeFixedRecipient("org.telegram.messenger")
    }

    private fun observeFixedRecipient(packageName: String) {
        require(packageName in setOf("fr.smarquis.fcm", "org.telegram.messenger"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = TargetPackageProbe(context)
        val before = requireNotNull(probe.packageSnapshot(packageName))
        assertTrue("Fixed recipient must qualify from fresh app-side evidence", probe.isRecoveryCandidate(before.identity))
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
            val raw = remote.observeTarget(1, TargetSelectionCodec.encode(listOf(before.identity)), callback)
            assertTrue("New recipient response must use qualified v2", raw.startsWith("2|"))
            val observation = StoppedObservationCodec.decode(raw, 1, before.identity)
            assertTrue("Expected fresh qualified observation, got $observation", observation is StoppedTargetObservation.Known)
            observation as StoppedTargetObservation.Known
            val after = requireNotNull(probe.packageSnapshot(packageName))
            assertEquals(before.identity, after.identity)
            assertEquals(after.identity, observation.identity)
            assertEquals(after.stopped, observation.stopped)
            assertTrue(observation.exclusiveUidOwner && observation.qualifiedRecipient)
            assertEquals(0, observation.currentUser)
            assertEquals(2000, observation.serviceUid)
            assertEquals(0, claims.get())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("guardian.recipient.read", "package=$packageName;identityMatch=true;qualified=true;exclusiveUid=true;stopped=${observation.stopped};serviceUid=2000;user=0;claims=0;write=false")
            })
        } finally {
            live.set(false)
            lifecycle.close()
            runtime.close()
        }
    }
}
