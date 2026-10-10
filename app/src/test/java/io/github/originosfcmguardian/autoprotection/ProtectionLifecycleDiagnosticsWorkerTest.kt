package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProtectionLifecycleDiagnosticsWorkerTest {
    // Exercise the actual process-shared executor without Android storage/clock calls or a
    // production-only test accessor. Restore its keepalive and drain all work after the test.
    private val worker: ThreadPoolExecutor
        get() = ProtectionLifecycleDiagnostics::class.java.getDeclaredField("worker").let {
            it.isAccessible = true
            it.get(null) as ThreadPoolExecutor
        }

    @Test fun `journal worker expires while idle and restarts with the same bounded FIFO`() {
        val executor = worker
        val keepAlive = executor.getKeepAliveTime(TimeUnit.MILLISECONDS)
        assertEquals(30_000L, keepAlive)
        assertTrue(executor.allowsCoreThreadTimeOut())
        assertEquals(1, executor.corePoolSize)
        assertEquals(1, executor.maximumPoolSize)
        assertEquals(64, executor.queue.remainingCapacity())
        try {
            executor.setKeepAliveTime(20, TimeUnit.MILLISECONDS)
            val first = executor.submit<Thread> { Thread.currentThread() }.get(2, TimeUnit.SECONDS)
            first.join(2_000)
            assertFalse("A drained journal worker must expire", first.isAlive)
            assertEquals(0, executor.poolSize)

            val second = executor.submit<Thread> { Thread.currentThread() }.get(2, TimeUnit.SECONDS)
            assertNotSame(first, second)
            second.join(2_000)
            assertFalse("The restarted journal worker must expire again", second.isAlive)
            assertEquals(64, executor.queue.remainingCapacity())
        } finally {
            executor.setKeepAliveTime(keepAlive, TimeUnit.MILLISECONDS)
        }
    }

    @Test fun `busy journal worker survives idle duration and queue saturation rejects overflow`() {
        val executor = worker
        val keepAlive = executor.getKeepAliveTime(TimeUnit.MILLISECONDS)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(64)
        val executed = AtomicInteger()
        val observed = mutableListOf<Int>()
        try {
            executor.setKeepAliveTime(20, TimeUnit.MILLISECONDS)
            val running = executor.submit<Thread> {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                Thread.currentThread()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(64) { index ->
                executor.execute { observed.add(index); executed.incrementAndGet(); drained.countDown() }
            }
            assertThrows(RejectedExecutionException::class.java) { executor.execute { executed.incrementAndGet() } }
            // A barrier timeout spans several idle periods while the one actual worker is busy.
            assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, executor.poolSize)
            assertEquals(0, executed.get())
            assertThrows(RejectedExecutionException::class.java) { executor.execute { executed.incrementAndGet() } }
            release.countDown()
            val actualWorker = running.get(2, TimeUnit.SECONDS)
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            actualWorker.join(2_000)
            assertFalse(actualWorker.isAlive)
            assertEquals(64, executed.get())
            assertEquals((0 until 64).toList(), observed)
            assertEquals(64, executor.queue.remainingCapacity())
        } finally {
            release.countDown()
            // This does not shut down the process-shared executor used by other tests.
            try { drained.await(2, TimeUnit.SECONDS) }
            finally { executor.setKeepAliveTime(keepAlive, TimeUnit.MILLISECONDS) }
        }
    }
}
