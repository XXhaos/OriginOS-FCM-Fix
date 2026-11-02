package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonAdoptionRetryTest {
    @Test fun transientPreflightFailureHasOneLaterEventOpportunityThenStops() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(2) {
            val ticket = requireNotNull(gate.offer(server, eligible = true, local = true))
            assertTrue(gate.start(ticket))
            gate.finish(ticket)
        }
        repeat(1_000) { assertNull(gate.offer(server, eligible = true, local = true)) }
    }

    @Test fun duplicateReadyCallbacksDoNotReopenExhaustedBudgetButNewServerDoes() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(2) {
            val ticket = requireNotNull(gate.offer(server, true, true))
            assertTrue(gate.start(ticket)); gate.finish(ticket)
        }
        repeat(100) { gate.readiness(server); assertNull(gate.offer(server, true, true)) }
        val replacement = requireNotNull(gate.offer(Any(), true, true))
        assertTrue(gate.start(replacement))
    }

    @Test fun actualPermissionLossAndReturnReopensBudgetEvenOnSameServer() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(2) {
            val ticket = requireNotNull(gate.offer(server, true, true))
            gate.start(ticket); gate.finish(ticket)
        }
        gate.readiness(null)
        assertNull(gate.offer(null, true, true))
        assertNotNull(gate.offer(server, true, true))
    }

    @Test fun unreadReadinessSamplesCannotReplenishSameServerBudget() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(2) {
            val ticket = requireNotNull(gate.offer(server, true, true))
            gate.start(ticket); gate.finish(ticket)
        }
        repeat(100) {
            assertNull(gate.offer(null, true, true))
            assertNull(gate.offer(server, true, true))
        }
    }

    @Test fun offUnavailableAndNonlocalCandidatesCannotStartOrSpendBudget() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(100) {
            assertNull(gate.offer(server, eligible = false, local = true))
            assertNull(gate.offer(server, eligible = true, local = false))
        }
        val ticket = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(ticket))
        assertFalse(gate.start(ticket))
    }

    @Test fun queuedCancellationDoesNotConsumeButDispatchedCancellationStillCounts() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(10) {
            val queued = requireNotNull(gate.offer(server, true, true))
            gate.finish(queued)
        }
        repeat(2) {
            val started = requireNotNull(gate.offer(server, true, true))
            assertTrue(gate.start(started))
            gate.finish(started)
        }
        assertNull(gate.offer(server, true, true))
    }

    @Test fun newReadinessEpochCannotOverlapOldWorkerOrBeConsumedByItsLateStart() {
        val gate = DaemonAdoptionRetry()
        val old = requireNotNull(gate.offer(Any(), true, true))
        val replacement = Any()
        gate.readiness(replacement)
        assertNull(gate.offer(replacement, true, true))
        assertFalse(gate.start(old))
        gate.finish(old)
        repeat(2) {
            val ticket = requireNotNull(gate.offer(replacement, true, true))
            assertTrue(gate.start(ticket)); gate.finish(ticket)
        }
        assertNull(gate.offer(replacement, true, true))
    }

    @Test fun readinessLossBetweenAdmissionAndPreflightBlocksThatWorker() {
        val gate = DaemonAdoptionRetry()
        val ticket = requireNotNull(gate.offer(Any(), true, true))
        gate.readiness(null)
        assertFalse(gate.start(ticket))
        gate.finish(ticket)
    }

    @Test fun candidateEventsDuringFlightCoalesceButCannotExtendEpochBudget() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        val first = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(first))
        repeat(1_000) { assertNull(gate.offer(server, true, true)) }
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(second))
        repeat(1_000) { assertNull(gate.offer(server, true, true)) }
        assertFalse(gate.finish(second))
    }

    @Test fun offOrMigrationMarkerWhileFlightIsActiveDiscardsPendingCandidate() {
        for (local in listOf(true, false)) {
            val gate = DaemonAdoptionRetry()
            val server = Any()
            val ticket = requireNotNull(gate.offer(server, true, true))
            gate.start(ticket)
            assertNull(gate.offer(server, true, true))
            assertNull(gate.offer(server, eligible = !local, local = local))
            assertFalse(gate.finish(ticket))
        }
    }

    @Test fun preflightResultFromOldServerCannotAuthorizeMigrationAfterReplacement() {
        val gate = DaemonAdoptionRetry()
        val ticket = requireNotNull(gate.offer(Any(), true, true))
        assertTrue(gate.start(ticket))
        assertTrue(gate.current(ticket))
        gate.readiness(Any())
        assertFalse(gate.current(ticket))
        gate.finish(ticket)
        assertFalse(gate.current(ticket))
    }

    @Test fun foregroundActionReopensAnExhaustedSameServerBatchWithoutMakingPollingUnbounded() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        repeat(3) {
            if (it != 0) gate.rearm(server) // A real foreground/permission action, not a state update.
            repeat(2) {
                val ticket = requireNotNull(gate.offer(server, true, true))
                assertTrue(gate.start(ticket)); gate.finish(ticket)
            }
            repeat(100) { assertNull(gate.offer(server, true, true)) }
        }
    }

    @Test fun rearmDoesNotOverlapOrInvalidateAnAlreadyStartedPreflight() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        val running = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(running))
        gate.rearm(server)
        assertTrue(gate.current(running))
        assertNull(gate.offer(server, true, true))
        assertTrue(gate.finish(running))
        val next = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(next)); gate.finish(next)
        assertNull(gate.offer(server, true, true))
    }

    @Test fun aTransientColdBindFailureUsesAtMostOneImmediateReadOnlyRetry() {
        val gate = DaemonAdoptionRetry()
        val server = Any()
        val first = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(first))
        gate.retryReadOnlyFailure(first, DaemonAdoptionReason.BIND_TIMEOUT)
        assertTrue(gate.finish(first))
        val second = requireNotNull(gate.offer(server, true, true))
        assertTrue(gate.start(second))
        gate.retryReadOnlyFailure(second, DaemonAdoptionReason.BIND_TIMEOUT)
        assertFalse(gate.finish(second))
        assertNull(gate.offer(server, true, true))
    }

    @Test fun denialMalformedResponseOrOldEpochCannotScheduleAnAutomaticRetry() {
        for (reason in listOf(DaemonAdoptionReason.PERMISSION_DENIED, DaemonAdoptionReason.WRONG_SERVICE_UID,
            DaemonAdoptionReason.INVALID_RESPONSE, DaemonAdoptionReason.PRINCIPAL_CHANGED)) {
            val gate = DaemonAdoptionRetry()
            val first = requireNotNull(gate.offer(Any(), true, true))
            assertTrue(gate.start(first))
            gate.retryReadOnlyFailure(first, reason)
            assertFalse(gate.finish(first))
        }
        val gate = DaemonAdoptionRetry()
        val first = requireNotNull(gate.offer(Any(), true, true))
        assertTrue(gate.start(first))
        gate.readiness(null)
        gate.retryReadOnlyFailure(first, DaemonAdoptionReason.BIND_DISCONNECTED)
        assertFalse(gate.finish(first))
        gate.rearm(null)
        assertNull(gate.offer(null, true, true))
    }
}
