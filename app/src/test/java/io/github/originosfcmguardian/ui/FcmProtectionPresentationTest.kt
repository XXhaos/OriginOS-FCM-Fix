package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test

class FcmProtectionPresentationTest {
    @Test fun `single control is OFF without new combined confirmation despite legacy ON`() {
        assertEquals("尚未开启", fcmProtectionHeadline(FcmProtectionUiState()))
        assertEquals("⚪ 尚未读取", fcmProtectionObservationText(FcmProtectionUiState(), 100))
    }
    @Test fun `waiting maintenance partial reboot and pending stop remain honest`() {
        assertEquals("等待连接", fcmProtectionHeadline(FcmProtectionUiState(
            configured = true, maintenance = FcmMaintenanceState.WAITING_CONNECTED)))
        assertEquals("需要重新确认", fcmProtectionHeadline(FcmProtectionUiState(
            configured = true, requiresConfirmation = true, maintenance = FcmMaintenanceState.ACTIVE)))
        assertEquals("停止待确认", fcmProtectionHeadline(FcmProtectionUiState(
            configured = false, maintenance = FcmMaintenanceState.STOP_PENDING)))
    }

    @Test fun `user copy distinguishes permission readiness from running protection`() {
        assertEquals("处理中…", fcmProtectionHeadline(FcmProtectionUiState(busy = true)))
        assertEquals("等待启动", fcmProtectionHeadline(FcmProtectionUiState(
            configured = true, maintenance = FcmMaintenanceState.WAITING_HOST)))
        assertTrue(fcmProtectionGuidance(FcmProtectionUiState(requiresConfirmation = true))
            .contains("请重新确认"))
        assertTrue(fcmProtectionGuidance(FcmProtectionUiState()).isEmpty())
    }

    private fun sample(service: GcmServiceSource = GcmServiceSource.unavailable()) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 2000, elapsedRealtimeMillis = 500,
        tcp = SocketSource(SourceCompleteness.COMPLETE, true),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, false),
        receiverInspectionComplete = false, receivers = emptyList(), gcmService = service)

    @Test fun `Home displays fixed reported duration and relative sample age instead of TCP clues`() {
        val observation = sample(GcmServiceSource(
            state = GcmConnectionState.REPORTED_CONNECTED, completeness = SourceCompleteness.COMPLETE,
            observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0, connectedDurationMillis = 3_661_000))
        val state = FcmProtectionUiState(latestObservation = observation)
        val expected = "🟢 01:01:01 · 更新于0秒前"
        assertEquals(expected, fcmProtectionObservationText(state, 500))
        assertEquals("🟢 01:01:01 · 更新于30秒前", fcmProtectionObservationText(state, 30_500))
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(1000))
        assertEquals("更新于$time", gmsObservationUpdateText(observation))
        assertFalse(expected.contains("TCP"))
    }

    @Test fun `Home never turns socket clues or stale service reports into a connected clock`() {
        val missingService = fcmProtectionObservationText(FcmProtectionUiState(latestObservation = sample()), 500)
        assertTrue(missingService.startsWith("🟡 状态未知"))
        assertFalse(missingService.contains("TCP"))
        val observation = sample(GcmServiceSource(
            state = GcmConnectionState.REPORTED_CONNECTED, completeness = SourceCompleteness.COMPLETE,
            observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0, connectedDurationMillis = 59_000))
        val state = FcmProtectionUiState(latestObservation = observation)
        assertTrue(fcmProtectionObservationText(state, 30_501).contains("状态已过期"))
        assertFalse(fcmProtectionObservationText(state, 30_501).contains("00:00:59"))
        assertFalse(fcmProtectionObservationText(state, 499).contains("00:00:59"))
        val historicalSource = state.copy(latestObservation = observation.copy(elapsedRealtimeMillis = 31_000))
        assertTrue(fcmProtectionObservationText(historicalSource, 31_000).contains("状态已过期"))
    }
}
