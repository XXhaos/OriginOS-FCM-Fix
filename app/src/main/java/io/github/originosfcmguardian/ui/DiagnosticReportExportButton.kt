package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A single JSON export; preparing it never changes protection state. */
@Composable
internal fun DiagnosticReportExportButton(busy: Boolean, modifier: Modifier = Modifier, onExport: () -> Unit) {
    OutlinedButton(onClick = onExport, enabled = !busy, modifier = modifier, shape = MaterialTheme.shapes.small) {
        Text(if (busy) "正在准备…" else "导出日志", style = MaterialTheme.typography.labelMedium)
    }
}
