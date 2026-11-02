package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DaemonReadRpcTest {
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }
    private fun await(latch: CountDownLatch) { assertTrue("worker entered", latch.await(5, TimeUnit.SECONDS)) }

    @Test fun tightSequentialReadsReleaseAdmissionBeforeContinuationAndNeverReturnFalseBusy() = runBlocking {
        val rpc = DaemonReadRpc()
        try { repeat(1_000) { index -> assertEquals(index, rpc.call { index }) } }
        finally { rpc.close() }
    }

    @Test fun coincidentReadsWaitForCompletionAndKeepOnlyOneActualActionActive() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rpc = DaemonReadRpc(); val active = AtomicInteger(); val secondCalls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.call {
                assertEquals(1, active.incrementAndGet()); entered.countDown()
                try { awaitUninterruptibly(release); "first" } finally { active.decrementAndGet() }
            }
        }
        try {
            await(entered)
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                rpc.call {
                    secondCalls.incrementAndGet(); assertEquals(1, active.incrementAndGet())
                    try { "second" } finally { active.decrementAndGet() }
                }
            }
            assertEquals(0, secondCalls.get()); assertFalse(second.isCompleted)
            release.countDown()
            assertEquals("first", first.await()); assertEquals("second", second.await())
            assertEquals(1, secondCalls.get()); assertEquals(0, active.get())
        } finally { release.countDown(); first.cancelAndJoin(); rpc.close() }
    }

    @Test fun timeoutOrCancellationWhileWaitingNeverInvokesTheQueuedBinderAction() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rpc = DaemonReadRpc(); val waitingCalls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.call { entered.countDown(); awaitUninterruptibly(release); "first" }
        }
        try {
            await(entered)
            assertNull(withTimeoutOrNull(30) { rpc.call { waitingCalls.incrementAndGet() } })
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { rpc.call { waitingCalls.incrementAndGet() } }
            cancelled.cancelAndJoin()
            assertEquals(0, waitingCalls.get())
            release.countDown(); assertEquals("first", first.await())
            assertEquals("next", rpc.call { "next" }); assertEquals(0, waitingCalls.get())
        } finally { release.countDown(); first.cancelAndJoin(); rpc.close() }
    }

    @Test fun canceledCompletionHandoffNeverRunsLateAndCannotAccumulateWorkerTasks() = runBlocking {
        val afterAction = CountDownLatch(1); val release = CountDownLatch(1)
        val paused = AtomicBoolean()
        val worker = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "test-daemon-read").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()) {
            override fun afterExecute(task: Runnable?, failure: Throwable?) {
                if (paused.compareAndSet(false, true)) { afterAction.countDown(); awaitUninterruptibly(release) }
                super.afterExecute(task, failure)
            }
        }
        val rpc = DaemonReadRpc(worker = worker); val lateCalls = AtomicInteger()
        try {
            assertEquals("first", rpc.call { "first" }); await(afterAction)
            // The first Runnable has published, but its worker deliberately cannot consume handoff.
            val handoff = async(start = CoroutineStart.UNDISPATCHED) { rpc.call { lateCalls.incrementAndGet() } }
            assertEquals(1, worker.queue.size)
            handoff.cancelAndJoin()
            assertFalse(rpc.isQuiescent())
            repeat(20) {
                try { rpc.call { lateCalls.incrementAndGet() }; fail("stale handoff must retain busy admission") }
                catch (_: RejectedExecutionException) { }
            }
            assertEquals(1, worker.queue.size); assertEquals(1, worker.largestPoolSize)
            release.countDown()
            withTimeout(5_000) { while (!rpc.isQuiescent()) yield() }
            assertEquals(0, lateCalls.get()); assertEquals("fresh", rpc.call { "fresh" })
        } finally { release.countDown(); rpc.close() }
    }

    @Test fun callerCancellationDuringActualBinderRetainsWorkerAndCannotInvokeNextBinderLater() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rpc = DaemonReadRpc(); val nextBinderCalls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.call { checkActive ->
                entered.countDown(); awaitUninterruptibly(release)
                checkActive(); nextBinderCalls.incrementAndGet()
            }
        }
        try {
            await(entered)
            // Timeout starts after the worker has definitely entered, independent of CPU load.
            assertNull(withTimeoutOrNull(30) { first.await() })
            first.cancelAndJoin()
            assertFalse(rpc.isQuiescent())
            repeat(20) {
                try { rpc.call { nextBinderCalls.incrementAndGet() }; fail("stuck worker must retain admission") }
                catch (_: RejectedExecutionException) { }
            }
            release.countDown()
            withTimeout(5_000) { while (!rpc.isQuiescent()) yield() }
            assertEquals(0, nextBinderCalls.get()); assertEquals(42, rpc.call { 42 })
        } finally { release.countDown(); first.cancelAndJoin(); rpc.close() }
    }
}
