package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.doze.cyclebroker.GmsDozeCycleUiState
import io.github.originosfcmguardian.doze.cyclebroker.GmsDozeCycleAutomaticUiState

@Composable
internal fun GmsDozeAutomaticControls(state: GmsDozeCycleAutomaticUiState, shellReady: Boolean,
    onEnable: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("定期检查电池豁免", style = MaterialTheme.typography.titleSmall)
            Text(state.detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.configured,
            onCheckedChange = { if (it) onEnable() else onStop() },
            enabled = state.configured || (shellReady && !state.busy && state.confirmationTarget != null))
    }
    Text("复用应用保护服务，每次至少间隔 15 分钟；实际写入尚未开放。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun GmsDozeAutomaticConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("授权定期检查 GMS 电池豁免？") },
        text = { Text("复用现有应用保护服务，每次核对间隔至少 15 分钟，后台等待可能延后。总保护关闭或 Shizuku 不可用时暂停；此开关关闭后须重新授权。当前只核验 Google Play 服务的系统全局豁免，实际修复写入尚未开放；不会启动目标应用。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("授权定期检查") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** Finite business controls, not a claim that periodic protection is running. */
@Composable
internal fun GmsDozeControls(state: GmsDozeCycleUiState, shellReady: Boolean,
    onEnable: () -> Unit, onApply: () -> Unit, onStop: () -> Unit, onRestore: () -> Unit) {
    Text("GMS 电池豁免", style = MaterialTheme.typography.titleSmall)
    Text(state.detail, style = MaterialTheme.typography.bodySmall)
    if (!state.writeApproved) Text("实际写入尚未开放；已有豁免时只核验，不修改。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = if (state.runningIntent) onApply else onEnable,
        modifier = Modifier.fillMaxWidth(),
        enabled = shellReady && if (state.runningIntent) state.canApplyOnce else state.canEnable) {
        Text(if (state.busy) "执行中…" else if (state.runningIntent) "再次执行一次" else "应用电池保护")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.runningIntent || state.busy || state.stopPending) TextButton(onClick = onStop) {
            Text(if (state.stopPending) "重试确认停止" else "停止操作")
        }
        if (state.restoreTarget != null) TextButton(onClick = onRestore,
            enabled = shellReady && state.canRestore) { Text("恢复原值") }
    }
    if (!shellReady) Text("请先连接 Shizuku。", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun GmsDozeOnConfirmation(reenable: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (reenable) "重新授权电池保护？" else "授权 GMS 电池保护？") },
        text = { Text("允许为 Google Play 服务应用系统全局电池豁免，作用于所有空间。当前仅执行一次，不启动定时保护或目标应用。停止会保留已应用设置，恢复原值需要另外确认。实际写入尚未开放；已有豁免时仅核验，不作修改。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("授权并执行一次") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun GmsDozeRestoreConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("恢复原电池设置？") },
        text = { Text("仅恢复当前已确认由 Guardian 添加的 GMS 豁免，不移除原有豁免。将重新核对安装身份和原值；记录或状态变化会拒绝执行。实际写入尚未开放。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认恢复原值") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
