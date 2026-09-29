package io.github.originosfcmguardian.notification

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedNotificationFactsExportTest {
    @Test
    fun selectedDocumentAfterProcessRecreationFailsClosedAndCanRetry() {
        val beforeProcessDeath = PreparedNotificationFactsExport()
        beforeProcessDeath.prepare("old history".toByteArray())

        val afterProcessDeath = PreparedNotificationFactsExport()
        assertEquals(PreparedExportResult.Missing, afterProcessDeath.consume(documentSelected = true))

        afterProcessDeath.prepare("fresh history".toByteArray())
        val result = afterProcessDeath.consume(documentSelected = true) as PreparedExportResult.Ready
        assertArrayEquals("fresh history".toByteArray(), result.bytes)
        assertEquals(PreparedExportResult.Missing, afterProcessDeath.consume(documentSelected = true))
    }

    @Test
    fun clearInvalidatesPreparedSnapshotAndPickerCancellationIsQuiet() {
        val pending = PreparedNotificationFactsExport()
        pending.prepare("before clear".toByteArray())
        pending.invalidate()
        assertEquals(PreparedExportResult.Missing, pending.consume(documentSelected = true))

        pending.prepare("new snapshot".toByteArray())
        assertEquals(PreparedExportResult.Cancelled, pending.consume(documentSelected = false))
        assertEquals(PreparedExportResult.Missing, pending.consume(documentSelected = true))
    }
}
