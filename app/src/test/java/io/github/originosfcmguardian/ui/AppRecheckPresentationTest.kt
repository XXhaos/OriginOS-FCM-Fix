package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.TargetStoppedReadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppRecheckPresentationTest {
    @Test fun readFailuresOfferRetryWithoutClaimingHealthyOrContinuingBusy() {
        for (failure in TargetStoppedReadFailure.entries) {
            val target = SelectedTargetUiState(SelectedTargetIdentity("example.failure", 10123, 1, listOf("a".repeat(64))), "Fixture",
                stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN, 10, failure))
            val result = appRecheckResult(target, false)
            assertEquals(TargetStatusTone.UNCERTAIN, result.tone)
            org.junit.Assert.assertTrue(result.text.contains("重试"))
            assertFalse(result.text.contains("复检中"))
        }
    }
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("example.recheck", 10123, 1, listOf("a".repeat(64))), "Fixture")

    @Test fun resultKeepsAllStoppedReadStatesWithoutClaimingDelivery() {
        for (status in TargetStoppedStatus.entries) {
            val current = target.copy(stoppedSnapshot = TargetStoppedSnapshot(status, 10))
            val wake = targetWakePresentation(current)
            val result = appRecheckResult(current, false)
            assertEquals("复检结果：${wake.text}", result.text)
            assertEquals(wake.tone, result.tone)
            assertFalse(result.text.contains("通知已送达"))
        }
    }

    @Test fun inFlightOrMissingTargetNeverDisplaysAnOldHealthyResult() {
        val healthy = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10))
        assertEquals(TargetStatusPresentation("正在复检…", TargetStatusTone.NEUTRAL), appRecheckResult(healthy, true))
        assertEquals(TargetStatusPresentation("应用状态不可用", TargetStatusTone.UNCERTAIN), appRecheckResult(null, false))
    }

    @Test fun changedIdentityOverridesEvenAnApparentlyClearFlag() {
        val changed = target.copy(status = TargetAutostartStatus.IDENTITY_CHANGED,
            stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10))
        assertEquals(TargetStatusTone.UNCERTAIN, appRecheckResult(changed, false).tone)
        assertEquals("复检结果：应用身份已变化，请重新选择", appRecheckResult(changed, false).text)
    }
}
