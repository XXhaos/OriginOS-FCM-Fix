package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*

internal fun fcmProtectionObservationText(state: FcmProtectionUiState, now: Long): String {
    val sample = state.latestObservation as? GmsConnectionObservation.Sample ?: return "尚无完整连接观察"
    if (!sample.isFresh(now)) return "上次连接观察已过期"
    val service = when (sample.gcmService.state) {
        GcmConnectionState.REPORTED_CONNECTED -> "服务报告已连接"
        GcmConnectionState.REPORTED_DISCONNECTED -> "服务存在但报告未连接"
        GcmConnectionState.SERVICE_ABSENT -> "完整确认服务缺失"
        GcmConnectionState.UNKNOWN -> "服务状态未知"
    }
    val socket = when (sample.observation) {
        SocketObservation.OBSERVED -> "有 TCP 连接迹象"
        SocketObservation.NO_MATCH -> "未观察到 TCP 连接迹象"
        SocketObservation.UNKNOWN -> "TCP 迹象未知"
    }
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(sample.observedAtMillis))
    return "$service · $socket · $time"
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
    Text(state.detail, style = MaterialTheme.typography.bodySmall)
    Text("连接时维护最短15分钟；仅精确服务缺失时单次恢复，未连接或未知不发送恢复请求。手机重启后恢复分支需重新确认；Guardian 不会启动 Shizuku。",
        style = MaterialTheme.typography.bodySmall)
    if (state.requiresConfirmation && hostReady && !state.busy && state.maintenance != FcmMaintenanceState.STOP_PENDING)
        TextButton(onClick = onEnable) { Text("重新确认 FCM 保护") }
    if (state.maintenance == FcmMaintenanceState.STOP_PENDING)
        TextButton(onClick = onDisable, enabled = !state.busy) { Text("重试确认停止") }
}
@Composable internal fun FcmProtectionConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开启 FCM 保护试用？") },
        text = { Text("保存新的统一授权：完整连接时进行最短15分钟的低频维护；实际保护、外置 Shizuku、可用网络和当前 GMS 版本身份均合格且完整确认服务缺失时，仅发送一次公开空请求。服务存在但未连接、未知、过期或读取不完整时不发送恢复请求。异常/超时/应用重启不重发，只有新的完整连接观察才重新准备。服务缺失时维护先等待连接。维护仅按原安全条件接续，结果不明和停止待确认阻断；手机重启后恢复分支需重新确认。保存少量无消息内容的事件和整机电量/主进程 CPU 参考，不代表本应用耗电量。旧分开授权不自动升级；Guardian 不会启动 Shizuku，不保证修复或通知送达。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认开启 FCM 保护") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
