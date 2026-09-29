package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.AppOpState
import io.github.originosfcmguardian.core.AppOpsWriteCapability
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.DozeSource
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.PlanDisposition
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.data.journal.OriginalRecordStatus
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.autostart.AutostartUiStatus
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
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
    onDetect: () -> Unit,
    onShizukuAction: () -> Unit,
    onCheckAutostart: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onShowTargetSelection: () -> Unit,
    onDismissTargetSelection: () -> Unit,
    onSetTargetSelected: (String, Boolean) -> Unit,
    onCheckTarget: (String) -> Unit,
    onOpenTargetSettings: (String) -> Unit,
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
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("OriginOS FCM Guardian") }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设备与推送诊断", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "当前提供系统状态检查、所选应用管理、Guardian 自测与通知事实记录；各卡片分别说明证据边界。",
                style = MaterialTheme.typography.bodyMedium,
            )

            StatusCard("Google Play services") {
                StatusRow("普通 API", state.gms?.summary ?: "正在读取")
                state.report?.let { report ->
                    StatusRow("shell 复核", report.gms.status.label())
                }
            }

            StatusCard("Shizuku") {
                StatusRow("状态", state.shizuku.status.label())
                Text(state.shizuku.detail, style = MaterialTheme.typography.bodySmall)
                if (state.shizuku.status in setOf(
                        ShizukuStatus.NOT_INSTALLED,
                        ShizukuStatus.NOT_RUNNING,
                        ShizukuStatus.PERMISSION_REQUIRED,
                    )
                ) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onShizukuAction) {
                        Text(state.shizuku.status.actionLabel())
                    }
                }
            }

            StatusCard("检测") {
                Text(state.detectionMessage, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDetect,
                    enabled = !state.detecting && state.autostart.status != AutostartUiStatus.CHECKING,
                ) {
                    Text(if (state.detecting) "检测中…" else "重新检测")
                }
            }

            StatusCard("Guardian 自启动") {
                StatusRow("状态", state.autostart.status.label())
                Text(state.autostart.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "这里只读 Guardian 自身状态。系统字段读数不能证明 FCM 投递成功。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCheckAutostart,
                        enabled = !state.detecting && state.autostart.status != AutostartUiStatus.CHECKING,
                    ) {
                        Text(if (state.autostart.status == AutostartUiStatus.CHECKING) "检查中…" else "手动检查")
                    }
                    OutlinedButton(
                        onClick = onOpenAutostartSettings,
                        enabled = !state.detecting && state.autostart.status != AutostartUiStatus.CHECKING,
                    ) {
                        Text("打开自启动设置")
                    }
                }
            }

            StatusCard("即时通知应用") {
                Text(
                    "选择最多 10 个需要即时通知的应用。Guardian 只读取其系统自启动权限，不会自动修改。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onShowTargetSelection) {
                    Text("选择应用（${state.selectedTargets.size}/10）")
                }
                if (state.targetSelectionStoreCorrupt) {
                    Text(
                        "应用选择记录损坏，Guardian 已停止使用且不会覆盖该记录。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onClearCorruptTargetSelection) {
                        Text("清除损坏记录")
                    }
                }
                if (state.selectedTargets.isEmpty()) {
                    Text("尚未选择应用", style = MaterialTheme.typography.bodyMedium)
                }
                val targetBusy = state.selectedTargets.any { it.status == TargetAutostartStatus.CHECKING }
                state.selectedTargets.forEach { target ->
                    Spacer(Modifier.height(6.dp))
                    Text(target.label, fontWeight = FontWeight.SemiBold)
                    StatusRow("自启动", target.status.label())
                    Text(target.detail, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onCheckTarget(target.identity.packageName) },
                            enabled = !targetBusy && !state.detecting,
                        ) {
                            Text("复检")
                        }
                        OutlinedButton(
                            onClick = { onOpenTargetSettings(target.identity.packageName) },
                            enabled = !targetBusy && !state.detecting,
                        ) {
                            Text("系统设置")
                        }
                        TextButton(onClick = { onSetTargetSelected(target.identity.packageName, false) }) {
                            Text("移除")
                        }
                    }
                }
                Text(
                    "自启动权限只是系统条件之一，不能证明 FCM 长连接或消息投递成功。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            StatusCard("每日保护") {
                state.dailyProtectionReport?.let { report ->
                    StatusRow("最近结果", report.status.label())
                    StatusRow("检查时间", formatRecordTime(report.checkedAtEpochMillis))
                    Text(report.summary, style = MaterialTheme.typography.bodySmall)
                } ?: Text("尚无巡检结果", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = onRunDailyProtection,
                    enabled = !state.dailyProtectionQueued,
                ) {
                    Text(if (state.dailyProtectionQueued) "已提交…" else "立即巡检")
                }
                Text(
                    "低频只读检查可能被系统延后；不会启动前台服务或自动修改系统设置。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

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
                        "请先在“即时通知应用”中选择至少一个身份有效的应用，才能开启记录。",
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

            DeepMonitoringCard(state, onStartDeepMonitoring, onStopDeepMonitoring, onClearDeepMonitoring, onExportMonitorSession)

            RetainedTimelineCard(state)

            StatusCard("原始状态记录") {
                StatusRow("状态", state.originalRecord.status.label())
                state.originalRecord.capturedAtEpochMillis?.let { capturedAt ->
                    StatusRow("记录时间", formatRecordTime(capturedAt))
                }
                state.originalRecord.matchesCurrentBuild?.let { matches ->
                    StatusRow("系统版本", if (matches) "与当前系统一致" else "来自旧的或不同的系统")
                }
                Text(state.originalRecord.detail, style = MaterialTheme.typography.bodySmall)
            }

            state.report?.let { DetectionDetails(it) }

            StatusCard("FCM 实际健康度") {
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

            StatusCard("修复与恢复") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, enabled = false) { Text("一键修复") }
                    OutlinedButton(onClick = {}, enabled = false) { Text("恢复原始配置") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "修复与恢复仍在验证中，当前版本仅支持检测。",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.plan?.let { plan ->
                    Text(
                        when {
                            plan.disposition == PlanDisposition.NO_WRITE ->
                                "当前检测未发现需要处理的异常；当前没有可执行的修复操作。"
                            plan.candidates.isEmpty() ->
                                "部分状态仍待验证；当前没有可执行的修复操作。"
                            else ->
                                "发现 ${plan.candidates.size} 项待验证状态；当前没有可执行的修复操作。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (state.targetSelection.visible) {
        val selectedCount = state.targetSelection.candidates.count { it.selected }
        AlertDialog(
            onDismissRequest = onDismissTargetSelection,
            title = { Text("选择即时通知应用") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.targetSelection.loading) Text("正在读取可启动应用…")
                    state.targetSelection.candidates.forEach { candidate ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = candidate.selected,
                                enabled = candidate.selected || selectedCount < 10,
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
private fun DetectionDetails(report: DetectionReport) {
    StatusCard("特权只读结果") {
        StatusRow("执行身份", report.privilegedAccess.name)
        StatusRow("Android 用户", report.currentUser?.toString() ?: "未知")
        StatusRow("Build 指纹", report.buildFingerprint ?: "读取失败")
        when (report.appOpsWriteAdvisory.capability) {
            AppOpsWriteCapability.SHELL_GUARD_WHILE_SHIZUKU_RUNNING -> {
                Text("已知系统限制（历史验证，非本次写入测试）", fontWeight = FontWeight.SemiBold)
                Text(
                    "该系统版本曾验证到：Shizuku 运行时存在 shell AppOps 写入拦截。" +
                        "手机端写入尚未实测，当前不提供此项自动修复；等效手动设置仍待验证。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AppOpsWriteCapability.UNKNOWN ->
                StatusRow("AppOps 写入能力", "未知；本次检测未测试写入")
        }
        StatusRow("GMS", report.gms.status.label())
        StatusRow("GMS 启用", report.gms.enabled.label())

        AppOp.entries.forEach { operation ->
            val check = report.appOps[operation]
            StatusRow(
                operation.shellName,
                check?.let {
                    "${it.status.label()} · 包 ${it.packageState.modeLabel()} · UID ${it.uidState.modeLabel()}"
                } ?: "无完整证据",
            )
        }

        val sources = report.doze.sources.sortedBy(DozeSource::ordinal)
            .joinToString { it.wireName }
            .ifBlank { "未报告 GMS 条目" }
        StatusRow("Doze whitelist", "${report.doze.status.label()} · $sources")
        StatusRow("Vivo PEM 包", report.pem.packagePresence.label())
        StatusRow("Vivo PEM", report.pem.listState.label())
        Text("PEM 的 OriginOS 7 语义仍未验证。", style = MaterialTheme.typography.bodySmall)

        if (report.problems.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("未决问题", fontWeight = FontWeight.SemiBold)
            report.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun StatusCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

private fun EvidenceStatus.label(): String = when (this) {
    EvidenceStatus.CONFORMING -> "读数符合预期"
    EvidenceStatus.ANOMALOUS -> "读数异常"
    EvidenceStatus.UNKNOWN -> "未知"
}

private fun Presence.label(): String = when (this) {
    Presence.PRESENT -> "存在"
    Presence.ABSENT -> "不存在"
    Presence.UNKNOWN -> "未知"
}

private fun EnabledState.label(): String = when (this) {
    EnabledState.ENABLED -> "已启用"
    EnabledState.DISABLED -> "已停用"
    EnabledState.UNKNOWN -> "未知"
}

private fun PemListState.label(): String = when (this) {
    PemListState.VALUE_REPORTED -> "读取到值（语义未验证）"
    PemListState.NULL_OR_MISSING_AMBIGUOUS -> "返回 null（缺失/空值未区分）"
    PemListState.UNKNOWN -> "未知"
}

private fun AppOpState.modeLabel(): String =
    (reportedMode ?: reportedDefault)?.label() ?: "未知"

private fun AppOpMode.label(): String = name.lowercase()

private fun ShizukuStatus.label(): String = when (this) {
    ShizukuStatus.NOT_INSTALLED -> "未安装"
    ShizukuStatus.NOT_RUNNING -> "未启动"
    ShizukuStatus.PERMISSION_REQUIRED -> "待授权"
    ShizukuStatus.READY_SHELL -> "shell 只读桥已连接（UID 2000）"
    ShizukuStatus.READY_ROOT -> "READY_ROOT（不可用）"
    ShizukuStatus.ERROR -> "错误"
}

private fun ShizukuStatus.actionLabel(): String = when (this) {
    ShizukuStatus.NOT_INSTALLED -> "获取 Shizuku"
    ShizukuStatus.NOT_RUNNING -> "打开 Shizuku"
    ShizukuStatus.PERMISSION_REQUIRED -> "请求授权"
    else -> "刷新状态"
}

private fun AutostartUiStatus.label(): String = when (this) {
    AutostartUiStatus.NOT_CHECKED -> "尚未检查"
    AutostartUiStatus.CHECKING -> "正在检查"
    AutostartUiStatus.ALLOWED -> "已允许"
    AutostartUiStatus.NEEDS_USER_ACTION -> "未允许"
    AutostartUiStatus.UNKNOWN -> "未知"
    AutostartUiStatus.UNAVAILABLE -> "暂不可读取"
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
    ProtectionCheckStatus.HEALTHY_READ_ONLY -> "关键读数符合预期"
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

private fun OriginalRecordStatus.label(): String = when (this) {
    OriginalRecordStatus.LOADING -> "正在检查"
    OriginalRecordStatus.NOT_SAVED -> "尚未保存"
    OriginalRecordStatus.SAVED -> "已保存"
    OriginalRecordStatus.INCOMPLETE -> "证据不完整"
    OriginalRecordStatus.DAMAGED -> "记录损坏或无法识别"
}

private fun formatRecordTime(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(epochMillis))
