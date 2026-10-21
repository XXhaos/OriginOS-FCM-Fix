package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.privileged.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

class PackageStoppedStateFallbackTest {
    private val fields = StoppedPackageFields("example.recipient", "example.recipient", 10234, 100,
        listOf(byteArrayOf(1, 2, 3)), true, RecoveryTargetQualifications(true, true, false, false, false))
    private val identity = validatedStoppedPackage(fields, fields.packageName!!)!!.first
    private fun args() = arrayOf("clear-recipient", TargetSelectionCodec.encode(listOf(identity)))
    private inner class Port : PackageStoppedStatePort {
        var env = StoppedProbeEnvironment(VERIFIED_AUTOSTART_FINGERPRINT, 0, 2000)
        var method = true
        var permission = true
        var permissionReads = 0
        var revokeOnSecondPermission = false
        var samples = listOf(fields, fields)
        var owners: List<String>? = listOf(identity.packageName)
        var packageReads = 0
        val writes = mutableListOf<String>()
        override val reads = object : StoppedIdentityReadPort {
            override fun environment() = env
            override fun telegramPackage() = samples[packageReads++]
            override fun targetPackage(packageName: String) = samples[packageReads++]
            override fun packagesForUid(uid: Int) = owners
        }
        override fun supported() = method
        override fun permissionGranted(): Boolean {
            permissionReads++
            return permission && !(revokeOnSecondPermission && permissionReads >= 2)
        }
        override fun clear(packageName: String) { writes += packageName }
    }

    class ExactApi {
        val writes = mutableListOf<Triple<String, Boolean, Int>>()
        fun setPackageStoppedState(name: String, stopped: Boolean, user: Int) { writes += Triple(name, stopped, user) }
    }
    class WrongReturn { fun setPackageStoppedState(name: String, stopped: Boolean, user: Int) = true }
    class WrongFlag { fun setPackageStoppedState(name: String, stopped: Int, user: Int) {} }
    class StaticApi { companion object { @JvmStatic fun setPackageStoppedState(name: String, stopped: Boolean, user: Int) {} } }

    @Test fun exactVoidInstanceSignatureIsRequiredWithoutInvokingIt() {
        val api = ExactApi()
        val method = packageStoppedStateMethod(ExactApi::class.java)
        assertEquals(Void.TYPE, method.returnType); assertTrue(api.writes.isEmpty())
        clearPackageStoppedState(method, api, identity.packageName)
        assertEquals(listOf(Triple(identity.packageName, false, 0)), api.writes)
        for (type in listOf(WrongReturn::class.java, WrongFlag::class.java, StaticApi::class.java, String::class.java))
            assertTrue(runCatching { packageStoppedStateMethod(type) }.isFailure)
    }

    @Test fun probeNeverReadsOrChangesAnyTargetAndValidClearRunsOneExactTarget() {
        val probe = Port()
        assertTrue(fixedPackageStoppedState(arrayOf("probe"), probe))
        assertEquals(0, probe.packageReads); assertTrue(probe.writes.isEmpty())
        val clear = Port()
        assertTrue(fixedPackageStoppedState(args(), clear))
        assertEquals(2, clear.packageReads); assertEquals(2, clear.permissionReads)
        assertEquals(listOf(identity.packageName), clear.writes)
        val alreadyClear = Port().apply { samples = listOf(fields, fields.copy(stopped = false)) }
        assertTrue(fixedPackageStoppedState(args(), alreadyClear)); assertTrue(alreadyClear.writes.isEmpty())
    }

    @Test fun wrongShellUserMethodPermissionIdentityAndQualificationNeverWrite() {
        val changes: List<(Port) -> Unit> = listOf(
            { it.env = it.env.copy(serviceUid = 0) }, { it.env = it.env.copy(currentUser = 10) },
            { it.method = false }, { it.permission = false }, { it.revokeOnSecondPermission = true },
            { it.samples = listOf(fields, fields.copy(firstInstallTime = 101)) },
            { it.samples = listOf(fields.copy(uid = 10235), fields.copy(uid = 10235)) },
            { it.samples = listOf(fields.copy(qualifications = null), fields.copy(qualifications = null)) },
            { it.owners = listOf("other.package") }, { it.owners = null })
        for (change in changes) {
            val port = Port().also(change)
            assertTrue(runCatching { fixedPackageStoppedState(args(), port) }.isFailure)
            assertTrue(port.writes.isEmpty())
        }
        for (invalid in listOf(emptyArray(), arrayOf("arbitrary"), arrayOf("probe", "extra"),
                arrayOf("clear-recipient", "unknown"), arrayOf("clear-legacy", args()[1]))) {
            val port = Port()
            assertTrue(runCatching { fixedPackageStoppedState(invalid, port) }.isFailure)
            assertTrue(port.writes.isEmpty())
        }
    }

    @Test fun freshSigningLineageAndUidRulesRemainTheExistingRecipientRules() {
        val rotated = fields.copy(currentSignerCertificates = listOf(byteArrayOf(4, 5, 6)))
        val current = validatedStoppedPackage(rotated, identity.packageName)!!.first
        val evidence = TargetSigningEvidence(false, identity.signerSha256 + current.signerSha256)
        val port = Port().apply {
            samples = listOf(rotated.copy(signing = evidence), rotated.copy(signing = evidence))
            owners = listOf(identity.packageName, "example.neighbor")
        }
        assertTrue(fixedPackageStoppedState(args(), port)); assertEquals(listOf(identity.packageName), port.writes)
        val unproved = Port().apply { samples = listOf(rotated, rotated) }
        assertTrue(runCatching { fixedPackageStoppedState(args(), unproved) }.isFailure)
        assertTrue(unproved.writes.isEmpty())
    }

    private class Child : Process() {
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() {}
    }

    @Test fun commandIsPreferredWithoutReadingClasspathOrProbingBinder() {
        var starts = 0
        val prepared = preparePackageUnstop(identity, true, { true }, command = { true },
            source = { error("No path read on command fast path") }, probe = { _, _ -> error("No Binder probe") },
            launcher = { argv, path ->
                assertEquals(stoppedRecoveryArgv(identity), argv); assertNull(path)
                val start: () -> Process = { starts++; Child() }
                start
            })!!
        assertEquals(PackageUnstopMode.COMMAND, prepared.mode); assertEquals(0, starts)
        prepared.start(); assertEquals(1, starts)
        assertTrue(runCatching { prepared.start() }.isFailure); assertEquals(1, starts)
    }

    @Test fun fallbackChoiceIsImmutableSingleUseAndNeverReprobesAfterUnknownLaunch() {
        var probes = 0; var paths = 0; var starts = 0
        val prepared = preparePackageUnstop(identity, true, { true }, command = { false },
            source = { paths++; "/data/app/example/base.apk" }, probe = { _, _ -> probes++; true },
            launcher = { argv, path ->
                assertEquals("/data/app/example/base.apk", path)
                assertEquals(listOf("/system/bin/app_process", "/system/bin", PACKAGE_STOPPED_STATE_MAIN,
                    "clear-recipient", args()[1]), argv)
                assertThrows(UnsupportedOperationException::class.java) { (argv as MutableList<String>)[0] = "other" }
                val start: () -> Process = { starts++; throw TimeoutException("unknown launch outcome") }
                start
            })!!
        assertEquals(PackageUnstopMode.BINDER_CHILD, prepared.mode)
        assertEquals(identity, prepared.identity); assertTrue(prepared.recipient)
        var claims = 0
        val observed = StoppedTargetObservation.Known(1, identity, true, true, 100, qualifiedRecipient = true)
        val result = StoppedRecoveryEngine({ observed }, { claims++; true }, { true }, { prepared.start(); 0 })
            .recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome); assertEquals(1, claims)
        assertTrue(runCatching { prepared.start() }.isFailure)
        assertEquals(1, probes); assertEquals(1, paths); assertEquals(1, starts)
    }

    @Test fun failedReadinessAndOffBeforeDispatchCreateNoWriteChild() {
        var starts = 0
        val launcher: (List<String>, String?) -> (() -> Process) = { _, _ -> { starts++; Child() } }
        assertNull(preparePackageUnstop(identity, true, { false }, command = { error("inactive") }, launcher = launcher))
        assertNull(preparePackageUnstop(identity, true, { true }, command = { false }, source = { null }, launcher = launcher))
        assertNull(preparePackageUnstop(identity, true, { true }, command = { false }, source = { "/data/app/base.apk" },
            probe = { _, _ -> false }, launcher = launcher))
        var live = true; var prepared: PreparedPackageUnstop? = null
        val observed = StoppedTargetObservation.Known(1, identity, true, true, 100, qualifiedRecipient = true)
        val engine = StoppedRecoveryEngine({ observed }, { live = false; true }, { live }, { prepared!!.start(); 0 })
        val result = engine.recover(1, identity) {
            prepared = preparePackageUnstop(identity, true, { live }, command = { true }, launcher = launcher)
            prepared != null
        }
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome); assertEquals(0, starts)
    }
}
