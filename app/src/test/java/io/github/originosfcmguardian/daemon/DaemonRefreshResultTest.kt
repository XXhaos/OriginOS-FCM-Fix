package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DaemonRefreshResultTest {
    @Test fun cancellationPropagatesWithoutChangingTheSharedProjection() = runBlocking {
        var globalReady = true
        var unavailableCalls = 0
        val cancellation = CancellationException("Reader ended")
        try {
            daemonRefreshOrUnavailable<Any>({ throw cancellation }, { globalReady = false; unavailableCalls++ })
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(globalReady)
        assertEquals(0, unavailableCalls)
    }

    @Test fun closingThePanelDuringASuspendedReadDoesNotPublishGlobalUnavailable() = runBlocking {
        var globalReady = true
        var unavailableCalls = 0
        val entered = CompletableDeferred<Unit>()
        val reader = launch {
            daemonRefreshOrUnavailable<Any>({ entered.complete(Unit); awaitCancellation() },
                { globalReady = false; unavailableCalls++ })
        }
        entered.await()
        reader.cancel()
        reader.join()
        assertTrue(reader.isCancelled)
        assertTrue(globalReady)
        assertEquals(0, unavailableCalls)
    }

    @Test fun actualReadFailureStillPublishesUnavailableExactlyOnce() = runBlocking {
        var globalReady = true
        var unavailableCalls = 0
        val result = daemonRefreshOrUnavailable<Any>({ error("IPC read failed") },
            { globalReady = false; unavailableCalls++ })
        assertNull(result)
        assertFalse(globalReady)
        assertEquals(1, unavailableCalls)
    }

    @Test fun successfulReadReturnsItsResultWithoutPublishingUnavailable() = runBlocking {
        val snapshot = Any()
        assertSame(snapshot, daemonRefreshOrUnavailable({ snapshot }, { fail("Read succeeded") }))
    }
}
