package io.github.originosfcmguardian.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.originosfcmguardian.autostart.SelectedTargetUiState

@Composable
internal fun StoppedRecoveryAction(presentation: RecoveryActionPresentation, onRequest: () -> Unit) {
    when (presentation) {
        RecoveryActionPresentation.HIDDEN -> Unit
        RecoveryActionPresentation.OFFER -> OutlinedButton(onClick = onRequest) { Text("恢复接收") }
        RecoveryActionPresentation.RECOVERING -> OutlinedButton(onClick = onRequest, enabled = false) {
            Text("正在恢复…")
        }
        RecoveryActionPresentation.SUCCESS -> Button(
            onClick = onRequest,
            enabled = false,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = TargetStatusTone.SUCCESS.color(),
                disabledContentColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF102F13) else Color.White,
            ),
        ) { Text("恢复完成") }
    }
}

@Composable
internal fun StoppedRecoveryReceipt(
    state: StoppedRecoveryUiState,
    target: SelectedTargetUiState? = null,
    reconcileBusy: Boolean = false,
    onReconcile: () -> Unit,
) {
    val presentation = stoppedRecoveryReceiptPresentation(state, target)
    presentation?.let { Text(it.text, color = it.tone.color(), style = MaterialTheme.typography.bodySmall) }
    state.recordedAtMillis?.let {
        Text("上次操作：${formatRecordTime(it)}", style = MaterialTheme.typography.bodySmall)
    }
    if (state.needsReconcile) {
        OutlinedButton(onClick = onReconcile,
            enabled = state.loaded && !state.busy && !state.storageBlocked && !reconcileBusy) {
            Text(if (state.busy) "核对中…" else "核对上次结果")
        }
    }
}

@Composable
internal fun TargetStatusTone.color(): Color = when (this) {
    TargetStatusTone.SUCCESS -> guardianSuccessColor(MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    TargetStatusTone.FAILURE -> MaterialTheme.colorScheme.error
    TargetStatusTone.UNCERTAIN -> guardianWarningColor(MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    TargetStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun StoppedRecoveryConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit, targetLabel: String = "应用", packageName: String = "") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复 $targetLabel 接收？") },
        text = { Text("允许解除 $targetLabel 的后台唤醒限制，以恢复通知接收。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
