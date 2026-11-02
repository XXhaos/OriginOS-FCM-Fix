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

class DaemonControlRpcTest {
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }
    private fun await(latch: CountDownLatch) { assertTrue("worker entered", latch.await(5, TimeUnit.SECONDS)) }

    @Test fun tightSequentialControlCallsNeverRejectTheirOwnCompletionHandoff() = runBlocking {
        val rpc = DaemonControlRpc()
        try { repeat(1_000) { index -> assertEquals(index, rpc.call { index }) } }
        finally { rpc.close() }
    }

    @Test fun nextControlWaitsInOneHandoffSlotAfterResumeBeforeWorkerExit() = runBlocking {
        val afterAction = CountDownLatch(1); val release = CountDownLatch(1)
        val paused = AtomicBoolean(); val secondWrites = AtomicInteger()
        val worker = object : ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "test-daemon-control").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()) {
            override fun afterExecute(task: Runnable?, failure: Throwable?) {
                if (paused.compareAndSet(false, true)) { afterAction.countDown(); awaitUninterruptibly(release) }
                super.afterExecute(task, failure)
            }
        }
        val rpc = DaemonControlRpc(worker = worker)
        try {
            assertEquals("bootstrap", rpc.call { "bootstrap" }); await(afterAction)
            val next = async(start = CoroutineStart.UNDISPATCHED) { rpc.call { secondWrites.incrementAndGet() } }
            assertEquals(1, worker.queue.size)
            assertFalse(next.isCompleted)
            assertEquals(0, secondWrites.get())
            assertEquals(1, worker.largestPoolSize)
            release.countDown()
            assertEquals(1, next.await())
        } finally { release.countDown(); rpc.close() }
    }

    @Test fun callerDeadlineDuringStuckWriteRetainsActualWorkerAndDoesNotRetryOrSendLate() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rpc = DaemonControlRpc(); val writes = AtomicInteger(); val laterBinder = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            rpc.call { checkActive ->
                writes.incrementAndGet(); entered.countDown(); awaitUninterruptibly(release)
                checkActive(); laterBinder.incrementAndGet()
            }
        }
        try {
            await(entered)
            // Start the caller's deadline only after the actual write has entered.
            assertNull(withTimeoutOrNull(30) { first.await() })
            first.cancelAndJoin()
            assertFalse(rpc.isQuiescent())
            repeat(10) {
                try { rpc.call { writes.incrementAndGet() }; fail("unsettled write must retain the worker") }
                catch (_: RejectedExecutionException) { }
            }
            assertEquals(1, writes.get())
            release.countDown()
            withTimeout(5_000) { while (!rpc.isQuiescent()) yield() }
            assertEquals(0, laterBinder.get())
            assertEquals(1, writes.get())
            assertEquals("new explicit call", rpc.call { "new explicit call" })
        } finally { release.countDown(); first.cancelAndJoin(); rpc.close() }
    }
}
