package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.core.gms.GmsObservationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsConnectionPresentationTest {
    @Test fun heartbeatTextNeverEquatesRequestWithRepair() {
        assertEquals("心跳未发送，请检查连接与 Shizuku 授权。", gmsHeartbeatMessage(GmsHeartbeatStatus.NOT_SENT))
        assertEquals("已请求心跳。", gmsHeartbeatMessage(GmsHeartbeatStatus.REQUESTED))
        assertEquals("无法确认请求结果，请重新读取连接状态。", gmsHeartbeatMessage(GmsHeartbeatStatus.UNKNOWN))
    }

    private fun sample(matched: Boolean = true, complete: Boolean = true) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 1000, elapsedRealtimeMillis = 500,
        tcp = SocketSource(if (complete) SourceCompleteness.COMPLETE else SourceCompleteness.PARTIAL, matched),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, false),
        receiverInspectionComplete = false, receivers = emptyList(),
    )

    @Test fun socketCluesNeverClaimKnownFcmConnection() {
        assertEquals("FCM 连接状态未知", gmsSampleHeadline(sample(), 500, false))
        assertEquals("FCM 连接状态未知", gmsSampleHeadline(sample(), 30500, false))
    }

    @Test fun staleOrBackwardClockNeverKeepsCurrentConnectionClaim() {
        for (now in listOf(499L, 30501L)) {
            assertEquals("状态已过期，请重新读取。", gmsSampleHeadline(sample(), now, false))
        }
    }

    @Test fun networkEventInvalidatesEvenFreshPositiveSample() {
        assertEquals("网络已变化，请重新读取。", gmsSampleHeadline(sample(), 500, true))
    }

    @Test fun completeNegativeExplicitlyDoesNotAssertDisconnection() {
        assertEquals("FCM 连接状态未知", gmsSampleHeadline(sample(matched = false), 500, false))
    }

    @Test fun partialSocketEvidenceKeepsConnectionUnknown() {
        assertEquals("FCM 连接状态未知", gmsSampleHeadline(sample(matched = false, complete = false), 500, false))
        assertEquals("FCM 连接状态未知", gmsSampleHeadline(sample(complete = false), 500, false))
    }

    @Test fun selfReportedStateIsIndependentOfSocketAndExpires() {
        val disconnected = sample().copy(gcmService = GcmServiceSource(
            state = GcmConnectionState.REPORTED_DISCONNECTED, completeness = SourceCompleteness.COMPLETE,
            observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0))
        assertEquals("FCM 已断开", gmsServiceHeadline(disconnected, 500, false))
        assertEquals("FCM 已连接 · 连接时长未知", gmsServiceHeadline(disconnected.copy(gcmService =
            disconnected.gcmService.copy(state = GcmConnectionState.REPORTED_CONNECTED)), 500, false))
        assertEquals("状态已过期，请重新读取。", gmsServiceHeadline(disconnected, 30501, false))
        assertEquals("状态已过期，请重新读取。", gmsServiceHeadline(disconnected, 499, false))
        assertEquals("网络已变化，请重新读取。", gmsServiceHeadline(disconnected, 500, true))
        assertTrue(gmsServiceHeadline(sample(), 500, false).contains("未知"))
    }

    private fun connected(durationMillis: Long? = 0) = sample().copy(gcmService = GcmServiceSource(
        state = GcmConnectionState.REPORTED_CONNECTED, completeness = SourceCompleteness.COMPLETE,
        observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0,
        connectedDurationMillis = durationMillis))

    @Test fun reportedDurationFormatsZeroMinuteHourAndMultiDayBoundaries() {
        for ((seconds, expected) in listOf(
            0L to "0秒", 47L to "47秒", 59L to "59秒", 60L to "1分0秒", 3287L to "54分47秒",
            3600L to "1小时0分0秒", (2 * 86400L + 3661) to "49小时1分1秒")) {
            assertEquals("FCM 已连接 · 连接时长 $expected", gmsServiceHeadline(connected(seconds * 1000), 500, false))
        }
        assertEquals("连接时长 59秒", gmsConnectedDurationText(59_999))
    }

    @Test fun reportedDurationStaysFixedUntilAnotherRead() {
        val observation = connected(59_000)
        assertEquals(gmsServiceHeadline(observation, 500, false), gmsServiceHeadline(observation, 30_500, false))
        assertEquals("状态已过期，请重新读取。", gmsServiceHeadline(observation, 30_501, false))
        assertTrue(!gmsServiceHeadline(observation, 30_501, false).contains("连接时长"))
    }

    @Test fun olderV2ConnectedSampleHasUnknownDuration() {
        val raw = "2|1|S|10208|1|1|1|${"a".repeat(64)}|11111111-2222-3333-4444-555555555555|1000|500|COMPLETE|1|COMPLETE|0|0||REPORTED_CONNECTED|COMPLETE|1000|500|0"
        val observation = GmsObservationCodec.decode(raw, 1) as GmsConnectionObservation.Sample
        assertEquals("FCM 已连接 · 连接时长未知", gmsServiceHeadline(observation, 500, false))
    }

    @Test fun durationNeverResolvesMissingInvalidOrHistoricalServiceEvidence() {
        assertEquals("FCM 已连接 · 连接时长未知", gmsServiceHeadline(connected(null), 500, false))
        assertEquals("FCM 连接状态未知", gmsServiceHeadline(sample(), 500, false))
        val incomplete = connected().copy(gcmService = GcmServiceSource(
            state = GcmConnectionState.UNKNOWN, completeness = SourceCompleteness.PARTIAL,
            observedAtMillis = 1000, elapsedRealtimeMillis = 500, userId = 0))
        assertEquals("FCM 连接状态未知", gmsServiceHeadline(incomplete, 500, false))
        val historicalService = connected().copy(elapsedRealtimeMillis = 31_000)
        assertEquals("状态已过期，请重新读取。", gmsServiceHeadline(historicalService, 31_000, false))
        val historicalSample = connected().copy(elapsedRealtimeMillis = 0,
            gcmService = connected().gcmService.copy(elapsedRealtimeMillis = 31_000))
        assertEquals("状态已过期，请重新读取。", gmsServiceHeadline(historicalSample, 31_000, false))
        assertTrue(!gmsServiceHeadline(connected(), 500, true).contains("连接时长"))
        assertTrue(!gmsServiceHeadline(connected(), 499, false).contains("连接时长"))
    }

    @Test fun disconnectedAndAbsentServiceNeverShowConnectedDuration() {
        for ((state, expected) in listOf(
            GcmConnectionState.REPORTED_DISCONNECTED to "FCM 已断开",
            GcmConnectionState.SERVICE_ABSENT to "FCM 服务未运行")) {
            val observation = connected().copy(gcmService = connected().gcmService.copy(
                state = state, connectedDurationMillis = null))
            assertEquals(expected, gmsServiceHeadline(observation, 500, false))
        }
    }
}
