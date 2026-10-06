package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedAutomaticTargetReadTest {
    private val healthy = AutomaticTargetObservation(true, TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 123))

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    private suspend fun awaitEntered(latch: CountDownLatch) = withTimeout(2_000) {
        while (latch.count > 0) delay(5)
    }

    @Test fun qualificationAndStoppedEvidenceTravelTogetherAndFailuresStayUnknown() = runBlocking {
        BoundedAutomaticTargetRead().use { reader ->
            assertEquals(listOf(healthy), reader.readChunk(listOf(1)) { healthy })
            val failure = reader.readChunk(listOf(1)) { error("fixture") }.single()
            assertNull(failure.qualified)
            assertNull(failure.stopped)
            assertEquals(AutomaticTargetReadFailure.FAILED, failure.failure)
            assertFalse(failure.locallyUnavailable)
            assertFalse(maySkipLocallyUnavailableTarget(failure, AutomaticCheckDecision.Recheck("unknown")))
        }
    }

    @Test fun chunkReadsOverlapButReturnedEvidenceKeepsTargetOrder() = runBlocking {
        val firstRelease = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val entered = CountDownLatch(2)
        BoundedAutomaticTargetRead().use { reader ->
            try {
                val result = async {
                    reader.readChunk(listOf(1, 2)) { target ->
                        entered.countDown()
                        if (target == 1) awaitIgnoringInterrupts(firstRelease) else secondCompleted.countDown()
                        healthy.copy(stopped = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, target.toLong()))
                    }
                }
                awaitEntered(entered)
                assertTrue(secondCompleted.await(1, TimeUnit.SECONDS))
                assertFalse(result.isCompleted)
                firstRelease.countDown()
                assertEquals(listOf(1L, 2L), result.await().map { it.stopped?.checkedAtEpochMillis })
            } finally { firstRelease.countDown() }
        }
    }

    @Test fun timeoutAbandonsLateRepliesWithoutQueuingOrReplacingStuckWorkers() = runBlocking {
        val release = CountDownLatch(1)
        val exited = CountDownLatch(2)
        val entered = AtomicInteger()
        val publications = mutableListOf<List<AutomaticTargetObservation>>()
        BoundedAutomaticTargetRead(150, maxParallel = 2).use { reader ->
            try {
                publications += withTimeout(2_000) {
                    reader.readChunk(listOf(1, 2)) {
                        entered.incrementAndGet()
                        try { awaitIgnoringInterrupts(release); healthy } finally { exited.countDown() }
                    }
                }
                assertEquals(2, entered.get())
                assertTrue(publications.single().all { it.failure == AutomaticTargetReadFailure.TIMED_OUT })
                repeat(4) {
                    val rejected = reader.readChunk(listOf(3, 4)) { entered.incrementAndGet(); healthy }
                    assertTrue(rejected.all { it.failure == AutomaticTargetReadFailure.BUSY })
                }
                assertEquals(2, entered.get())
                release.countDown()
                assertTrue(exited.await(2, TimeUnit.SECONDS))
                delay(30)
                assertEquals(1, publications.size)
                assertTrue(publications.single().all { it.failure == AutomaticTargetReadFailure.TIMED_OUT })
            } finally { release.countDown() }
        }
    }

    @Test fun cancelDiscardsBothLateResultsPromptly() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(2)
        BoundedAutomaticTargetRead().use { reader ->
            var published = false
            try {
                val job = launch {
                    reader.readChunk(listOf(1, 2)) {
                        entered.countDown()
                        try { awaitIgnoringInterrupts(release); healthy } finally { exited.countDown() }
                    }
                    published = true
                }
                awaitEntered(entered)
                withTimeout(1_000) { job.cancelAndJoin() }
                assertFalse(published)
                release.countDown()
                assertTrue(exited.await(2, TimeUnit.SECONDS))
                delay(30)
                assertFalse(published)
            } finally { release.countDown() }
        }
    }

    @Test fun callerDeadlineIsNotConvertedIntoLocalTimeoutEvidence() = runBlocking {
        val release = CountDownLatch(1)
        BoundedAutomaticTargetRead(5_000).use { reader ->
            try {
                val result = withTimeoutOrNull(100) {
                    reader.readChunk(listOf(1)) { awaitIgnoringInterrupts(release); healthy }
                }
                assertNull(result)
            } finally { release.countDown() }
        }
    }

    @Test fun finiteChunksStayWithinConfiguredReadsAndSerialConsumerPreservesRotatedOrder() = runBlocking {
        val activeReads = AtomicInteger()
        val maxReads = AtomicInteger()
        var activeRecovery = 0
        var maxRecovery = 0
        val consumed = mutableListOf<Long?>()
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val rotated = listOf(4, 5, 1, 2, 3)
        val overflowReads = AtomicInteger()
        BoundedAutomaticTargetRead().use { reader ->
            for (chunk in rotated.chunked(BoundedAutomaticTargetRead.CHUNK_SIZE)) {
                val entered = CountDownLatch(chunk.size)
                val release = CountDownLatch(1)
                val pending = async {
                    reader.readChunk(chunk) { target ->
                        val current = activeReads.incrementAndGet()
                        maxReads.updateAndGet { maxOf(it, current) }
                        entered.countDown()
                        try {
                            awaitIgnoringInterrupts(release)
                            completed += target
                            healthy.copy(stopped = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, target.toLong()))
                        } finally { activeReads.decrementAndGet() }
                    }
                }
                val observations = try {
                    awaitEntered(entered)
                    assertEquals(chunk.size, activeReads.get())
                    assertTrue(maxReads.get() <= BoundedAutomaticTargetRead.CHUNK_SIZE)
                    assertFalse(pending.isCompleted)
                    if (chunk.size == BoundedAutomaticTargetRead.CHUNK_SIZE) {
                        assertEquals(AutomaticTargetReadFailure.BUSY,
                            reader.readChunk(listOf(6)) { overflowReads.incrementAndGet(); healthy }.single().failure)
                        assertEquals(0, overflowReads.get())
                    }
                    release.countDown()
                    pending.await()
                } finally { release.countDown() }
                assertEquals(0, activeReads.get())
                for (observation in observations) {
                    activeRecovery++
                    maxRecovery = maxOf(maxRecovery, activeRecovery)
                    consumed += observation.stopped?.checkedAtEpochMillis
                    delay(5)
                    activeRecovery--
                }
            }
        }
        assertEquals(rotated.map { it.toLong() }, consumed)
        assertEquals(rotated.sorted(), completed.sorted())
        assertEquals(BoundedAutomaticTargetRead.CHUNK_SIZE, maxReads.get())
        assertEquals(1, maxRecovery)
        assertEquals(0, overflowReads.get()) // Rejected overflow never runs after the chunk releases.
    }

    @Test fun localSkipRequiresFreshClearAuthorizedDecisionAndDoesNotRecoverUnknown() {
        for (failure in listOf(AutomaticTargetReadFailure.TIMED_OUT, AutomaticTargetReadFailure.BUSY)) {
            val observation = AutomaticTargetObservation(failure = failure)
            val clear = automaticCheckDecision(true, true, false, false, TargetStoppedStatus.UNKNOWN)
            assertTrue(maySkipLocallyUnavailableTarget(observation, clear))
            assertFalse(maySkipLocallyUnavailableTarget(observation,
                automaticCheckDecision(false, true, false, false, TargetStoppedStatus.UNKNOWN)))
            assertFalse(maySkipLocallyUnavailableTarget(observation,
                automaticCheckDecision(true, false, false, false, TargetStoppedStatus.UNKNOWN)))
            assertFalse(maySkipLocallyUnavailableTarget(observation,
                automaticCheckDecision(true, true, true, false, TargetStoppedStatus.UNKNOWN)))
            assertFalse(maySkipLocallyUnavailableTarget(observation,
                automaticCheckDecision(true, true, false, true, TargetStoppedStatus.UNKNOWN)))
            assertFalse(maySkipLocallyUnavailableTarget(observation, AutomaticCheckDecision.Recover))
        }
        assertFalse(maySkipLocallyUnavailableTarget(AutomaticTargetObservation(true,
            TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN)), AutomaticCheckDecision.Recheck("unknown")))
        assertFalse(maySkipLocallyUnavailableTarget(AutomaticTargetObservation(false), AutomaticCheckDecision.Pause("ineligible")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedChunkIsRejectedBeforeCreatingQueuedWork(): Unit = runBlocking {
        BoundedAutomaticTargetRead().use { reader -> reader.readChunk((1..9).toList()) { healthy }; Unit }
    }
}
