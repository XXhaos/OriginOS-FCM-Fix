package io.github.originosfcmguardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsProtectionList(
    state: HomeUiState,
    query: String,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (SelectedTargetIdentity) -> Unit,
    onClearCorrupt: () -> Unit,
    details: @Composable (ProtectionAppRow) -> Unit,
    modifier: Modifier = Modifier,
    onRecheckSelected: () -> Unit = {},
) {
    val groups = protectionAppGroups(state, query)
    var selectedApp by remember { mutableStateOf<ProtectionAppRow?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(query, onQuery, singleLine = true,
            shape = MaterialTheme.shapes.medium, placeholder = { Text("搜索应用") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索名称或包名" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRecheckSelected, enabled = canRecheckSelectedApps(state)) {
                Text(if (state.selectedAppsRecheckRunning) "正在复检…" else "复检已选应用")
            }
            TextButton(onClick = onRefresh, enabled = state.visibleAppsLoadState != HomeSourceLoadState.LOADING) { Text("刷新") }
        }
        if (state.visibleAppsLoadState == HomeSourceLoadState.LOADING) Text("正在读取应用…", style = MaterialTheme.typography.bodySmall)
        state.appsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (state.visibleAppsLoadState == HomeSourceLoadState.FAILED) TextButton(onClick = onRefresh) { Text("重试") }
        if (state.targetSelectionStoreCorrupt) {
            Text("应用记录损坏，需处理后才能开启保护。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onClearCorrupt) { Text("清除损坏记录") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                ApplicationSectionTitle(if (state.automaticProtection.configurationAvailable) "已保护"
                    else "上次已知选择", groups.protected.size)
                Text("应用选择不代表当前状态；首页总开关控制运行。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            if (groups.protected.isEmpty()) item { Text(protectedAppsEmptyLabel(state.automaticProtection.configurationAvailable, query),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            items(groups.protected, key = { if (it.currentInstallation) "current:${it.app.packageName}" else "old:${it.app.identity}" }) { row ->
                ProtectionApplicationRow(state, row, onEnable, onDisable) { selectedApp = row }
            }
            item { ApplicationSectionTitle("所有应用", groups.all.size, Modifier.padding(top = 16.dp)) }
            if (groups.all.isEmpty() && state.visibleAppsLoadState != HomeSourceLoadState.LOADING) item {
                Text(if (query.isBlank()) "没有其他可见应用" else "没有匹配的应用", modifier = Modifier.padding(vertical = 8.dp))
            }
            items(groups.all, key = { if (it.currentInstallation) "current:${it.app.packageName}" else "old:${it.app.identity}" }) { row ->
                ProtectionApplicationRow(state, row, onEnable, onDisable) { selectedApp = row }
            }
        }
    }
    selectedApp?.let { openedRow ->
        // Keep the opened identity fixed while presenting live state. A replaced installation cannot
        // inherit this sheet's selection or recheck action.
        val allRows = protectionAppGroups(state, "").let { it.protected + it.all }
        val row = allRows.firstOrNull {
            it.app.identity == openedRow.app.identity && it.app.packageName == openedRow.app.packageName &&
                it.currentInstallation == openedRow.currentInstallation
        } ?: openedRow.copy(currentInstallation = false)
        ModalBottomSheet(onDismissRequest = { selectedApp = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            ProtectionApplicationSheet(state, row, onEnable, onDisable, details) { selectedApp = null }
        }
    }
}

@Composable
private fun ApplicationSectionTitle(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$title（$count）" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(count.toString(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun ProtectionApplicationRow(state: HomeUiState, row: ProtectionAppRow,
    onEnable: (String) -> Unit, onDisable: (SelectedTargetIdentity) -> Unit,
    onOpen: () -> Unit) {
    val app = row.app
    val configured = app.identity in state.automaticProtection.configuredIdentities
    val rowShape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth()
        .padding(vertical = 2.dp)
        .clip(rowShape)
        .background(if (configured) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow)
        .then(if (configured) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, rowShape) else Modifier)
        .clickable(role = Role.Button, onClickLabel = "查看应用详情", onClick = onOpen)
        .testTag("app-details:${app.packageName}:${row.currentInstallation}")
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TargetAppIcon(app.packageName, app.label, app.identity, size = 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppProtectionSelection(configured,
            row.currentInstallation && canPrepareAppProtection(state, app),
            { onEnable(app.packageName) }, { app.identity?.let(onDisable) }, app.label)
    }
}

@Composable
private fun ProtectionApplicationSheet(state: HomeUiState, row: ProtectionAppRow,
    onEnable: (String) -> Unit, onDisable: (SelectedTargetIdentity) -> Unit,
    details: @Composable (ProtectionAppRow) -> Unit, onClose: () -> Unit) {
    val app = row.app
    val configured = app.identity in state.automaticProtection.configuredIdentities
    Column(Modifier.fillMaxWidth().testTag("app-sheet:${app.packageName}")
        .verticalScroll(rememberScrollState()).navigationBarsPadding()
        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TargetAppIcon(app.packageName, app.label, app.identity, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(app.label, style = MaterialTheme.typography.headlineMedium)
                Text(app.packageName, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("自动保护选择", style = MaterialTheme.typography.titleMedium)
                Text("总开关开启时运行；关闭此开关移除选择。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppProtectionSelection(configured, row.currentInstallation && canPrepareAppProtection(state, app),
                { onClose(); onEnable(app.packageName) }, { app.identity?.let(onDisable) }, "${app.label} 详情内")
        }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("当前状态", style = MaterialTheme.typography.titleSmall)
            Text(protectionAppStatus(state, row), style = MaterialTheme.typography.bodyMedium)
            if (app.identity != null) details(row)
        }
        if (configured) {
            val counts = protectionAppRecoveryCounts(state, row)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(counts?.let { "今日 ${it.today} · 本周 ${it.week} 次确认解除" } ?: "确认次数暂不可用",
                    style = MaterialTheme.typography.bodyMedium)
                Text("仅计解除限制，不代表消息送达。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (counts != null) Text(recoveryCountsSnapshotText(state.recoveryCounts.calculatedAtMillis, state.recoveryCounts.zoneId),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
    }
}

@Composable
internal fun AppProtectionSelection(configured: Boolean, canSelect: Boolean,
    onSelect: () -> Unit, onRemove: () -> Unit, label: String) {
    Switch(checked = configured, enabled = configured || canSelect,
        onCheckedChange = { if (it) onSelect() else onRemove() },
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics { contentDescription = "$label 自动保护选择" })
}
