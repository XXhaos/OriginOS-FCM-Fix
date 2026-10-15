package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.fcm.FcmReceipt
import io.github.originosfcmguardian.fcm.FcmUiState
import io.github.originosfcmguardian.fcm.MessagePriority
import io.github.originosfcmguardian.fcm.NetworkAvailability
import io.github.originosfcmguardian.fcm.ObservedBoolean
import io.github.originosfcmguardian.fcm.SensitiveToken
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
            fcmConfiguration = "CONFIGURED_NOT_TESTED",
            fcmRegistration = "TOKEN_READY",
            notificationPermission = "GRANTED",
            latestFcmReceipt = DiagnosticFcmReceipt(
                2101, "HIGH", "POST_REQUESTED", "NO", "YES", "AVAILABLE", 27, 504,
            ),
            notificationFacts = DiagnosticNotificationFacts(true, false, true, 5, 3, 2, 4101, 1, true),
            monitor = DiagnosticMonitorSummary(
                "org.example.monitor", 2501, 32501, "STOPPED", 19, 6, 4, 3, 5, 1, 7,
            ),
            dailyProtectionStatus = "HEALTHY_READ_ONLY",
            dailyProtectionCheckedAtEpochMillis = 5101,
            sourceAvailability = mapOf("notificationFacts" to DiagnosticSourceAvailability.PARTIAL),
        )
        val markdown = render(report)
        assertTrue(markdown.contains("仅已选应用安装身份；不代表停止标志、FCM 连接或通知送达"))
        listOf("环境", "Shizuku", "目标应用", "FCM 本应用证据",
            "通知观察摘要", "最近深度监测摘要", "日常只读检查", "来源可用性", "仍未知的关系", "局限")
            .forEach { assertTrue(it, markdown.contains("## $it")) }
        listOf("当前显示名称：目标名称",
            "最近回调时间（Unix 毫秒）：2101", "处理耗时（毫秒）：27", "回调时进程年龄（毫秒）：504",
            "保留事件数：5", "发布或更新数：3", "移除数：2", "最近观察时间（Unix 毫秒）：4101",
            "丢弃事件数：1", "停止后的持久化不确定：是（true）", "开始时间（Unix 毫秒）：2501",
            "截止时间（Unix 毫秒）：32501", "保留事件数：19", "进程启动数：6", "UID 活跃事件数：4",
            "UID 空闲事件数：3", "UID 运行事件数：5", "UID 停止事件数：1", "采样轮数：7",
            "检查时间（Unix 毫秒）：5101", "部分记录（PARTIAL）", "未知（UNKNOWN）")
            .forEach { assertTrue(it, markdown.contains(it)) }
        assertTrue(markdown.contains("Shell 权限就绪（READY\\_SHELL）"))
        assertTrue(markdown.contains("当次只读检查符合条件（HEALTHY\\_READ\\_ONLY）"))
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
        assertTrue(markdown.contains("最近回调：未知或不可用（null）"))
        assertTrue(markdown.contains("must not be interpreted as empty or healthy"))
        val pending = render(base())
        assertTrue(pending.contains("加载中（LOADING）"))
        assertTrue(pending.contains("目标应用：未知或不可用（null）"))
    }

    @Test
    fun nullsAreExplicitAndNeverRenderedAsHealthy() {
        val markdown = render(base())
        assertTrue(markdown.contains("通知观察：未知或不可用（null）"))
        assertTrue(markdown.contains("最近监测：未知或不可用（null）"))
        assertTrue(markdown.contains("检查时间（Unix 毫秒）：未知或不可用（null）"))
        assertFalse(markdown.contains("当次只读检查符合条件"))
    }

    @Test
    fun everyTargetIsIncludedWithoutSilentTruncation() {
        val targets = (0 until 32).map { DiagnosticTarget("org.example.p$it", "应用$it") }
        val markdown = render(base().copy(targets = targets))
        assertEquals(32, Regex("(?m)^### 目标应用 ").findAll(markdown).count())
        targets.forEachIndexed { index, _ -> assertTrue(markdown.contains("当前显示名称：应用$index\n")) }
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
        val manyTargets = base().copy(targets = (0 until 2_000).map {
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
            fcmEvidenceLoadState = HomeSourceLoadState.LOADED,
            fcm = FcmUiState(
                token = SensitiveToken("privateTokenSentinel"),
                registrationDetail = "privateFreeTextSentinel",
                latestReceipt = FcmReceipt("privateTestSentinel", 1000, 2000, 4000,
                    MessagePriority.HIGH, ObservedBoolean.NO, ObservedBoolean.YES, NetworkAvailability.UNKNOWN),
            ),
        )
        val report = buildCombinedDiagnosticReport(state, environment(), 9000)
        val markdown = render(report)
        listOf("privateTokenSentinel", "privateTestSentinel", "privateFreeTextSentinel", signer,
            "12345", "99123456789").forEach { assertFalse(it, markdown.contains(it)) }
        assertTrue(markdown.contains("包名：org\\.example\\.recipient"))
    }

    @Test
    fun jsonAndMarkdownDescribeTheSameSnapshotAndLimitations() {
        val report = base().copy(capturedAtEpochMillis = 123456789, dailyProtectionCheckedAtEpochMillis = 987654321)
        val markdown = render(report)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"capturedAtEpochMillis\":123456789"))
        assertTrue(markdown.contains("导出快照时间（Unix 毫秒）：123456789"))
        assertTrue(markdown.contains("检查时间（Unix 毫秒）：987654321"))
        assertTrue(markdown.contains("sampled independently and may be older"))
        assertTrue(markdown.contains("does not perform new privileged reads"))
        assertTrue(markdown.contains("Temporal proximity does not prove FCM delivery"))
        val unescaped = markdown.replace("\\.", ".").replace("\\,", ",").replace("\\-", "-").replace("\\;", ";")
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

    private fun base(): CombinedDiagnosticReport = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9000)
    private fun environment() = DiagnosticEnvironment(36, "17", "vivo", "iQOO", "1", 40)
    private fun render(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
}
