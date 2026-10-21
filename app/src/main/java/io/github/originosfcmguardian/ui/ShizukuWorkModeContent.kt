package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
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
    if (presentation.summary.isNotBlank()) Text(presentation.summary, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presentation.actionLabel?.let { label ->
            OutlinedButton(onClick = onAction, shape = MaterialTheme.shapes.small) {
                Text(label, textAlign = TextAlign.Center)
            }
        }
        if (presentation.showOpenManager) {
            OutlinedButton(onClick = onOpenManager, shape = MaterialTheme.shapes.small) {
                Text("打开 Shizuku", textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(onClick = onRefresh, shape = MaterialTheme.shapes.small) {
            Text("刷新状态", textAlign = TextAlign.Center)
        }
    }
    Text(SHIZUKU_REBOOT_NOTICE, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onNoTouch, shape = MaterialTheme.shapes.small) {
        Text("无感保活", textAlign = TextAlign.Center)
    }
}
