package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonServiceDrainTest {
    private fun DaemonServiceDrain.claimExit(creating: Boolean, callersQuiescent: Boolean, hostReleased: Boolean,
        observedHostIdentity: Any? = null): Boolean = claimExit(creating, { callersQuiescent }, hostReleased, observedHostIdentity)

    @Test fun ordinaryDetachOrIdleDoesNotAuthorizeExitAndDestroyPermanentlyClosesAdmission() {
        val drain = DaemonServiceDrain()
        assertFalse(drain.claimExit(false, true, true))
        assertTrue(drain.enter()); drain.leave()
        assertTrue(drain.closeAdmission())
        assertFalse(drain.closeAdmission())
        assertFalse(drain.enter())
        assertTrue(drain.claimExit(false, true, true))
        assertFalse(drain.claimExit(false, true, true))
    }

    @Test fun destroyCannotExitDuringBootstrapOrAnyAlreadyAdmittedTransaction() {
        val drain = DaemonServiceDrain()
        assertTrue(drain.enter()); assertTrue(drain.enter())
        drain.closeAdmission()
        assertFalse(drain.claimExit(false, true, true))
        drain.leave()
        assertFalse(drain.claimExit(false, true, true))
        drain.leave()
        assertFalse(drain.claimExit(true, true, true))
        assertTrue(drain.claimExit(false, true, true))
    }

    @Test fun actualCallerOrHostWorkersRetainProcessRegardlessOfRepeatedReapOpportunities() {
        val drain = DaemonServiceDrain()
        drain.closeAdmission()
        repeat(10_000) {
            assertFalse(drain.claimExit(false, false, true))
            assertFalse(drain.claimExit(false, true, false))
        }
        assertTrue(drain.claimExit(false, true, true))
    }

    @Test fun hostConstructedAcrossDestroyIsPublishedClosedAndStillBlocksExitUntilActualRelease() {
        val drain = DaemonServiceDrain()
        assertTrue(drain.enter())
        drain.closeAdmission()
        var tracked = false
        val host = Any()
        assertFalse(drain.publishHost(host) { tracked = true })
        assertTrue(tracked)
        assertFalse(drain.claimExit(false, true, true, host)) // Bootstrap still owns its transaction.
        drain.leave()
        assertFalse(drain.claimExit(false, true, false, host)) // Raced host still owns workers/lease.
        assertTrue(drain.claimExit(false, true, true, host))
    }

    @Test fun staleReleasedOrNullHostObservationCannotExitAfterRacedHostPublication() {
        val drain = DaemonServiceDrain()
        val old = Any(); val next = Any()
        assertTrue(drain.enter()); assertTrue(drain.publishHost(old) {})
        drain.leave()
        assertTrue(drain.enter()); drain.closeAdmission()
        assertFalse(drain.publishHost(next) {})
        drain.leave()
        assertFalse(drain.claimExit(false, true, true))
        assertFalse(drain.claimExit(false, true, true, old))
        assertFalse(drain.claimExit(false, true, false, next))
        assertTrue(drain.claimExit(false, true, true, next))
    }

    @Test fun actualCallerProbeStateIsReadOnlyAfterEveryAdmittedTransactionHasLeft() {
        val drain = DaemonServiceDrain()
        var probes = 0
        var quiescent = true
        val observe = { probes++; quiescent }
        assertTrue(drain.enter()); drain.closeAdmission()
        assertFalse(drain.claimExit(false, observe, true))
        assertEquals(0, probes)
        // The admitted transaction submits a worker and then times out; actual work remains.
        quiescent = false
        drain.leave()
        assertFalse(drain.claimExit(false, observe, true))
        assertEquals(1, probes)
        quiescent = true
        assertTrue(drain.claimExit(false, observe, true))
        assertEquals(2, probes)
    }
}
