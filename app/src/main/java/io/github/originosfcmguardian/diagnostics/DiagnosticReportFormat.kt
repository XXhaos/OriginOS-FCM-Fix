package io.github.originosfcmguardian.diagnostics

enum class DiagnosticReportFormat(val mimeType: String, val fileName: String) {
    JSON("application/json", "guardian-combined-diagnostic.json"),
    MARKDOWN("text/markdown", "guardian-combined-diagnostic.md"),
}

/** Format selection changes encoding only, never the captured evidence or prepared-byte lifetime. */
fun encodeDiagnosticReport(report: CombinedDiagnosticReport, format: DiagnosticReportFormat): ByteArray =
    when (format) {
        DiagnosticReportFormat.JSON -> encodeCombinedDiagnosticReport(report)
        DiagnosticReportFormat.MARKDOWN -> encodeCombinedDiagnosticMarkdown(report)
    }
