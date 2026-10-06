package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class StoppedObservationRpcTest {
    private fun known(requestId: Long) = StoppedTargetObservation.Known(
        requestId, SelectedTargetIdentity(STOPPED_TARGET_PACKAGE, 10234, 123L, listOf("a".repeat(64))),
        stopped = false, exclusiveUidOwner = true, observedAtMillis = 456L,
    )
    private fun reason(result: StoppedTargetObservation) = (result as StoppedTargetObservation.Unknown).reason
    private fun uninterruptibleAwait(latch: CountDownLatch) {
        while (latch.count > 0) { try { latch.await() } catch (_: InterruptedException) { } }
    }

    @Test fun successfulReadRejectsNonShellAndMalformedTransport() = runBlocking {
        val rpc = StoppedObservationRpc()
        try {
            assertEquals(known(1), rpc.read(1, { 2000 }, { StoppedObservationCodec.encode(known(1)) }))
            // Use separate instances to avoid a completed noqueue worker's handoff race.
        } finally { rpc.close() }
        val wrongUid = StoppedObservationRpc()
        try {
            assertEquals(StoppedObservationUnknown.SHELL_UNAVAILABLE, reason(wrongUid.read(2, { 0 }, { error("Must not dispatch") })))
        } finally { wrongUid.close() }
        val malformed = StoppedObservationRpc()
        try {
            assertEquals(StoppedObservationUnknown.MALFORMED_RESPONSE, reason(malformed.read(3, { 2000 }, { "invalid" })))
        } finally { malformed.close() }
    }

    @Test fun stuckUidTimesOutWithoutDispatchingLateReadAndRejectsMoreWork() = runBlocking {
        val rpc = StoppedObservationRpc(100)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val dispatched = CountDownLatch(1)
        val reads = AtomicInteger()
        val startedAt = System.nanoTime()
        try {
            val result = rpc.read(1, {
                entered.countDown(); uninterruptibleAwait(release); finished.countDown(); 2000
            }, { dispatched.countDown(); reads.incrementAndGet(); StoppedObservationCodec.encode(known(1)) })
            assertEquals(StoppedObservationUnknown.TIMED_OUT, reason(result))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(StoppedObservationUnknown.BUSY, reason(rpc.read(2, { 2000 }, { error("Must not dispatch") })))
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertFalse(dispatched.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, reads.get())
        } finally { release.countDown(); rpc.close() }
    }

    @Test fun stuckReadTimesOutDiscardsLateKnownAndDoesNotBlockCleanupScheduling() = runBlocking {
        val rpc = StoppedObservationRpc(100)
        val release = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val startedAt = System.nanoTime()
        try {
            val result = rpc.read(1, { 2000 }, { uninterruptibleAwait(release); StoppedObservationCodec.encode(known(1)) })
            assertEquals(StoppedObservationUnknown.TIMED_OUT, reason(result))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000)
            assertEquals(StoppedObservationUnknown.BUSY, reason(rpc.read(2, { 2000 }, { error("Must not dispatch") })))
            rpc.cleanupBestEffort { cleanupEntered.countDown(); uninterruptibleAwait(cleanupRelease) }
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            rpc.cleanupBestEffort { error("A stuck cleanup must not accumulate queued work") }
            release.countDown()
            assertEquals(StoppedObservationUnknown.TIMED_OUT, reason(result))
        } finally { release.countDown(); cleanupRelease.countDown(); rpc.close() }
    }

    @Test fun cancellationOfStuckUidReturnsPromptlyAndNeverDispatchesRead() = runBlocking {
        val rpc = StoppedObservationRpc(2_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uidFinished = CountDownLatch(1)
        val dispatched = CountDownLatch(1)
        val result = AtomicReference<StoppedTargetObservation>()
        val reads = AtomicInteger()
        val call = launch(Dispatchers.Default) {
            result.set(rpc.read(1, {
                entered.countDown(); uninterruptibleAwait(release); uidFinished.countDown(); 2000
            }, { dispatched.countDown(); reads.incrementAndGet(); StoppedObservationCodec.encode(known(1)) }))
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            call.cancel()
            call.join()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000)
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(result.get()))
            assertEquals(StoppedObservationUnknown.BUSY, reason(rpc.read(2, { 2000 }, { error("Must not dispatch") })))
            release.countDown()
            assertTrue(uidFinished.await(1, TimeUnit.SECONDS))
            assertFalse(dispatched.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, reads.get())
        } finally { release.countDown(); rpc.close() }
    }

    @Test fun cancellationOfStuckReadDiscardsLateKnown() = runBlocking {
        val rpc = StoppedObservationRpc(2_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<StoppedTargetObservation>()
        val call = launch(Dispatchers.Default) {
            result.set(rpc.read(1, { 2000 }, {
                entered.countDown(); uninterruptibleAwait(release); StoppedObservationCodec.encode(known(1))
            }))
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            call.cancel()
            call.join()
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(result.get()))
            assertEquals(StoppedObservationUnknown.BUSY, reason(rpc.read(2, { 2000 }, { error("Must not dispatch") })))
            release.countDown()
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(result.get()))
        } finally { release.countDown(); rpc.close() }
    }
}
