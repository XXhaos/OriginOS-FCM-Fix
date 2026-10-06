package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class GmsObservationRpcTest {
    private fun reason(result: GmsConnectionObservation) = (result as GmsConnectionObservation.Unknown).reason
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) { try { latch.await() } catch (_: InterruptedException) { } }
    }
    private fun raw(requestId: Long) = GmsObservationCodec.encode(GmsConnectionObservation.Unknown(requestId, GmsUnknown.IDENTITY_UNKNOWN))
    @Test fun rootCannotDispatchPayload() = runBlocking {
        val rpc = GmsObservationRpc()
        try { assertEquals(GmsUnknown.SHELL_UNAVAILABLE, reason(rpc.read(1, { 0 }, { error("no dispatch") }))) }
        finally { rpc.close() }
    }
    @Test fun requestBindingSizeAndMalformedTransportFailClosed() = runBlocking {
        listOf(raw(2), "x".repeat(12001), "address 1.2.3.4", null).forEach { payload ->
            val rpc = GmsObservationRpc()
            try { assertEquals(GmsUnknown.MALFORMED_RESPONSE, reason(rpc.read(1, { 2000 }, { payload }))) }
            finally { rpc.close() }
        }
    }
    @Test fun thrownPrivateErrorNeverLeaksIntoWire() = runBlocking {
        val rpc = GmsObservationRpc()
        try {
            val result = rpc.read(1, { 2000 }, { error("private IP 10.0.0.1") })
            assertEquals(GmsUnknown.BRIDGE_UNAVAILABLE, reason(result))
            assertFalse(GmsObservationCodec.encode(result).contains("10.0.0.1"))
        } finally { rpc.close() }
    }
    @Test fun stuckUidTimesOutAndNeverDispatchesLatePayload() = runBlocking {
        val rpc = GmsObservationRpc(100); val release = CountDownLatch(1); val dispatched = CountDownLatch(1)
        try {
            assertEquals(GmsUnknown.TIMED_OUT, reason(rpc.read(1, { awaitUninterruptibly(release); 2000 }, { dispatched.countDown(); raw(1) })))
            assertEquals(GmsUnknown.BUSY, reason(rpc.read(2, { 2000 }, { raw(2) })))
            release.countDown(); assertFalse(dispatched.await(150, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); rpc.close() }
    }
    @Test fun cancellationDiscardsStuckLatePayloadAndAllowsBoundedCleanup() = runBlocking {
        val rpc = GmsObservationRpc(); val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val result = AtomicReference<GmsConnectionObservation>(); val cleaned = CountDownLatch(1)
        val call = launch(Dispatchers.Default) { result.set(rpc.read(1, { 2000 }, {
            entered.countDown(); awaitUninterruptibly(release); raw(1)
        })) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); call.cancelAndJoin()
            assertEquals(GmsUnknown.CANCELLED, reason(result.get()))
            assertEquals(GmsUnknown.BUSY, reason(rpc.read(2, { 2000 }, { raw(2) })))
            rpc.cleanupBestEffort { cleaned.countDown() }
            assertTrue(cleaned.await(1, TimeUnit.SECONDS))
            release.countDown(); assertEquals(GmsUnknown.CANCELLED, reason(result.get()))
        } finally { release.countDown(); rpc.close() }
    }
    @Test fun synchronousReadinessOrBindIsBoundedWithoutBlockingSessionMonitor() = runBlocking {
        val rpc = GmsObservationRpc(100); val release = CountDownLatch(1)
        val session = PrivilegedSessionState<Any, Any>(); session.open()
        val ticket = requireNotNull(session.beginBind()); val connection = Any(); session.attachConnection(ticket, connection)
        try {
            try { rpc.bounded {
                check(session.isCurrentBind(ticket, connection)); awaitUninterruptibly(release)
            }; fail("expected timeout") } catch (_: TimeoutCancellationException) { }
            val start = System.nanoTime(); session.cleanup()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100)
            assertFalse(session.acceptBind(ticket, connection, Any()))
            try { rpc.bounded { true }; fail("expected BUSY") } catch (_: RejectedExecutionException) { }
        } finally { release.countDown(); rpc.close() }
    }
    @Test fun consecutiveHealthyPrerequisitesAndReadsNeverReturnSpuriousBusy() = runBlocking {
        val rpc = GmsObservationRpc()
        try {
            repeat(500) { index ->
                assertTrue(rpc.bounded { true })
                assertEquals(GmsUnknown.IDENTITY_UNKNOWN, reason(rpc.read(index + 1L, { 2000 }, { raw(index + 1L) })))
            }
        } finally { rpc.close() }
    }
}
