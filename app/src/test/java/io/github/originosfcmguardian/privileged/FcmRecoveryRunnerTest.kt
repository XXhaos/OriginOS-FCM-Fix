package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FcmRecoveryRunnerTest {
    private class Port : GmsConnectionReadPort {
        var state = GcmConnectionState.SERVICE_ABSENT
        var qualified = true
        var uid = 10208
        var version = FcmRecoveryPolicy.VERSION
        var signer = FcmRecoveryPolicy.SIGNER
        var senderReads = 0
        var loseSenderOnSecondRead = false
        var mcsQualified = true
        var network = true
        var mcsReads = 0
        var mcsDuringTarget: (() -> Unit)? = null
        var socketCompleteness = SourceCompleteness.COMPLETE
        var socketMatched = false
        override fun environment() = GmsProbeEnvironment(0, 2000, "12345678-1234-1234-1234-123456789abc")
        override fun gmsPackage() = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
            uid, version, 100, 900, signer)
        override fun packagesForUid(uid: Int) = listOf(if (uid == 10333) "io.github.originosfcmguardian" else GMS_OBSERVATION_PACKAGE)
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean) = SocketSource(socketCompleteness, socketMatched)
        override fun gcmService(interrupted: () -> Boolean) = GcmServiceSource(state,
            if (state == GcmConnectionState.UNKNOWN) SourceCompleteness.MALFORMED else SourceCompleteness.COMPLETE)
        override fun reconnectReceivers() = GmsReceiverInspection(true, emptyList())
        override fun elapsedRealtime() = 100L
        override fun recoverySenderQualified(): Boolean {
            senderReads++
            return qualified && !(loseSenderOnSecondRead && senderReads >= 2)
        }
        override fun disconnectedMcsQualified(): Boolean {
            mcsReads++; mcsDuringTarget?.invoke(); return mcsQualified
        }
        override fun usableInternet() = network
    }
    private class Child(mcs: Boolean = false) : Process() {
        private val out = ByteArrayInputStream((if (mcs)
            "Broadcasting: Intent { act=com.google.android.intent.action.MCS_HEARTBEAT flg=0x400000 pkg=com.google.android.gms }\nBroadcast completed: result=0\n"
            else "Broadcasting: Intent { act=com.google.android.gcm.intent.SEND flg=0x400000 cmp=com.google.android.gms/.gcm.GcmSenderProxy }\nBroadcast completed: result=0\n").toByteArray())
        private val err = ByteArrayInputStream(ByteArray(0))
        override fun getInputStream() = out
        override fun getErrorStream() = err
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun waitFor() = 0
        override fun destroy() {}
    }
    private fun binding(port: Port) = FcmRecoveryPolicy.binding(observeGmsConnection(1, port) as GmsConnectionObservation.Sample)
    @Test fun `guarded preflight rejection never launches and settles actual worker`() {
        val settled = CountDownLatch(1); val settledValue = AtomicReference<PrivilegedExecutionSettlement>()
        val fence = object : PrivilegedExecutionFence {
            override fun prepare() = false
            override fun start(launch: () -> Process): Process? = error("No launch after denied prepare")
            override fun workerSettled(value: PrivilegedExecutionSettlement) { settledValue.set(value); settled.countDown() }
        }
        val port = Port(); val runner = FcmRecoveryRunner({ port }, { error("Must not launch") })
        try {
            val result = runner.requestGuarded(1, 10333, binding(port), { true }, { false },
                FcmRecoveryOperation.SERVICE_ABSENT_SEND, fence)
            assertEquals(FcmRecoveryStatus.NOT_SENT, result.status)
            assertTrue(settled.await(1, TimeUnit.SECONDS))
            assertEquals(PrivilegedExecutionSettlement.NOT_STARTED, settledValue.get())
        } finally { runner.close() }
    }
    @Test fun `caller timeout cannot report settlement before a blocked actual worker exits`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val settled = CountDownLatch(1)
        val port = Port(); val settledValue = AtomicReference<PrivilegedExecutionSettlement>()
        val fence = object : PrivilegedExecutionFence {
            override fun prepare() = true
            override fun start(launch: () -> Process): Process? = error("Cancelled validation must not reach launch")
            override fun workerSettled(value: PrivilegedExecutionSettlement) { settledValue.set(value); settled.countDown() }
        }
        val runner = FcmRecoveryRunner({ port }, { error("No launch") }, 200)
        try {
            val caller = Thread { runner.requestGuarded(1, 10333, binding(port), {
                entered.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }
                true
            }, { false }, FcmRecoveryOperation.SERVICE_ABSENT_SEND, fence) }
            caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.join(2000); assertFalse(caller.isAlive)
            assertEquals(1L, settled.count)
            assertFalse(runner.isQuiescent())
            release.countDown(); assertTrue(settled.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegedExecutionSettlement.NOT_STARTED, settledValue.get())
        } finally { release.countDown(); runner.close() }
    }
    @Test fun `GMS changes while ownership prepare runs are rechecked before launch`() {
        val changes: List<(Port) -> Unit> = listOf({ it.version++ }, { it.qualified = false },
            { it.state = GcmConnectionState.REPORTED_CONNECTED })
        for (change in changes) {
            val port = Port(); var launches = 0
            val fence = object : PrivilegedExecutionFence {
                override fun prepare(): Boolean { change(port); return true }
                override fun start(launch: () -> Process) = launch()
                override fun workerSettled(value: PrivilegedExecutionSettlement) {}
            }
            val runner = FcmRecoveryRunner({ port }, { launches++; Child() })
            try {
                val result = runner.requestGuarded(1, 10333, binding(port), { true }, { false },
                    FcmRecoveryOperation.SERVICE_ABSENT_SEND, fence)
                assertEquals(FcmRecoveryStatus.NOT_SENT, result.status)
                assertEquals(0, launches)
            } finally { runner.close() }
        }
    }
    @Test fun `only exact absent qualified installation uses fixed no extras request`() {
        val port = Port(); var calls = 0
        fun request(id: Long): FcmRecoveryResult {
            val runner = FcmRecoveryRunner({ port }, { calls++; Child() })
            return try { runner.request(id, 10333, binding(port), { true }, { false }) }
            finally { runner.close() }
        }
        assertEquals(FcmRecoveryStatus.REQUESTED, request(1).status)
        assertEquals(1, calls)
        assertEquals(listOf("/system/bin/am", "broadcast", "--user", "0", "-a", FcmRecoveryPolicy.ACTION,
            "-n", "com.google.android.gms/.gcm.GcmSenderProxy"), FCM_RECOVERY_ARGV)
        port.state = GcmConnectionState.REPORTED_CONNECTED
        assertEquals(FcmRecoveryStatus.NOT_SENT, request(2).status)
        port.state = GcmConnectionState.REPORTED_DISCONNECTED
        assertEquals(FcmRecoveryStatus.NOT_SENT, request(3).status)
        port.state = GcmConnectionState.UNKNOWN
        assertEquals(FcmRecoveryStatus.NOT_SENT, request(4).status)
        assertEquals(1, calls)
    }
    @Test fun `revocation target qualification and identity changes block future dispatch`() {
        val port = Port(); val digest = binding(port); var calls = 0
        // Independent policy cases do not race SynchronousQueue worker teardown after Future completion.
        fun request(id: Long, authorize: () -> Boolean = { true }, cancelled: () -> Boolean = { false }): FcmRecoveryResult {
            val runner = FcmRecoveryRunner({ port }, { calls++; Child() })
            return try { runner.request(id, 10333, digest, authorize, cancelled) }
            finally { runner.close() }
        }
        port.qualified = false
        assertEquals(FcmRecoveryReason.TARGET_UNQUALIFIED, request(1).reason)
        port.qualified = true; port.uid++
        assertEquals(FcmRecoveryReason.IDENTITY_CHANGED, request(2).reason)
        port.uid--
        request(3, { false })
        request(4, cancelled = { true })
        assertEquals(0, calls)
    }
    @Test fun `cancel is prompt during stuck authority and timed out worker blocks a second attempt`() {
        val port = Port(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val runner = FcmRecoveryRunner({ port }, { error("must not launch") }, 80)
        val value = AtomicReference<FcmRecoveryResult>()
        val caller = Thread { value.set(runner.request(1, 10333, binding(port), {
            started.countDown(); while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }; true
        }, { false })) }
        try {
            caller.start(); assertTrue(started.await(1, TimeUnit.SECONDS))
            val before = System.nanoTime(); runner.cancel(1)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 40)
            caller.join(1000); assertFalse(caller.isAlive)
            assertEquals(FcmRecoveryStatus.UNKNOWN, value.get().status)
            assertEquals(FcmRecoveryReason.BUSY, runner.request(2, 10333, binding(port), { true }, { false }).reason)
        } finally { release.countDown(); runner.close() }
    }
    @Test fun `OFF before dispatch entry wins and authority false after entry prevents child launch`() {
        val before = FcmRecoveryDispatchGate(); before.cancel()
        assertNull(before.dispatch({ false }, { error("no authorization") }, { error("no launch") }))
        assertFalse(before.entered())
        val after = FcmRecoveryDispatchGate()
        assertNull(after.dispatch({ false }, { after.cancel(); false }, { error("no launch") }))
        assertTrue(after.entered()); assertTrue(after.cancelled())
    }
    @Test fun `new GMS version requires fresh binding and two current sender capability checks`() {
        val port = Port(); val oldBinding = binding(port); port.version++
        var launches = 0
        fun request(digest: String): FcmRecoveryResult {
            val runner = FcmRecoveryRunner({ port }, { launches++; Child() })
            return try { runner.request(1, 10333, digest, { true }, { false }) }
            finally { runner.close() }
        }
        assertEquals(FcmRecoveryReason.IDENTITY_CHANGED, request(oldBinding).reason)
        assertEquals(FcmRecoveryStatus.REQUESTED, request(binding(port)).status)
        assertEquals(2, port.senderReads)
        port.senderReads = 0; port.loseSenderOnSecondRead = true
        assertEquals(FcmRecoveryReason.TARGET_UNQUALIFIED, request(binding(port)).reason)
        assertEquals(1, launches)
        port.signer = "a".repeat(64)
        assertEquals(FcmRecoveryStatus.NOT_SENT, request(binding(port)).status)
        assertEquals(1, launches)
    }
    @Test fun `package flags allow absent OEM extension but reject true or malformed extension`() {
        val text = "Packages:\n  Package [com.google.android.gms] (abcdef):\n    User 0: installed=true hidden=false suspended=false stopped=false vStopped=false enabled=1\n"
        assertTrue(FcmPackageStateReader.parse(text.toByteArray()))
        assertTrue(FcmPackageStateReader.parse(text.replace("vStopped=false", "").toByteArray()))
        listOf(text.replace("vStopped=false", "vStopped=unknown"), text.replace("vStopped=false", "vStopped=true"),
            text.replace("vStopped=false", "vStopped"), text.replace("vStopped=false", "vStopped="),
            text.replace("stopped=false", "stopped=true"), text.replace("User 0:", "User 10:"),
            text.replace("    User 0:", "  Package [other] (def):\n    User 0:"),
            text + "    User 0: installed=true hidden=false suspended=false stopped=false vStopped=false enabled=1\n",
            text.trimEnd(), text + "Permission Denial\n").forEach { assertFalse(FcmPackageStateReader.parse(it.toByteArray())) }
    }
    @Test fun `disconnected branch uses fixed MCS and rechecks source network identity and capability twice`() {
        val port = Port().also { it.version = FcmRecoveryPolicy.MCS_VERSION; it.state = GcmConnectionState.REPORTED_DISCONNECTED }
        var mcs = 0; var send = 0
        fun request(): FcmRecoveryResult {
            val runner = FcmRecoveryRunner({ port }, { send++; Child() }, mcsLaunch = { mcs++; Child(true) })
            return try { runner.request(1, 10333, binding(port), { true }, { false }, FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS) }
            finally { runner.close() }
        }
        assertEquals(FcmRecoveryStatus.REQUESTED, request().status)
        assertEquals(2, port.mcsReads); assertEquals(0, port.senderReads); assertEquals(0, send); assertEquals(1, mcs)
        for (state in listOf(GcmConnectionState.SERVICE_ABSENT, GcmConnectionState.REPORTED_CONNECTED, GcmConnectionState.UNKNOWN)) {
            port.state = state; assertEquals(FcmRecoveryStatus.NOT_SENT, request().status)
        }
        port.state = GcmConnectionState.REPORTED_DISCONNECTED
        port.network = false; assertEquals(FcmRecoveryStatus.NOT_SENT, request().status); port.network = true
        port.socketCompleteness = SourceCompleteness.DENIED
        assertEquals(FcmRecoveryStatus.NOT_SENT, request().status); port.socketCompleteness = SourceCompleteness.COMPLETE
        port.socketMatched = true; assertEquals(FcmRecoveryStatus.NOT_SENT, request().status); port.socketMatched = false
        port.version++; assertEquals(FcmRecoveryStatus.REQUESTED, request().status); port.version--
        port.mcsQualified = false; assertEquals(FcmRecoveryReason.TARGET_UNQUALIFIED, request().reason); port.mcsQualified = true
        port.mcsDuringTarget = { port.state = GcmConnectionState.REPORTED_CONNECTED }
        assertEquals(FcmRecoveryStatus.NOT_SENT, request().status)
        port.state = GcmConnectionState.REPORTED_DISCONNECTED; port.mcsDuringTarget = { port.network = false }
        assertEquals(FcmRecoveryStatus.NOT_SENT, request().status)
        port.network = true; port.mcsDuringTarget = { if (port.mcsReads % 2 == 0) port.mcsQualified = false }
        // Ensure the second qualification independently rejects receiver disappearance.
        port.mcsReads = 0; assertEquals(FcmRecoveryReason.TARGET_UNQUALIFIED, request().reason)
        port.mcsQualified = true
        for (state in listOf(GcmConnectionState.REPORTED_CONNECTED, GcmConnectionState.SERVICE_ABSENT)) {
            port.state = GcmConnectionState.REPORTED_DISCONNECTED; port.mcsReads = 0
            port.mcsDuringTarget = { if (port.mcsReads == 2) port.state = state }
            assertEquals(FcmRecoveryReason.NOT_DISCONNECTED, request().reason)
        }
        port.state = GcmConnectionState.REPORTED_DISCONNECTED; port.mcsReads = 0
        port.mcsDuringTarget = { if (port.mcsReads == 2) port.uid++ }
        assertEquals(FcmRecoveryReason.IDENTITY_CHANGED, request().reason)
        assertEquals(2, mcs); assertEquals(0, send)
    }
}
