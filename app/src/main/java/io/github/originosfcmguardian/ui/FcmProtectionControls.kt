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
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*

internal fun fcmProtectionObservationText(state: FcmProtectionUiState, now: Long): String {
    return fcmConnectionStatus(state.latestObservation, now).text
}
internal fun fcmProtectionHeadline(state: FcmProtectionUiState) = when {
    state.storageBlocked -> "记录异常 · 已暂停"
    state.maintenance == FcmMaintenanceState.STOP_PENDING -> "停止待确认"
    state.busy -> "正在核对统一授权"
    state.requiresConfirmation -> "部分分支需重新确认"
    !state.configured -> "尚未开启"
    state.maintenance == FcmMaintenanceState.ACTIVE -> "已开启 · 低频维护"
    state.maintenance == FcmMaintenanceState.WAITING_HOST -> "已保存 · 等待宿主"
    else -> "已开启 · 维护等待完整连接"
}
@Composable internal fun FcmProtectionControls(state: FcmProtectionUiState, hostReady: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FCM 保护（试用）", style = MaterialTheme.typography.titleSmall)
            Text(fcmProtectionHeadline(state), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.configured,
            onCheckedChange = { if (it) onEnable() else onDisable() },
            enabled = state.configured || hostReady && !state.busy && !state.storageBlocked && state.maintenance != FcmMaintenanceState.STOP_PENDING,
            modifier = Modifier.semantics { contentDescription = "FCM 保护开关" })
    }
    Text(fcmProtectionGuidance(state), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.requiresConfirmation && hostReady && !state.busy && state.maintenance != FcmMaintenanceState.STOP_PENDING)
        TextButton(onClick = onEnable) { Text("重新确认 FCM 保护") }
    if (state.maintenance == FcmMaintenanceState.STOP_PENDING)
        TextButton(onClick = onDisable, enabled = !state.busy) { Text("重试确认停止") }
}
internal fun fcmProtectionGuidance(state: FcmProtectionUiState): String = when {
    state.storageBlocked -> "记录无法确认，请关闭后重新检查。"
    state.confirmationFailure != null -> state.confirmationFailure.detail
    state.maintenance == FcmMaintenanceState.STOP_PENDING -> "上次停止尚未确认，请重试；不会重复发送请求。"
    state.requiresConfirmation -> "重启或权限变化后，请重新确认恢复授权。"
    state.maintenance == FcmMaintenanceState.WAITING_HOST -> "请开启自动保护，并启动 Shizuku。"
    else -> "低频维护连接；确认服务缺失时尝试恢复，不保证消息送达。"
}
@Composable internal fun FcmProtectionConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开启 FCM 保护试用？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("保存统一授权：连接正常时，最短每 15 分钟尝试维护一次。")
            Text("自动保护、Shizuku、网络和当前 Google Play 服务版本与安装身份核验通过后，仅在完整确认服务缺失时发送一次公开空请求尝试恢复。已断连、未知或过期状态不触发恢复。")
            Text("异常、超时或应用重启不会重发；只有新的完整连接记录才允许下一次恢复。维护等待连接，结果不明或停止待确认时暂停。")
            Text("手机重启后需重新确认恢复授权。旧授权不自动升级，Guardian 不会启动 Shizuku。")
            Text("仅保存少量无消息内容的事件、整机电量和主进程 CPU 参考，不代表本应用耗电量，也不保证修复或消息送达。")
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认开启 FCM 保护") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
