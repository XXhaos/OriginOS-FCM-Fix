package io.github.originosfcmguardian.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.core.gms.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GmsConnectionUiState(
    val visible: Boolean = false, val busy: Boolean = false,
    val observation: GmsConnectionObservation? = null,
    val networkBackground: String = "尚未取样", val networkKey: String? = null,
    val networkChanged: Boolean = false,
    val networkTrackingAvailable: Boolean = false,
    val heartbeatBusy: Boolean = false,
    val heartbeatStatus: GmsHeartbeatStatus? = null,
    val readAttempted: Boolean = false,
    val readCompletedAtElapsedMillis: Long? = null,
    val automaticObservationAtReadCompletion: GmsConnectionObservation? = null,
)

internal fun gmsHeartbeatMessage(status: GmsHeartbeatStatus): String = when (status) {
    GmsHeartbeatStatus.NOT_SENT -> "本次未发送心跳；当前连接或特权条件不满足。"
    GmsHeartbeatStatus.REQUESTED -> "已请求心跳；不代表连接已修复或通知送达。"
    GmsHeartbeatStatus.UNKNOWN -> "请求结果不明；不会自动重发。"
}
internal data class GmsNetworkContext(val key: String, val summary: String)
internal fun gmsNetworkContext(context: Context): GmsNetworkContext = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.activeNetwork
    val caps = network?.let(cm::getNetworkCapabilities)
    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    GmsNetworkContext("${network?.networkHandle}:$wifi:$cell:$vpn:$validated",
        "${when { vpn -> "VPN"; wifi -> "Wi-Fi"; cell -> "移动网络"; network != null -> "其他网络"; else -> "无默认网络" }}；系统验证=${if (validated) "是" else "否"}")
}.getOrElse { GmsNetworkContext("unknown", "默认网络背景不可读取") }

internal fun gmsSampleHeadline(sample: GmsConnectionObservation.Sample, nowElapsed: Long, networkChanged: Boolean): String = when {
    networkChanged -> "网络背景已变化；该历史样本无法判断当前连接"
    !sample.isFresh(nowElapsed) -> "历史样本已超过30秒；当前连接未知"
    sample.observation == SocketObservation.OBSERVED -> "本次观察到匹配 TCP 迹象"
    sample.observation == SocketObservation.NO_MATCH -> "本次可读来源未观察到匹配；不等于断线"
    else -> "本次来源覆盖不足，无法判断"
}

internal fun gmsServiceHeadline(sample: GmsConnectionObservation.Sample, nowElapsed: Long, networkChanged: Boolean): String {
    val source = sample.gcmService
    val sampledElapsed = source.elapsedRealtimeMillis
    return when {
        networkChanged -> "网络背景已变化；当前 FCM 连接未知"
        sampledElapsed == null || source.completeness != SourceCompleteness.COMPLETE ||
            source.state == GcmConnectionState.UNKNOWN -> "FCM 连接状态未知"
        !sample.isFresh(nowElapsed) || nowElapsed < sampledElapsed ||
            nowElapsed - sampledElapsed > 30_000 -> "历史自报状态已过期；当前连接未知"
        source.state == GcmConnectionState.REPORTED_CONNECTED ->
            "FCM 已连接 · ${gmsConnectedDurationText(source.connectedDurationMillis)}"
        source.state == GcmConnectionState.SERVICE_ABSENT -> "本次完整读取：FCM 服务不存在"
        else -> "FCM 已断开"
    }
}

/** Fixed GMS-reported duration for this read; never extrapolate from clocks or socket evidence. */
internal fun gmsConnectedDurationText(durationMillis: Long?): String {
    if (durationMillis == null || durationMillis < 0) return "连接时长未知"
    val seconds = durationMillis / 1_000
    val duration = when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3_600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3_600}小时${seconds % 3_600 / 60}分${seconds % 60}秒"
    }
    return "连接时长 $duration"
}

internal fun gmsObservationUpdateText(sample: GmsConnectionObservation.Sample): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        .format(Date(sample.gcmService.observedAtMillis ?: sample.observedAtMillis))
    return "更新于$time"
}

@Composable
fun GmsConnectionDialog(state: GmsConnectionUiState, onRead: () -> Unit, onDismiss: () -> Unit,
                        onHeartbeat: () -> Unit, manualHeartbeatAllowed: Boolean = true) {
    val nowElapsed = fcmPresentationClock(state.observation)
    val status = fcmConnectionStatus(state.observation, nowElapsed,
        networkInvalid = state.networkChanged, networkTrackingUnavailable = !state.networkTrackingAvailable)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("FCM 连接") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status.text, modifier = Modifier.clearAndSetSemantics { contentDescription = status.accessibilityLabel },
                style = MaterialTheme.typography.titleMedium)
            Text("状态仅供参考，不代表消息送达。", style = MaterialTheme.typography.bodySmall)
            if (state.busy) Text(if (state.heartbeatBusy) "正在请求心跳…" else "读取中…")
            OutlinedButton(onClick = onHeartbeat, enabled = !state.busy && manualHeartbeatAllowed) { Text("触发一次心跳") }
            if (!manualHeartbeatAllowed) Text("请先关闭 FCM 保护并确认停止，再手动请求心跳。")
            state.heartbeatStatus?.let { Text(gmsHeartbeatMessage(it)) }
            if (state.observation == null || state.observation is GmsConnectionObservation.Unknown)
                Text("请确认 Shizuku 已授权，再读取一次。", style = MaterialTheme.typography.bodySmall)
            if (state.observation is GmsConnectionObservation.Sample && !state.networkTrackingAvailable)
                Text("无法确认网络变化，请重新读取。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onRead, enabled = !state.busy) { Text("读取一次") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.busy) "取消并关闭" else "关闭") } })
}
