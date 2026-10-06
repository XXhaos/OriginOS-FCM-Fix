package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.gmsconfig.*
import io.github.originosfcmguardian.data.journal.OriginalRecordState
import io.github.originosfcmguardian.data.journal.OriginalRecordStatus
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.protection.*
import org.junit.Assert.*
import org.junit.Test

class CompactDiagnosticsPresentationTest {
    private fun loaded() = HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "private detail"),
        originalRecord = OriginalRecordState(OriginalRecordStatus.NOT_SAVED), stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        normalGmsLoadState = HomeSourceLoadState.LOADED, targetSelectionLoadState = HomeSourceLoadState.LOADED,
        dailyProtectionLoadState = HomeSourceLoadState.LOADED, notificationFactsLoadState = HomeSourceLoadState.LOADED,
        deepMonitoringLoadState = HomeSourceLoadState.LOADED, fcmEvidenceLoadState = HomeSourceLoadState.LOADED)
    private fun sample(matched: Boolean) = GmsConnectionObservation.Sample(1, 10208, 1, 1, 1, "a".repeat(64),
        "11111111-2222-3333-4444-555555555555", 1000, 500,
        SocketSource(SourceCompleteness.COMPLETE, matched), SocketSource(SourceCompleteness.COMPLETE, false), false, emptyList())

    @Test fun healthySummaryRequiresCompletedLoadsAndNoIssues() {
        assertEquals("暂无需要处理的问题", diagnosticSummary(loaded()))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(loaded().copy(dailyProtectionLoadState = HomeSourceLoadState.LOADING)))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(loaded().copy(deepMonitoringLoadState = HomeSourceLoadState.FAILED)))
    }

    @Test fun unresolvedRecoveryStorageAndRecordingStopRemainIssues() {
        val state = loaded().copy(stoppedRecovery = StoppedRecoveryUiState(loaded = true, needsReconcile = true, storageBlocked = true),
            originalRecord = OriginalRecordState(OriginalRecordStatus.DAMAGED),
            notificationFacts = loaded().notificationFacts.copy(stopPersistenceUncertain = true))
        val issues = diagnosticIssues(state).joinToString()
        assertTrue(issues.contains("核对")); assertTrue(issues.contains("存储")); assertTrue(issues.contains("原始记录"))
        assertTrue(issues.contains("停止未可靠保存")); assertFalse(issues.contains("private detail"))
    }

    @Test fun positiveNegativeStaleAndUnknownConnectionNeverClaimProtection() {
        val positive = GmsConnectionUiState(observation = sample(true), networkTrackingAvailable = true)
        assertTrue(gmsConnectionSummary(positive, 500).contains("TCP 迹象"))
        assertTrue(gmsConnectionSummary(positive, 30501).contains("当前连接未知"))
        assertTrue(gmsConnectionSummary(positive.copy(networkChanged = true), 500).contains("无法判断当前连接"))
        assertTrue(gmsConnectionSummary(positive.copy(observation = sample(false)), 500).contains("不等于断线"))
        assertTrue(gmsConnectionSummary(GmsConnectionUiState(), 500).contains("未知"))
        assertFalse(gmsConnectionSummary(positive, 500).contains("已保护"))
    }

    @Test fun recentActionsAreBoundedFixedCategoriesNotRawSummary() {
        val state = loaded().copy(dailyProtectionReport = DailyProtectionReport(1, ProtectionCheckReason.MANUAL,
            "private fingerprint", ProtectionCheckStatus.UNKNOWN, "private summary"))
        assertEquals(listOf("最近只读巡检：结果未知"), recentDiagnosticActions(state))
        assertFalse(recentDiagnosticActions(state).joinToString().contains("private"))
    }

    @Test fun oldBuildAndChangedBuildCannotHaveHealthySummary() {
        assertTrue(diagnosticIssues(loaded().copy(originalRecord = OriginalRecordState(
            OriginalRecordStatus.SAVED, matchesCurrentBuild = false))).any { it.contains("系统版本") })
        val changed = loaded().copy(dailyProtectionReport = DailyProtectionReport(1, ProtectionCheckReason.BUILD_CHANGED,
            "private fingerprint", ProtectionCheckStatus.BUILD_CHANGED, "private summary"))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(changed))
    }

    @Test fun backgroundSummaryIncludesDozeUnknownAndAnomalyWithoutProtectedClaim() {
        val context = GmsConfigContext("private fingerprint", 0, 14,
            GmsConfigIdentity(SelectedTargetIdentity(GMS_PACKAGE_NAME, 10208, 100, listOf("a".repeat(64))), 1, 200, true))
        fun state(doze: EvidenceStatus) = loaded().copy(gmsConfigContext = context,
            gmsConfigSnapshot = GmsConfigSnapshot(context, 100_000, 10_000, EvidenceStatus.CONFORMING,
                EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, EvidenceStatus.CONFORMING, doze, GmsConfigFailure.NONE))
        val anomaly = gmsBackgroundSummary(state(EvidenceStatus.ANOMALOUS), 100_001, 10_001)
        val unknown = gmsBackgroundSummary(state(EvidenceStatus.UNKNOWN), 100_001, 10_001)
        assertTrue(anomaly.contains("待处理"))
        assertTrue(unknown.contains("未知"))
        assertFalse(anomaly.contains("已保护"))
        assertFalse(unknown.contains("已保护"))
        assertFalse(gmsBackgroundSummary(loaded()).contains("已保护"))
    }

    @Test fun legacyTransientReportCannotStandInForSavedGmsConfiguration() {
        val report = DetectionReport(PrivilegedAccessState.READY_SHELL, 0, "private fingerprint",
            GmsPackageState(Presence.PRESENT, EnabledState.ENABLED, 10208, "", ""), emptyMap(),
            DozeState(emptySet(), "", EvidenceStatus.CONFORMING),
            PemState(Presence.PRESENT, PemListState.UNKNOWN, emptySet(), "", ""))
        assertEquals("尚无可用后台配置快照", gmsBackgroundSummary(loaded().copy(report = report)))
    }
}
