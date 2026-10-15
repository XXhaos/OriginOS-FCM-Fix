package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class CompactDiagnosticsPresentationTest {
    private fun loaded() = HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "private detail"),
        stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        targetSelectionLoadState = HomeSourceLoadState.LOADED,
         )
    private fun sample(matched: Boolean) = GmsConnectionObservation.Sample(1, 10208, 1, 1, 1, "a".repeat(64),
        "11111111-2222-3333-4444-555555555555", 1000, 500,
        SocketSource(SourceCompleteness.COMPLETE, matched), SocketSource(SourceCompleteness.COMPLETE, false), false, emptyList())

    @Test fun healthySummaryRequiresCompletedLoadsAndNoIssues() {
        assertEquals("暂无需要处理的问题", diagnosticSummary(loaded()))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(loaded().copy(targetSelectionLoadState = HomeSourceLoadState.LOADING)))
        assertNotEquals("暂无需要处理的问题", diagnosticSummary(loaded().copy(targetSelectionLoadState = HomeSourceLoadState.FAILED)))
    }

    @Test fun unresolvedRecoveryAndStorageRemainIssues() {
        val state = loaded().copy(stoppedRecovery = StoppedRecoveryUiState(loaded = true, needsReconcile = true, storageBlocked = true),
            )
        val issues = diagnosticIssues(state).joinToString()
        assertTrue(issues.contains("核对")); assertTrue(issues.contains("存储"))
        assertFalse(issues.contains("private detail"))
    }

    @Test fun positiveNegativeStaleAndUnknownConnectionNeverClaimProtection() {
        val positive = GmsConnectionUiState(observation = sample(true), networkTrackingAvailable = true)
        assertEquals("FCM 连接状态未知", gmsConnectionSummary(positive, 500))
        assertTrue(gmsConnectionSummary(positive, 30501).contains("状态已过期"))
        assertTrue(gmsConnectionSummary(positive.copy(networkChanged = true), 500).contains("网络已变化"))
        assertEquals("FCM 连接状态未知", gmsConnectionSummary(positive.copy(observation = sample(false)), 500))
        assertTrue(gmsConnectionSummary(GmsConnectionUiState(), 500).contains("未知"))
        assertFalse(gmsConnectionSummary(positive, 500).contains("已保护"))
    }

    @Test fun recentActionsAreBoundedFixedCategoriesNotRawSummary() {
        val state = loaded().copy(automaticProtection = loaded().automaticProtection.copy(lastOutcome = "FLAG_CLEARED"))
        assertEquals(listOf("最近自动恢复：已确认解除停止限制"), recentDiagnosticActions(state))
        assertFalse(recentDiagnosticActions(state).joinToString().contains("private"))
    }

    @Test fun incompleteRecentRecoveryDoesNotReferToRemovedHistoryPage() {
        val state = loaded().copy(automaticProtection = loaded().automaticProtection.copy(lastOutcome = "FAILED"))
        assertEquals(listOf("最近自动恢复：未完成，请检查问题摘要"), recentDiagnosticActions(state))
    }


}
