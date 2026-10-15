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

        section("Shizuku")
        statusRow("Shizuku 状态", report.shizukuStatus)

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

        section("FCM 恢复事件")
        row("统一 FCM 保护授权", report.fcmProtection.configured)
        row("维护分支", report.fcmProtection.maintenance.name)
        row("恢复分支当前资格", report.fcmProtection.recoveryReady)
        row("统一授权需重新确认", report.fcmProtection.requiresConfirmation)
        row("本次统一确认未通过原因", report.fcmProtection.confirmationFailure?.name)
        row("恢复配置", report.fcmRecoveryTrial.configured)
        row("记录存储阻断", report.fcmRecoveryTrial.storageBlocked)
        append("\n仅保留最近64条状态变化与请求结果；REQUESTED仅表示请求被接受，连接观察不证明通知送达。\n\n")
        append("电量为整机参考，不能归因于 Guardian，也不是 mAh/能量测量。CPU 毫秒仅为 Guardian 主进程累计工作，排除 Shizuku/独立 shell/GMS/无线电；仅同一进程标识且进程年龄与 CPU 连续递增时才可比较。字段缺失为未知。小时样本只搭载现有轮询，不额外唤醒。\n\n")
        report.fcmRecoveryTrial.events.takeLast(64).forEach { e ->
            append("- ").append(e.atMillis).append(" / ").append(e.action.name)
                .append(" / ").append(e.service.name).append(" / ").append(e.completeness.name)
                .append(" / ").append(e.socket.name).append(" / ").append(e.eligibility.name)
            e.status?.let { append(" / ").append(it.name).append(" / ").append(e.reason?.name) }
            e.operation?.let { append(" / 请求操作=").append(it.name) }
            e.observationUnknownReason?.let { append(" / 读取未知原因=").append(it.name) }
            e.power?.let { p ->
                append(" / 整机电量=").append(p.batteryPercent?.let { "$it%" } ?: "未知")
                    .append(" / 充电=").append(p.chargingStatus?.name ?: "未知")
                    .append(" / 电源=").append(p.pluggedSource?.name ?: "未知")
                    .append(" / 交互=").append(p.interactive?.toString() ?: "未知")
                    .append(" / 空闲=").append(p.deviceIdle?.toString() ?: "未知")
                    .append(" / 主进程CPUms=").append(p.mainProcessCpuMillis?.toString() ?: "未知")
                    .append(" / 进程年龄ms=").append(p.processAgeMillis?.toString() ?: "未知")
                    .append(" / 进程标识=").append(p.processInstanceId ?: "未知")
            }
            append('\n')
        }
        section("FCM 断连与恢复片段")
        val episodes = report.fcmRecoveryEpisodes
        row("片段记录可用性", episodes.availability.name)
        row("片段来源状态", episodeSourceAvailability(episodes).name)
        row("已保留片段数", if (episodes.availability == io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability.AVAILABLE)
            episodes.episodes.size else null)
        row("历史淘汰片段数", episodes.evictedEpisodes)
        row("片段写入失败数", episodes.writeFailures)
        row("片段更新丢弃数", episodes.droppedUpdates)
        row("报告片段预览上限", MAX_EPISODE_PREVIEW)
        row("未在报告预览显示的片段数", if (episodes.availability == io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability.AVAILABLE)
            (episodes.episodes.size - MAX_EPISODE_PREVIEW).coerceAtLeast(0) else null)
        append("\n仅预览最近8个片段；完整有界记录保留在应用内；片段时刻属于已保留的状态转换，不代表每次轮询都已记录。来源不可用或有缺口不代表没有断连。请求开始、未决与返回时间分开；旧记录无返回时刻则未知，诊断关联UUID不导出。请求接受不等于恢复成功；请求之后观察到连接不证明唯一原因或通知送达。服务仍在但断连不授权缺失服务恢复。超时只由后续已有观察标记，不是独立计时保证。\n")
        if (episodes.availability == io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability.AVAILABLE) {
            episodes.episodes.takeLast(MAX_EPISODE_PREVIEW).forEach { episode ->
                append("\n### FCM 片段 ").append(episode.sequence).append("\n\n")
                row("Guardian 进程", episode.processId)
                row("进程启动时开机毫秒", episode.processStartedElapsedMillis)
                row("最后观察到的故障类型", episode.kind.name)
                row("开始时间（Unix 毫秒）", episode.startedAtMillis)
                row("开始时开机毫秒", episode.startedElapsedMillis)
                row("最后保留观察时间（Unix 毫秒）", episode.lastObservedAtMillis)
                row("最后保留观察时开机毫秒", episode.lastObservedElapsedMillis)
                row("请求时间（Unix 毫秒）", episode.requestAtMillis)
                row("请求时开机毫秒", episode.requestElapsedMillis)
                row("请求结果仍未决", episode.requestPending)
                row("请求状态", episode.requestStatus?.name)
                episode.requestOperation?.let { row("请求操作", it.name) }
                row("请求返回时间（Unix 毫秒）", episode.resultAtMillis)
                row("请求返回时开机毫秒", episode.resultElapsedMillis)
                row("请求原因", episode.reason?.name)
                row("结束时间（Unix 毫秒）", episode.endedAtMillis)
                row("结束时开机毫秒", episode.endedElapsedMillis)
                row("片段结论", episode.outcome.name)
                row("后续观察时已超时", episode.timeoutObserved)
                row("最后阻断原因", episode.lastBlock.name)
            }
        }
        appendProtectionResourceMarkdown(report.protectionResources)
        appendDaemonDiagnosticsMarkdown(report.daemonDiagnostics)
        section("保护停止与进程记录")
        row("记录可用性", report.protectionLifecycle.availability.name)
        row("丢弃记录数", report.protectionLifecycle.droppedEvents)
        row("系统退出记录可用", report.protectionLifecycle.exitHistoryAvailable)
        append("\n仅保留最近64条生命周期事件。未记录干净结束不等于系统杀进程；记录异步写入，丢失或不可用时原因未知。系统退出时间为历史原始时间，不收集异常正文或堆栈。\n\n")
        report.protectionLifecycle.events.takeLast(64).forEach { event ->
            append("- ").append(event.atMillis).append(" / ").append(event.kind.name)
                .append(" / ").append(event.reason.name).append(" / Guardian进程=").append(event.processId)
            event.systemExitReason?.let { append(" / 系统退出类型=").append(it).append(" / 状态=").append(event.systemExitStatus) }
            append('\n')
        }
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

        section("已移除的排查工具")
        append("深度监控、通知事实采集、Guardian Firebase 推送自测和定期巡检已移除；历史文件保留但不读取。未采集不代表没有事件，也不能证明通知送达或没有漏收。\n")

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
    "targetSelection" -> "目标应用选择（targetSelection）"
    "dailyProtection" -> "已移除的定期巡检（兼容字段）"
    "stoppedRecovery" -> "停止标志恢复（stoppedRecovery）"
    "automaticProtection" -> "自动保护应用内快照（automaticProtection）"
    "fcmRecoveryEpisodes" -> "FCM 断连与恢复片段（fcmRecoveryEpisodes）"
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
    "DAEMON_PROJECTED_UI_SNAPSHOT" -> "独立核心投影显示快照"
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
