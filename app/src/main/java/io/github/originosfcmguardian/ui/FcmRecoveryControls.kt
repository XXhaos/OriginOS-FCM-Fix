package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState

@Composable internal fun FcmRecoveryControls(state: FcmRecoveryUiState, hostReady: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FCM 恢复（试用）", style = MaterialTheme.typography.titleSmall)
            Text(when { state.storageBlocked -> "记录异常 · 已暂停"; state.busy -> "正在核对";
                state.requiresConfirmation -> "等待重新确认"; state.configured -> "试用已开启"; else -> "尚未开启" },
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = state.configured,
            onCheckedChange = { if (it) onEnable() else onDisable() },
            enabled = state.configured || hostReady && !state.busy && !state.storageBlocked,
            modifier = Modifier.semantics { contentDescription = "FCM 恢复试用开关" })
    }
    Text(state.detail, style = MaterialTheme.typography.bodySmall)
    Text("关闭总开关或 Shizuku 不可用时暂停。手机重启需重新确认；GMS 更新后需重新核验适配。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.requiresConfirmation && hostReady && !state.busy)
        TextButton(onClick = onEnable) { Text("重新确认恢复试用") }
}

@Composable internal fun FcmRecoveryConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开启 FCM 恢复试用？") },
        text = { Text("允许 Guardian 在实际自动保护和外置 Shizuku 就绪、当前版本身份一致、有可用网络且完整确认 FCM 服务不存在时，发送一次公开空请求。每次缺失只尝试一次，异常、超时或重启应用都不重发；只有随后完整观察到连接才能重新准备。保存少量无消息内容的事件用于后续排查。请求被系统接受或随后观察到连接不代表已证明修复或通知送达。重启手机需重新确认；Guardian 不会启动 Shizuku。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认开启试用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
