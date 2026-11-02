package io.github.originosfcmguardian.diagnostics

enum class DiagnosticReportFormat(val mimeType: String, val fileName: String) {
    JSON("application/json", "guardian-combined-diagnostic.json"),
}

/** One machine-readable export, using only the already captured snapshot. */
fun encodeDiagnosticReport(report: CombinedDiagnosticReport): ByteArray = encodeCombinedDiagnosticReport(report)
