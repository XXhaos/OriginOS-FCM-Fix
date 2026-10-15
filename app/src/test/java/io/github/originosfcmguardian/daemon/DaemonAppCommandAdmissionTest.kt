package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonAppCommandAdmissionTest {
    @Test fun enablesCannotAccumulateAheadOfOff() {
        val gate = DaemonAppCommandAdmission()
        assertEquals(DaemonAppCommandAdmission.Offer.START, gate.offer(DaemonAppCommand(DaemonWire.MASTER_ON)))
        repeat(1_000) { assertEquals(DaemonAppCommandAdmission.Offer.REJECTED, gate.offer(DaemonAppCommand(DaemonWire.FCM_ON))) }
        val off = DaemonAppCommand(DaemonWire.MASTER_OFF)
        assertEquals(DaemonAppCommandAdmission.Offer.QUEUED_OFF, gate.offer(off))
        assertEquals(off, gate.finishAndNext())
        assertTrue(gate.busy())
        assertEquals(DaemonAppCommandAdmission.Offer.REJECTED, gate.offer(DaemonAppCommand(DaemonWire.MASTER_ON)))
        assertNull(gate.finishAndNext())
        assertFalse(gate.busy())
    }
    @Test fun duplicateOffIsCoalescedAndOtherOverflowNeverClaimsAcceptance() {
        val gate = DaemonAppCommandAdmission()
        gate.offer(DaemonAppCommand(DaemonWire.MASTER_ON))
        val off = DaemonAppCommand(DaemonWire.FCM_OFF)
        repeat(1_000) { assertEquals(DaemonAppCommandAdmission.Offer.QUEUED_OFF, gate.offer(off)) }
        assertEquals(DaemonAppCommandAdmission.Offer.REJECTED, gate.offer(DaemonAppCommand(DaemonWire.MASTER_OFF)))
        assertEquals(off, gate.finishAndNext())
        assertNull(gate.finishAndNext())
        assertEquals(DaemonAppCommandAdmission.Offer.START, gate.offer(DaemonAppCommand(DaemonWire.MASTER_OFF)))
    }
}
