package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GmsRpcLanesTest {
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }
    private fun raw(id: Long) = GmsObservationCodec.encode(GmsConnectionObservation.Unknown(id, GmsUnknown.IDENTITY_UNKNOWN))
    private fun reason(result: GmsConnectionObservation) = (result as GmsConnectionObservation.Unknown).reason

    @Test fun freshClientsCannotReplaceStuckBackgroundWorkerAndOtherLanesRemainUsable() = runBlocking {
        val owner = GmsRpcLanes(100)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val actualCalls = AtomicInteger()
        try {
            val firstClient = owner.lane(GmsRpcLane.BACKGROUND_OBSERVATION)
            assertEquals(GmsUnknown.TIMED_OUT, reason(firstClient.read(1, { 2000 }, {
                actualCalls.incrementAndGet(); entered.countDown(); awaitUninterruptibly(release); raw(1)
            })))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { index ->
                // Each lookup represents a new poll/session executor; it must retain the occupied admission.
                val freshClient = owner.lane(GmsRpcLane.BACKGROUND_OBSERVATION)
                assertSame(firstClient, freshClient)
                assertEquals(GmsUnknown.BUSY, reason(freshClient.read(index + 2L, {
                    actualCalls.incrementAndGet(); 2000
                }, { error("busy clients must never reach the remote") })))
            }
            assertEquals(1, actualCalls.get())
            assertEquals(GmsUnknown.IDENTITY_UNKNOWN, reason(owner.lane(GmsRpcLane.INTERACTIVE_OBSERVATION)
                .read(1, { 2000 }, { raw(1) })))
            assertEquals("heartbeat", owner.lane(GmsRpcLane.HEARTBEAT_WRITE).bounded { "heartbeat" })
            assertEquals("revoke", owner.lane(GmsRpcLane.REVOKE).bounded { "revoke" })
        } finally { release.countDown(); owner.close() }
    }

    @Test fun cancelledCallerDiscardsLateResultWithoutOpeningAdmissionForAnotherClient() = runBlocking {
        val owner = GmsRpcLanes()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicReference<GmsConnectionObservation>()
        val call = launch(Dispatchers.Default) {
            result.set(owner.lane(GmsRpcLane.BACKGROUND_OBSERVATION).read(1, { 2000 }, {
                entered.countDown(); awaitUninterruptibly(release); returned.countDown(); raw(1)
            }))
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            call.cancelAndJoin()
            assertEquals(GmsUnknown.CANCELLED, reason(result.get()))
            assertEquals(GmsUnknown.BUSY, reason(owner.lane(GmsRpcLane.BACKGROUND_OBSERVATION)
                .read(2, { 2000 }, { raw(2) })))
            release.countDown()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertEquals(GmsUnknown.CANCELLED, reason(result.get()))
        } finally { call.cancelAndJoin(); release.countDown(); owner.close() }
    }

    @Test fun stuckRevokeBoundsAllCleanupWhileLocalOffImmediatelyRejectsLateBinding() = runBlocking {
        val owner = GmsRpcLanes(100)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val revokeCalls = AtomicInteger()
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        val ticket = requireNotNull(state.beginBind())
        val connection = Any()
        assertTrue(state.attachConnection(ticket, connection))
        try {
            assertTrue(owner.lane(GmsRpcLane.REVOKE).cleanupBestEffort {
                revokeCalls.incrementAndGet(); entered.countDown(); awaitUninterruptibly(release)
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            // This is the ordering used by executor retirement: local state first, asynchronous cleanup second.
            state.close()
            repeat(100) {
                assertFalse(owner.lane(GmsRpcLane.REVOKE).cleanupBestEffort { revokeCalls.incrementAndGet() })
            }
            try {
                owner.lane(GmsRpcLane.REVOKE).bounded { revokeCalls.incrementAndGet() }
                fail("a new revoke must not queue behind an abandoned one")
            } catch (_: RejectedExecutionException) { }
            assertEquals(1, revokeCalls.get())
            assertFalse(state.isOpen())
            assertNull(state.beginBind())
            assertFalse(state.acceptBind(ticket, connection, "late remote"))
            assertTrue(owner.lane(GmsRpcLane.BACKGROUND_OBSERVATION).bounded { true })
            assertTrue(owner.lane(GmsRpcLane.INTERACTIVE_OBSERVATION).bounded { true })
            assertTrue(owner.lane(GmsRpcLane.HEARTBEAT_WRITE).bounded { true })
        } finally { release.countDown(); owner.close() }
    }

    @Test fun boundedRevokeAndAsyncCleanupShareOneAdmissionAndRetiredTestOwnerCannotReopenIt() = runBlocking {
        val owner = GmsRpcLanes(100)
        val release = CountDownLatch(1)
        try {
            try {
                owner.lane(GmsRpcLane.REVOKE).bounded { awaitUninterruptibly(release) }
                fail("expected revoke timeout")
            } catch (_: TimeoutCancellationException) { }
            assertFalse(owner.lane(GmsRpcLane.REVOKE).cleanupBestEffort { error("no replacement worker") })
            owner.close()
            assertFalse(owner.lane(GmsRpcLane.REVOKE).cleanupBestEffort { error("retired owner") })
            val independent = GmsRpcLanes(100)
            try { assertTrue(independent.lane(GmsRpcLane.REVOKE).bounded { true }) }
            finally { independent.close() }
        } finally { release.countDown(); owner.close() }
    }
}
