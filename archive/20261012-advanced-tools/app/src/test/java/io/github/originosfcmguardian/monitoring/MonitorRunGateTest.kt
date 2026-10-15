package io.github.originosfcmguardian.monitoring

import org.junit.Assert.*
import org.junit.Test

class MonitorRunGateTest {
    @Test fun lateServiceCannotClaimCancelledReservationOrNewSession() {
        val gate = MonitorRunGate()
        assertTrue(gate.reserve("first"))
        assertTrue(gate.finish("first"))
        assertTrue(gate.reserve("second"))
        assertFalse(gate.claim("first"))
        assertFalse(gate.finish("first"))
        assertEquals("second", gate.id)
        assertTrue(gate.claim("second"))
    }

    @Test fun duplicateStartClaimAndFinishAreRejected() {
        val gate = MonitorRunGate()
        assertTrue(gate.reserve("one"))
        assertFalse(gate.reserve("two"))
        assertTrue(gate.claim("one"))
        assertFalse(gate.claim("one"))
        assertTrue(gate.finish("one"))
        assertFalse(gate.finish("one"))
        assertNull(gate.id)
    }

    @Test fun endingAReservationInvalidatesAnEarlierHistoryRead() {
        val gate = MonitorRunGate()
        val before = gate.revision
        gate.reserve("one")
        gate.finish("one")
        assertTrue(gate.revision > before)
        assertFalse(gate.claim("one"))
    }
}
