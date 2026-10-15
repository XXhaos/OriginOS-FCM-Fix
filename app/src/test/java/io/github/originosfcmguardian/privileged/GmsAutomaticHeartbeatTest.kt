package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class GmsAutomaticHeartbeatTest {
    private val caller = 10333
    private val sample = GmsConnectionObservation.Sample(1, 10208, 99, 100, 200, "a".repeat(64),
        "11111111-2222-3333-4444-555555555555", 1000, 900,
        SocketSource(SourceCompleteness.UNAVAILABLE, false), SocketSource(SourceCompleteness.UNAVAILABLE, false), false, emptyList(),
        GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1000, 900, 0))
    private val accepted = "Broadcasting: Intent { act=com.google.android.intent.action.MCS_HEARTBEAT flg=0x400000 pkg=com.google.android.gms }\nBroadcast completed: result=0\n"
    private class Lifetime : GmsHeartbeatLifetime {
        var living = true
        var callback: (() -> Unit)? = null
        override fun alive() = living
        override fun link(death: () -> Unit) { callback = death }
        override fun unlink() { callback = null }
        fun die() { living = false; callback?.invoke() }
    }
    private inner class Port : GmsConnectionReadPort {
        var callerOwners = listOf("io.github.originosfcmguardian")
        var versionOffset = 0L
        var serviceState = sample.gcmService
        override fun environment() = GmsProbeEnvironment(0, 2000, sample.bootId)
        override fun gmsPackage() = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
            sample.uid, sample.versionCode + versionOffset, sample.firstInstallTime, sample.lastUpdateTime, sample.signerSha256)
        override fun packagesForUid(uid: Int) = if (uid == sample.uid) listOf(GMS_OBSERVATION_PACKAGE) else callerOwners
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean) = SocketSource(SourceCompleteness.UNAVAILABLE, false)
        override fun gcmService(interrupted: () -> Boolean) = serviceState
        override fun reconnectReceivers() = GmsReceiverInspection(false, emptyList())
        override fun elapsedRealtime() = 900L
    }
    private class Child(text: String, private var alive: Boolean = false) : Process() {
        var destroyed = false
        val destruction = CountDownLatch(1)
        private val out = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream() = out
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun waitFor() = exitValue()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { if (alive) Thread.sleep(unit.toMillis(timeout)); return !alive }
        override fun destroy() { destroyed = true; destruction.countDown() }
        override fun destroyForcibly(): Process { alive = false; return this }
    }
    @Test fun guardedHeartbeatRechecksConnectionAndIdentityAfterOwnershipReads() {
        val changes: List<(Port) -> Unit> = listOf({ it.versionOffset++ },
            { it.serviceState = GcmServiceSource(GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.COMPLETE) })
        for (change in changes) {
            var time = 0L; var launches = 0
            val port = Port(); val workerThread = AtomicReference<Thread>()
            val settled = CountDownLatch(1)
            val runner = GmsHeartbeatRunner({ workerThread.set(Thread.currentThread()); port },
                { launches++; Child(accepted) }, clock = { time })
            val executorField = GmsHeartbeatRunner::class.java.getDeclaredField("worker").apply { isAccessible = true }
            (executorField.get(runner) as ThreadPoolExecutor).setKeepAliveTime(1, TimeUnit.MILLISECONDS)
            val fence = object : PrivilegedExecutionFence {
                override fun prepare(): Boolean { change(port); return true }
                override fun start(launch: () -> Process) = launch()
                override fun workerSettled(value: PrivilegedExecutionSettlement) {
                    assertEquals(PrivilegedExecutionSettlement.NOT_STARTED, value); settled.countDown()
                }
            }
            try {
                val session = runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), Lifetime()) { false }.session!!
                val old = checkNotNull(workerThread.get()); old.join(5000); assertFalse(old.isAlive)
                time = GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
                val result = runner.requestAutomaticGuarded(2, caller, session, UUID.randomUUID().toString(), { false }, fence)
                assertEquals(GmsHeartbeatStatus.NOT_SENT, result.status)
                assertEquals(0, launches); assertTrue(settled.await(1, TimeUnit.SECONDS))
            } finally { runner.close() }
        }
    }
    @Test fun armRequiresExclusiveGuardianCallerAndExactFreshBinding() {
        val bad = Port().apply { callerOwners = listOf("other.pkg") }
        val runner = GmsHeartbeatRunner({ bad }, { error("arm never launches") })
        try { assertEquals(GmsAutoArmReason.CALLER_UNAVAILABLE,
            runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), Lifetime()) { false }.reason) }
        finally { runner.close() }
        val good = GmsHeartbeatRunner({ Port() }, { error("arm never launches") })
        try { assertEquals(GmsAutoArmReason.IDENTITY_CHANGED, good.armAutomatic(2, caller, 1, "b".repeat(64), Lifetime()) { false }.reason) }
        finally { good.close() }
    }
    @Test fun firstAutomaticAndEveryManualWaitFullProductionIntervalAndDuplicateCannotReplay() {
        var time = 0L; var launches = 0
        val workerThread = AtomicReference<Thread>()
        val runner = GmsHeartbeatRunner({ workerThread.set(Thread.currentThread()); Port() },
            { launches++; Child(accepted) }, clock = { time })
        // White-box fixture only: Future.get() does not prove the no-queue worker is ready.
        // Let the core-zero worker terminate, then join it before the next single-shot call.
        // No production admission, heartbeat retry, interval or policy is changed.
        val field = GmsHeartbeatRunner::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val executor = field.get(runner) as ThreadPoolExecutor
        assertEquals(0, executor.corePoolSize)
        assertEquals(1, executor.maximumPoolSize)
        assertTrue(executor.queue is SynchronousQueue<*>)
        executor.setKeepAliveTime(1, TimeUnit.MILLISECONDS)
        fun awaitWorkerExit() {
            val thread = checkNotNull(workerThread.get())
            thread.join(5_000)
            assertFalse("Previous fixture worker must exit before the next submission", thread.isAlive)
        }
        try {
            val session = runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), Lifetime()) { false }.session!!
            awaitWorkerExit()
            val early = UUID.randomUUID().toString()
            assertEquals(GmsHeartbeatReason.INTERVAL_NOT_ELAPSED, runner.requestAutomatic(2, caller, session, early) { false }.reason)
            time = GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
            assertEquals(GmsHeartbeatReason.DUPLICATE_ATTEMPT, runner.requestAutomatic(3, caller, session, early) { false }.reason)
            assertEquals(GmsHeartbeatStatus.REQUESTED, runner.request(4) { false }.status)
            awaitWorkerExit()
            time += GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS - 1
            assertEquals(GmsHeartbeatReason.INTERVAL_NOT_ELAPSED, runner.requestAutomatic(5, caller, session, UUID.randomUUID().toString()) { false }.reason)
            time++
            assertEquals(GmsHeartbeatStatus.REQUESTED, runner.requestAutomatic(6, caller, session, UUID.randomUUID().toString()) { false }.status)
            assertEquals(2, launches)
        } finally { runner.close() }
    }
    @Test fun lifetimeDeathAndUnknownManualCloseAutomaticCapability() {
        var time = 0L
        val lifetime = Lifetime()
        val runner = GmsHeartbeatRunner({ Port() }, { Child(accepted) }, clock = { time })
        try {
            val session = runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), lifetime) { false }.session!!
            lifetime.die(); time += GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
            assertEquals(GmsHeartbeatReason.SESSION_REVOKED, runner.requestAutomatic(2, caller, session, UUID.randomUUID().toString()) { false }.reason)
        } finally { runner.close() }
        val uncertain = GmsHeartbeatRunner({ Port() }, { Child("torn") }, clock = { time })
        try {
            val session = uncertain.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), Lifetime()) { false }.session!!
            assertEquals(GmsHeartbeatStatus.UNKNOWN, uncertain.request(2) { false }.status)
            time += GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
            assertEquals(GmsHeartbeatReason.SESSION_REVOKED, uncertain.requestAutomatic(3, caller, session, UUID.randomUUID().toString()) { false }.reason)
        } finally { uncertain.close() }
    }
    @Test fun cleanOriginalEndpointRevocationRearmsNewGenerationAndLifetimeWithFullInterval() {
        assertEquals(900_000L, GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS)
        var time = 0L
        var launches = 0
        val originalLifetime = Lifetime()
        val nextLifetime = Lifetime()
        val runner = GmsHeartbeatRunner({ Port() }, { launches++; Child(accepted) }, clock = { time })
        try {
            val original = runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), originalLifetime) { false }.session!!
            assertNotNull(originalLifetime.callback)
            time = 250_000L // Host loss happens before the old session's first due time.
            assertTrue(runner.revokeAutomatic(caller, original.generation, original.id))
            assertNull(originalLifetime.callback)
            assertEquals(GmsAutoArmReason.BUSY,
                runner.armAutomatic(2, caller, original.generation, original.bindingDigest, Lifetime()) { false }.reason)
            // The no-queue worker can still be handing off its prior completed Future. BUSY
            // is a legitimate no-grant response, not ARMED. Only this read/arm fixture waits
            // for admission; the automatic heartbeat attempt below is never retried.
            val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            var nextArm: GmsAutoArmResult
            do {
                nextArm = runner.armAutomatic(3, caller, 2, original.bindingDigest, nextLifetime) { false }
                if (nextArm.reason == GmsAutoArmReason.BUSY) {
                    assertNull(nextArm.session)
                    assertTrue("Prior no-queue worker did not settle", System.nanoTime() < admissionDeadline)
                    Thread.sleep(1)
                }
            } while (nextArm.reason == GmsAutoArmReason.BUSY)
            assertEquals(GmsAutoArmReason.ARMED, nextArm.reason)
            val next = checkNotNull(nextArm.session)
            assertEquals(2L, next.generation)
            assertNotEquals(original.id, next.id)
            assertNotSame(originalLifetime, nextLifetime)
            assertNotNull(nextLifetime.callback)
            originalLifetime.die() // A retired lifetime must not close the new capability.
            assertEquals(GmsHeartbeatReason.SESSION_REVOKED,
                runner.requestAutomatic(4, caller, original, UUID.randomUUID().toString()) { false }.reason)
            time = 250_000L + 899_999L
            assertEquals(GmsHeartbeatReason.INTERVAL_NOT_ELAPSED,
                runner.requestAutomatic(5, caller, next, UUID.randomUUID().toString()) { false }.reason)
            assertEquals(0, launches)
            time++
            assertEquals(GmsHeartbeatStatus.REQUESTED,
                runner.requestAutomatic(6, caller, next, UUID.randomUUID().toString()) { false }.status)
            assertEquals(1, launches)
        } finally { runner.close() }
    }
    @Test fun finalLaunchAndRevokeAreOrderedButRevokeDoesNotWaitForCommandCompletion() {
        var time = 0L
        val gate = GmsAutomaticHeartbeatGate { time }
        val cap = gate.newCapability(1, sample, caller, Lifetime())
        assertTrue(gate.arm(cap) { false }); time += GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
        assertTrue(gate.revoke(caller, 1, cap.session.id))
        var calls = 0
        assertEquals(GmsHeartbeatReason.IDENTITY_CHANGED, gate.launch(cap, sample, { true }, { calls++; Child(accepted) }).second)
        assertEquals(0, calls)
        val runnerTime = GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
        val entered = CountDownLatch(1); val result = AtomicReference<GmsHeartbeatResult>(); val child = Child(accepted, alive = true)
        var current = 0L
        val runner = GmsHeartbeatRunner({ Port() }, { entered.countDown(); child }, clock = { current })
        try {
            val session = runner.armAutomatic(1, caller, 1, gmsHeartbeatBindingDigest(sample), Lifetime()) { false }.session!!
            current = runnerTime
            val sender = Thread { result.set(runner.requestAutomatic(2, caller, session, UUID.randomUUID().toString()) { false }) }
            sender.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(runner.revokeAutomatic(caller, 1, session.id))
            sender.join(1000)
            assertFalse(sender.isAlive); assertEquals(GmsHeartbeatStatus.UNKNOWN, result.get().status)
            // The bounded caller can return before the worker's asynchronous cleanup finishes.
            assertTrue(child.destruction.await(1, TimeUnit.SECONDS)); assertTrue(child.destroyed)
        } finally { runner.close() }
    }
    @Test fun manualAndAutoShareBusyWorkerAndUnknownOwnerCannotRearmUntilItExits() {
        val gate = GmsAutomaticHeartbeatGate { 0L }
        val first = gate.newCapability(1, sample, caller, Lifetime())
        assertTrue(gate.arm(first) { false })
        gate.workerEntered(); gate.ended(first, GmsHeartbeatStatus.UNKNOWN, settled = false)
        val second = gate.newCapability(2, sample, caller, Lifetime())
        assertFalse(gate.arm(second) { false })
        gate.workerExited(); assertTrue(gate.arm(second) { false })
        assertFalse(gate.revoke(caller + 1, 2, second.session.id))
    }
    @Test fun finalLifetimeCheckRejectsDeadBinderWithoutCallbackAndBinderFailureBeforeDispatch() {
        listOf(false, true).forEach { throws ->
            var time = 0L
            val lifetime = object : GmsHeartbeatLifetime {
                override fun alive(): Boolean { if (throws) error("Binder failure"); return false }
                override fun link(death: () -> Unit) = Unit
                override fun unlink() = Unit
            }
            val gate = GmsAutomaticHeartbeatGate { time }
            val cap = gate.newCapability(1, sample, caller, lifetime)
            assertTrue(gate.arm(cap) { false })
            time = GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS
            var begins = 0; var launches = 0
            assertEquals(GmsHeartbeatReason.SESSION_REVOKED,
                gate.launch(cap, sample, { begins++; true }, { launches++; Child(accepted) }).second)
            assertEquals(0, begins); assertEquals(0, launches); assertFalse(cap.live.get())
        }
    }
}
