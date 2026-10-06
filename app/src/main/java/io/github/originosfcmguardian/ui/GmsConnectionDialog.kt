package io.github.originosfcmguardian.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

data class GmsConnectionUiState(
    val visible: Boolean = false, val busy: Boolean = false,
    val observation: GmsConnectionObservation? = null,
    val networkBackground: String = "尚未取样", val networkKey: String? = null,
    val networkChanged: Boolean = false,
    val networkTrackingAvailable: Boolean = false,
    val heartbeatBusy: Boolean = false,
    val heartbeatStatus: GmsHeartbeatStatus? = null,
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
        networkChanged -> "网络背景已变化；当前 GMS 自报状态未知"
        sampledElapsed == null || source.completeness != SourceCompleteness.COMPLETE ||
            source.state == GcmConnectionState.UNKNOWN -> "GMS 自报连接状态未知"
        nowElapsed < sampledElapsed || nowElapsed - sampledElapsed > 30_000 -> "历史自报状态已过期；当前连接未知"
        source.state == GcmConnectionState.REPORTED_CONNECTED -> "GMS 自报：已连接"
        else -> "GMS 自报：已断开"
    }
}

@Composable
fun GmsConnectionDialog(state: GmsConnectionUiState, onRead: () -> Unit, onDismiss: () -> Unit,
                        onHeartbeat: () -> Unit, manualHeartbeatAllowed: Boolean = true) {
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.observation) {
        while (true) { nowElapsed = SystemClock.elapsedRealtime(); delay(1_000) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("GMS 连接") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("连接读取为只读；这里仅触发单次手动心跳，不表示已修复。")
            if (state.busy) Text(if (state.heartbeatBusy) "正在请求心跳…" else "读取中…")
            OutlinedButton(onClick = onHeartbeat, enabled = !state.busy && manualHeartbeatAllowed) { Text("触发一次心跳") }
            if (!manualHeartbeatAllowed) Text("需先关闭低频心跳并确认停止；未结算的旧操作不能手动绕过。")
            state.heartbeatStatus?.let { Text(gmsHeartbeatMessage(it)) }
            when (val value = state.observation) {
                null -> Text("尚未取样；需要已授权的 Shizuku shell。")
                is GmsConnectionObservation.Unknown -> Text("无法判断：${value.reason}")
                is GmsConnectionObservation.Sample -> {
                    Text(gmsServiceHeadline(value, nowElapsed, state.networkChanged || !state.networkTrackingAvailable))
                    Text("GMS 自报状态不保证连接活性或通知送达。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text(gmsSampleHeadline(value, nowElapsed, state.networkChanged || !state.networkTrackingAvailable))
                    if (!state.networkTrackingAvailable) Text("网络变化回调不可用；仅保留历史样本。")
                    Text("取样开始：${DateFormat.getDateTimeInstance().format(Date(value.observedAtMillis))}；样本年龄 ${((nowElapsed - value.elapsedRealtimeMillis).coerceAtLeast(0) / 1000)} 秒")
                    Text("shell /proc/net/tcp：${value.tcp.completeness}；tcp6：${value.tcp6.completeness}")
                    Text("仅匹配 GMS 独占 UID、established、远端5228–5230。不能证明端点、TLS、MCS 或通知送达；443 未用作 FCM 证据。")
                    Text("取样时 Guardian 默认网络背景：${state.networkBackground}。这不是 GMS 的实际路由；VPN 可影响可见性。")
                    Text(if (!value.receiverInspectionComplete) "GCM_RECONNECT manifest 接收者查询不可用。"
                        else if (value.receivers.isEmpty()) "未查询到 manifest 接收者；不排除动态注册。"
                        else "GCM_RECONNECT manifest 接收者：")
                    value.receivers.forEach {
                        Text("${it.name}\nexported=${it.exported}；manifest enabled=${it.enabled}；permission=${it.permission ?: "无"}")
                    }
                    Text("接收者元数据不证明 shell 可分发，也不证明重连有效。")
                }
            }
        } },
        confirmButton = { TextButton(onClick = onRead, enabled = !state.busy) { Text("读取一次") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.busy) "取消并关闭" else "关闭") } })
}
