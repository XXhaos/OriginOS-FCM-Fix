package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import io.github.originosfcmguardian.ui.HomeSourceLoadState
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.StoppedRecoveryPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDiagnosticReportTest {
    @Test
    fun populatedReportRetainsEveryRepresentedSectionAndValue() {
        val report = base().copy(
            shizukuStatus = "READY_SHELL",
            targets = listOf(DiagnosticTarget("org.example.recipient", "目标名称")),

            sourceAvailability = mapOf("targetSelection" to DiagnosticSourceAvailability.PARTIAL),
        )
        val markdown = render(report)
        assertFalse(markdown.contains("## 日常只读检查"))
        listOf("环境", "Shizuku", "目标应用", "FCM 恢复事件",
            "后台资源与中断分析", "保护停止与进程记录", "来源可用性", "仍未知的关系", "已移除的排查工具", "局限")
            .forEach { assertTrue(it, markdown.contains("## $it")) }
        listOf("| 1 | 目标名称 | org\\.example\\.recipient |",
            "部分记录（PARTIAL）", "未知（UNKNOWN）", "未采集不代表没有事件")
            .forEach { assertTrue(it, markdown.contains(it)) }
        assertTrue(markdown.contains("Shell 权限就绪（READY\\_SHELL）"))
        assertTrue(markdown.contains("定期巡检已移除"))
        assertFalse(markdown.contains("自启动"))
    }

    @Test
    fun emptyLoadingCorruptPartialAndUnavailableSourcesRemainDistinct() {
        val states = DiagnosticSourceAvailability.entries
        val report = base().copy(
            targets = emptyList(),
            sourceAvailability = states.associate { "source${it.name}" to it },
        )
        val markdown = render(report)
        states.forEach { assertTrue(it.name, markdown.contains("（${it.name}）")) }
        assertTrue(markdown.contains("目标应用数量：0"))
        assertFalse(markdown.contains("### 目标应用"))
        assertTrue(markdown.contains("must not be interpreted as empty or healthy"))
        val pending = render(base())
        assertTrue(pending.contains("加载中（LOADING）"))
        assertTrue(pending.contains("目标应用：未知或不可用（null）"))
    }

    @Test
    fun nullsAreExplicitAndNeverRenderedAsHealthy() {
        val markdown = render(base())
        assertFalse(markdown.contains("## 日常只读检查"))
        assertFalse(markdown.contains("当次只读检查符合条件"))
    }

    @Test
    fun everyTargetIsIncludedWithoutSilentTruncation() {
        val targets = (0 until 32).map { DiagnosticTarget("org.example.p$it", "应用$it") }
        val markdown = render(base().copy(targets = targets))
        assertEquals(32, Regex("(?m)^\\| [0-9]+ \\| 应用").findAll(markdown).count())
        targets.forEachIndexed { index, _ ->
            assertTrue(markdown.contains("| ${index + 1} | 应用$index | org\\.example\\.p$index |\n"))
        }
        assertTrue(markdown.contains("目标应用数量：32"))
    }

    @Test
    fun suppliedTextCannotInjectHeadingsLinksHtmlTablesOrInvisibleControls() {
        val hostile = "\n# heading\r\n[link](https://evil.invalid)\t<img src=x>|cell|`code`&amp;" +
            "\u0000\u001B\u007F\u0085\u2028\u2029\u202E\u200B\uD800"
        val report = base().copy(
            environment = environment().copy(manufacturer = hostile, model = "正常😀设备"),
            shizukuStatus = hostile,
            targets = listOf(DiagnosticTarget(hostile, hostile)),
            sourceAvailability = mapOf(hostile to DiagnosticSourceAvailability.PARTIAL),
        )
        val markdown = render(report)
        assertFalse(markdown.contains("\n# heading"))
        assertFalse(markdown.contains("[link](https://evil.invalid)"))
        assertFalse(markdown.contains("<img"))
        assertFalse(markdown.contains("|cell|"))
        assertFalse(markdown.contains("`code`"))
        assertTrue(markdown.contains("\\[link\\]\\(https\\:\\/\\/evil\\.invalid\\)"))
        assertTrue(markdown.contains("&lt;img src\\=x&gt;"))
        assertTrue(markdown.contains("\\|cell\\|"))
        assertTrue(markdown.contains("&amp;amp\\;"))
        listOf("0000", "001B", "007F", "0085", "2028", "2029", "202E", "200B", "D800")
            .forEach { assertTrue(it, markdown.contains("［U+$it］")) }
        assertFalse(markdown.any { it.isISOControl() && it != '\n' })
        assertTrue(markdown.contains("正常😀设备"))
    }

    @Test
    fun oversizeUtf8ReportFailsInsteadOfReturningTruncatedTargetsOrLabels() {
        // Exceed the shared UTF-8 byte budget even after a legitimate budget increase.
        val oversized = base().copy(targets = listOf(
            DiagnosticTarget("org.example.app", "字".repeat(MAX_REPORT_BYTES / 3 + 1)),
        ))
        assertThrows(IllegalArgumentException::class.java) { encodeCombinedDiagnosticMarkdown(oversized) }
        val manyTargets = base().copy(targets = (0 until MAX_REPORT_BYTES / 40 + 100).map {
            DiagnosticTarget("org.example.p$it", "应用$it")
        })
        assertThrows(IllegalArgumentException::class.java) { encodeCombinedDiagnosticMarkdown(manyTargets) }
    }

    @Test
    fun renderingIsDeterministicAcrossMapInsertionOrders() {
        val report = base().copy(
            sourceAvailability = linkedMapOf("Z" to DiagnosticSourceAvailability.EMPTY, "A" to DiagnosticSourceAvailability.CORRUPT),
        )
        val reordered = report.copy(
            sourceAvailability = report.sourceAvailability.entries.reversed().associate { it.toPair() },
        )
        assertArrayEquals(encodeCombinedDiagnosticMarkdown(report), encodeCombinedDiagnosticMarkdown(reordered))
        assertArrayEquals(encodeCombinedDiagnosticMarkdown(report), encodeCombinedDiagnosticMarkdown(report))
    }

    @Test
    fun redactedProjectionNeverExportsTokenTestIdOrInstallationIdentity() {
        val signer = "c".repeat(64)
        val identity = SelectedTargetIdentity("org.example.recipient", 12345, 99123456789, listOf(signer))
        val state = HomeUiState(
            targetSelectionLoadState = HomeSourceLoadState.LOADED,
            selectedTargets = listOf(SelectedTargetUiState(identity, "Recipient", TargetAutostartStatus.UNKNOWN)),

        )
        val report = buildCombinedDiagnosticReport(state, environment(), 9000)
        val markdown = render(report)
        listOf("privateTokenSentinel", "privateTestSentinel", "privateFreeTextSentinel", signer,
            "12345", "99123456789").forEach { assertFalse(it, markdown.contains(it)) }
        assertTrue(markdown.contains("| 1 | Recipient | org\\.example\\.recipient |"))
    }

    @Test
    fun jsonAndMarkdownDescribeTheSameSnapshotAndLimitations() {
        val report = base().copy(capturedAtEpochMillis = 123456789)
        val markdown = render(report)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"capturedAtEpochMillis\":123456789"))
        assertTrue(markdown.contains("导出快照时间（Unix 毫秒）：123456789"))
        assertTrue(json.contains("\"retired\":true,\"status\":null,\"checkedAtEpochMillis\":null"))
        assertTrue(markdown.contains("sampled independently and may be older"))
        assertTrue(markdown.contains("does not perform new privileged reads"))
        assertTrue(markdown.contains("Temporal proximity does not prove FCM delivery"))
        val unescaped = markdown.replace("\\.", ".").replace("\\,", ",").replace("\\-", "-").replace("\\;", ";").replace("\\_", "_")
        assertTrue(unescaped.contains(DIAGNOSTIC_REPORT_SCHEMA))
        DIAGNOSTIC_REPORT_LIMITATIONS.forEach { assertTrue(it, unescaped.contains(it)) }
    }

    @Test
    fun recoveryAndAutomaticSnapshotsRetainTypedFlagsHistoryAndIndependentTimes() {
        val report = base().copy(
            stoppedRecovery = DiagnosticStoppedRecovery(
                loaded = true, storageBlocked = false, needsReconcile = true, busy = true,
                phase = StoppedRecoveryPhase.UNCONFIRMED,
                latestRecord = DiagnosticRecoveryRecord("org.example.historical", 2311,
                    StoppedRecoverySource.AUTOMATIC, StoppedRecoveryStage.RECONCILED, StoppedRecoveryOutcome.FLAG_CLEARED),
            ),
            automaticProtection = DiagnosticAutomaticProtection(
                enabled = true, busy = true, savedAuthorization = true, revocationSaveFailed = true,
                waitingForPrivilege = true, waitingForReconciliation = true,
                lastCheckedAtEpochMillis = 7112, lastOutcome = DiagnosticRecoveryOutcome.STILL_STOPPED,
            ),
        )
        val markdown = render(report)
        listOf("恢复来源已加载：是（true）", "恢复存储阻断：否（false）", "存在待核对结果：是（true）",
            "应用内恢复操作忙碌：是（true）", "记录创建时间（Unix 毫秒，非完成时间）：2311",
            "历史记录来源：自动恢复（AUTOMATIC）", "历史记录阶段：后续只读观察已核对（RECONCILED）",
            "界面启用标志：是（true）", "界面忙碌标志：是（true）", "界面保存授权标志：是（true）",
            "关闭授权保存失败：是（true）", "等待特权就绪：是（true）", "等待未决结果核对：是（true）",
            "最近检查时间（Unix 毫秒，独立采样）：7112", "历史记录不能证明当前安装状态或通知送达",
            "不能配成同一次操作").forEach { assertTrue(it, markdown.contains(it)) }
        assertTrue(markdown.contains("APP\\_RUNTIME\\_SNAPSHOT"))
        assertTrue(markdown.contains("历史记录结果：记录中观察到停止标志已解除（FLAG\\_CLEARED）"))
        assertTrue(markdown.contains("保留结果（无对应结果时间）：记录观察时仍处于停止状态（STILL\\_STOPPED）"))
        assertTrue(markdown.contains("历史记录包名：org\\.example\\.historical"))
    }

    @Test
    fun typedRecoveryOutcomesUnknownsAndPendingRecordNullsArePreserved() {
        DiagnosticRecoveryOutcome.entries.forEach { outcome ->
            val markdown = render(base().copy(
                automaticProtection = DiagnosticAutomaticProtection(lastOutcome = outcome),
            ))
            assertTrue(outcome.name, markdown.contains("（${outcome.name.replace("_", "\\_")}）"))
        }
        StoppedRecoveryOutcome.entries.forEach { outcome ->
            val markdown = render(base().copy(stoppedRecovery = DiagnosticStoppedRecovery(
                loaded = true, latestRecord = DiagnosticRecoveryRecord("org.example.app", 1001,
                    StoppedRecoverySource.MANUAL, StoppedRecoveryStage.FINISHED, outcome),
            )))
            assertTrue(outcome.name, markdown.contains("（${outcome.name.replace("_", "\\_")}）"))
        }
        val pending = render(base().copy(stoppedRecovery = DiagnosticStoppedRecovery(
            loaded = true, needsReconcile = true,
            latestRecord = DiagnosticRecoveryRecord("org.example.app", 1001,
                StoppedRecoverySource.MANUAL, StoppedRecoveryStage.CLAIMED, null),
        )))
        assertTrue(pending.contains("历史记录阶段：操作已认领（CLAIMED）"))
        assertTrue(pending.contains("历史记录结果：未知或不可用（null）"))
        assertTrue(pending.contains("保留结果（无对应结果时间）：未知或不可用（null）"))
        val blocked = render(base().copy(stoppedRecovery = DiagnosticStoppedRecovery(storageBlocked = true)))
        assertTrue(blocked.contains("恢复来源已加载：否（false）"))
        assertTrue(blocked.contains("恢复存储阻断：是（true）"))
        assertTrue(blocked.contains("最近历史恢复记录：未知或不可用（null）"))
    }

    @Test
    fun preparedMarkdownBytesRemainTheClickTimeSnapshot() {
        val original = encodeCombinedDiagnosticMarkdown(base().copy(capturedAtEpochMillis = 111))
        val expected = original.copyOf()
        val prepared = PreparedCombinedDiagnosticExport()
        prepared.prepare(original)
        original.fill(0)
        encodeCombinedDiagnosticMarkdown(base().copy(capturedAtEpochMillis = 222))
        val result = prepared.consume(documentSelected = true) as CombinedDiagnosticExportResult.Ready
        assertArrayEquals(expected, result.bytes)
        assertTrue(prepared.consume(documentSelected = true) is CombinedDiagnosticExportResult.Missing)
    }

    @Test
    fun quickSummaryKeepsIndependentContradictoryFlagsAndExplicitUnknowns() {
        val report = base().copy(
            targets = null,
            automaticProtection = DiagnosticAutomaticProtection(enabled = true, savedAuthorization = false,
                waitingForPrivilege = true, waitingForReconciliation = true),
            stoppedRecovery = DiagnosticStoppedRecovery(needsReconcile = true),
            fcmProtection = FcmProtectionUiState(configured = true, storageBlocked = true,
                requiresConfirmation = true, maintenance = FcmMaintenanceState.ACTIVE, recoveryReady = false),
        )
        val markdown = render(report)
        val summary = markdown.substringAfter("## 快速摘要\n").substringBefore("## 阅读导航")
        listOf("| 目标应用数量 | 未知或不可用（null），请结合来源状态 |",
            "| 界面启用标志 | 是（true） |", "| 界面保存授权标志 | 否（false） |",
            "| 等待特权就绪 | 是（true） |", "| 等待未决结果核对 | 是（true） |",
            "| 维护分支 | ACTIVE |", "| 恢复分支当前资格 | 否（false） |",
            "| 统一 FCM 保护存储阻断 | 是（true） |", "| 统一授权需重新确认 | 是（true） |",
            "| 存在待核对结果 | 是（true） |").forEach { assertTrue(it, summary.contains(it)) }
        assertTrue(summary.contains("不合成为“全部健康”结论"))
        assertTrue(markdown.contains("统一 FCM 保护存储阻断：是（true）"))
        val emptySummary = render(report.copy(targets = emptyList())).substringBefore("## 阅读导航")
        assertTrue(emptySummary.contains("| 目标应用数量 | 0 |"))
        assertFalse(emptySummary.contains("目标应用数量 | 未知"))
    }

    @Test
    fun staticNavigationPointsToRetainedSectionsAndNeverUsesUserTextAsLinks() {
        val report = base().copy(targets = listOf(DiagnosticTarget("org.example.app", "普通名称")))
        val safe = render(report)
        val hostile = render(report.copy(
            environment = report.environment.copy(manufacturer = "| --- |\n## injected\n<a id=x>"),
            shizukuStatus = "[replace](https://evil.invalid)",
            targets = listOf(DiagnosticTarget("[pkg](https://evil.invalid)", "|x|\n# injected\n[go](#局限)")),
        ))
        fun headings(value: String) = Regex("(?m)^#{1,3} .+$").findAll(value).map { it.value }.toList()
        assertEquals(headings(safe), headings(hostile))
        fun navigation(value: String) = value.substringAfter("## 阅读导航\n").substringBefore("## 环境")
        assertEquals(navigation(safe), navigation(hostile))
        listOf("#环境", "#shizuku", "#目标应用", "#fcm-恢复事件", "#fcm-断连与恢复片段",
            "#后台资源与中断分析", "#独立保护核心运行记录", "#保护停止与进程记录",
            "#停止标志恢复摘要", "#自动保护应用内快照", "#来源可用性", "#仍未知的关系", "#局限")
            .forEach { assertTrue(it, navigation(safe).contains("]($it)")) }
        assertFalse(hostile.contains("\n## injected"))
        assertFalse(hostile.contains("[replace](https://evil.invalid)"))
        assertFalse(hostile.contains("<a id=x>"))
        val targetRow = hostile.lineSequence().single { it.startsWith("| 1 |") }
        assertEquals(4, Regex("(?<!\\\\)\\|").findAll(targetRow).count())
        assertTrue(targetRow.contains("\\|x\\|"))
    }

    @Test
    fun revisedPresentationRetainsEveryFcmEventAndPowerEvidenceWithoutChangingJson() {
        val process = "00000000-0000-0000-0000-000000000001"
        val event = FcmRecoveryEvent(41001, 411, FcmRecoveryEventAction.REQUEST_RESULT,
            GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.COMPLETE, SocketObservation.NO_MATCH,
            eligible = true, status = FcmRecoveryStatus.NOT_SENT, reason = FcmRecoveryReason.TARGET_UNQUALIFIED,
            observationUnknownReason = GmsUnknown.TIMED_OUT,
            operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS,
            power = FcmPowerReference(63, FcmChargingStatus.DISCHARGING, FcmPluggedSource.UNPLUGGED,
                false, true, 171, 172, process))
        val report = base().copy(fcmRecoveryTrial = FcmRecoveryUiState(configured = true,
            storageBlocked = true, events = listOf(event)))
        val originalJson = encodeCombinedDiagnosticReport(report)
        val markdown = render(report)
        listOf("41001 / REQUEST_RESULT / REPORTED_DISCONNECTED / COMPLETE / NO_MATCH / ELIGIBLE",
            "NOT_SENT / TARGET_UNQUALIFIED", "请求操作=PRESENT_DISCONNECTED_MCS", "读取未知原因=TIMED_OUT",
            "整机电量=63%", "充电=DISCHARGING", "电源=UNPLUGGED", "交互=false", "空闲=true",
            "主进程CPUms=171", "进程年龄ms=172", "进程标识=$process",
            "恢复配置：是（true）", "记录存储阻断：是（true）", "REQUESTED仅表示请求被接受",
            "连接观察不证明通知送达", "不能归因于 Guardian").forEach { assertTrue(it, markdown.contains(it)) }
        assertArrayEquals(originalJson, encodeCombinedDiagnosticReport(report))
    }

    @Test
    fun tableTextCannotInjectAutolinksImagesFootnotesOrExtraRows() {
        val payloads = listOf("<https://evil.invalid>", "![img](https://evil.invalid/x)",
            "[x][ref]\n[ref]: https://evil.invalid", "[^note]\n[^note]: secret", "x\\|y|z\r\n| --- |",
            "&lt;script&gt;", "\u202Ehidden\u2066\u2069", "\uD800\uDC00\uD800")
        payloads.forEach { payload ->
            val markdown = render(base().copy(environment = environment().copy(model = payload),
                targets = listOf(DiagnosticTarget(payload, payload))))
            val targetRows = markdown.lineSequence().filter { it.startsWith("| 1 |") }.toList()
            assertEquals(payload, 1, targetRows.size)
            assertEquals(payload, 4, Regex("(?<!\\\\)\\|").findAll(targetRows.single()).count())
            assertFalse(markdown.contains("<https://evil.invalid>"))
            assertFalse(markdown.contains("![img]"))
            assertFalse(markdown.contains("\n[ref]:"))
            assertFalse(markdown.contains("\n[^note]:"))
            assertFalse(markdown.any { Character.getType(it) in setOf(Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) })
        }
    }

    @Test
    fun emptyTargetTableIsExplicitAndDoesNotCreatePlaceholderIdentities() {
        val empty = render(base().copy(targets = emptyList()))
        val targetSection = empty.substringAfter("## 目标应用\n").substringBefore("## FCM 恢复事件")
        assertTrue(targetSection.contains("目标应用数量：0"))
        assertTrue(targetSection.contains("| 序号 | 当前显示名称 | 包名 |"))
        assertFalse(Regex("(?m)^\\| [0-9]+ \\|").containsMatchIn(targetSection))
        val unknown = render(base().copy(targets = null)).substringAfter("## 目标应用\n")
            .substringBefore("## FCM 恢复事件")
        assertTrue(unknown.contains("目标应用：未知或不可用（null）"))
        assertFalse(unknown.contains("| 序号 |"))
        assertFalse(unknown.contains("目标应用数量：0"))
    }

    @Test
    fun exactUtf8BudgetIncludesNewHeaderAndWorstCaseEscapingWithoutTruncatingEvidence() {
        val template = base().copy(targets = listOf(DiagnosticTarget("org.example.app", "")))
        val empty = encodeCombinedDiagnosticMarkdown(template)
        val remaining = MAX_REPORT_BYTES - empty.size
        // Each ampersand becomes five UTF-8 bytes; include the new summary/navigation in the budget.
        val label = "&".repeat(remaining / 5) + "a".repeat(remaining % 5)
        val full = template.copy(targets = listOf(DiagnosticTarget("org.example.app", label)))
        val encoded = encodeCombinedDiagnosticMarkdown(full)
        assertEquals(MAX_REPORT_BYTES, encoded.size)
        val markdown = encoded.decodeToString()
        assertTrue(markdown.contains("## 快速摘要"))
        assertTrue(markdown.contains("## 阅读导航"))
        assertTrue(markdown.contains("| 1 | ${"&amp;".repeat(remaining / 5)}${"a".repeat(remaining % 5)} |"))
        assertEquals(empty.decodeToString().substringAfter("## 局限"), markdown.substringAfter("## 局限"))
        val tooLarge = full.copy(targets = listOf(DiagnosticTarget("org.example.app", label + "a")))
        assertThrows(IllegalArgumentException::class.java) { encodeCombinedDiagnosticMarkdown(tooLarge) }
    }

    @Test
    fun episodeLayoutRetainsEveryIndependentTimeCounterOutcomeAndPrivacyBoundary() {
        val correlation = "00000000-0000-0000-0000-000000000007"
        val episode = FcmRecoveryEpisode(7, 321, 10, 1001, 20,
            FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED, 1101, 30,
            requestAtMillis = 1051, requestElapsedMillis = 25,
            requestStatus = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.TIMED_OUT,
            endedAtMillis = 1151, endedElapsedMillis = 35, outcome = FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN,
            timeoutObserved = true, lastBlock = FcmRecoveryEpisodeBlock.INELIGIBLE,
            requestCorrelationId = correlation, resultAtMillis = 1091, resultElapsedMillis = 29,
            requestOperation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS)
        val markdown = render(base().copy(fcmRecoveryEpisodes = FcmRecoveryEpisodeSnapshot(
            FcmEpisodeAvailability.AVAILABLE, listOf(episode), evictedEpisodes = 3, writeFailures = 4, droppedUpdates = 5)))
        listOf("历史淘汰片段数：3", "片段写入失败数：4", "片段更新丢弃数：5", "已保留片段数：1",
            "未在报告预览显示的片段数：0", "### FCM 片段 7", "Guardian 进程：321", "进程启动时开机毫秒：10",
            "最后观察到的故障类型：PRESENT\\_DISCONNECTED", "开始时间（Unix 毫秒）：1001", "开始时开机毫秒：20",
            "最后保留观察时间（Unix 毫秒）：1101", "最后保留观察时开机毫秒：30",
            "请求时间（Unix 毫秒）：1051", "请求时开机毫秒：25", "请求结果仍未决：否（false）", "请求状态：UNKNOWN",
            "请求操作：PRESENT\\_DISCONNECTED\\_MCS", "请求返回时间（Unix 毫秒）：1091", "请求返回时开机毫秒：29",
            "请求原因：TIMED\\_OUT", "结束时间（Unix 毫秒）：1151", "结束时开机毫秒：35",
            "片段结论：REQUEST\\_UNKNOWN", "后续观察时已超时：是（true）", "最后阻断原因：INELIGIBLE",
            "请求接受不等于恢复成功", "不证明唯一原因或通知送达").forEach { assertTrue(it, markdown.contains(it)) }
        assertFalse(markdown.contains(correlation))
        assertFalse(markdown.contains("requestCorrelationId"))
    }

    private fun base(): CombinedDiagnosticReport = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9000)
    private fun environment() = DiagnosticEnvironment(36, "17", "vivo", "iQOO", "1", 40)
    private fun render(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
}
