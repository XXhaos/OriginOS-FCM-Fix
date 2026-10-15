package io.github.originosfcmguardian.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.monitoring.RetainedTimelineKind
import io.github.originosfcmguardian.monitoring.RetainedTimelineQuery
import io.github.originosfcmguardian.monitoring.MAX_RETAINED_TIMELINE_SEARCH_LENGTH
import io.github.originosfcmguardian.monitoring.RetainedTimelineSource
import io.github.originosfcmguardian.monitoring.RetainedTimelineTimeWindow
import io.github.originosfcmguardian.monitoring.RetainedTimelineTimestampMeaning
import io.github.originosfcmguardian.monitoring.RetainedTimelineWarning
import io.github.originosfcmguardian.monitoring.combineRetainedTimeline
import io.github.originosfcmguardian.monitoring.queryRetainedTimeline
import java.text.DateFormat
import java.util.Date

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RetainedTimelineCard(state: HomeUiState) {
    val currentLabels = state.selectedTargets.associate { it.identity.packageName to it.label }
    val snapshot = remember(
        state.notificationFacts.events,
        state.notificationFacts.historyCorrupt,
        state.deepMonitoring.session,
        state.deepMonitoring.historyCorrupt,
        currentLabels,
    ) {
        combineRetainedTimeline(
            notificationEvents = state.notificationFacts.events,
            notificationHistoryCorrupt = state.notificationFacts.historyCorrupt,
            processSession = state.deepMonitoring.session,
            processHistoryCorrupt = state.deepMonitoring.historyCorrupt,
            currentSelectedAppLabels = currentLabels,
        )
    }
    val packages = remember(snapshot) { snapshot.items.map { it.packageName }.distinct().sorted() }

    var searchText by remember { mutableStateOf("") }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var recentWindow by remember { mutableStateOf<RetainedTimelineTimeWindow?>(null) }
    var selectedSource by remember { mutableStateOf<RetainedTimelineSource?>(null) }
    var selectedKind by remember { mutableStateOf<RetainedTimelineKind?>(null) }

    val effectivePackage = selectedPackage
    val packageChoices = (packages + listOfNotNull(selectedPackage)).distinct()
    val query = RetainedTimelineQuery(
        searchText = searchText,
        packageName = effectivePackage,
        timeWindow = recentWindow,
        source = selectedSource,
        kind = selectedKind,
    )
    val filteredItems = remember(snapshot, query) { queryRetainedTimeline(snapshot, query) }

    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("保留事件时间线", style = MaterialTheme.typography.titleMedium)
            Text(
                "部分历史：最多 200 条通知和最近会话的 500 条进程事件。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "仅供观察，不证明消息送达、唤醒原因或连接状态；没有记录仍为未知。",
                style = MaterialTheme.typography.bodySmall,
            )

            snapshot.warnings.forEach { warning ->
                Text(
                    when (warning) {
                        RetainedTimelineWarning.NOTIFICATION_SOURCE_OMITTED ->
                            "通知历史损坏或超出范围，已省略该来源。"
                        RetainedTimelineWarning.PROCESS_SOURCE_OMITTED ->
                            "进程历史损坏或超出范围，已省略该来源。"
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it.take(MAX_RETAINED_TIMELINE_SEARCH_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索当前应用名称或包名") },
            )
            Text(
                "名称来自当前应用选择，不证明旧记录的安装身份；旧记录仍可按包名搜索。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("应用", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = effectivePackage == null,
                    onClick = { selectedPackage = null },
                    label = { Text("全部") },
                )
                packageChoices.forEach { packageName ->
                    FilterChip(
                        selected = effectivePackage == packageName,
                        onClick = { selectedPackage = packageName },
                        label = { Text(currentLabels[packageName] ?: packageName) },
                    )
                }
            }

            Text("时间", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = recentWindow == null,
                    onClick = { recentWindow = null },
                    label = { Text("全部时间") },
                )
                FilterChip(
                    selected = recentWindow != null,
                    onClick = {
                        recentWindow = RetainedTimelineTimeWindow.recent24Hours(System.currentTimeMillis())
                    },
                    label = { Text("近 24 小时") },
                )
            }
            recentWindow?.let { window ->
                Text(
                    "固定范围：${formatTimelineTime(window.fromEpochMillis)} 至 ${formatTimelineTime(window.throughEpochMillis)}（含端点）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("来源", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceChip(null, "全部来源", selectedSource) {
                    selectedSource = null
                }
                SourceChip(RetainedTimelineSource.NOTIFICATION, "通知观察", selectedSource) {
                    selectedSource = RetainedTimelineSource.NOTIFICATION
                    if (selectedKind?.source() == RetainedTimelineSource.PROCESS_SESSION) selectedKind = null
                }
                SourceChip(RetainedTimelineSource.PROCESS_SESSION, "最近进程会话", selectedSource) {
                    selectedSource = RetainedTimelineSource.PROCESS_SESSION
                    if (selectedKind?.source() == RetainedTimelineSource.NOTIFICATION) selectedKind = null
                }
            }

            Text("事件", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedKind == null,
                    onClick = { selectedKind = null },
                    label = { Text("全部事件") },
                )
                RetainedTimelineKind.entries
                    .filter { selectedSource == null || it.source() == selectedSource }
                    .forEach { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = { selectedKind = kind },
                            label = { Text(kind.label()) },
                        )
                    }
            }

            Text(
                "当前筛选 ${filteredItems.size} 条",
                style = MaterialTheme.typography.bodySmall,
            )
            if (filteredItems.isEmpty()) {
                Text("当前筛选没有保留事件；实际发生情况未知。", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredItems) { item ->
                        val currentLabel = item.currentSelectedAppLabel?.let { "$it · " }.orEmpty()
                        Text(
                            "$currentLabel${item.packageName} · ${item.kind.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${if (item.timestampMeaning == RetainedTimelineTimestampMeaning.NOTIFICATION_OBSERVED_AT) "观察于" else "事件于"} ${formatTimelineTime(item.timestampEpochMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                "导出请使用通知或进程会话各自的筛选入口；时间线不合并导出。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceChip(
    source: RetainedTimelineSource?,
    label: String,
    selectedSource: RetainedTimelineSource?,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selectedSource == source,
        onClick = onClick,
        label = { Text(label) },
    )
}

private fun RetainedTimelineKind.source(): RetainedTimelineSource = when (this) {
    RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED,
    RetainedTimelineKind.NOTIFICATION_REMOVED,
    -> RetainedTimelineSource.NOTIFICATION
    RetainedTimelineKind.PROCESS_START,
    RetainedTimelineKind.UID_ACTIVE,
    RetainedTimelineKind.UID_IDLE,
    RetainedTimelineKind.UID_RUNNING,
    RetainedTimelineKind.UID_STOPPED,
    -> RetainedTimelineSource.PROCESS_SESSION
}

private fun RetainedTimelineKind.label(): String = when (this) {
    RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED -> "通知发布/更新"
    RetainedTimelineKind.NOTIFICATION_REMOVED -> "通知移除"
    RetainedTimelineKind.PROCESS_START -> "进程启动"
    RetainedTimelineKind.UID_ACTIVE -> "应用活跃"
    RetainedTimelineKind.UID_IDLE -> "应用空闲"
    RetainedTimelineKind.UID_RUNNING -> "应用开始运行"
    RetainedTimelineKind.UID_STOPPED -> "应用停止运行"
}

private fun formatTimelineTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance().format(Date(epochMillis))
