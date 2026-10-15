package io.github.originosfcmguardian.monitoring

import org.junit.Assert.*
import org.junit.Test

class PreparedMonitorSessionExportTest {
    @Test fun invalidationDuringEncodingCannotRestoreClearedData() {
        val pending = PreparedMonitorSessionExport()
        val ticket = requireNotNull(pending.begin("session-a"))
        pending.invalidate()
        assertFalse(pending.complete(ticket, byteArrayOf(1)))
        assertNull(pending.begin("session-b"))
        assertEquals(MonitorExportResult.Missing, pending.consume(true, "session-a"))
        assertNotNull(pending.begin("session-b"))
    }

    @Test fun replacementSessionAndProcessLossFailClosed() {
        val pending = PreparedMonitorSessionExport()
        val ticket = requireNotNull(pending.begin("session-a"))
        assertTrue(pending.complete(ticket, byteArrayOf(1)))
        assertEquals(MonitorExportResult.Missing, pending.consume(true, "session-b"))
        assertEquals(MonitorExportResult.Missing, PreparedMonitorSessionExport().consume(true, "session-a"))
    }

    @Test fun snapshotIsCopiedAndConsumedExactlyOnce() {
        val pending = PreparedMonitorSessionExport()
        val ticket = requireNotNull(pending.begin("session-a"))
        val input = byteArrayOf(1, 2)
        pending.complete(ticket, input)
        input[0] = 9
        val result = pending.consume(true, "session-a") as MonitorExportResult.Ready
        assertArrayEquals(byteArrayOf(1, 2), result.bytes)
        assertEquals(MonitorExportResult.Missing, pending.consume(true, "session-a"))
    }

    @Test fun canceledPickerReleasesReservationAndStaleCompletionCannotReplaceNext() {
        val pending = PreparedMonitorSessionExport()
        val old = requireNotNull(pending.begin("session-a"))
        assertEquals(MonitorExportResult.Cancelled, pending.consume(false, "session-a"))
        val next = requireNotNull(pending.begin("session-a"))
        assertFalse(pending.complete(old, byteArrayOf(1)))
        pending.cancel(old)
        assertTrue(pending.complete(next, byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(2), (pending.consume(true, "session-a") as MonitorExportResult.Ready).bytes)
    }

    @Test fun launchFailureAllowsRetry() {
        val pending = PreparedMonitorSessionExport()
        val ticket = requireNotNull(pending.begin("session-a"))
        pending.cancel(ticket)
        assertNotNull(pending.begin("session-a"))
    }
}
