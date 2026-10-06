package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.protection.*
import org.junit.Assert.*
import org.junit.Test

class CompactDiagnosticsPresentationTest {
    private fun loaded() = HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "private detail"),
        stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        targetSelectionLoadState = HomeSourceLoadState.LOADED,
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
            notificationFacts = loaded().notificationFacts.copy(stopPersistenceUncertain = true))
        val issues = diagnosticIssues(state).joinToString()
        assertTrue(issues.contains("核对")); assertTrue(issues.contains("存储"))
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
        val changed = loaded().copy(dailyProtectionReport = DailyProtectionReport(1, ProtectionCheckReason.BUILD_CHANGED,
            "private fingerprint", ProtectionCheckStatus.BUILD_CHANGED, "private summary"))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(changed))
    }


}
