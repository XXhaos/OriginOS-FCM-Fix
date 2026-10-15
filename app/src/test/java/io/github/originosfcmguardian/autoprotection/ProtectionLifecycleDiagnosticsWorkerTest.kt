package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProtectionLifecycleDiagnosticsWorkerTest {
    @Test fun `resource sidecar failure remains outside lifecycle write failure and drop counters`() {
        fun field(name: String) = ProtectionLifecycleDiagnostics::class.java.getDeclaredField(name).apply { isAccessible = true }
        val failure = field("writeFailed")
        val drops = field("dropped").get(null) as java.util.concurrent.atomic.AtomicLong
        val availability = field("resourceAvailability")
        val resourceFailures = field("resourceFailed").get(null) as java.util.concurrent.atomic.AtomicLong
        val oldLifecycleFailure = failure.getBoolean(null)
        val oldDrops = drops.get()
        val oldAvailability = availability.get(null)
        val oldResourceFailures = resourceFailures.get()
        try {
            val enqueue = ProtectionLifecycleDiagnostics::class.java.getDeclaredMethod("enqueueResource", kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }
            enqueue.invoke(ProtectionLifecycleDiagnostics, { throw ProtectionResourceCorruption(IllegalArgumentException("fixture")) })
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(oldLifecycleFailure, failure.getBoolean(null))
            assertEquals(oldDrops, drops.get())
            val snapshot = ProtectionLifecycleDiagnostics.readResourceSnapshot()
            assertEquals(ProtectionResourceAvailability.CORRUPT, snapshot.availability)
            assertEquals(oldResourceFailures + 1, snapshot.failed)
        } finally {
            availability.set(null, oldAvailability)
            resourceFailures.set(oldResourceFailures)
        }
    }
    // Exercise the actual process-shared executor without Android storage/clock calls or a
    // production-only test accessor. Drain all work without shutting down the shared executor.
    private val worker: ThreadPoolExecutor
        get() = ProtectionLifecycleDiagnostics::class.java.getDeclaredField("worker").let {
            it.isAccessible = true
            it.get(null) as ThreadPoolExecutor
        }

    @Test fun `journal core worker remains available with the same bounded FIFO`() {
        val executor = worker
        assertEquals(0L, executor.getKeepAliveTime(TimeUnit.MILLISECONDS))
        assertFalse(executor.allowsCoreThreadTimeOut())
        assertEquals(1, executor.corePoolSize)
        assertEquals(1, executor.maximumPoolSize)
        assertEquals(64, executor.queue.remainingCapacity())
        val first = executor.submit<Thread> { Thread.currentThread() }.get(2, TimeUnit.SECONDS)
        first.join(100)
        assertTrue("A drained journal core worker must remain available", first.isAlive)
        assertEquals(1, executor.poolSize)

        val second = executor.submit<Thread> { Thread.currentThread() }.get(2, TimeUnit.SECONDS)
        assertSame(first, second)
        assertEquals(64, executor.queue.remainingCapacity())
    }

    @Test fun `single journal worker preserves FIFO and queue saturation rejects overflow`() {
        val executor = worker
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(64)
        val executed = AtomicInteger()
        val observed = mutableListOf<Int>()
        try {
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
            // Queued diagnostics cannot execute while the single actual worker is busy.
            assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, executor.poolSize)
            assertEquals(0, executed.get())
            assertThrows(RejectedExecutionException::class.java) { executor.execute { executed.incrementAndGet() } }
            release.countDown()
            val actualWorker = running.get(2, TimeUnit.SECONDS)
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            assertTrue(actualWorker.isAlive)
            assertEquals(64, executed.get())
            assertEquals((0 until 64).toList(), observed)
            assertEquals(64, executor.queue.remainingCapacity())
        } finally {
            release.countDown()
            // This does not shut down the process-shared executor used by other tests.
            drained.await(2, TimeUnit.SECONDS)
        }
    }
}
