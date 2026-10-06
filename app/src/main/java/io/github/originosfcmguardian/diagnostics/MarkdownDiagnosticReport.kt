package io.github.originosfcmguardian.diagnostics

/** Pure presentation of the same redacted report used by JSON; no reads or new evidence. */
fun encodeCombinedDiagnosticMarkdown(report: CombinedDiagnosticReport): ByteArray {
    val markdown = buildString {
        append("# Guardian 综合诊断报告\n\n")
        row("报告模型", DIAGNOSTIC_REPORT_SCHEMA)
        row("导出快照时间（Unix 毫秒）", report.capturedAtEpochMillis)
        append("\n以下为点击导出时应用内已知状态的摘要。未知、未加载和损坏不代表健康或空记录。\n")

        section("环境")
        row("Android SDK", report.environment.sdkInt)
        row("Android 版本", report.environment.androidRelease)
        row("厂商", report.environment.manufacturer)
        row("设备型号", report.environment.model)
        row("Guardian 版本名称", report.environment.guardianVersionName)
        row("Guardian 版本代码", report.environment.guardianVersionCode)

        section("GMS")
        row("已安装", report.gmsInstalled)
        row("已启用", report.gmsEnabled)
        row("版本名称", report.gmsVersionName)
        row("版本代码", report.gmsVersionCode)

        section("Shizuku 与特权检测")
        statusRow("Shizuku 状态", report.shizukuStatus)
        statusRow("特权检测状态", report.privilegedDetectionStatus)
        if (report.appOps.isEmpty()) row("AppOps", "空列表（0 项）")
        report.appOps.toSortedMap().forEach { (operation, status) ->
            statusRow("AppOps 操作（$operation）", status)
        }
        statusRow("Doze 状态", report.dozeStatus)
        if (report.dozeSources.isEmpty()) row("Doze 来源", "空列表（0 项），请结合 Doze 状态")
        report.dozeSources.forEachIndexed { index, source -> row("Doze 来源 ${index + 1}", source) }
        statusRow("PEM 状态", report.pemStatus)

        section("目标应用")
        when (val targets = report.targets) {
            null -> row("目标应用", null)
            else -> {
                row("目标应用数量", targets.size)
                targets.forEachIndexed { index, target ->
                    append("\n### 目标应用 ").append(index + 1).append("\n\n")
                    row("包名", target.packageName)
                    row("当前显示名称", target.currentLabel)
                }
            }
        }

        section("FCM 本应用证据")
        statusRow("配置状态", report.fcmConfiguration)
        statusRow("注册状态", report.fcmRegistration)
        statusRow("通知权限", report.notificationPermission)
        val receipt = report.latestFcmReceipt
        if (receipt == null) {
            row("最近回调", null)
        } else {
            row("最近回调时间（Unix 毫秒）", receipt.receivedAtEpochMillis)
            statusRow("消息优先级", receipt.messagePriority)
            statusRow("通知处理结果", receipt.notificationOutcome)
            statusRow("设备可交互", receipt.deviceInteractive)
            statusRow("设备处于空闲模式", receipt.deviceIdleMode)
            statusRow("网络可用性", receipt.networkAvailability)
            row("处理耗时（毫秒）", receipt.handlerDurationMillis)
            row("回调时进程年龄（毫秒）", receipt.processAgeAtCallbackMillis)
        }

        section("通知观察摘要")
        val facts = report.notificationFacts
        if (facts == null) {
            row("通知观察", null)
        } else {
            row("观察已启用", facts.enabled)
            row("访问权限已授予", facts.accessGranted)
            row("监听器已连接", facts.connected)
            row("保留事件数", facts.retainedCount)
            row("发布或更新数", facts.postedOrUpdatedCount)
            row("移除数", facts.removedCount)
            row("最近观察时间（Unix 毫秒）", facts.latestObservedAtEpochMillis)
            row("丢弃事件数", facts.droppedEvents)
            row("停止后的持久化不确定", facts.stopPersistenceUncertain)
        }

        section("最近深度监测摘要")
        val monitor = report.monitor
        if (monitor == null) {
            row("最近监测", null)
        } else {
            row("包名", monitor.packageName)
            row("开始时间（Unix 毫秒）", monitor.startedAtEpochMillis)
            row("截止时间（Unix 毫秒）", monitor.deadlineEpochMillis)
            statusRow("监测状态", monitor.status)
            row("保留事件数", monitor.retainedEventCount)
            row("进程启动数", monitor.processStartCount)
            row("UID 活跃事件数", monitor.uidActiveCount)
            row("UID 空闲事件数", monitor.uidIdleCount)
            row("UID 运行事件数", monitor.uidRunningCount)
            row("UID 停止事件数", monitor.uidStoppedCount)
            row("采样轮数", monitor.pollCount)
        }

        section("日常只读检查")
        statusRow("检查状态", report.dailyProtectionStatus)
        row("检查时间（Unix 毫秒）", report.dailyProtectionCheckedAtEpochMillis)

        section("停止标志恢复摘要")
        val recovery = report.stoppedRecovery
        row("恢复来源已加载", recovery.loaded)
        row("恢复存储阻断", recovery.storageBlocked)
        row("存在待核对结果", recovery.needsReconcile)
        row("应用内恢复操作忙碌", recovery.busy)
        statusRow("应用内恢复界面阶段", recovery.phase.name)
        val record = recovery.latestRecord
        if (record == null) {
            row("最近历史恢复记录", null)
        } else {
            row("历史记录包名", record.packageName)
            row("记录创建时间（Unix 毫秒，非完成时间）", record.createdAtEpochMillis)
            statusRow("历史记录来源", record.source.name)
            statusRow("历史记录阶段", record.stage.name)
            statusRow("历史记录结果", record.outcome?.name)
        }
        append("\n历史记录不能证明当前安装状态或通知送达；RECONCILED 表示后续只读观察，不证明原命令成功。\n")

        section("自动保护应用内快照")
        val automatic = report.automaticProtection
        statusRow("证据范围", automatic.evidenceScope.name)
        row("界面启用标志", automatic.enabled)
        row("界面忙碌标志", automatic.busy)
        row("界面保存授权标志", automatic.savedAuthorization)
        row("关闭授权保存失败", automatic.revocationSaveFailed)
        row("等待特权就绪", automatic.waitingForPrivilege)
        row("等待未决结果核对", automatic.waitingForReconciliation)
        row("最近检查时间（Unix 毫秒，独立采样）", automatic.lastCheckedAtEpochMillis)
        statusRow("保留结果（无对应结果时间）", automatic.lastOutcome?.name)
        append("\n这些标志仅是应用内显示状态，未重新验证持久授权或特权。最近检查时间与保留结果独立，不能配成同一次操作。\n")

        section("来源可用性")
        if (report.sourceAvailability.isEmpty()) row("来源列表", "空列表（0 项），来源状态未知")
        report.sourceAvailability.toSortedMap().forEach { (source, availability) ->
            statusRow(sourceLabel(source), availability.name)
        }

        section("仍未知的关系")
        statusRow("FCM 到通知的因果关系（fcmToNotificationCausality）", "UNKNOWN")
        statusRow("应用唤起原因（appWakeCause）", "UNKNOWN")
        statusRow("持续 FCM 连接（continuousFcmConnection）", "UNKNOWN")
        statusRow("通知与安装身份连续性（notificationInstallationContinuity）", "UNKNOWN")

        section("局限")
        append("报告仅复用已有摘要；未导出原始事件。时间接近不能证明 FCM、唤起或通知的因果关系。\n")
        append("导出时间与各来源的采样时间不同，各字段可能更旧；导出不触发新的特权读取，也不确认之后的状态变化。\n\n")
        DIAGNOSTIC_REPORT_LIMITATIONS.forEach { append("- ").append(escapeDiagnosticMarkdown(it)).append('\n') }
    }
    val bytes = markdown.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_REPORT_BYTES) { "Combined diagnostic report exceeds size limit" }
    return bytes
}

private fun StringBuilder.section(title: String) {
    append("\n## ").append(title).append("\n\n")
}

private fun StringBuilder.row(label: String, value: Any?) {
    val text = when (value) {
        null -> "未知或不可用（null），请结合来源状态"
        true -> "是（true）"
        false -> "否（false）"
        else -> value.toString()
    }
    append("- ").append(escapeDiagnosticMarkdown(label)).append("：")
        .append(escapeDiagnosticMarkdown(text)).append('\n')
}

private fun StringBuilder.statusRow(label: String, value: String?) {
    row(label, value?.let { "${statusLabel(it)}（$it）" })
}

private fun sourceLabel(source: String): String = when (source) {
    "normalGms" -> "常规 GMS 信息（normalGms）"
    "privilegedDetection" -> "特权检测（privilegedDetection）"
    "targetSelection" -> "目标应用选择（targetSelection）"
    "fcmEvidence" -> "FCM 回调证据（fcmEvidence）"
    "notificationFacts" -> "通知观察（notificationFacts）"
    "recentMonitor" -> "最近深度监测（recentMonitor）"
    "dailyProtection" -> "日常只读检查（dailyProtection）"
    "stoppedRecovery" -> "停止标志恢复（stoppedRecovery）"
    "automaticProtection" -> "自动保护应用内快照（automaticProtection）"
    else -> "来源（$source）"
}

private fun statusLabel(status: String): String = when (status) {
    "UNKNOWN" -> "未知"
    "LOADING" -> "加载中"
    "AVAILABLE" -> "可用"
    "EMPTY" -> "空记录"
    "PARTIAL" -> "部分记录"
    "MISSING" -> "缺失"
    "CORRUPT" -> "存储损坏"
    "UNAVAILABLE" -> "不可用"
    "CONFORMING" -> "符合检查条件"
    "ANOMALOUS" -> "检查异常"
    "NOT_INSTALLED" -> "未安装"
    "NOT_RUNNING" -> "未运行"
    "PERMISSION_REQUIRED" -> "需要授权"
    "READY_SHELL" -> "Shell 权限就绪"
    "READY_ROOT" -> "Root 模式（当前路线不支持）"
    "ROOT_REJECTED" -> "Root 权限被拒绝"
    "NON_SHELL_REJECTED" -> "非 Shell 权限被拒绝"
    "ERROR" -> "错误"
    "NOT_CHECKED" -> "未检查"
    "CHECKING" -> "检查中"
    "ALLOWED" -> "已允许"
    "NEEDS_USER_ACTION" -> "需要用户处理"
    "IDENTITY_CHANGED" -> "安装身份已变化"
    "CONFIGURED_NOT_TESTED" -> "已配置但未测试"
    "NOT_REQUESTED" -> "尚未请求"
    "REQUESTING" -> "请求中"
    "TOKEN_READY" -> "注册令牌就绪"
    "CONFIG_MISSING" -> "配置缺失"
    "TIMED_OUT" -> "超时"
    "SDK_IO_FAILURE" -> "SDK 输入输出失败"
    "FAILED" -> "失败"
    "GRANTED" -> "已授予"
    "REQUIRED" -> "需要权限"
    "HIGH" -> "高优先级"
    "NORMAL" -> "普通优先级"
    "YES" -> "是"
    "NO" -> "否"
    "RECEIPT_STORED" -> "回调记录已保存"
    "PERMISSION_DENIED" -> "通知权限被拒绝"
    "APP_NOTIFICATIONS_BLOCKED" -> "应用通知被禁用"
    "CHANNEL_BLOCKED" -> "通知渠道被禁用"
    "POST_REQUESTED" -> "已请求发布通知"
    "POST_FAILED" -> "通知发布请求失败"
    "RUNNING" -> "运行中（部分记录）"
    "STOPPED" -> "已停止"
    "DEADLINE" -> "到达监测截止时间"
    "COMPLETED" -> "已结束"
    "INTERRUPTED" -> "已中断"
    "LIMIT_REACHED" -> "已达到记录上限"
    "HEALTHY_READ_ONLY" -> "当次只读检查符合条件"
    "BUILD_CHANGED" -> "系统构建已变化"
    "IDLE" -> "空闲"
    "RECOVERING" -> "恢复操作中"
    "SUCCESS" -> "界面结果阶段成功（历史状态）"
    "UNCONFIRMED" -> "结果未确认"
    "MANUAL" -> "手动恢复"
    "AUTOMATIC" -> "自动恢复"
    "PREPARED" -> "记录已准备"
    "CLAIMED" -> "操作已认领"
    "FINISHED" -> "操作记录已结束"
    "RECONCILED" -> "后续只读观察已核对"
    "FLAG_CLEARED" -> "记录中观察到停止标志已解除"
    "STILL_STOPPED" -> "记录观察时仍处于停止状态"
    "NO_WRITE_NEEDED" -> "当次无需写入"
    "BLOCKED" -> "操作被阻断"
    "APP_RUNTIME_SNAPSHOT" -> "应用内运行显示快照"
    else -> "状态原值"
}

/** Inline text only: preserve content without permitting Markdown, HTML or invisible controls. */
private fun escapeDiagnosticMarkdown(value: String): String = buildString {
    value.forEachIndexed { index, character ->
        when {
            character == '&' -> append("&amp;")
            character == '<' -> append("&lt;")
            character == '>' -> append("&gt;")
            character == '\n' -> append("\\\\n")
            character == '\r' -> append("\\\\r")
            character == '\t' -> append("\\\\t")
            character.isISOControl() || Character.getType(character) in INVISIBLE_CHARACTER_TYPES ||
                (character.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() != true) ||
                (character.isLowSurrogate() && value.getOrNull(index - 1)?.isHighSurrogate() != true) -> {
                append("［U+").append(character.code.toString(16).uppercase().padStart(4, '0')).append('］')
            }
            character in MARKDOWN_PUNCTUATION -> append('\\').append(character)
            else -> append(character)
        }
    }
}

private const val MARKDOWN_PUNCTUATION = "!\"#$%'()*+,-./:;=?@[\\]^_`{|}~"
private val INVISIBLE_CHARACTER_TYPES = setOf(
    Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
)
