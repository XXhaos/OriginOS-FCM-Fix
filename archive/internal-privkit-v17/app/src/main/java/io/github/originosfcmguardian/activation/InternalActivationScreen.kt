package io.github.originosfcmguardian.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.ui.INTERNAL_CONNECTION_TITLE
import io.github.originosfcmguardian.ui.internalConnectionPresentation

/** Callback-only UI; synthetic fixtures cannot reach privilege or system APIs. */
@Composable
internal fun InternalActivationScreen(
    status: InternalActivationStatus,
    pairingActive: Boolean,
    pairingMessage: String,
    pairingNotificationsAvailable: Boolean,
    connectPort: String,
    onConnectPortChange: (String) -> Unit,
    onPair: () -> Unit,
    onNotificationSettings: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onManualCleanup: () -> Unit,
    onOpenSettings: () -> Unit,
    onReturn: () -> Unit,
) {
    val presentation = internalConnectionPresentation(status.busy, status.ready, status.needsManualCleanup, pairingActive)
    var showComputerHelp by remember { mutableStateOf(false) }
    var showConnectionHelp by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(INTERNAL_CONNECTION_TITLE, style = MaterialTheme.typography.headlineSmall)
                Text(presentation.headline, style = MaterialTheme.typography.titleSmall)
                Text(presentation.protectionNotice, style = MaterialTheme.typography.bodySmall)
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status.needsManualCleanup) {
                    Text("请先在系统开发者选项关闭无线调试，再确认。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("打开系统开发者选项") }
                    Button(onClick = onManualCleanup, enabled = !status.busy) {
                        Text("确认已手动关闭无线调试")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onReturn) { Text("返回") }
                    if (status.busy || pairingActive) Button(onClick = onCancel) { Text("取消并清理") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status.message, style = MaterialTheme.typography.bodyMedium)
            ActivationDiagnosticText(status)
            Text("配对和启动会保留原本开启的无线调试；仅关闭本次临时开启的无线调试，期间请勿切换开关。",
                style = MaterialTheme.typography.bodySmall)
            ActivationStepCard {
                TextButton(onClick = { showComputerHelp = !showComputerHelp }) {
                    Text(if (showComputerHelp) "收起一次性电脑授权" else "一次性电脑授权 · 展开教程")
                }
                if (showComputerHelp) {
                    Text("首次使用需通过 USB 连接电脑，在电脑终端执行：", style = MaterialTheme.typography.bodySmall)
                    Text("adb shell pm grant io.github.originosfcmguardian android.permission.WRITE_SECURE_SETTINGS",
                        style = MaterialTheme.typography.bodySmall)
                    Text("该权限用于本次无线调试开关事务。PrivKit 启动时可能重新授予本应用已声明的该权限；服务仍可连接时，撤销它不保证永久保持撤销。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            ActivationStepCard {
                Text("1 · 通知配对", style = MaterialTheme.typography.titleMedium)
                Text("开始后，打开系统无线调试的“使用配对码配对设备”，保持窗口开启，下拉通知输入配对码。不需要分屏。",
                    style = MaterialTheme.typography.bodySmall)
                Text("通知仅在配对期间临时保留；结束后可清除。日常保护通知可单独关闭。", style = MaterialTheme.typography.bodySmall)
                if (!pairingNotificationsAvailable) {
                    Text("请允许 Guardian 通知及“无线调试配对”类别。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onNotificationSettings, enabled = !pairingActive) { Text("开启配对通知") }
                }
                Button(onClick = onPair, enabled = presentation.canPair && pairingNotificationsAvailable) {
                    Text("开始通知配对")
                }
                if (pairingMessage.isNotBlank()) Text(pairingMessage, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings, enabled = !status.busy || pairingActive) { Text("打开系统开发者选项") }
            }
            ActivationStepCard {
                Text("2 · 启动并核验", style = MaterialTheme.typography.titleMedium)
                Text("连接端口来自无线调试主页面，与配对端口不同。仅连接本机。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(connectPort, onConnectPortChange, modifier = Modifier.fillMaxWidth(),
                    label = { Text("连接端口（可留空自动发现）") }, enabled = !status.busy && !pairingActive,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Button(onClick = onStart, enabled = presentation.canStart) { Text("启动并核验内置服务") }
                Text(status.message, style = MaterialTheme.typography.bodySmall)
                ActivationDiagnosticText(status)
            }
            ActivationStepCard {
                TextButton(onClick = { showConnectionHelp = !showConnectionHelp }) {
                    Text(if (showConnectionHelp) "收起连接说明与隐私" else "连接说明与隐私 · 展开")
                }
                if (showConnectionHelp) {
                    Text("本页仅由你手动启动自有特权服务，启动同意独立于推送保护。尚未接入自动无线启动或第三方恢复。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Guardian 使用自己的密钥，官方 Shizuku 的配对不能替代。关闭或离开系统配对码对话框会使配对失效。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("配对码只在系统通知中输入，不保存或写入日志。系统通知输入不受本页截图保护，请勿截图或分享配对码。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("原本开启的无线调试会保留；仅清理本次由关闭开启的无线设置。无法识别所有用户开关操作。陈旧或不确定事务不会自动修改设置，需手动关闭无线调试并确认。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ActivationDiagnosticText(status: InternalActivationStatus) {
    status.failure?.let {
        Text("诊断：${it.code}", style = MaterialTheme.typography.bodySmall)
        Text(it.guidance, style = MaterialTheme.typography.bodySmall)
    } ?: status.stage?.let {
        if (status.busy) Text("阶段：${it.name}", style = MaterialTheme.typography.bodySmall)
    }
    status.cleanupFailure?.let {
        if (it != status.failure) Text("清理诊断：${it.code}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActivationStepCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
