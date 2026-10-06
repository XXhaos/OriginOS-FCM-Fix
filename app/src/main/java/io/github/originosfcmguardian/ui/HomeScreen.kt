package io.github.originosfcmguardian.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.monitoring.MonitorRunPhase
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.protection.ProtectionCheckStatus
import io.github.originosfcmguardian.fcm.FcmConfigurationStatus
import io.github.originosfcmguardian.fcm.MessagePriority
import io.github.originosfcmguardian.fcm.NetworkAvailability
import io.github.originosfcmguardian.fcm.NotificationOutcome
import io.github.originosfcmguardian.fcm.NotificationPermissionStatus
import io.github.originosfcmguardian.fcm.ObservedBoolean
import io.github.originosfcmguardian.fcm.RegistrationStatus
import io.github.originosfcmguardian.notification.NotificationFactKind
import io.github.originosfcmguardian.notification.NotificationFactsQuery
import io.github.originosfcmguardian.notification.queryNotificationFacts

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
    onRunDailyProtection: () -> Unit,
    onClearCorruptTargetSelection: () -> Unit,
    onRequestFcmToken: () -> Unit,
    onToggleFcmTokenVisibility: () -> Unit,
    onCopyFcmToken: () -> Unit,
    onClearFcmToken: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSetNotificationFactsEnabled: (Boolean) -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onClearNotificationFactsHistory: () -> Unit,
    onExportNotificationFacts: (NotificationFactsQuery) -> Unit,
    onStartDeepMonitoring: (String) -> Unit = {},
    onStopDeepMonitoring: () -> Unit = {},
    onClearDeepMonitoring: () -> Unit = {},
    onExportMonitorSession: (io.github.originosfcmguardian.monitoring.MonitorSessionQuery) -> Unit = {},
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
    onOpenGmsConnection: () -> Unit = {},
    onOpenFcmDiagnostics: () -> Boolean = { false },
    onReadGmsConnection: () -> Unit = {},
    onRequestGmsHeartbeat: () -> Unit = {},
    onRequestFcmProtection: () -> Unit = {},
    onConfirmFcmProtection: () -> Unit = {},
    onDismissFcmProtection: () -> Unit = {},
    onDisableFcmProtection: () -> Unit = {},
    onDismissGmsConnection: () -> Unit = {},
    onRequestAddGuardianTile: ((String) -> Unit) -> Unit = { result ->
        result("下拉控制中心，进入编辑，将“推送保护”拖到常用区域。")
    },
    onCheckVisibleApp: (SelectedTargetIdentity) -> Unit = { onCheckTarget(it.packageName) },
    onRecheckSelectedApps: () -> Unit = {},
) {
    var primaryTab by rememberSaveable { mutableStateOf("home") }
    var secondaryDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticToolName by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedDiagnostics by rememberSaveable { mutableStateOf(false) }
    var fcmDiagnosticsUnavailable by rememberSaveable { mutableStateOf(false) }
    val diagnosticTool = DiagnosticTool.entries.firstOrNull { it.name == diagnosticToolName }
    var appSearch by rememberSaveable { mutableStateOf("") }
    val destination = secondaryDestination ?: primaryTab
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val diagnosticsScroll = rememberScrollState()
    LaunchedEffect(secondaryDestination, diagnosticToolName) {
        if (secondaryDestination == "diagnostics") diagnosticsScroll.scrollTo(0)
    }
    BackHandler(enabled = secondaryDestination != null || primaryTab != "home") {
        if (secondaryDestination == "diagnostics" && diagnosticTool != null) diagnosticToolName = null
        else if (secondaryDestination != null) secondaryDestination = null else primaryTab = "home"
    }
    if (state.gmsConnection.visible) GmsConnectionDialog(state.gmsConnection, onReadGmsConnection,
        onDismissGmsConnection, onRequestGmsHeartbeat, !state.gmsAutoHeartbeat.manualBlocked)
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
                Column {
                    Text(when (destination) {
                        "apps" -> "应用"
                        "settings" -> "设置"
                        "diagnostics" -> diagnosticTool?.label ?: "诊断"
                        else -> "Guardian"
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (destination == "home") Text("OriginOS 后台管理",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                if (secondaryDestination != null) TextButton(onClick = {
                    if (secondaryDestination == "diagnostics" && diagnosticTool != null) diagnosticToolName = null
                    else secondaryDestination = null
                }) { Text("返回") }
            },
            actions = {
                if (secondaryDestination == null) TextButton(onClick = {
                    fcmDiagnosticsUnavailable = !onOpenFcmDiagnostics()
                }) { Text("FCM Diagnostics", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                else if (destination == "diagnostics") TextButton(onClick = { secondaryDestination = "settings" }) { Text("设置") }
                else if (destination == "settings") TextButton(onClick = {
                    diagnosticToolName = null
                    secondaryDestination = "diagnostics"
                }) { Text("诊断") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        },
        bottomBar = {
            Column {
                // Stops stay outside scrollable destinations, even after the target is removed.
                ProtectionStopBar(state, onStopAutomaticProtection, onStopDeepMonitoring,
                    { onSetNotificationFactsEnabled(false) }, onOpenNotificationListenerSettings)
                NavigationBar {
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
                    "diagnostics" -> diagnosticsScroll
                    else -> homeScroll
                }))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (fcmDiagnosticsUnavailable && secondaryDestination == null) Text(
                io.github.originosfcmguardian.fcm.GMS_DIAGNOSTICS_UNAVAILABLE,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (destination == "home") {
                HomeOverview(state, onOpenGmsConnection, { primaryTab = "apps" },
                    { secondaryDestination = "settings" }, {
                        diagnosticToolName = null
                        secondaryDestination = "diagnostics"
                    }, onShizukuAction = onShizukuAction,
                    onEnableMaster = onRequestAutomaticProtection, onDisableMaster = onStopAutomaticProtection,
                    onEnableFcm = onRequestFcmProtection, onDisableFcm = onDisableFcmProtection)
            }

            if (destination == "settings") {
            StatusCard("工作模式") {
                ShizukuWorkModeContent(state.shizuku, onShizukuAction, onRefreshShizuku,
                    onOpenShizuku, onNoTouch = { showNoTouchProtection = true })
            }

            AutomaticProtectionCard(state.automaticProtection, canEnableAutomaticProtection(state),
                state.hiddenFromRecents, state.recentsMessage, onRequestAutomaticProtection,
                onStopAutomaticProtection, onSetHiddenFromRecents, onOpenProtectionNotificationSettings,
                showProtectionControls = false, notificationVisibility = state.protectionNotificationVisibility)

            StatusCard("自启动设置") {
                Text("请手动允许 Guardian 和需要接收通知的应用自启动。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenAutostartSettings) { Text("打开自启动设置") }
                state.autostartSettingsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            StatusCard("关于") {
                Text("OriginOS FCM Guardian", style = MaterialTheme.typography.bodyMedium)
                StatusRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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

            if (destination == "diagnostics") {
            if (diagnosticTool == null) {
                StatusCard("问题摘要") {
                    Text(diagnosticSummary(state), style = MaterialTheme.typography.bodyMedium)
                    diagnosticIssues(state).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.stoppedRecovery.needsReconcile || state.stoppedRecovery.storageBlocked) {
                        StoppedRecoveryReceipt(state.stoppedRecovery,
                            reconcileBusy = state.detecting || state.automaticProtection.enabled || state.automaticProtection.busy,
                            onReconcile = onReconcileStoppedRecovery)
                    }
                }
                StatusCard("最近操作") {
                    val recent = recentDiagnosticActions(state)
                    if (recent.isEmpty()) Text("暂无已保存操作")
                    recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { diagnosticToolName = DiagnosticTool.RECOVERY.name }) { Text("查看操作历史") }
                }
                StatusCard("导出报告") {
                    Text("含设备型号、系统版本、所选应用包名和名称，分享前请检查；不含令牌、通知正文、testId、Android ID、签名、UID、PID 或原始命令输出。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("只导出已有快照，不执行新的特权读取；连接和投递因果仍可能未知。", style = MaterialTheme.typography.bodySmall)
                    DiagnosticReportExportButton(state.diagnosticReportExportBusy, onExportCombinedDiagnosticReport)
                    state.diagnosticReportExportFeedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                StatusCard("高级排查") {
                    TextButton(onClick = { advancedDiagnostics = !advancedDiagnostics }) {
                        Text(if (advancedDiagnostics) "收起工具入口" else "查看工具入口")
                    }
                    if (advancedDiagnostics) DiagnosticTool.entries.forEach { tool ->
                        TextButton(onClick = { diagnosticToolName = tool.name }) { Text(tool.label) }
                    }
                }
                if (advancedDiagnostics) {
                    FcmConnectionDetails(state, onOpenGmsConnection)
                    RecoveryCountsCard(state)
                    StatusCard("自动保护详情") {
                        Text(state.automaticProtection.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diagnosticTool == DiagnosticTool.RECOVERY) {
            StoppedRecoveryReceipt(state.stoppedRecovery,
                reconcileBusy = state.detecting || state.automaticProtection.enabled || state.automaticProtection.busy,
                onReconcile = onReconcileStoppedRecovery)
            if (state.stoppedRecovery.receiptOutcome == null && !state.stoppedRecovery.needsReconcile) Text("暂无恢复操作记录")
            }
            if (diagnosticTool == DiagnosticTool.DAILY) {
            StatusCard("定期巡检") {
                Text("每日巡检不含应用停止标记；请在“应用”页复检。", style = MaterialTheme.typography.bodySmall)
                when (state.dailyProtectionLoadState) {
                    HomeSourceLoadState.LOADING -> Text("正在读取巡检记录…")
                    HomeSourceLoadState.FAILED -> Text(
                        "巡检记录读取失败；下方若有旧记录，不代表本次读取成功",
                        color = MaterialTheme.colorScheme.error,
                    )
                    HomeSourceLoadState.LOADED -> Unit
                }
                state.dailyProtectionReport?.let { report ->
                    StatusRow("上次完整巡检", if (report.containsRetiredAutostartEvidence) "旧范围记录" else report.status.label())
                    StatusRow("检查时间", formatRecordTime(report.checkedAtEpochMillis))
                    Text(if (report.containsRetiredAutostartEvidence) "检测范围已更新，请重新巡检。" else report.summary, style = MaterialTheme.typography.bodySmall)
                    DailyProtectionDetails(
                        report = report,
                        selectedTargets = state.selectedTargets,
                        selectionReady = state.targetSelectionLoadState == HomeSourceLoadState.LOADED &&
                            !state.targetSelectionStoreCorrupt &&
                            state.dailyProtectionLoadState == HomeSourceLoadState.LOADED,
                        busy = state.detecting || state.selectedTargets.any {
                            it.status == TargetAutostartStatus.CHECKING
                        },
                        onCheckTarget = onCheckTarget,
                        onShowTargetSelection = onShowTargetSelection,
                    )
                } ?: if (state.dailyProtectionLoadState == HomeSourceLoadState.LOADED) {
                    Text("尚无可用巡检结果", style = MaterialTheme.typography.bodyMedium)
                } else Unit
                Button(
                    onClick = onRunDailyProtection,
                    enabled = !state.dailyProtectionSubmitting,
                ) {
                    Text(if (state.dailyProtectionSubmitting) "提交中…" else "立即巡检")
                }
                state.dailyProtectionSubmissionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "低频只读检查可能被系统延后；不会启动前台服务或自动修改系统设置。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            }
            if (diagnosticTool == DiagnosticTool.NOTIFICATIONS) {
            StatusCard("所选应用通知记录") {
                var chosenPackage by remember { mutableStateOf<String?>(null) }
                var recentOnly by remember { mutableStateOf(false) }
                var chosenKind by remember { mutableStateOf<NotificationFactKind?>(null) }
                val validPackages = state.notificationFacts.validTargetPackages
                val packageName = chosenPackage?.takeIf(validPackages::contains) ?: validPackages.firstOrNull()
                val query = packageName?.let {
                    NotificationFactsQuery(
                        packageName = it,
                        fromEpochMillis = if (recentOnly) {
                            (System.currentTimeMillis() - 24L * 60 * 60 * 1000).coerceAtLeast(1)
                        } else null,
                        kind = chosenKind,
                    )
                }
                val filteredEvents = query?.let { queryNotificationFacts(state.notificationFacts.events, it) }.orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (state.notificationFacts.enabled) "已启用" else "默认关闭")
                        Text(
                            "仅记录所选应用的包名、时间和发布/移除事件，不采集通知内容。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.notificationFacts.enabled,
                        enabled = state.notificationFacts.enabled ||
                            (!state.targetSelectionStoreCorrupt && state.notificationFacts.targetCount > 0),
                        onCheckedChange = onSetNotificationFactsEnabled,
                    )
                }
                StatusRow(
                    "系统通知使用权",
                    if (state.notificationFacts.accessGranted) "已授予" else "未授予",
                )
                StatusRow(
                    "监听服务连接",
                    if (state.notificationFacts.connected) "已连接" else "未连接",
                )
                StatusRow("有效所选应用", state.notificationFacts.targetCount.toString())
                Text(
                    "系统通知使用权覆盖所有应用；Guardian 只保存所选应用的包名、时间和事件，不保存通知内容。可在系统页面撤销权限。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "启用监听可能让 Guardian 在后台驻留，耗电影响尚未实测；不需要时可关闭记录并撤销权限。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.notificationFacts.enabled && !state.notificationFacts.accessGranted) {
                    Text(
                        "记录已开启，但系统尚未授予通知使用权。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (state.notificationFacts.enabled && !state.notificationFacts.connected) {
                    Text(
                        "系统已授权，但监听服务当前未连接；授权状态不等于服务正在工作。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.notificationFacts.targetCount < 1) {
                    Text(
                        "请先在“关注应用”中选择至少一个身份有效的应用，才能开启记录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenNotificationListenerSettings) {
                        Text("系统通知使用权")
                    }
                    TextButton(
                        onClick = onClearNotificationFactsHistory,
                        enabled = state.notificationFacts.events.isNotEmpty() ||
                            state.notificationFacts.historyCorrupt ||
                            state.notificationFacts.droppedEvents > 0,
                    ) {
                        Text("清除本地记录")
                    }
                }
                state.notificationFactsMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.notificationFactsExportFeedback?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (state.notificationFacts.stopPersistenceUncertain) {
                    Text(
                        "停止设置未可靠保存。请在系统通知使用权页面撤销 Guardian 权限，避免应用重启后恢复记录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.notificationFacts.historyCorrupt) {
                    Text(
                        "本地通知历史损坏或无法识别；未把损坏数据展示为有效事件。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.notificationFacts.droppedEvents > 0) {
                    Text(
                        "有 ${state.notificationFacts.droppedEvents} 条事件未能保存；以下记录不完整。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (validPackages.isNotEmpty()) {
                    Text("查看应用", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        validPackages.forEach { candidate ->
                            FilterChip(
                                selected = packageName == candidate,
                                onClick = { chosenPackage = candidate },
                                label = { Text(candidate) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !recentOnly, onClick = { recentOnly = false }, label = { Text("全部时间") })
                        FilterChip(selected = recentOnly, onClick = { recentOnly = true }, label = { Text("近24小时") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = chosenKind == null, onClick = { chosenKind = null }, label = { Text("全部事件") })
                        FilterChip(selected = chosenKind == NotificationFactKind.POSTED_OR_UPDATED,
                            onClick = { chosenKind = NotificationFactKind.POSTED_OR_UPDATED }, label = { Text("发布/更新") })
                        FilterChip(selected = chosenKind == NotificationFactKind.REMOVED,
                            onClick = { chosenKind = NotificationFactKind.REMOVED }, label = { Text("移除") })
                    }
                    Text("当前筛选 ${filteredEvents.size} 条；本地最多保存 200 条", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { query?.let(onExportNotificationFacts) }, enabled = query != null && !state.notificationFacts.historyCorrupt) {
                        Text("导出筛选结果 JSON")
                    }
                }
                if (filteredEvents.isEmpty()) {
                    Text("当前筛选无通知事件", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("筛选历史（系统回调已确认）", fontWeight = FontWeight.SemiBold)
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        filteredEvents.forEach { event ->
                            Text(
                                "${formatRecordTime(event.observedAtEpochMillis)} · " +
                                    "${event.packageName} · ${event.kind.label()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    "这些记录只确认系统观察到通知发布、更新或移除；不能证明通知来自 FCM，也不能证明应用被推送拉起。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "历史按包名归类，未记录每条事件所属的安装身份；重新安装前的同包名记录可能仍在列表中。导出会保存点击时的筛选快照。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            }
            if (diagnosticTool == DiagnosticTool.MONITOR) {
            DeepMonitoringCard(state, onStartDeepMonitoring, onStopDeepMonitoring, onClearDeepMonitoring, onExportMonitorSession)
            }
            if (diagnosticTool == DiagnosticTool.TIMELINE) {
            RetainedTimelineCard(state)
            }
            if (diagnosticTool == DiagnosticTool.SELF_TEST) {
            StatusCard("Guardian 推送自测") {
                StatusRow("客户端配置", state.fcm.configuration.label())
                StatusRow("注册", state.fcm.registration.label())
                Text(state.fcm.registrationDetail, style = MaterialTheme.typography.bodySmall)
                if (state.fcm.configuration == FcmConfigurationStatus.MISSING) {
                    Text(
                        "尚未加入本应用包名对应的 google-services.json。当前构建可正常使用其他功能，FCM 注册与投递未测试。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onRequestFcmToken,
                    enabled = state.fcm.configuration == FcmConfigurationStatus.CONFIGURED_NOT_TESTED &&
                        state.fcm.registration != RegistrationStatus.REQUESTING,
                ) {
                    Text(if (state.fcm.registration == RegistrationStatus.REQUESTING) "请求中…" else "主动获取测试令牌")
                }
                state.fcm.token?.let { token ->
                    Text(
                        if (state.fcm.tokenVisible) token.value else "令牌已隐藏（${token.length} 字符）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleFcmTokenVisibility) {
                            Text(if (state.fcm.tokenVisible) "隐藏" else "显示")
                        }
                        OutlinedButton(onClick = onCopyFcmToken) { Text("敏感复制") }
                        TextButton(onClick = onClearFcmToken) { Text("清除显示") }
                    }
                    state.fcm.clipboardDetail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                StatusRow("通知权限", state.fcm.notificationPermission.label())
                if (state.fcm.notificationPermission == NotificationPermissionStatus.REQUIRED) {
                    OutlinedButton(onClick = onRequestNotificationPermission) {
                        Text("请求通知权限")
                    }
                }
                if (state.fcm.evidenceCorrupt) {
                    Text(
                        "FCM 本地证据文件损坏或写入中断；已停止覆盖，需人工保留并检查。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.fcm.latestReceipt?.let { receipt ->
                    Spacer(Modifier.height(4.dp))
                    Text("最近 Guardian 回调", fontWeight = FontWeight.SemiBold)
                    StatusRow("testId", receipt.testId)
                    StatusRow("收到时间", formatRecordTime(receipt.receivedAtEpochMillis))
                    StatusRow("消息优先级", receipt.messagePriority.label())
                    StatusRow("表观墙钟差", "${receipt.apparentWallClockDeltaMillis} ms（可能含时钟偏差）")
                    StatusRow("本机处理", receipt.handlerDurationMillis?.let { "$it ms" } ?: "通知结果尚未落盘")
                    StatusRow("回调时进程已运行", receipt.processAgeAtCallbackMillis?.let { "$it ms" } ?: "未记录")
                    Text("进程运行时长仅辅助判断接收时的状态，不能单独证明由推送启动。")
                    StatusRow("通知提交", receipt.notificationOutcome.label())
                    StatusRow("屏幕交互", receipt.deviceInteractive.label())
                    StatusRow("Device Idle", receipt.deviceIdleMode.label())
                    StatusRow("粗粒度网络", receipt.networkAvailability.label())
                } ?: Text("尚无受控 FCM 回调证据", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "本卡仅证明 Guardian 自身回调与本机通知提交请求。notify() 返回不代表系统界面实际可见；墙钟差也不是精确网络延迟。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            }
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
                    Text("建议仅选择需要重点检查的应用，避免选择过多。")
                    Text("关注选择不授权自动恢复；保护需要另行确认。", style = MaterialTheme.typography.bodySmall)
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
                Text("将盾牌“推送保护”放到控制中心常用区域。下拉到按钮可见时，Guardian 会尝试恢复已开启的保护，无需点击。")
                Text("先连接特权服务并开启自动保护。亮起表示检测运行中；点击按钮可打开 Guardian。手动关闭的保护不会自动开启。")
                Text("这是辅助恢复，不保证永久常驻。权限未就绪或系统限制时，请打开应用处理。")
                Text("也可手动添加：控制中心 → 编辑 → 推送保护。")
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
private fun HomeOverview(state: HomeUiState, onGms: () -> Unit, onApps: () -> Unit,
    onSettings: () -> Unit, onDiagnostics: () -> Unit, onShizukuAction: () -> Unit,
    onEnableMaster: () -> Unit, onDisableMaster: () -> Unit,
    onEnableFcm: () -> Unit, onDisableFcm: () -> Unit) {
    val automatic = state.automaticProtection
    val shizuku = shizukuWorkModePresentation(state.shizuku)
    StatusCard("Shizuku", attention = state.shizuku.status != ShizukuStatus.READY_SHELL) {
        Text(shizuku.statusLabel, style = MaterialTheme.typography.bodyLarge)
        Text(when (state.shizuku.status) {
            ShizukuStatus.PERMISSION_REQUIRED -> if (state.shizuku.authorizationBlocked)
                "请在 Shizuku 中检查 Guardian 的授权。" else "授权 Guardian 后，可检查并恢复应用的停止限制。"
            else -> shizuku.summary
        }, style = MaterialTheme.typography.bodyMedium)
        shizuku.actionLabel?.let { label -> Button(onClick = onShizukuAction) { Text(label) } }
    }
    StatusCard("自动保护") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(automaticProtectionStatus(automatic), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            AutomaticProtectionToggle(automatic, canEnableAutomaticProtection(state), onEnableMaster, onDisableMaster)
        }
        Text(if (automatic.configurationAvailable) "已选择 ${automatic.configuredIdentities.size} 个应用；关闭总开关会保留选择。"
            else "应用选择记录暂不可用；保留上次已知选择。", style = MaterialTheme.typography.bodySmall)
        if (automatic.revocationSaveFailed) TextButton(onClick = onDisableMaster) { Text("重试保存关闭状态") }
        Text("每轮检查后等待约 30 秒；休眠可能延后，不保证通知送达。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onApps) { Text("管理应用（${automatic.configuredIdentities.size}）") }
    }
    StatusCard("FCM 连接与保活") {
        Text("FCM 连接状态", style = MaterialTheme.typography.titleSmall)
        Text(fcmProtectionObservationText(state.fcmProtection, android.os.SystemClock.elapsedRealtime()), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onGms) { Text("查看连接状态") }
        FcmProtectionControls(state.fcmProtection,
            automatic.enabled && state.shizuku.status == ShizukuStatus.READY_SHELL,
            onEnableFcm, onDisableFcm)
    }
    StatusCard("上次检查") {
        Text(diagnosticSummary(state), style = MaterialTheme.typography.bodyMedium,
            color = if (diagnosticIssues(state).isNotEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface)
        val lastCheck = state.dailyProtectionReport?.checkedAtEpochMillis
        Text(lastCheck?.let { "已保存检查：${formatRecordTime(it)}（历史记录）" } ?: "尚无已保存检查记录",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onDiagnostics) { Text("查看技术详情") }
    }
    StatusCard("诊断信息") {
        Text("用于故障排查和技术支持的记录。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("打开诊断信息") }
    }
    StatusCard("设置") {
        Text("Shizuku 连接、后台运行与通知设置。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onSettings) { Text("连接与后台设置") }
    }
}

@Composable
private fun gmsConnectionClock(state: HomeUiState): Long {
    var elapsed by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.gmsConnection.observation) {
        elapsed = android.os.SystemClock.elapsedRealtime()
        val sample = state.gmsConnection.observation as? io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample
            ?: return@LaunchedEffect
        while (sample.isFresh(elapsed)) {
            kotlinx.coroutines.delay(1_000)
            elapsed = android.os.SystemClock.elapsedRealtime()
        }
    }
    return elapsed
}

@Composable
private fun FcmConnectionDetails(state: HomeUiState, onGms: () -> Unit) {
    StatusCard("FCM 连接详情") {
        Text(gmsConnectionSummary(state.gmsConnection, gmsConnectionClock(state)), style = MaterialTheme.typography.bodySmall)
        (state.gmsConnection.observation as? io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample)?.let {
            Text("观察时间：${formatRecordTime(it.observedAtMillis)}", style = MaterialTheme.typography.bodySmall)
        }
        Text("自动重连未接入，未开启；TCP 迹象不证明通知送达。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onGms) { Text("单次只读观察") }
    }
}

@Composable
private fun RecoveryCountsCard(state: HomeUiState) {
    StatusCard("解除停止限制") {
        val counts = state.recoveryCounts
        Text("当前关注应用的已保存确认次数", style = MaterialTheme.typography.bodySmall)
        if (counts.available) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(counts.byIdentity.values.sumOf { it.today }.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text("今日确认解除", style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(counts.byIdentity.values.sumOf { it.week }.toString(), style = MaterialTheme.typography.headlineMedium)
                    Text("本周确认解除", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(counts.detail, style = MaterialTheme.typography.bodySmall)
        if (counts.available) Text(recoveryCountsSnapshotLabel(counts.calculatedAtMillis, counts.zoneId),
            style = MaterialTheme.typography.bodySmall)
        Text("仅计实际写入并读回确认的解除；不是通知送达次数。", style = MaterialTheme.typography.bodySmall)
    }
    state.automaticProtection.lastOutcome?.let { outcome ->
        Text(when (outcome) {
            "FLAG_CLEARED" -> "最新操作：停止限制已解除"
            "NO_WRITE_NEEDED" -> "最新操作：无需恢复"
            "STILL_STOPPED" -> "最新操作：限制仍存在"
            "UNCONFIRMED" -> "最新操作：恢复结果待确认"
            else -> "最新操作：未完成，请查看应用详情"
        }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun automaticProtectionStatus(state: io.github.originosfcmguardian.autoprotection.AutomaticProtectionState): String = when {
    state.waitingForReconciliation -> "等待只读复检"
    state.busy -> "启动或恢复中"
    state.waitingForPrivilege -> "等待特权；尚未执行保护"
    state.enabled -> "停止限制检测已开启"
    state.revocationSaveFailed -> "关闭状态未可靠保存"
    state.savedAuthorization -> "等待恢复检测"
    else -> "检测未开启"
}

private fun recoveryCountsSnapshotLabel(calculatedAtMillis: Long?, zoneId: String?): String {
    val timestamp = if (calculatedAtMillis != null && zoneId != null) runCatching {
        java.time.Instant.ofEpochMilli(calculatedAtMillis).atZone(java.time.ZoneId.of(zoneId))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrNull() else null
    return if (timestamp != null) "统计截至 $timestamp（$zoneId）；回到应用刷新"
        else "统计时间或时区未知；回到应用刷新"
}

@Composable
private fun ProtectionStopBar(state: HomeUiState, onStopProtection: () -> Unit, onStopMonitor: () -> Unit,
    onStopNotificationRecording: () -> Unit, onOpenNotificationSettings: () -> Unit) {
    if (state.deepMonitoring.busy) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.deepMonitoring.phase == MonitorRunPhase.STOPPING) "监控正在停止…" else "深度监控活动中",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onStopMonitor, enabled = state.deepMonitoring.phase != MonitorRunPhase.STOPPING) { Text("停止监控") }
        }
    }
    if (state.notificationFacts.enabled || state.notificationFacts.stopPersistenceUncertain) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.notificationFacts.stopPersistenceUncertain) "记录停止未可靠保存" else "通知记录已开启",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onStopNotificationRecording) {
                Text(if (state.notificationFacts.stopPersistenceUncertain) "重试停止记录" else "停止通知记录")
            }
            if (state.notificationFacts.stopPersistenceUncertain) TextButton(onClick = onOpenNotificationSettings) { Text("撤销使用权") }
        }
    }
}

@Composable
private fun NavigationGlyph(home: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
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
private fun StatusCard(title: String, attention: Boolean = false, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (attention) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

private fun ProtectionCheckStatus.label(): String = when (this) {
    ProtectionCheckStatus.HEALTHY_READ_ONLY -> "已选应用安装身份未变化"
    ProtectionCheckStatus.NEEDS_USER_ACTION -> "需要手动处理"
    ProtectionCheckStatus.UNKNOWN -> "结果不完整"
    ProtectionCheckStatus.BUILD_CHANGED -> "系统构建已变化"
}

private fun FcmConfigurationStatus.label(): String = when (this) {
    FcmConfigurationStatus.MISSING -> "未配置 / 未测试"
    FcmConfigurationStatus.CONFIGURED_NOT_TESTED -> "已配置 / 未测试"
}

private fun RegistrationStatus.label(): String = when (this) {
    RegistrationStatus.NOT_REQUESTED -> "未请求"
    RegistrationStatus.REQUESTING -> "请求中"
    RegistrationStatus.TOKEN_READY -> "令牌已获取"
    RegistrationStatus.CONFIG_MISSING -> "缺少配置"
    RegistrationStatus.TIMED_OUT -> "等待超时（结果未知）"
    RegistrationStatus.SDK_IO_FAILURE -> "注册失败（原因待查）"
    RegistrationStatus.FAILED -> "失败（已脱敏）"
}

private fun NotificationPermissionStatus.label(): String = when (this) {
    NotificationPermissionStatus.GRANTED -> "已授权"
    NotificationPermissionStatus.REQUIRED -> "未授权"
}

private fun NotificationFactKind.label(): String = when (this) {
    NotificationFactKind.POSTED_OR_UPDATED -> "发布或更新"
    NotificationFactKind.REMOVED -> "移除"
}

private fun NotificationOutcome.label(): String = when (this) {
    NotificationOutcome.RECEIPT_STORED -> "回调已落盘，通知尚未尝试"
    NotificationOutcome.PERMISSION_DENIED -> "权限未授予"
    NotificationOutcome.APP_NOTIFICATIONS_BLOCKED -> "应用通知被系统关闭"
    NotificationOutcome.CHANNEL_BLOCKED -> "测试通知渠道被关闭"
    NotificationOutcome.POST_REQUESTED -> "已请求系统提交（不等于实际显示）"
    NotificationOutcome.POST_FAILED -> "提交失败"
}

private fun MessagePriority.label(): String = when (this) {
    MessagePriority.HIGH -> "HIGH"
    MessagePriority.NORMAL -> "NORMAL"
    MessagePriority.UNKNOWN -> "UNKNOWN"
}

private fun ObservedBoolean.label(): String = when (this) {
    ObservedBoolean.YES -> "是"
    ObservedBoolean.NO -> "否"
    ObservedBoolean.UNKNOWN -> "未知"
}

private fun NetworkAvailability.label(): String = when (this) {
    NetworkAvailability.AVAILABLE -> "可用网络存在"
    NetworkAvailability.UNAVAILABLE -> "未观察到可用网络"
    NetworkAvailability.UNKNOWN -> "未知"
}

internal fun formatRecordTime(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(epochMillis))
