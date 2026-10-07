package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

internal data class FcmConnectionStatus(val text: String, val accessibilityLabel: String)

/** A sampled GMS duration, never a time of day or a locally extrapolated stopwatch. */
internal fun fcmDurationClock(durationMillis: Long?): String? {
    if (durationMillis == null || durationMillis < 0) return null
    val seconds = durationMillis / 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}

internal fun fcmRelativeUpdate(sampledElapsed: Long, nowElapsed: Long): String {
    if (nowElapsed < sampledElapsed) return "更新时间未知"
    val seconds = (nowElapsed - sampledElapsed) / 1_000
    return when {
        seconds < 60 -> "更新于${seconds}秒前"
        seconds < 3_600 -> "更新于${seconds / 60}分钟前"
        seconds < 86_400 -> "更新于${seconds / 3_600}小时前"
        else -> "更新于${seconds / 86_400}天前"
    }
}

internal fun fcmConnectionStatus(observation: GmsConnectionObservation?, nowElapsed: Long,
    networkInvalid: Boolean = false, networkTrackingUnavailable: Boolean = false): FcmConnectionStatus {
    val sample = observation as? GmsConnectionObservation.Sample ?: return when (observation) {
        null -> FcmConnectionStatus("⚪ 尚未读取", "FCM 尚未读取")
        else -> FcmConnectionStatus("🟡 无法读取", "FCM 当前状态无法读取")
    }
    val source = sample.gcmService
    val elapsed = source.elapsedRealtimeMillis
    val age = fcmRelativeUpdate(elapsed ?: sample.elapsedRealtimeMillis, nowElapsed)
    fun status(symbol: String, label: String) = FcmConnectionStatus("$symbol $label · $age", "FCM $label，取样$age")
    if (networkInvalid) return status("⚪", "网络已变化")
    if (networkTrackingUnavailable) return status("⚪", "网络待确认")
    if (!sample.isFresh(nowElapsed) || elapsed != null &&
        (nowElapsed < elapsed || nowElapsed - elapsed > 30_000)) return status("⚪", "状态已过期")
    if (elapsed == null || source.completeness != SourceCompleteness.COMPLETE ||
        source.state == GcmConnectionState.UNKNOWN) return status("🟡", "状态未知")
    return when (source.state) {
        GcmConnectionState.REPORTED_CONNECTED -> {
            val duration = fcmDurationClock(source.connectedDurationMillis)
            FcmConnectionStatus("🟢 ${duration ?: "时长未知"} · $age",
                "FCM 已连接，取样时连接时长${duration ?: "未知"}，取样$age")
        }
        GcmConnectionState.REPORTED_DISCONNECTED -> status("🔴", "未连接")
        GcmConnectionState.SERVICE_ABSENT -> status("🔴", "服务未运行")
        GcmConnectionState.UNKNOWN -> status("🟡", "状态未知")
    }
}

/** Presentation only: ages existing data while visible; no reads, commands or background polling. */
@Composable
internal fun fcmPresentationClock(observation: GmsConnectionObservation?): Long {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(owner, observation) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    return maxOf(now, SystemClock.elapsedRealtime())
}
