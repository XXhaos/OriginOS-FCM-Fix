package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot

/** Existing settings card content only. No alternate transport, consent or startup action. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ShizukuWorkModeContent(snapshot: ShizukuStatusSnapshot, onAction: () -> Unit,
    onRefresh: () -> Unit, onOpenManager: () -> Unit, onNoTouch: () -> Unit) {
    val presentation = shizukuWorkModePresentation(snapshot)
    Text(CURRENT_RECOVERY_BACKEND_LABEL, style = MaterialTheme.typography.titleSmall)
    Text(presentation.statusLabel, style = MaterialTheme.typography.bodyMedium)
    Text(presentation.summary, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presentation.actionLabel?.let { label -> OutlinedButton(onClick = onAction) { Text(label) } }
        if (presentation.showOpenManager) TextButton(onClick = onOpenManager) { Text("打开 Shizuku") }
        TextButton(onClick = onRefresh) { Text("刷新状态") }
    }
    Text(SHIZUKU_REBOOT_NOTICE, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onNoTouch) { Text("无感保活") }
}
