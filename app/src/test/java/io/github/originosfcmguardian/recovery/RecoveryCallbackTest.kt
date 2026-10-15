package io.github.originosfcmguardian.recovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RecoveryCallbackTest {
    @Test fun migrationSettlementDoesNotFollowFutureCancellationOfRunningCallback() {
        val worker = recoveryCallbackWorker("migration-callback-test")
        val activity = io.github.originosfcmguardian.privileged.PrivilegedWorkerActivity()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            assertThrows(TimeoutException::class.java) {
                boundedRecoveryCallback(worker, { true }, {}, 100, activity = activity) {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    true
                }
            }
            assertEquals(0L, entered.count)
            assertFalse(activity.isQuiescent())
            release.countDown()
            worker.submit<Int> { 1 }.get(2, TimeUnit.SECONDS)
            assertTrue(activity.isQuiescent())
        } finally { release.countDown(); worker.shutdownNow() }
    }
    /** Holds afterExecute after Future completion, reproducing the old handoff race exactly. */
    private class HeldHandoff : AutoCloseable {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val drained = CountDownLatch(1)
        private val completed = AtomicInteger()
        private val queue = object : ArrayBlockingQueue<Runnable>(1) {
            override fun offer(task: Runnable): Boolean = super.offer(task).also {
                if (it && held.count == 0L) queued.countDown()
            }
        }
        val worker = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, queue,
            { task -> Thread(task, "callback-test").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()) {
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                super.afterExecute(task, failure)
                val count = completed.incrementAndGet()
                if (count == 1) {
                    held.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                if (count == 2) drained.countDown()
            }
        }
        init {
            assertEquals(1, boundedRecoveryCallback(worker, { true }, { fail("cancel") }) { 1 })
            assertTrue(held.await(2, TimeUnit.SECONDS))
        }
        override fun close() { release.countDown(); worker.shutdownNow() }
    }

    @Test fun completedFutureAllowsOneHandoffButThirdCallbackIsRejected() {
        HeldHandoff().use { fixture ->
            val caller = Executors.newSingleThreadExecutor()
            try {
                val second = caller.submit<Int> {
                    boundedRecoveryCallback(fixture.worker, { true }, { fail("cancel") }) { 2 }
                }
                assertTrue(fixture.queued.await(2, TimeUnit.SECONDS))
                assertEquals(1, fixture.worker.queue.size)
                assertThrows(RejectedExecutionException::class.java) { fixture.worker.submit { error("third") } }
                var closed = false
                assertThrows(RejectedExecutionException::class.java) {
                    boundedRecoveryCallback(fixture.worker, { true }, { closed = true }) { error("third Binder") }
                }
                assertTrue(closed)
                fixture.release.countDown()
                assertEquals(2, second.get(2, TimeUnit.SECONDS))
            } finally { caller.shutdownNow() }
        }
        val worker = recoveryCallbackWorker("factory-test")
        try {
            assertEquals(1, worker.maximumPoolSize)
            assertTrue(worker.queue is ArrayBlockingQueue<*>)
            assertEquals(1, worker.queue.remainingCapacity())
        } finally { worker.shutdownNow() }
    }

    @Test fun queuedRevocationNeverEntersBinderBlockAndClosesSession() {
        HeldHandoff().use { fixture ->
            val live = AtomicBoolean(true)
            val calls = AtomicInteger()
            val cancellations = AtomicInteger()
            val caller = Executors.newSingleThreadExecutor()
            try {
                val second = caller.submit<Boolean> {
                    runCatching { boundedRecoveryCallback(fixture.worker, live::get,
                        { cancellations.incrementAndGet(); live.set(false) }) { calls.incrementAndGet() } }.isFailure
                }
                assertTrue(fixture.queued.await(2, TimeUnit.SECONDS))
                live.set(false)
                fixture.release.countDown()
                assertTrue(second.get(2, TimeUnit.SECONDS))
                assertEquals(0, calls.get())
                assertEquals(1, cancellations.get())
            } finally { caller.shutdownNow() }
        }
    }

    @Test fun timedOutQueuedCallbackStaysCancelledEvenAfterWorkerBecomesIdle() {
        HeldHandoff().use { fixture ->
            val calls = AtomicInteger()
            val cancelled = AtomicBoolean(false)
            assertThrows(TimeoutException::class.java) {
                boundedRecoveryCallback(fixture.worker, { !cancelled.get() }, { cancelled.set(true) }, 50) {
                    calls.incrementAndGet()
                }
            }
            assertTrue(cancelled.get())
            fixture.release.countDown()
            assertTrue(fixture.drained.await(2, TimeUnit.SECONDS))
            // A later sentinel confirms the cancelled queued Future has already been consumed.
            assertEquals(3, fixture.worker.submit<Int> { 3 }.get(2, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
        }
    }

    @Test fun capabilityPollingUsesLocalStateAndOnlyBracketsAuthority() {
        val localCalls = AtomicInteger()
        val authorityCalls = AtomicInteger()
        val owner = Thread.currentThread()
        assertTrue(readRecoveryCapability({ localCalls.incrementAndGet(); true }, {
            assertSame(owner, Thread.currentThread())
            authorityCalls.incrementAndGet()
            true
        }) { local -> repeat(100) { assertTrue(local()) }; true })
        assertEquals(100, localCalls.get())
        assertEquals(2, authorityCalls.get())
    }

    @Test fun revokedCapabilityBeforeOrAfterReadNeverReturnsTrustedSupport() {
        assertThrows(IllegalStateException::class.java) {
            readRecoveryCapability({ true }, { false }) { error("must not read") }
        }
        var live = true
        assertThrows(IllegalStateException::class.java) {
            readRecoveryCapability({ true }, { live }) { live = false; true }
        }
    }
}
