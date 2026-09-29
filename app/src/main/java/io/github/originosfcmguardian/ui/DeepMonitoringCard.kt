package io.github.originosfcmguardian.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.monitoring.MonitorRunPhase
import io.github.originosfcmguardian.monitoring.MonitorSessionStatus
import io.github.originosfcmguardian.monitoring.MonitorSessionQuery
import io.github.originosfcmguardian.monitoring.queryMonitorSession
import io.github.originosfcmguardian.privileged.ShizukuStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DeepMonitoringCard(
    state: HomeUiState,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: (MonitorSessionQuery) -> Unit,
) {
    val monitor = state.deepMonitoring
    val packages = state.selectedTargets.map { it.identity.packageName }
    var chosen by remember(packages) { mutableStateOf(packages.firstOrNull()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手动深度监控", style = MaterialTheme.typography.titleMedium)
            Text("仅排障时开启，一次观察一个应用，30 分钟后自动结束。会显示持续通知，不修改智能耗电策略。", style = MaterialTheme.typography.bodySmall)
            Text("需要 Shizuku shell 和可见通知。长时间熄屏时系统可能暂停监控；空记录不代表没有发生事件。", style = MaterialTheme.typography.bodySmall)
            Text(when (monitor.phase) {
                MonitorRunPhase.IDLE -> "状态：未运行"
                MonitorRunPhase.STARTING -> "状态：准备中"
                MonitorRunPhase.RUNNING -> "状态：运行中"
                MonitorRunPhase.STOPPING -> "状态：清理中"
            })
            Text(monitor.message, style = MaterialTheme.typography.bodySmall)
            state.deepMonitoringActionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.deepMonitoringExportFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.selectedTargets.forEach { target ->
                    FilterChip(
                        selected = chosen == target.identity.packageName,
                        onClick = { chosen = target.identity.packageName },
                        enabled = !monitor.busy,
                        label = { Text(target.label) },
                    )
                }
            }
            if (packages.isEmpty()) Text("请先在“即时通知应用”中选择目标应用。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { chosen?.let(onStart) },
                    enabled = !monitor.busy && !state.deepMonitoringExportBusy && !monitor.historyCorrupt && chosen != null &&
                        state.shizuku.status == ShizukuStatus.READY_SHELL,
                ) { Text("开启 30 分钟") }
                OutlinedButton(onClick = onStop, enabled = monitor.busy && monitor.phase != MonitorRunPhase.STOPPING) {
                    Text("停止监控")
                }
            }
            monitor.session?.let { session ->
                var chosenKind by remember(session.id) { mutableStateOf<ProcessTraceKind?>(null) }
                var recentOnly by remember(session.id) { mutableStateOf(false) }
                val latestTime = session.events.maxOfOrNull { it.timestampEpochMillis } ?: session.startedAtEpochMillis
                val query = MonitorSessionQuery(
                    sessionId = session.id,
                    fromEpochMillis = if (recentOnly) maxOf(session.startedAtEpochMillis, latestTime - 5 * 60_000L) else null,
                    throughEpochMillis = if (recentOnly) latestTime else null,
                    kind = chosenKind,
                )
                val events = remember(session, query) { queryMonitorSession(session, query) }
                Text("最近会话：${session.identity.packageName}", style = MaterialTheme.typography.bodySmall)
                Text("${session.status.label()} · ${session.events.size}/500 条 · ${session.pollCount} 次有限读取", style = MaterialTheme.typography.bodySmall)
                Text("开始于 ${DateFormat.getDateTimeInstance().format(Date(session.startedAtEpochMillis))}", style = MaterialTheme.typography.bodySmall)
                Text(if (session.events.isEmpty()) {
                    "尚无可显示的进程事件，发生情况未知；空记录不能证明应用未启动。"
                } else {
                    "下列事件来自系统记录；FCM 来源、唤醒原因及连续连接均未知。记录仅属于该次固定安装身份。"
                }, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !recentOnly, onClick = { recentOnly = false }, label = { Text("全会话") })
                    FilterChip(selected = recentOnly, onClick = { recentOnly = true }, label = { Text("末尾 5 分钟") })
                }
                if (recentOnly) Text("以本会话最近一条记录的时间为终点。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = chosenKind == null, onClick = { chosenKind = null }, label = { Text("全部事件") })
                    ProcessTraceKind.entries.forEach { kind ->
                        FilterChip(selected = chosenKind == kind, onClick = { chosenKind = kind }, label = { Text(kind.label()) })
                    }
                }
                Text("当前筛选 ${events.size} 条 · 系统事件事实", style = MaterialTheme.typography.bodySmall)
                if (events.isEmpty()) Text("当前筛选没有记录，事件发生情况未知。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    events.forEach { event ->
                        val name = when (event.kind) {
                            ProcessTraceKind.PROCESS_START -> "进程启动（PID ${event.pid}）"
                            ProcessTraceKind.UID_ACTIVE -> "UID 活跃"
                            ProcessTraceKind.UID_IDLE -> "UID 空闲"
                        }
                        Text("${DateFormat.getDateTimeInstance().format(Date(event.timestampEpochMillis))} · $name", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = { onExport(query) },
                    enabled = !monitor.historyCorrupt && !state.deepMonitoringExportBusy,
                ) { Text(if (state.deepMonitoringExportBusy) "正在导出…" else "导出会话筛选 JSON") }
                Text("导出点击时的记录；运行中的会话只是当时快照。文件含应用包名和进程事件，不含通知内容或安装签名。", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClear, enabled = !monitor.busy && !state.deepMonitoringExportBusy && (monitor.session != null || monitor.historyCorrupt)) {
                Text("清除最近监控记录")
            }
            Text("只保留最近一次会话；新会话会替换旧会话。通知记录与监控记录分开保存。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ProcessTraceKind.label(): String = when (this) {
    ProcessTraceKind.PROCESS_START -> "进程启动"
    ProcessTraceKind.UID_ACTIVE -> "UID 活跃"
    ProcessTraceKind.UID_IDLE -> "UID 空闲"
}

private fun MonitorSessionStatus.label(): String = when (this) {
    MonitorSessionStatus.RUNNING -> "运行中"
    MonitorSessionStatus.STOPPED -> "已手动停止"
    MonitorSessionStatus.DEADLINE -> "时限结束"
    MonitorSessionStatus.UNAVAILABLE -> "读取或保存不可用"
    MonitorSessionStatus.IDENTITY_CHANGED -> "身份改变，已停止"
    MonitorSessionStatus.LIMIT_REACHED -> "记录达到上限"
    MonitorSessionStatus.INTERRUPTED -> "上次运行中断"
}
