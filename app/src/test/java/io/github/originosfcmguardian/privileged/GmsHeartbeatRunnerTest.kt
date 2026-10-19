package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.SafeCommandFactory
import io.github.originosfcmguardian.core.gms.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class GmsHeartbeatRunnerTest {
    private val accepted = "Broadcasting: Intent { act=com.google.android.intent.action.MCS_HEARTBEAT flg=0x400000 pkg=com.google.android.gms }\nBroadcast completed: result=0\n"
    private class Port : GmsConnectionReadPort {
        var environment = GmsProbeEnvironment(0, 2000, "11111111-2222-3333-4444-555555555555")
        var identity = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE, 10208, 99, 100, 200, "a".repeat(64))
        var owners = listOf(GMS_OBSERVATION_PACKAGE)
        var report = GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE)
        var reads = 0
        var identityRead: () -> Unit = {}
        override fun environment() = environment
        override fun gmsPackage(): GmsPackageIdentity { reads++; identityRead(); return identity }
        override fun packagesForUid(uid: Int) = if (uid == 10238) listOf("io.github.originosfcmguardian") else owners
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean) = SocketSource(SourceCompleteness.UNAVAILABLE, false)
        override fun gcmService(interrupted: () -> Boolean) = report
        override fun reconnectReceivers() = GmsReceiverInspection(false, emptyList())
        override fun elapsedRealtime() = 900L
    }
    private open class Child(text: String, errors: String = "", private val exit: Int = 0, private var alive: Boolean = false) : Process() {
        var destroyed = false
        var closed = false
        private val out = object : ByteArrayInputStream(text.toByteArray()) {
            override fun close() { closed = true; onClose(); super.close() }
        }
        private val err = ByteArrayInputStream(errors.toByteArray())
        open fun onClose() {}
        override fun getInputStream() = out
        override fun getErrorStream() = err
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue(): Int { check(!alive); return exit }
        override fun isAlive() = alive
        override fun waitFor(): Int { check(!alive); return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { if (alive) Thread.sleep(unit.toMillis(timeout)); return !alive }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { alive = false; return this }
    }
    @Test fun connectedRequestUsesOnlyFixedNativeActionAndReportsRequestNotRepair() {
        assertEquals(listOf("/system/bin/am", "broadcast", "--user", "0", "-a",
            "com.google.android.intent.action.MCS_HEARTBEAT", "-p", GMS_OBSERVATION_PACKAGE), GMS_HEARTBEAT_ARGV)
        assertNull(SafeCommandFactory.fromIpc("request_gms_heartbeat"))
        val child = Child(accepted); var calls = 0
        val runner = GmsHeartbeatRunner({ Port() }, { calls++; child })
        try {
            assertEquals(GmsHeartbeatStatus.REQUESTED, runner.request(1) { false }.status)
            assertEquals(1, calls); assertTrue(child.destroyed && child.closed)
        } finally { runner.close() }
    }
    @Test fun disconnectedUnknownWrongUserRootSharedOwnerAndFinalIdentityDriftNeverLaunch() {
        val ports = listOf(Port().apply { report = GcmServiceSource(GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.COMPLETE) },
            Port().apply { report = GcmServiceSource.unavailable() },
            Port().apply { environment = environment.copy(currentUser = 10) },
            Port().apply { environment = environment.copy(serviceUid = 0) },
            Port().apply { owners = listOf(GMS_OBSERVATION_PACKAGE, "other.pkg") },
            Port().apply { identityRead = { if (reads == 3) identity = identity.copy(signerSha256 = "b".repeat(64)) } })
        ports.forEach { port ->
            var launched = false
            val runner = GmsHeartbeatRunner({ port }, { launched = true; Child(accepted) })
            try { assertEquals(GmsHeartbeatStatus.NOT_SENT, runner.request(1) { false }.status); assertFalse(launched) }
            finally { runner.close() }
        }
    }
    @Test fun rotatedGoogleSignerCanPassFreshFinalIdentityCheck() {
        val port = Port().apply {
            identity = identity.copy(signerSha256 = "b".repeat(64),
                signingCertificateHistorySha256 = listOf(FcmRecoveryPolicy.SIGNER, "b".repeat(64)))
        }
        var launches = 0
        val runner = GmsHeartbeatRunner({ port }, { launches++; Child(accepted) })
        try {
            assertEquals(GmsHeartbeatStatus.REQUESTED, runner.request(1) { false }.status)
            assertEquals(1, launches)
            assertEquals(3, port.reads)
        } finally { runner.close() }
    }

    @Test fun finalGoogleProofOrInstallationTupleDriftNeverLaunches() {
        val mutations = listOf<(GmsPackageIdentity) -> GmsPackageIdentity>(
            { it.copy(packageName = "other.pkg") }, { it.copy(applicationPackageName = "other.pkg") },
            { it.copy(uid = it.uid + 1) }, { it.copy(versionCode = it.versionCode + 1) },
            { it.copy(firstInstallTime = it.firstInstallTime + 1) }, { it.copy(lastUpdateTime = it.lastUpdateTime + 1) },
            { it.copy(signingCertificateHistorySha256 = null) }, { it.copy(hasMultipleSigners = true) },
            { it.copy(signingCertificateHistorySha256 = listOf(FcmRecoveryPolicy.SIGNER)) },
            { it.copy(signerSha256 = "c".repeat(64)) })
        for (mutation in mutations) {
            val port = Port().apply {
                identity = identity.copy(signerSha256 = "b".repeat(64),
                    signingCertificateHistorySha256 = listOf(FcmRecoveryPolicy.SIGNER, "b".repeat(64)))
                identityRead = { if (reads == 3) identity = mutation(identity) }
            }
            var launches = 0
            val runner = GmsHeartbeatRunner({ port }, { launches++; Child(accepted) })
            try {
                assertEquals(GmsHeartbeatReason.IDENTITY_CHANGED, runner.request(1) { false }.reason)
                assertEquals(0, launches)
            } finally { runner.close() }
        }
    }
    @Test fun cancellationBeforeDispatchIsUnsentButAfterLaunchIntentIsUnknown() {
        val runner = GmsHeartbeatRunner({ Port() }, { error("must not launch") })
        try { assertEquals(GmsHeartbeatStatus.NOT_SENT, runner.request(1) { true }.status) } finally { runner.close() }
        val cancelled = AtomicBoolean(false); val child = Child(accepted, alive = true)
        val after = GmsHeartbeatRunner({ Port() }, { cancelled.set(true); child })
        try {
            assertEquals(GmsHeartbeatStatus.UNKNOWN, after.request(2) { cancelled.get() }.status)
        } finally { after.close() }
    }
    @Test fun launchExceptionAndIncompleteOrFailedCommandAreUnknownWithoutRetry() {
        val throwing = GmsHeartbeatRunner({ Port() }, { throw IllegalStateException("private token") })
        try { assertEquals(GmsHeartbeatStatus.UNKNOWN, throwing.request(1) { false }.status) } finally { throwing.close() }
        listOf(Child(accepted.trimEnd()), Child(accepted, errors = "denied"), Child(accepted, exit = 1),
            Child(accepted.replace("result=0", "result=1")), Child(accepted + "extra\n"),
            Child("x".repeat(16_385))).forEach { child ->
            var calls = 0
            val runner = GmsHeartbeatRunner({ Port() }, { calls++; child })
            try { assertEquals(GmsHeartbeatStatus.UNKNOWN, runner.request(1) { false }.status); assertEquals(1, calls) }
            finally { runner.close() }
        }
    }
    @Test fun blockedValidationTimesOutUnsentKeepsAdmissionAndCannotDispatchLate() {
        val release = CountDownLatch(1); val entered = CountDownLatch(1); var launches = 0
        val runner = GmsHeartbeatRunner({
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            Port()
        }, { launches++; Child(accepted) }, 50)
        try {
            assertEquals(GmsHeartbeatResult.notSent(1, GmsHeartbeatReason.TIMED_OUT), runner.request(1) { false })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(runner.isQuiescent())
            assertEquals(GmsHeartbeatReason.BUSY, runner.request(2) { false }.reason)
        } finally { release.countDown(); runner.close() }
        assertEquals(0, launches)
    }
    @Test fun timedOutChildRemainsSingleFlightUntilDestroyAndPipeCleanupFinish() {
        val release = CountDownLatch(1); val cleaning = CountDownLatch(1)
        val child = object : Child(accepted, alive = true) {
            override fun onClose() {
                cleaning.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            }
        }
        val runner = GmsHeartbeatRunner({ Port() }, { child }, 50)
        try {
            assertEquals(GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT), runner.request(1) { false })
            assertTrue(cleaning.await(1, TimeUnit.SECONDS)); assertTrue(child.destroyed)
            assertFalse(runner.isQuiescent())
            assertEquals(GmsHeartbeatReason.BUSY, runner.request(2) { false }.reason)
        } finally { release.countDown(); runner.close() }
    }
    @Test fun abandonedClientGateRejectsLateRpcAndNeverReturnsUnsentAfterAdmission() {
        val gate = GmsHeartbeatDispatchGate { null }
        assertEquals(GmsHeartbeatStatus.NOT_SENT, gate.failure(1, GmsHeartbeatReason.CANCELLED, abandon = true).status)
        assertFalse(gate.begin())
        val started = GmsHeartbeatDispatchGate { null }
        assertTrue(started.begin())
        assertEquals(GmsHeartbeatStatus.UNKNOWN, started.failure(1, GmsHeartbeatReason.BRIDGE_UNAVAILABLE).status)
    }

    private fun lifetime() = object : GmsHeartbeatLifetime {
        override fun alive() = true
        override fun link(death: () -> Unit) = Unit
        override fun unlink() = Unit
    }

    @Test fun failedArmRecordsOneBoundedDiagnosticAndNeverLaunchesAHeartbeat() {
        val diagnostics = mutableListOf<GmsHeartbeatArmDiagnostic>()
        var launches = 0
        val runner = GmsHeartbeatRunner({ Port() }, { launches++; Child(accepted) }, armDiagnostic = diagnostics::add)
        try {
            val result = runner.armAutomatic(1, 10239, 1, "a".repeat(64), lifetime()) { false }
            assertEquals(GmsAutoArmReason.CALLER_UNAVAILABLE, result.reason)
            assertNull(result.session)
            assertEquals(0, launches)
            assertEquals(1, diagnostics.size)
            assertEquals(GmsHeartbeatArmStage.CALLER, diagnostics.single().stage)
            assertEquals(result.reason, diagnostics.single().reason)
            assertTrue(diagnostics.single().durationMillis >= 0)
        } finally { runner.close() }
    }

    @Test fun timedOutArmDiagnosticDoesNotPretendItsWorkerHasDrained() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val diagnostics = java.util.concurrent.CopyOnWriteArrayList<GmsHeartbeatArmDiagnostic>()
        var launches = 0
        val runner = GmsHeartbeatRunner({
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            Port()
        }, { launches++; Child(accepted) }, 100, armDiagnostic = diagnostics::add)
        try {
            assertEquals(GmsAutoArmReason.TIMED_OUT,
                runner.armAutomatic(1, 10238, 1, "a".repeat(64), lifetime()) { false }.reason)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(runner.isQuiescent())
            assertEquals(1, diagnostics.size)
            assertEquals(GmsHeartbeatArmStage.PORT, diagnostics.single().stage)
            assertFalse(diagnostics.single().workerQuiescent)
            assertEquals(0, launches)
        } finally { release.countDown(); runner.close() }
    }
}
