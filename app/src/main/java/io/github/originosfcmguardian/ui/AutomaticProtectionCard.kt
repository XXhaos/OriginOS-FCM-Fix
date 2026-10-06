package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
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
    state.waitingForReconciliation -> "等待只读复检"
    state.busy -> "启动或恢复中…"
    state.waitingForPrivilege -> "等待 Shizuku"
    state.enabled -> "检测已开启"
    state.savedAuthorization -> "等待恢复检测"
    else -> "检测未开启"
}

internal fun ProtectionNotificationVisibility.label(): String = when (this) {
    ProtectionNotificationVisibility.ALLOWED -> "保护通知：允许显示"
    ProtectionNotificationVisibility.BLOCKED -> "保护通知：系统已关闭"
    ProtectionNotificationVisibility.UNKNOWN -> "保护通知：状态未知"
}

@Composable
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
    showSharedGuidance: Boolean = true,
) {
    var confirmHide by remember { mutableStateOf(false) }
    var notificationSettingsFailed by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (showProtectionControls) "$targetLabel 自动保护" else "后台与通知", style = MaterialTheme.typography.titleMedium)
            if (showProtectionControls) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(automaticProtectionStatusLabel(state))
                if (showToggle) AutomaticProtectionToggle(state, canEnable, onEnable, onDisable, targetLabel)
            }
            Text(state.detail, style = MaterialTheme.typography.bodySmall)
            if (state.revocationSaveFailed) {
                TextButton(onClick = onDisable) { Text("重试保存关闭状态") }
            }
            state.lastCheckedAtMillis?.let { Text("上次检测：${formatRecordTime(it)}", style = MaterialTheme.typography.bodySmall) }
            state.lastOutcome?.let { Text(when (it) {
                "FLAG_CLEARED" -> "上次自动恢复：停止限制已解除"
                "NO_WRITE_NEEDED" -> "上次核对：无需恢复"
                "STILL_STOPPED" -> "上次恢复未成功：限制仍存在"
                "UNCONFIRMED" -> "上次恢复结果待确认，请只读核对"
                else -> "上次操作未完成，请查看暂停原因"
            }, style = MaterialTheme.typography.bodySmall) }
            if (showSharedGuidance) {
                Text("约每 30 秒检查，休眠可能延后；不保持目标应用常驻。",
                    style = MaterialTheme.typography.bodySmall)
                Text("检测到停止限制时尝试恢复，无固定恢复次数或间隔限制。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!canEnable && !state.masterEnabled && !state.busy) Text("请检查存储与待确认记录；开启时会重新核对安装身份和运行条件。",
                style = MaterialTheme.typography.bodySmall)
            }
            if (showPreferences) {
                Text(notificationVisibility.label(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { notificationSettingsFailed = !onOpenNotificationSettings() }) { Text("通知显示设置") }
                Text("关闭此类通知不关闭保护。", style = MaterialTheme.typography.bodySmall)
                if (notificationSettingsFailed) Text("未能打开设置，请在系统通知管理中调整 Guardian 的保护通知。",
                    style = MaterialTheme.typography.bodySmall)
            Text("Guardian 可在系统中开启自启动、允许后台耗电并锁定后台；隐藏任务不等于系统保活，锁定效果依机型而定。",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("在最近任务中隐藏")
                Switch(modifier = Modifier.semantics { contentDescription = "最近任务隐藏开关" },
                    checked = hiddenFromRecents, onCheckedChange = { if (it) confirmHide = true else onSetHidden(false) })
            }
            recentsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (confirmHide) AlertDialog(
        onDismissRequest = { confirmHide = false },
        title = { Text("隐藏 Guardian 任务？") },
        text = { Text("仅隐藏最近任务卡片，保留桌面入口。如需锁定后台，可先锁定再隐藏；锁定效果需在本机确认。可从桌面重新打开并关闭此选项。") },
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
        text = { Text("$packageName\n仅授权这个应用的当前安装：检测到停止限制时尝试解除，不让它常驻后台。其他应用不会因此获得授权。\n\n应用选择会保存；首页总开关开启时才运行保护。关闭总开关会保留应用选择，取消此应用选择会移除它的授权。\n\n无固定恢复次数或间隔限制；约每 30 秒检查，休眠可能延后，不保证通知送达。无法区分系统停止与主动强停，想保持应用停止时请先关闭总开关。已派发操作可能完成。缺少 Shizuku 时仅等待，重启后可能需重新启动 Shizuku。") },
        confirmButton = { Button(onClick = onConfirm) { Text("授权并选择") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
