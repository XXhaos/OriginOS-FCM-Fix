package io.github.originosfcmguardian.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.R
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onShizukuAction: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onShowTargetSelection: () -> Unit,
    onDismissTargetSelection: () -> Unit,
    onSetTargetSelected: (String, Boolean) -> Unit,
    onCheckTarget: (String) -> Unit,
    onClearCorruptTargetSelection: () -> Unit,
    onExportCombinedDiagnosticReport: (DiagnosticReportFormat) -> Unit = {},
    onRequestStoppedRecovery: (SelectedTargetIdentity) -> Unit = {},
    onConfirmStoppedRecovery: () -> Unit = {},
    onDismissStoppedRecovery: () -> Unit = {},
    onReconcileStoppedRecovery: () -> Unit = {},
    onRequestAutomaticProtection: () -> Unit = {},
    onConfirmAutomaticProtection: () -> Unit = {},
    onDismissAutomaticProtection: () -> Unit = {},
    onStopAutomaticProtection: () -> Unit = {},
    onRequestTargetAutomaticProtection: (SelectedTargetIdentity) -> Unit = { onRequestAutomaticProtection() },
    onStopTargetAutomaticProtection: (SelectedTargetIdentity) -> Unit = { onStopAutomaticProtection() },
    onRequestAppAutomaticProtection: (String) -> Unit = {},
    onRefreshApps: () -> Unit = {},
    onSetHiddenFromRecents: (Boolean) -> Unit = {},
    onOpenProtectionNotificationSettings: () -> Boolean = { false },
    onRefreshShizuku: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    onOpenFcmDiagnostics: () -> Boolean = { false },
    onReadGmsConnection: () -> Unit = {},
    onRequestFcmProtection: () -> Unit = {},
    onConfirmFcmProtection: () -> Unit = {},
    onDismissFcmProtection: () -> Unit = {},
    onDisableFcmProtection: () -> Unit = {},
    onRequestAddGuardianTile: ((String) -> Unit) -> Unit = { result ->
        result("下拉控制中心，进入编辑，将“推送保护”拖到常用区域。")
    },
    onCheckVisibleApp: (SelectedTargetIdentity) -> Unit = { onCheckTarget(it.packageName) },
    onRecheckSelectedApps: () -> Unit = {},
) {
    var primaryTab by rememberSaveable { mutableStateOf("home") }
    var secondaryDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var fcmDiagnosticsUnavailable by rememberSaveable { mutableStateOf(false) }
    var appSearch by rememberSaveable { mutableStateOf("") }
    val destination = secondaryDestination ?: primaryTab
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    BackHandler(enabled = secondaryDestination != null || primaryTab != "home") {
        if (secondaryDestination != null) secondaryDestination = null else primaryTab = "home"
    }
    if (state.fcmProtectionConfirmation) FcmProtectionConfirmation(onConfirmFcmProtection, onDismissFcmProtection)
    var showNoTouchProtection by remember { mutableStateOf(false) }
    if (showNoTouchProtection) {
        NoTouchProtectionDialog(onDismiss = { showNoTouchProtection = false },
            onRequestAddTile = onRequestAddGuardianTile)
    }
    fun eligible(target: io.github.originosfcmguardian.autostart.SelectedTargetUiState) =
        !state.automaticProtection.enabled && !state.automaticProtection.busy && canOfferStoppedRecovery(target, state.stoppedRecovery,
            state.targetSelectionLoadState == HomeSourceLoadState.LOADED && !state.targetSelectionStoreCorrupt,
            state.recoveryFirmwareSupported, state.shizuku.status == ShizukuStatus.READY_SHELL,
            state.detecting || target.identity in state.targetReadInFlight)
    if (recoveryConfirmationStillValid(state.stoppedRecovery.confirmationIdentity, state.selectedTargets, ::eligible)) {
        val target = state.selectedTargets.first { it.identity == state.stoppedRecovery.confirmationIdentity }
        StoppedRecoveryConfirmation(onConfirmStoppedRecovery, onDismissStoppedRecovery, target.label, target.identity.packageName)
    }
    val confirmingApp = state.visibleApps.firstOrNull { it.identity == state.automaticConfirmationIdentity }
    if (state.automaticConfirmationIdentity != null && confirmingApp != null && appProtectionPrerequisites(state, confirmingApp)) {
        AutomaticProtectionConfirmation(onConfirmAutomaticProtection, onDismissAutomaticProtection,
            confirmingApp.label,
            state.automaticConfirmationIdentity.packageName)
    }
    Scaffold(
        topBar = { TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (destination == "home") Surface(
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_guardian_tile), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                        }
                    }
                    Column {
                        Text(when (destination) {
                            "apps" -> "应用"
                            "settings" -> "设置"
                            else -> "Guardian"
                        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (destination == "home") Text("OriginOS 后台管理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = {
                if (secondaryDestination != null) TextButton(onClick = {
                    secondaryDestination = null
                }) { Text("返回") }
            },
            actions = {
                if (secondaryDestination == null) TextButton(onClick = {
                    fcmDiagnosticsUnavailable = !onOpenFcmDiagnostics()
                }) { Text("FCM Diagnostics", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp) {
                    NavigationBarItem(selected = primaryTab == "home" && secondaryDestination == null,
                        onClick = { primaryTab = "home"; secondaryDestination = null },
                        icon = { NavigationGlyph(home = true) }, label = { Text("主页") },
                        modifier = Modifier.semantics { contentDescription = "主页标签" })
                    NavigationBarItem(selected = primaryTab == "apps" && secondaryDestination == null,
                        onClick = { primaryTab = "apps"; secondaryDestination = null },
                        icon = { NavigationGlyph(home = false) }, label = { Text("应用") },
                        modifier = Modifier.semantics { contentDescription = "应用标签" })
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .then(if (destination == "apps") Modifier else Modifier.verticalScroll(when (destination) {
                    "settings" -> settingsScroll
                    else -> homeScroll
                }))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (fcmDiagnosticsUnavailable && secondaryDestination == null) Text(
                io.github.originosfcmguardian.fcm.GMS_DIAGNOSTICS_UNAVAILABLE,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (destination == "home") {
                HomeOverview(state, onReadGmsConnection,
                    { secondaryDestination = "settings" }, onShizukuAction = onShizukuAction,
                    onEnableMaster = onRequestAutomaticProtection, onDisableMaster = onStopAutomaticProtection,
                    onExport = onExportCombinedDiagnosticReport, onReconcile = onReconcileStoppedRecovery)
            }

            if (destination == "settings") {
            StatusCard("工作模式") {
                ShizukuWorkModeContent(state.shizuku, onShizukuAction, onRefreshShizuku,
                    onOpenShizuku, onNoTouch = { showNoTouchProtection = true })
            }

            AutomaticProtectionCard(state.automaticProtection, canEnableAutomaticProtection(state),
                state.hiddenFromRecents, state.recentsMessage, onRequestAutomaticProtection,
                onStopAutomaticProtection, onSetHiddenFromRecents, onOpenProtectionNotificationSettings,
                showProtectionControls = false, notificationVisibility = state.protectionNotificationVisibility,
                onOpenAutostartSettings = onOpenAutostartSettings,
                autostartSettingsMessage = state.autostartSettingsMessage)
            StatusCard("关于") {
                Text("OriginOS FCM Guardian", style = MaterialTheme.typography.bodyMedium)
                StatusRow("版本", BuildConfig.VERSION_NAME)
            }
            }

            if (destination == "apps") {
            AppsProtectionList(state, appSearch, { appSearch = it }, onRefreshApps,
                onRequestAppAutomaticProtection, onStopTargetAutomaticProtection, onClearCorruptTargetSelection,
                onRecheckSelected = onRecheckSelectedApps,
                modifier = Modifier.weight(1f), details = { row ->
                    val target = protectionAppRecheckTarget(state, row)
                    AppRecheckDetails(target,
                        checking = row.currentInstallation && row.app.identity in state.targetReadInFlight,
                        blocked = !canRecheckProtectionApp(state, row),
                        onCheck = {
                            if (canRecheckProtectionApp(state, row)) row.app.identity?.let(onCheckVisibleApp)
                        })
                }
            )
            }

        }
    }

    if (state.targetSelection.visible) {
        var selectionSearch by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissTargetSelection,
            title = { Text("选择关注应用") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(value = selectionSearch, onValueChange = { selectionSearch = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索可选应用") })
                    if (state.targetSelection.loading) Text("正在读取可启动应用…")
                    val candidates = state.targetSelection.candidates.filter {
                        it.label.contains(selectionSearch.trim(), ignoreCase = true) ||
                            it.packageName.contains(selectionSearch.trim(), ignoreCase = true)
                    }
                    if (!state.targetSelection.loading && candidates.isEmpty()) Text("没有匹配的应用")
                    candidates.forEach { candidate ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = candidate.selected,
                                onCheckedChange = { checked ->
                                    onSetTargetSelected(candidate.packageName, checked)
                                },
                            )
                            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                                Text(candidate.label)
                            }
                        }
                    }
                    state.targetSelection.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissTargetSelection) { Text("完成") }
            },
        )
    }
}

@Composable
internal fun NoTouchProtectionDialog(onDismiss: () -> Unit,
    onRequestAddTile: ((String) -> Unit) -> Unit) {
    var additionPending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无感保活") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将“推送保护”添加到控制中心，下拉显示时可恢复意外中断的保护。")
                Text("请保持 Shizuku 和 FCM 推送保护开启。手动关闭保护后不会自动开启。")
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !additionPending, onClick = {
                additionPending = true
                onRequestAddTile { additionPending = false; message = it }
            }) { Text(if (additionPending) "等待系统确认…" else "添加磁贴") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun SelectedTargetDetails(
    target: SelectedTargetUiState,
    checking: Boolean,
    recovery: StoppedRecoveryUiState,
    recoveryEligible: Boolean,
    detectionBusy: Boolean = false,
    onCheck: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    onRequestRecovery: () -> Unit,
    onReconcile: () -> Unit,
    automaticControls: (@Composable () -> Unit)? = null,
    automaticToggle: (@Composable () -> Unit)? = null,
    automaticStatus: String? = null,
    initiallyExpanded: Boolean = true,
    confirmedCounts: String? = null,
    showHeader: Boolean = true,
) {
    var expanded by rememberSaveable(target.identity.packageName, target.identity.firstInstallTime) { mutableStateOf(initiallyExpanded) }
    val recovering = recovery.phase == StoppedRecoveryPhase.RECOVERING && recovery.activeIdentity == target.identity
    val wake = targetWakePresentation(target, checking)
    Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showHeader) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TargetAppIcon(target)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(target.label, fontWeight = FontWeight.SemiBold)
                automaticStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (automaticControls == null) Text("仅检查与设置引导", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(wake.tone.color(), CircleShape))
                    Text(wake.text, color = wake.tone.color(), style = MaterialTheme.typography.bodySmall)
                }
            }
            automaticToggle?.invoke()
            TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = "${target.label}详情" }) {
                Text(if (expanded) "收起" else "详情")
            }
        }
        }
    if (!showHeader || expanded) {
    if (!showHeader) Text(wake.text, color = wake.tone.color(), style = MaterialTheme.typography.bodySmall)
    Text(target.identity.packageName, style = MaterialTheme.typography.bodySmall)
    confirmedCounts?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (automaticControls == null) Text("此应用目前仅支持只读检查与设置引导。", style = MaterialTheme.typography.bodySmall)
    automaticControls?.invoke()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCheck, enabled = !checking && !recovering && !detectionBusy) {
            Text(if (checking) "复检中…" else "复检")
        }
        OutlinedButton(onClick = onSettings) { Text("系统设置") }
        TextButton(onClick = onRemove) { Text("移除") }
    }
    StoppedRecoveryAction(stoppedRecoveryActionPresentation(target, recovery, recoveryEligible), onRequestRecovery)
    if (recovery.recordIdentity == target.identity) {
        StoppedRecoveryReceipt(recovery, target, reconcileBusy = checking || detectionBusy, onReconcile = onReconcile)
    }
    }
    }
    }
}

@Composable
private fun HomeOverview(state: HomeUiState, onGms: () -> Unit,
    onSettings: () -> Unit, onShizukuAction: () -> Unit,
    onEnableMaster: () -> Unit, onDisableMaster: () -> Unit,
    onExport: (DiagnosticReportFormat) -> Unit, onReconcile: () -> Unit) {
    ShizukuHomeCard(state.shizuku, onSettings, onShizukuAction)
    PushProtectionCard(state, onEnableMaster, onDisableMaster, onGms, onReconcile)
    RecoveryCountsCard(state)
    StatusCard("导出报告") {
        Text("报告含设备与应用信息，请私下分享。", style = MaterialTheme.typography.bodySmall)
        DiagnosticReportExportButton(state.diagnosticReportExportBusy, onExport)
        state.diagnosticReportExportFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ShizukuHomeCard(state: io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot,
    onSettings: () -> Unit, onAction: () -> Unit) {
    val presentation = shizukuWorkModePresentation(state)
    val colors = MaterialTheme.colorScheme
    val ready = state.status == ShizukuStatus.READY_SHELL
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(presentation.statusLabel, style = MaterialTheme.typography.bodySmall,
                        color = if (state.status == ShizukuStatus.ERROR || state.status == ShizukuStatus.READY_ROOT)
                            colors.error else colors.onSurfaceVariant)
                }
                OutlinedButton(onClick = onSettings, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.semantics { contentDescription = "连接与后台设置" },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("设置", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!ready) {
                Text(when (state.status) {
                    ShizukuStatus.PERMISSION_REQUIRED -> if (state.authorizationBlocked)
                        "请在 Shizuku 中允许 Guardian。" else "请授权 Guardian 使用 Shizuku。"
                    else -> presentation.summary
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                presentation.actionLabel?.let { label -> Button(onClick = onAction) { Text(label) } }
            }
        }
    }
}

@Composable
private fun RecoveryCountsCard(state: HomeUiState) {
    StatusCard("解除停止限制") {
        val counts = state.recoveryCounts
        if (counts.available) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(counts.byIdentity.values.sumOf { it.today }.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text("今日", style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(counts.byIdentity.values.sumOf { it.week }.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text("本周", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (!counts.available) Text("统计暂不可用", style = MaterialTheme.typography.bodySmall)
        else Text(recoveryCountsSnapshotText(counts.calculatedAtMillis, counts.zoneId),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigationGlyph(home: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 1.8.dp.toPx())
        if (home) {
            drawPath(Path().apply {
                moveTo(3 * unit, 11 * unit); lineTo(12 * unit, 3 * unit); lineTo(21 * unit, 11 * unit)
                moveTo(6 * unit, 9 * unit); lineTo(6 * unit, 21 * unit); lineTo(18 * unit, 21 * unit); lineTo(18 * unit, 9 * unit)
                moveTo(10 * unit, 21 * unit); lineTo(10 * unit, 15 * unit); lineTo(14 * unit, 15 * unit); lineTo(14 * unit, 21 * unit)
            }, color, style = stroke)
        } else {
            listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f).forEach { (x, y) ->
                drawRect(color, Offset(x * unit, y * unit), Size(6 * unit, 6 * unit), style = stroke)
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, attention: Boolean = false, emphasis: Boolean = false,
    content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Accent marks the primary controls, not connection health or runnable authority.
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (attention) colors.error.copy(alpha = 0.3f)
            else if (emphasis) colors.primary.copy(alpha = 0.24f) else colors.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = if (attention) MaterialTheme.colorScheme.errorContainer
            else if (emphasis) lerp(colors.surfaceContainer, colors.primaryContainer, 0.24f)
            else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (attention) colors.onErrorContainer else if (emphasis) colors.primary else colors.onSurface)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun ShizukuStatus.label(): String = when (this) {
    ShizukuStatus.NOT_INSTALLED -> "未安装"
    ShizukuStatus.NOT_RUNNING -> "未启动"
    ShizukuStatus.PERMISSION_REQUIRED -> "待授权"
    ShizukuStatus.READY_SHELL -> "已授权 · shell 就绪"
    ShizukuStatus.READY_ROOT -> "Root 模式（不支持）"
    ShizukuStatus.ERROR -> "错误"
}

private fun TargetAutostartStatus.label(): String = when (this) {
    TargetAutostartStatus.NOT_CHECKED -> "尚未检查"
    TargetAutostartStatus.CHECKING -> "正在检查"
    TargetAutostartStatus.ALLOWED -> "已允许"
    TargetAutostartStatus.NEEDS_USER_ACTION -> "未允许"
    TargetAutostartStatus.UNKNOWN -> "未知"
    TargetAutostartStatus.IDENTITY_CHANGED -> "应用身份已变化"
    TargetAutostartStatus.UNAVAILABLE -> "暂不可读取"
}

internal fun formatRecordTime(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(epochMillis))
