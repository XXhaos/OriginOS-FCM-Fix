package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GmsAutoHeartbeatPresentationTest {
    @Test fun legacyConsentScopeDoesNotAdvertiseCrossBootContinuation() {
        assertTrue(gmsAutoHeartbeatScopeText(GmsAutoHeartbeatUiState(consentVersion = 2)).contains("仅允许同次开机"))
        assertTrue(gmsAutoHeartbeatScopeText(GmsAutoHeartbeatUiState(consentVersion = 2)).contains("不自动升级"))
        assertTrue(gmsAutoHeartbeatScopeText(GmsAutoHeartbeatUiState(consentVersion = 1)).contains("不允许冷进程"))
        val text = gmsAutoHeartbeatScopeText(GmsAutoHeartbeatUiState(consentVersion = 3))
        assertTrue(text.contains("手机重启后")); assertTrue(text.contains("Shizuku 就绪"))
        assertTrue(text.contains("重新等待至少15分钟")); assertFalse(text.contains("已保护"))
    }
    @Test fun savedPreferenceIsNotRunningAuthority() {
        assertEquals("偏好已保存 · 心跳已暂停", gmsAutoHeartbeatHeadline(
            GmsAutoHeartbeatUiState(configured = true, requiresConfirmation = true)))
        assertEquals("低频心跳未开启", gmsAutoHeartbeatHeadline(GmsAutoHeartbeatUiState()))
    }
    @Test fun pendingStopCannotShowActiveOrSuccessfulProtection() {
        assertEquals("停止待确认", gmsAutoHeartbeatHeadline(GmsAutoHeartbeatUiState(
            configured = true, active = true, stopPending = true, lastStatus = GmsHeartbeatStatus.REQUESTED)))
    }
    @Test fun activeSessionIsNotConnectionOrDeliveryVerdict() {
        assertEquals("会话已开启 · 最短 15 分钟", gmsAutoHeartbeatHeadline(
            GmsAutoHeartbeatUiState(configured = true, active = true)))
    }
    @Test fun cleanResumeConsentDoesNotClaimAnActiveSession() {
        assertEquals("心跳已暂停 · 等待服务恢复", gmsAutoHeartbeatHeadline(
            GmsAutoHeartbeatUiState(configured = true, resumeAuthorized = true)))
        assertEquals("偏好已保存 · 心跳已暂停", gmsAutoHeartbeatHeadline(
            GmsAutoHeartbeatUiState(configured = true, resumeAuthorized = true, requiresConfirmation = true)))
    }
}
