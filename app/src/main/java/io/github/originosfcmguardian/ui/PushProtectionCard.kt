package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private fun pushProtectionCommandBusy(state: HomeUiState): Boolean =
    state.pushProtectionBusy || state.automaticProtection.busy || state.fcmProtection.busy ||
        state.gmsAutoHeartbeat.stopPending

internal fun canStartPushProtection(state: HomeUiState): Boolean =
    !pushProtectionCommandBusy(state) && canEnableAutomaticProtection(state.copy(
        automaticProtection = state.automaticProtection.copy(masterEnabled = false))) &&
        (state.fcmProtection.configured || !state.fcmProtection.storageBlocked) &&
        state.fcmProtection.maintenance != FcmMaintenanceState.STOP_PENDING

/** The unified switch represents confirmed intent; an old unfinished master alone is not FCM consent. */
internal fun pushProtectionRequested(state: HomeUiState): Boolean =
    state.pushActivation.pending || state.pushActivation.blocked ||
        state.automaticProtection.revocationSaveFailed ||
        state.automaticProtection.masterEnabled && state.fcmProtection.configured

internal fun pushProtectionToggleEnabled(state: HomeUiState): Boolean =
    pushProtectionRequested(state) || state.automaticProtection.masterEnabled || canStartPushProtection(state)

internal fun dispatchPushProtectionToggle(state: HomeUiState, checked: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    if (!checked) onDisable()
    else if (!pushProtectionRequested(state) && canStartPushProtection(state)) onEnable()
}

internal fun pushProtectionStatus(state: HomeUiState): String = when {
    state.automaticProtection.revocationSaveFailed -> "关闭失败，请重试"
    state.fcmProtection.maintenance == FcmMaintenanceState.STOP_PENDING -> "停止待确认"
    state.pushActivation.storageFailed -> "开启设置暂不可用"
    state.pushActivation.blocked && state.pushActivation.reason ==
        io.github.originosfcmguardian.pushactivation.PushActivationReason.UNKNOWN_RESULT -> "请关闭后重新开启"
    state.pushActivation.blocked -> "开启结果待核对"
    state.pushActivation.pending && !state.automaticProtection.masterEnabled -> "等待保护启动"
    !state.automaticProtection.masterEnabled -> "保护未开启"
    state.pushProtectionBusy || state.automaticProtection.busy || state.fcmProtection.busy -> "正在处理…"
    state.automaticProtection.waitingForReconciliation -> "等待复检"
    state.automaticProtection.waitingForPrivilege -> "等待 Shizuku"
    state.automaticProtection.waitingForService -> "等待保护服务"
    state.fcmProtection.storageBlocked -> "FCM 保护暂不可用"
    state.fcmProtection.configured && (state.fcmProtection.requiresConfirmation ||
        state.fcmProtection.confirmationFailure != null) -> "FCM 保护暂不可用"
    !state.automaticProtection.enabled -> "等待保护恢复"
    !state.fcmProtection.configured -> if (state.pushActivation.pending) "正在开启 FCM 保护" else "FCM 保护未开启"
    state.fcmProtection.maintenance == FcmMaintenanceState.WAITING_HOST -> "等待保护恢复"
    state.fcmProtection.maintenance == FcmMaintenanceState.ACTIVE -> "保护已开启"
    state.fcmProtection.maintenance == FcmMaintenanceState.RECONFIRM -> "FCM 保护暂不可用"
    state.fcmProtection.maintenance == FcmMaintenanceState.WAITING_CONNECTED -> "已开启，等待 FCM 连接"
    else -> "FCM 保护等待启动"
}

/** Normal progress is represented by the switch and uptime; retain actionable failures only. */
private fun pushProtectionNotice(state: HomeUiState): String? = when {
    !state.pushProtectionMessage.isNullOrBlank() -> state.pushProtectionMessage
    state.automaticProtection.revocationSaveFailed ||
        state.fcmProtection.maintenance == FcmMaintenanceState.STOP_PENDING ||
        state.pushActivation.storageFailed || state.pushActivation.blocked -> pushProtectionStatus(state)
    state.fcmProtection.storageBlocked || state.fcmProtection.confirmationFailure != null ||
        state.fcmProtection.configured && (state.fcmProtection.requiresConfirmation ||
            state.fcmProtection.maintenance == FcmMaintenanceState.RECONFIRM) ->
        fcmProtectionGuidance(state.fcmProtection).takeIf { it.isNotBlank() } ?: "FCM 保护暂不可用"
    else -> null
}

@Composable
internal fun PushProtectionCard(state: HomeUiState, onEnable: () -> Unit, onDisable: () -> Unit,
    onReconcile: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Presentation only: leaving Home or RESUMED stops the uptime ticker.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    val displayNow = maxOf(now, SystemClock.elapsedRealtime())
    val uptime = automaticProtectionUptimeText(state.automaticProtection, displayNow) ?: "已开启时长 --:--:--"
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.24f)),
        colors = CardDefaults.cardColors(containerColor = guardianPrimaryCardColor(colors))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FCM推送保护", modifier = Modifier.weight(1f).semantics { heading() }, color = colors.primary,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Switch(checked = pushProtectionRequested(state),
                    enabled = pushProtectionToggleEnabled(state),
                    onCheckedChange = { dispatchPushProtectionToggle(state, it, onEnable, onDisable) },
                    modifier = Modifier.semantics {
                        contentDescription = "FCM推送保护开关"
                        stateDescription = "总开关${if (pushProtectionRequested(state)) "已开启" else "已关闭"}，${pushProtectionStatus(state)}"
                    })
            }
            PushProtectionClockLine("已开启时长", uptime.removePrefix("已开启时长 "))
            Text(if (state.automaticProtection.configurationAvailable)
                "已选 ${state.automaticProtection.configuredIdentities.size} 个应用" else "应用列表暂不可用",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val guidance = pushProtectionNotice(state)
                ?.replace("请开启自动保护，并启动 Shizuku。", "请启动并授权 Shizuku。")
            guidance?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            if (state.stoppedRecovery.needsReconcile) {
                TextButton(onClick = onReconcile, enabled = state.stoppedRecovery.loaded &&
                    !state.stoppedRecovery.busy && !state.stoppedRecovery.storageBlocked && !state.detecting &&
                    !state.automaticProtection.enabled && !state.automaticProtection.busy && !state.pushProtectionBusy) {
                    Text("核对上次结果")
                }
            }
            if (state.automaticProtection.revocationSaveFailed ||
                state.fcmProtection.maintenance == FcmMaintenanceState.STOP_PENDING) {
                TextButton(onClick = onDisable, enabled = !state.pushProtectionBusy) { Text("重试关闭") }
            }
        }
    }
}

@Composable
internal fun PushProtectionClockLine(label: String, value: String) {
    // Normal widths form two columns. Large fonts wrap as a pair without truncating the clock.
    FlowRow(Modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString("$label $value") },
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}
