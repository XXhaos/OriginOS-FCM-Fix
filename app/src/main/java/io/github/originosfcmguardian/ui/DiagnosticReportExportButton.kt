package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat

/** One compact export entry; opening or dismissing the format choice does not prepare a report. */
@Composable
internal fun DiagnosticReportExportButton(busy: Boolean, onExport: (DiagnosticReportFormat) -> Unit) {
    var choosingFormat by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { choosingFormat = true }, enabled = !busy) {
        Text(if (busy) "正在准备…" else "导出脱敏报告")
    }
    if (choosingFormat) {
        AlertDialog(
            onDismissRequest = { choosingFormat = false },
            title = { Text("选择报告格式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        choosingFormat = false
                        onExport(DiagnosticReportFormat.JSON)
                    }) { Text("JSON（结构化）") }
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        choosingFormat = false
                        onExport(DiagnosticReportFormat.MARKDOWN)
                    }) { Text("Markdown（便于阅读）") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingFormat = false }) { Text("取消") } },
        )
    }
}
