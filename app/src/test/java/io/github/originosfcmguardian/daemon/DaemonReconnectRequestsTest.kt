package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DaemonReconnectRequestsTest {
    @Test fun simultaneousColdTileAndBinderEventsUseOneWorkerAndOnePendingPass() {
        val gate = DaemonReconnectRequests()
        assertTrue(gate.offer())
        repeat(1_000) { assertFalse(gate.offer()) }
        assertTrue(gate.takePending())
        repeat(1_000) { assertFalse(gate.offer()) }
        assertTrue(gate.takePending())
        repeat(1_000) { assertFalse(gate.offer()) } // Rebinding deaths cannot extend this worker forever.
        assertFalse(gate.takePending())
        assertFalse(gate.takePending())
        assertTrue(gate.offer())
    }

    @Test fun releasedOldHostGetsFiniteRetriesAndStopsOnSuccessfulFreshRestore() = runBlocking {
        var attempts = 0
        var failures = 0
        val waits = mutableListOf<Long>()
        assertTrue(reconnectDaemonWhileReady({ true }, {
            attempts++
            if (attempts < 3) error("Old host still draining")
        }, { failures++ }, { waits += it }))
        assertEquals(3, attempts)
        assertEquals(2, failures)
        assertEquals(listOf(1_000L, 4_000L), waits)
    }

    @Test fun temporaryShizukuAbsenceDoesNotScheduleOrAttemptRestore() = runBlocking {
        assertFalse(reconnectDaemonWhileReady({ false }, { fail("Shizuku unavailable") },
            { fail("No invocation failed") }, { fail("No timer while unavailable") }))
    }

    @Test fun privilegeLossDuringBackoffPreventsAnyFurtherAttempt() = runBlocking {
        var ready = true
        var attempts = 0
        assertFalse(reconnectDaemonWhileReady({ ready }, { attempts++; error("Host unavailable") }, {},
            { ready = false }))
        assertEquals(1, attempts)
    }

    @Test fun permanentlyUnavailableHostDoesNotStartAnIndefiniteRetryLoop() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        assertFalse(reconnectDaemonWhileReady({ true }, { attempts++; error("Unavailable") }, {}, { waits += it }))
        assertEquals(4, attempts)
        assertEquals(listOf(1_000L, 4_000L, 30_000L), waits)
    }

    @Test fun cancellationDoesNotBecomeAnotherRetryOrFailureAcknowledgement() = runBlocking {
        var attempts = 0
        try {
            reconnectDaemonWhileReady({ true }, { attempts++; throw CancellationException("Cancelled") },
                { fail("Cancellation is not a retryable failure") }, { fail("Cancelled") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }

    @Test fun localAttemptTimeoutRetriesWhileItsParentIsStillActive() = runBlocking {
        var attempts = 0
        var failures = 0
        assertTrue(reconnectDaemonWhileReady({ true }, {
            attempts++
            if (attempts == 1) withTimeout(1) { delay(1_000) }
        }, { failures++ }, {}))
        assertEquals(2, attempts)
        assertEquals(1, failures)
    }

    @Test fun enclosingTimeoutDoesNotBecomeARetryableAttemptTimeout() = runBlocking {
        var attempts = 0
        var failures = 0
        try {
            withTimeout(10) {
                reconnectDaemonWhileReady({ true }, { attempts++; delay(1_000) }, { failures++ }, {})
            }
            fail("Expected outer cancellation")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }
}
