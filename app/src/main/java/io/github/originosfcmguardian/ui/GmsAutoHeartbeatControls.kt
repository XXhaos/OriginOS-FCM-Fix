package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState

internal fun gmsAutoHeartbeatHeadline(state: GmsAutoHeartbeatUiState): String = when {
    state.stopPending -> "停止待确认"
    state.busy -> "正在核对心跳会话"
    state.active -> "会话已开启 · 最短 15 分钟"
    state.configured && state.resumeAuthorized && !state.requiresConfirmation -> "心跳已暂停 · 等待服务恢复"
    state.configured -> "偏好已保存 · 心跳已暂停"
    else -> "低频心跳未开启"
}

internal fun gmsAutoHeartbeatScopeText(state: GmsAutoHeartbeatUiState): String = when (state.consentVersion) {
    3 -> "服务、App 或手机重启后，实际保护与 Shizuku 就绪且安全核验通过才接续；每次重新等待至少15分钟。"
    2 -> "旧授权仅允许同次开机内安全接续；重启手机后需重新确认，旧授权不自动升级。"
    1 -> "旧授权不允许冷进程或手机重启后自动接续；需重新确认。"
    else -> "新确认可允许手机重启后安全接续；需实际保护与 Shizuku 就绪。休眠可能延后。"
}

@Composable
internal fun GmsAutoHeartbeatControls(state: GmsAutoHeartbeatUiState, hostReady: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("低频心跳", style = MaterialTheme.typography.titleSmall)
            Text(gmsAutoHeartbeatHeadline(state), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.configured,
            onCheckedChange = { if (it) onEnable() else onDisable() },
            enabled = !state.stopPending && (state.configured || (hostReady && !state.busy)),
            modifier = Modifier.semantics { contentDescription = "低频心跳开关" })
    }
    Text(state.detail, style = MaterialTheme.typography.bodySmall)
    Text(gmsAutoHeartbeatScopeText(state),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!hostReady && !state.configured) Text("需先开启自动保护并连接 Shizuku。",
        style = MaterialTheme.typography.bodySmall)
    if (state.requiresConfirmation && !state.stopPending && !state.busy && hostReady)
        TextButton(onClick = onEnable) { Text("重新确认心跳") }
    if (state.stopPending)
        TextButton(onClick = onDisable, enabled = !state.busy) { Text("重试确认停止") }
}

@Composable
internal fun GmsAutoHeartbeatConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("启用低频心跳？") },
        text = { Text("允许 Guardian 保存新的心跳授权，在服务、App 或手机重启后接续。需实际自动保护与外置 Shizuku 已就绪、GMS 安装身份一致且安全核验通过；Guardian 不会启动 Shizuku。每次新会话重新等待至少15分钟，休眠可能延后，不保证连接修复或通知送达。结果不明、未结算或停止未确认的旧操作仍阻断接续，不重发。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认开启心跳") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
