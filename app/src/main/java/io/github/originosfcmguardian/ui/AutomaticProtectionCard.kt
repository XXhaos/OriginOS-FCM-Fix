package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.ProtectionNotificationVisibility
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage

internal fun canEnableAutomaticProtection(state: HomeUiState): Boolean =
    !state.automaticProtection.masterEnabled && !state.automaticProtection.busy &&
        !state.automaticProtection.revocationSaveFailed && state.recoveryFirmwareSupported &&
        state.targetSelectionLoadState == HomeSourceLoadState.LOADED && !state.targetSelectionStoreCorrupt &&
        state.stoppedRecovery.loaded && !state.stoppedRecovery.storageBlocked && !state.stoppedRecovery.busy && !state.detecting

/** UI availability only. Fresh package support and durable authority are checked before execution. */
internal fun canEnableTargetAutomaticProtection(state: HomeUiState, identity: SelectedTargetIdentity): Boolean =
    isRecoveryTargetPackage(identity.packageName) &&
    identity !in state.automaticProtection.configuredIdentities &&
    !state.automaticProtection.busy && !state.automaticProtection.waitingForPrivilege && !state.automaticProtection.waitingForReconciliation && !state.automaticProtection.revocationSaveFailed && state.recoveryFirmwareSupported &&
        state.shizuku.status == ShizukuStatus.READY_SHELL &&
        state.targetSelectionLoadState == HomeSourceLoadState.LOADED && !state.targetSelectionStoreCorrupt &&
        state.stoppedRecovery.loaded && !state.stoppedRecovery.storageBlocked &&
        !state.stoppedRecovery.needsReconcile && !state.stoppedRecovery.busy && !state.detecting &&
        state.selectedTargets.any { it.identity == identity &&
            it.status != TargetAutostartStatus.IDENTITY_CHANGED && it.identity !in state.targetReadInFlight }

internal fun targetAutomaticProtectionState(state: AutomaticProtectionState, identity: SelectedTargetIdentity): AutomaticProtectionState {
    val target = state.targets.firstOrNull { it.identity == identity }
        ?: return AutomaticProtectionState(detail = "此应用未开启自动保护")
    return AutomaticProtectionState(enabled = target.enabled && !state.waitingForReconciliation, busy = target.busy,
        detail = target.detail, lastCheckedAtMillis = target.lastCheckedAtMillis,
        lastOutcome = target.lastOutcome, savedAuthorization = target.savedAuthorization,
        masterEnabled = state.masterEnabled,
        waitingForPrivilege = target.waitingForPrivilege, revocationSaveFailed = state.revocationSaveFailed,
        waitingForReconciliation = state.waitingForReconciliation && target.savedAuthorization)
}

internal fun automaticProtectionStatusLabel(state: AutomaticProtectionState): String = when {
    state.waitingForReconciliation -> "等待复检"
    state.busy -> "正在启动…"
    state.waitingForService -> "等待保护服务"
    state.waitingForPrivilege -> "等待 Shizuku"
    state.enabled -> "保护已开启"
    state.savedAuthorization -> "等待复检"
    else -> "保护未开启"
}

internal fun ProtectionNotificationVisibility.label(): String = when (this) {
    ProtectionNotificationVisibility.ALLOWED -> "保护通知：允许显示"
    ProtectionNotificationVisibility.BLOCKED -> "保护通知：系统已关闭"
    ProtectionNotificationVisibility.UNKNOWN -> "保护通知：状态未知"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AutomaticProtectionCard(
    state: AutomaticProtectionState,
    canEnable: Boolean,
    hiddenFromRecents: Boolean,
    recentsMessage: String?,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onSetHidden: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Boolean = { false },
    showPreferences: Boolean = true,
    showProtectionControls: Boolean = true,
    showToggle: Boolean = true,
    targetLabel: String = "应用",
    notificationVisibility: ProtectionNotificationVisibility = ProtectionNotificationVisibility.UNKNOWN,
    onOpenAutostartSettings: (() -> Unit)? = null,
    autostartSettingsMessage: String? = null,
) {
    var confirmHide by remember { mutableStateOf(false) }
    var notificationSettingsFailed by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (showProtectionControls) "$targetLabel 自动保护" else "后台设置", style = MaterialTheme.typography.titleMedium)
            if (showProtectionControls) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(automaticProtectionStatusLabel(state), modifier = Modifier.weight(1f))
                if (showToggle) AutomaticProtectionToggle(state, canEnable, onEnable, onDisable, targetLabel)
            }
            if (state.revocationSaveFailed) {
                Text("关闭状态保存失败，请重试。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDisable, shape = MaterialTheme.shapes.small) {
                        Text("重试保存关闭状态", textAlign = TextAlign.Center)
                    }
                }
            } else if (!state.enabled && !state.busy && !state.waitingForPrivilege &&
                !state.waitingForReconciliation && state.detail != "自动保护未开启") {
                Text(state.detail, style = MaterialTheme.typography.bodySmall)
            }
            }
            if (showPreferences) {
                Text(notificationVisibility.label(), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { notificationSettingsFailed = !onOpenNotificationSettings() },
                        shape = MaterialTheme.shapes.small) {
                        Text("通知显示设置", textAlign = TextAlign.Center)
                    }
                }
                if (notificationSettingsFailed) Text("打开失败，请在系统通知管理中设置。",
                    style = MaterialTheme.typography.bodySmall)
                onOpenAutostartSettings?.let { openSettings ->
                    Text("请允许 Guardian 和需要接收通知的应用自启动。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openSettings, shape = MaterialTheme.shapes.small) {
                            Text("打开自启动设置", textAlign = TextAlign.Center)
                        }
                    }
                    autostartSettingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("在最近任务中隐藏", modifier = Modifier.weight(1f))
                Switch(modifier = Modifier.semantics { contentDescription = "最近任务隐藏开关" },
                    checked = hiddenFromRecents, onCheckedChange = { if (it) confirmHide = true else onSetHidden(false) })
            }
            recentsMessage?.let { Text("隐藏设置失败，请重试。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmHide) AlertDialog(
        onDismissRequest = { confirmHide = false },
        title = { Text("隐藏 Guardian 任务？") },
        text = { Text("请先锁定后台任务，再隐藏。隐藏后可从桌面打开 Guardian。") },
        confirmButton = { TextButton(onClick = { confirmHide = false; onSetHidden(true) }) { Text("确认隐藏") } },
        dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("取消") } },
    )
}

@Composable
internal fun AutomaticProtectionToggle(state: AutomaticProtectionState, canEnable: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit, targetLabel: String = "应用") {
    Switch(modifier = Modifier.semantics { contentDescription = "自动保护总开关" },
        checked = state.masterEnabled,
        enabled = automaticProtectionToggleEnabled(state, canEnable),
        onCheckedChange = { dispatchAutomaticProtectionToggle(state, canEnable, it, onEnable, onDisable) })
}

internal fun dispatchAutomaticProtectionToggle(state: AutomaticProtectionState, canEnable: Boolean,
    checked: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
    // A rejected ON is not an OFF request, including a second click during queued startup.
    if (!checked) onDisable()
    else if (!state.masterEnabled && !state.busy && !state.revocationSaveFailed && canEnable) onEnable()
}

internal fun automaticProtectionToggleEnabled(state: AutomaticProtectionState, canEnable: Boolean): Boolean =
    state.masterEnabled || (!state.busy && !state.revocationSaveFailed && canEnable)

@Composable
internal fun AutomaticProtectionConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit, targetLabel: String = "应用", packageName: String = "") {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择 $targetLabel 自动保护？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (packageName.isNotBlank()) Text(packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("允许 Guardian 在此应用被停止时自动恢复后台唤醒。")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("授权并选择") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
