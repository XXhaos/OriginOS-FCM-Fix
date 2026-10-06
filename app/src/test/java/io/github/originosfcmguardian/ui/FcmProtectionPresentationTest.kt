package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.fcmprotection.*
import org.junit.Assert.*
import org.junit.Test

class FcmProtectionPresentationTest {
    @Test fun `single control is OFF without new combined confirmation despite legacy ON`() {
        assertEquals("尚未开启", fcmProtectionHeadline(FcmProtectionUiState()))
        assertEquals("尚无完整连接观察", fcmProtectionObservationText(FcmProtectionUiState(), 100))
    }
    @Test fun `waiting maintenance partial reboot and pending stop remain honest`() {
        assertEquals("已开启 · 维护等待完整连接", fcmProtectionHeadline(FcmProtectionUiState(
            configured = true, maintenance = FcmMaintenanceState.WAITING_CONNECTED)))
        assertEquals("部分分支需重新确认", fcmProtectionHeadline(FcmProtectionUiState(
            configured = true, requiresConfirmation = true, maintenance = FcmMaintenanceState.ACTIVE)))
        assertEquals("停止待确认", fcmProtectionHeadline(FcmProtectionUiState(
            configured = false, maintenance = FcmMaintenanceState.STOP_PENDING)))
    }
}
