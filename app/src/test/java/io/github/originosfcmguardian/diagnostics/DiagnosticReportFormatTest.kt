package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticReportFormatTest {
    @Test fun onlyMachineReadableJsonIsOffered() {
        assertEquals("application/json", DiagnosticReportFormat.JSON.mimeType)
        assertEquals("guardian-combined-diagnostic.json", DiagnosticReportFormat.JSON.fileName)
        assertEquals(listOf(DiagnosticReportFormat.JSON), DiagnosticReportFormat.entries)
    }

    @Test fun dispatchAndPreparedBytesUseTheSameAlreadyCapturedReport() {
        val report = buildCombinedDiagnosticReport(HomeUiState(),
            DiagnosticEnvironment(37, "17", "vivo", "fixture", "fixture", 1), 9_000)
        val expected = encodeCombinedDiagnosticReport(report)
        val prepared = PreparedCombinedDiagnosticExport()
        prepared.prepare(encodeDiagnosticReport(report))
        val ready = prepared.consume(true) as CombinedDiagnosticExportResult.Ready
        assertArrayEquals(expected, ready.bytes)
        assertEquals(CombinedDiagnosticExportResult.Missing, prepared.consume(true))
        prepared.prepare(expected)
        assertEquals(CombinedDiagnosticExportResult.Cancelled, prepared.consume(false))
    }
}
