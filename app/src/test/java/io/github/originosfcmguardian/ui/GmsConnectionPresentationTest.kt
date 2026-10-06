package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsConnectionPresentationTest {
    @Test fun heartbeatTextNeverEquatesRequestWithRepair() {
        assertEquals("本次未发送心跳；当前连接或特权条件不满足。", gmsHeartbeatMessage(GmsHeartbeatStatus.NOT_SENT))
        assertEquals("已请求心跳；不代表连接已修复或通知送达。", gmsHeartbeatMessage(GmsHeartbeatStatus.REQUESTED))
        assertEquals("请求结果不明；不会自动重发。", gmsHeartbeatMessage(GmsHeartbeatStatus.UNKNOWN))
    }

    private fun sample(matched: Boolean = true, complete: Boolean = true) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 1000, elapsedRealtimeMillis = 500,
        tcp = SocketSource(if (complete) SourceCompleteness.COMPLETE else SourceCompleteness.PARTIAL, matched),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, false),
        receiverInspectionComplete = false, receivers = emptyList(),
    )

    @Test fun freshSampleReportsObservationNotDelivery() {
        assertEquals("本次观察到匹配 TCP 迹象", gmsSampleHeadline(sample(), 500, false))
        assertEquals("本次观察到匹配 TCP 迹象", gmsSampleHeadline(sample(), 30500, false))
    }

    @Test fun staleOrBackwardClockNeverKeepsCurrentConnectionClaim() {
        for (now in listOf(499L, 30501L)) {
            assertTrue(gmsSampleHeadline(sample(), now, false).contains("当前连接未知"))
        }
    }

    @Test fun networkEventInvalidatesEvenFreshPositiveSample() {
        assertTrue(gmsSampleHeadline(sample(), 500, true).contains("无法判断当前连接"))
    }

    @Test fun completeNegativeExplicitlyDoesNotAssertDisconnection() {
        assertEquals("本次可读来源未观察到匹配；不等于断线", gmsSampleHeadline(sample(matched = false), 500, false))
    }

    @Test fun partialNegativeIsUnknownWhileValidPositiveRemainsObservationOnly() {
        assertEquals("本次来源覆盖不足，无法判断", gmsSampleHeadline(sample(matched = false, complete = false), 500, false))
        assertEquals("本次观察到匹配 TCP 迹象", gmsSampleHeadline(sample(complete = false), 500, false))
    }

    @Test fun selfReportedStateIsIndependentOfSocketAndExpires() {
        val disconnected = sample().copy(gcmService = GcmServiceSource(
            state = GcmConnectionState.REPORTED_DISCONNECTED, completeness = SourceCompleteness.COMPLETE,
            observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0))
        assertEquals("GMS 自报：已断开", gmsServiceHeadline(disconnected, 500, false))
        assertEquals("GMS 自报：已连接", gmsServiceHeadline(disconnected.copy(gcmService =
            disconnected.gcmService.copy(state = GcmConnectionState.REPORTED_CONNECTED)), 500, false))
        assertTrue(gmsServiceHeadline(disconnected, 30501, false).contains("当前连接未知"))
        assertTrue(gmsServiceHeadline(disconnected, 499, false).contains("当前连接未知"))
        assertTrue(gmsServiceHeadline(disconnected, 500, true).contains("未知"))
        assertTrue(gmsServiceHeadline(sample(), 500, false).contains("未知"))
    }
}
