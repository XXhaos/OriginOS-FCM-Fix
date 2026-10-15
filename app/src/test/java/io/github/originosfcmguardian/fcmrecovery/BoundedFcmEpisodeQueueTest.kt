package io.github.originosfcmguardian.fcmrecovery

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

class BoundedFcmEpisodeQueueTest {
    @Test fun migrationDrainWaitsForActualFifoCompletionWithoutDiscardingEvidence() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = BoundedFcmEpisodeQueue({}, { _, _ -> }, { _, _ -> }, {}, capacity = 4)
        try {
            queue.offer(1000, 100) { entered.countDown(); release.await(5, TimeUnit.SECONDS); events += "old" }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.offer(2000, 200) { events += "tail" }
            val drain = async { queue.awaitMigrationDrain() }
            yield()
            assertFalse(drain.isCompleted)
            release.countDown()
            assertTrue(withTimeout(5_000) { drain.await() })
            assertEquals(listOf("old", "tail"), events)
        } finally { release.countDown(); queue.closeForTest() }
    }
    @Test fun blockedInitializationNeverBlocksCallerAndOverflowInvalidatesOldTasks() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val published = CountDownLatch(1); val recoveryPublished = CountDownLatch(1)
        val dropped = AtomicLong(0)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = BoundedFcmEpisodeQueue({ entered.countDown(); release.await(5, TimeUnit.SECONDS) },
            { _, _ -> events += "gap" }, { count, gap ->
                if (count > 0 && !gap) recoveryPublished.countDown()
                published.countDown()
            }, { dropped.set(it) }, capacity = 1)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.offer(1000, 100) { events += "old-connected" }
            queue.offer(1100, 110) { events += "rejected" }
            assertEquals(1, dropped.get())
            release.countDown(); assertTrue(published.await(5, TimeUnit.SECONDS))
            // Old task carries the invalid epoch. Wait for it to leave the finite queue.
            val accepted = CountDownLatch(1)
            repeat(20) {
                if (accepted.count > 0) {
                    queue.offer(1200 + it.toLong(), 120 + it.toLong()) { events += "fresh"; accepted.countDown() }
                    accepted.await(20, TimeUnit.MILLISECONDS)
                }
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            assertTrue(recoveryPublished.await(5, TimeUnit.SECONDS))
            assertFalse(events.contains("old-connected")); assertFalse(events.contains("rejected"))
            assertEquals(listOf("gap", "fresh"), events)
        } finally { release.countDown(); queue.closeForTest() }
    }

    @Test fun exceptionInOptionalEvidenceInvalidatesCoverageWithoutThrowingToCaller() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val queue = BoundedFcmEpisodeQueue({}, { _, _ -> events += "gap" }, { _, _ -> }, { rejected.countDown() }, capacity = 4)
        try {
            queue.offer(1000, 100) { throw IllegalStateException("diagnostic only") }
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            queue.offer(2000, 200) { events += "next"; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(listOf("gap", "next"), events)
        } finally { queue.closeForTest() }
    }

    @Test fun lateWorkerSnapshotCannotEraseNewerDropAndDropCallbacksAreMonotonic() {
        val newer = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.UNAVAILABLE, droppedUpdates = 2)
        val older = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, droppedUpdates = 1)
        assertEquals(newer, mergeFcmEpisodeSnapshot(newer, older))
        assertEquals(newer, droppedFcmEpisodeSnapshot(newer, 1))
        val current = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, droppedUpdates = 2)
        assertEquals(current, mergeFcmEpisodeSnapshot(newer, current))
    }
}
