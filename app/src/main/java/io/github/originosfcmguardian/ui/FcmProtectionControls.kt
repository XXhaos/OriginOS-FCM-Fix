package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.fcmprotection.*

internal fun fcmProtectionObservationText(state: FcmProtectionUiState, now: Long): String {
    return fcmConnectionStatus(state.latestObservation, now).text
}
internal fun fcmProtectionHeadline(state: FcmProtectionUiState) = when {
    state.storageBlocked -> "记录异常 · 已暂停"
    state.maintenance == FcmMaintenanceState.STOP_PENDING -> "停止待确认"
    state.busy -> "处理中…"
    state.configured && (state.requiresConfirmation || state.maintenance == FcmMaintenanceState.RECONFIRM ||
        state.confirmationFailure != null) -> "FCM 保护暂不可用"
    !state.configured -> "尚未开启"
    state.maintenance == FcmMaintenanceState.ACTIVE -> "已开启"
    state.maintenance == FcmMaintenanceState.WAITING_HOST -> "等待启动"
    else -> "等待连接"
}
@Composable internal fun FcmProtectionControls(state: FcmProtectionUiState, hostReady: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FCM 保护", style = MaterialTheme.typography.titleSmall)
            Text(fcmProtectionHeadline(state), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.configured,
            onCheckedChange = { if (it) onEnable() else onDisable() },
            enabled = state.configured || hostReady && !state.busy && !state.storageBlocked && state.maintenance != FcmMaintenanceState.STOP_PENDING,
            modifier = Modifier.semantics { contentDescription = "FCM 保护开关" })
    }
    val guidance = fcmProtectionGuidance(state)
    if (guidance.isNotEmpty()) Text(guidance, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.maintenance == FcmMaintenanceState.STOP_PENDING)
        TextButton(onClick = onDisable, enabled = !state.busy) { Text("重试确认停止") }
}
internal fun fcmProtectionGuidance(state: FcmProtectionUiState): String = when {
    state.storageBlocked -> "记录暂不可用，保护已暂停。"
    state.maintenance == FcmMaintenanceState.STOP_PENDING -> "请重试确认停止。"
    state.configured && (state.requiresConfirmation || state.confirmationFailure != null) -> "FCM 保护暂不可用。"
    state.confirmationFailure != null -> when (state.confirmationFailure) {
        FcmProtectionConfirmationFailure.HOST_UNAVAILABLE -> "请开启自动保护，并启动 Shizuku。"
        FcmProtectionConfirmationFailure.BUSY -> "请稍后重试。"
        FcmProtectionConfirmationFailure.STOP_PENDING -> "请先重试确认停止。"
        FcmProtectionConfirmationFailure.READ_UNAVAILABLE -> "无法读取连接状态，请稍后重试。"
        FcmProtectionConfirmationFailure.NOT_QUALIFIED -> "无法确认 Google Play 服务状态，请稍后重试。"
        FcmProtectionConfirmationFailure.SHELL_UNAVAILABLE -> "请启动 Shizuku 并授权。"
        FcmProtectionConfirmationFailure.NETWORK_UNAVAILABLE -> "请检查网络后重试。"
        FcmProtectionConfirmationFailure.HOST_CHANGED -> "开启未完成，请重试。"
    }
    state.maintenance == FcmMaintenanceState.WAITING_HOST -> "请开启自动保护，并启动 Shizuku。"
    else -> ""
}
@Composable internal fun FcmProtectionConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开启 FCM 推送保护？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自动恢复已选应用的后台唤醒，并维护 FCM 连接。")
            Text("请保持 Shizuku 已启动并授权。")
            Text("保存运行记录，不读取消息内容。")
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认开启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
