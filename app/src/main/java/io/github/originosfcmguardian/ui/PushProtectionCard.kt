package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PUSH_CONNECTION_REFRESH_MILLIS = 15_000L
private const val PUSH_CONNECTION_SOURCE_FRESHNESS_MILLIS = 30_000L

internal data class PushConnectionClock(val duration: String? = null, val message: String? = null)

/** An in-flight refresh keeps its last completed evidence, subject to the same freshness/network guards. */
internal fun selectPushConnection(manual: GmsConnectionUiState,
    automatic: GmsConnectionObservation?): FcmInlineSelection =
    selectFcmInlineConnection(manual.copy(busy = false), automatic)

/** Only a fresh, complete GMS report on the same network can advance the display clock. */
internal fun pushConnectionClock(selection: FcmInlineSelection, nowElapsed: Long): PushConnectionClock {
    if (selection.busy) return PushConnectionClock(message = "正在读取 FCM 连接。")
    if (selection.networkInvalid) return PushConnectionClock(message = "网络已变化，等待刷新。")
    if (selection.networkTrackingUnavailable) return PushConnectionClock(message = "网络状态待确认。")
    val sample = selection.observation as? GmsConnectionObservation.Sample
        ?: return PushConnectionClock(message = if (selection.observation == null) "FCM 连接尚未读取。" else "FCM 连接暂不可读取。")
    val source = sample.gcmService
    val sampledAt = source.elapsedRealtimeMillis
    if (!sample.isFresh(nowElapsed) || sampledAt != null &&
        (nowElapsed < sampledAt || nowElapsed - sampledAt > PUSH_CONNECTION_SOURCE_FRESHNESS_MILLIS)) {
        return PushConnectionClock(message = "FCM 连接状态待更新。")
    }
    if (sampledAt == null || source.completeness != SourceCompleteness.COMPLETE) {
        return PushConnectionClock(message = "FCM 连接状态未知。")
    }
    return when (source.state) {
        GcmConnectionState.REPORTED_CONNECTED -> {
            val reported = source.connectedDurationMillis?.takeIf { it >= 0 }
            val age = nowElapsed - sampledAt
            // Preserve unknown and guard arithmetic even if a source reports an extreme duration.
            val duration = reported?.takeIf { it <= Long.MAX_VALUE - age }?.plus(age)
            PushConnectionClock(fcmDurationClock(duration), if (duration == null) "FCM 已连接，连接时长未知。" else null)
        }
        GcmConnectionState.REPORTED_DISCONNECTED -> PushConnectionClock(message = "FCM 尚未连接。")
        GcmConnectionState.SERVICE_ABSENT -> PushConnectionClock(message = "Google Play 服务尚未运行。")
        GcmConnectionState.UNKNOWN -> PushConnectionClock(message = "FCM 连接状态未知。")
    }
}

private fun pushProtectionCommandBusy(state: HomeUiState): Boolean =
    state.pushProtectionBusy || state.automaticProtection.busy || state.fcmProtection.busy ||
        state.gmsAutoHeartbeat.stopPending

internal fun canStartPushProtection(state: HomeUiState): Boolean =
    !pushProtectionCommandBusy(state) && canEnableAutomaticProtection(state) &&
        (state.fcmProtection.configured || !state.fcmProtection.storageBlocked) &&
        state.fcmProtection.maintenance != FcmMaintenanceState.STOP_PENDING

internal fun canCompletePushProtection(state: HomeUiState): Boolean =
    state.automaticProtection.masterEnabled && !state.automaticProtection.revocationSaveFailed &&
        !pushProtectionCommandBusy(state) && !state.fcmProtection.storageBlocked &&
        state.fcmProtection.maintenance != FcmMaintenanceState.STOP_PENDING &&
        !state.fcmProtection.configured

internal fun pushProtectionToggleEnabled(state: HomeUiState): Boolean =
    state.automaticProtection.masterEnabled || canStartPushProtection(state)

internal fun dispatchPushProtectionToggle(state: HomeUiState, checked: Boolean,
    onEnable: () -> Unit, onDisable: () -> Unit) {
    if (!checked) onDisable()
    else if (!state.automaticProtection.masterEnabled && canStartPushProtection(state)) onEnable()
}

internal fun pushProtectionStatus(state: HomeUiState): String = when {
    state.automaticProtection.revocationSaveFailed -> "关闭失败，请重试"
    state.fcmProtection.maintenance == FcmMaintenanceState.STOP_PENDING -> "停止待确认"
    !state.automaticProtection.masterEnabled -> "保护未开启"
    state.pushProtectionBusy || state.automaticProtection.busy || state.fcmProtection.busy -> "正在处理…"
    state.automaticProtection.waitingForReconciliation -> "等待复检"
    state.automaticProtection.waitingForPrivilege -> "等待 Shizuku"
    state.automaticProtection.waitingForService -> "等待保护服务"
    state.fcmProtection.storageBlocked -> "FCM 保护暂不可用"
    state.fcmProtection.configured && (state.fcmProtection.requiresConfirmation ||
        state.fcmProtection.confirmationFailure != null) -> "FCM 保护暂不可用"
    !state.automaticProtection.enabled -> "等待保护恢复"
    !state.fcmProtection.configured -> "FCM 保护待完成"
    state.fcmProtection.maintenance == FcmMaintenanceState.WAITING_HOST -> "等待保护恢复"
    state.fcmProtection.maintenance == FcmMaintenanceState.ACTIVE -> "保护已开启"
    state.fcmProtection.maintenance == FcmMaintenanceState.RECONFIRM -> "FCM 保护暂不可用"
    state.fcmProtection.maintenance == FcmMaintenanceState.WAITING_CONNECTED -> "已开启，等待 FCM 连接"
    else -> "FCM 保护等待启动"
}

@Composable
internal fun PushProtectionCard(state: HomeUiState, onEnable: () -> Unit, onDisable: () -> Unit,
    onReadConnection: () -> Unit, onReconcile: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val readConnection by rememberUpdatedState(onReadConnection)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // This composable is mounted only on Home. Leaving Home or RESUMED cancels both UI loops.
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var nextReadAt = 0L
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime()
                now = elapsed
                if (elapsed >= nextReadAt) {
                    readConnection()
                    nextReadAt = elapsed + PUSH_CONNECTION_REFRESH_MILLIS
                }
                delay(1_000)
            }
        }
    }
    val displayNow = maxOf(now, SystemClock.elapsedRealtime())
    val connection = pushConnectionClock(
        selectPushConnection(state.gmsConnection, state.fcmProtection.latestObservation), displayNow)
    val uptime = automaticProtectionUptimeText(state.automaticProtection, displayNow) ?: "已开启时长 --:--:--"
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FCM推送保护", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Switch(checked = state.automaticProtection.masterEnabled,
                    enabled = pushProtectionToggleEnabled(state),
                    onCheckedChange = { dispatchPushProtectionToggle(state, it, onEnable, onDisable) },
                    modifier = Modifier.semantics { contentDescription = "FCM推送保护开关" })
            }
            Text(pushProtectionStatus(state), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            PushProtectionClockLine(uptime)
            PushProtectionClockLine("FCM连接时长 ${connection.duration ?: "--:--:--"}")
            Text(if (state.automaticProtection.configurationAvailable)
                "已选 ${state.automaticProtection.configuredIdentities.size} 个应用" else "应用列表暂不可用",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val guidance = state.pushProtectionMessage ?: fcmProtectionGuidance(state.fcmProtection)
                .replace("请开启自动保护，并启动 Shizuku。", "请启动并授权 Shizuku。")
                .takeIf { it.isNotEmpty() && state.automaticProtection.masterEnabled &&
                    (state.fcmProtection.maintenance != FcmMaintenanceState.WAITING_HOST ||
                        state.shizuku.status != io.github.originosfcmguardian.privileged.ShizukuStatus.READY_SHELL ||
                        state.fcmProtection.confirmationFailure != null || state.fcmProtection.storageBlocked) }
            guidance?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            if (canCompletePushProtection(state)) {
                TextButton(onClick = onEnable) { Text("完成 FCM 保护") }
            }
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
private fun PushProtectionClockLine(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
