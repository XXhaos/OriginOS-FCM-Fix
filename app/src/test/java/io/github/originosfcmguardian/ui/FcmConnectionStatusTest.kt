package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import org.junit.Assert.*
import org.junit.Test

class FcmConnectionStatusTest {
    private fun source(state: GcmConnectionState = GcmConnectionState.REPORTED_CONNECTED,
        elapsed: Long = 1_000, duration: Long? = 3_661_000) = GcmServiceSource(
        state, SourceCompleteness.COMPLETE, observedAtMillis = 2_000,
        elapsedRealtimeMillis = elapsed, userId = 0,
        connectedDurationMillis = duration.takeIf { state == GcmConnectionState.REPORTED_CONNECTED })

    private fun sample(service: GcmServiceSource = source(), elapsed: Long = 1_000,
        tcpMatched: Boolean = true, tcp6Matched: Boolean = true) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 2_000, elapsedRealtimeMillis = elapsed,
        tcp = SocketSource(SourceCompleteness.COMPLETE, tcpMatched),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, tcp6Matched),
        receiverInspectionComplete = false, receivers = emptyList(), gcmService = service)

    @Test fun durationUsesReportedHoursMinutesAndSecondsWithoutDayWrapping() {
        listOf(
            0L to "00:00:00", 59_000L to "00:00:59", 60_000L to "00:01:00",
            3_661_000L to "01:01:01", 90_000_000L to "25:00:00",
        ).forEach { (duration, expected) -> assertEquals(expected, fcmDurationClock(duration)) }
        assertEquals("00:00:59", fcmDurationClock(59_999))
        assertNull(fcmDurationClock(null))
        assertNull(fcmDurationClock(-1))
    }

    @Test fun relativeUpdateUsesElapsedAgeAtEveryUnitBoundary() {
        listOf(
            0L to "更新于0秒前", 999L to "更新于0秒前", 1_000L to "更新于1秒前",
            59_999L to "更新于59秒前", 60_000L to "更新于1分钟前",
            3_599_999L to "更新于59分钟前", 3_600_000L to "更新于1小时前",
            86_399_999L to "更新于23小时前", 86_400_000L to "更新于1天前",
        ).forEach { (age, expected) -> assertEquals(expected, fcmRelativeUpdate(1_000, 1_000 + age)) }
        assertEquals("更新时间未知", fcmRelativeUpdate(1_000, 999))
    }

    @Test fun onlyUpdateAgeTicksWhileReportedDurationRemainsFixed() {
        val observation = sample()
        listOf(1_000L, 2_000L, 30_000L, 31_000L).forEach { now ->
            val status = fcmConnectionStatus(observation, now)
            assertTrue(status.text.startsWith("🟢 01:01:01 · "))
            assertTrue(status.accessibilityLabel.contains("取样时连接时长01:01:01"))
        }
        assertEquals("🟢 01:01:01 · 更新于0秒前", fcmConnectionStatus(observation, 1_000).text)
        assertEquals("🟢 01:01:01 · 更新于30秒前", fcmConnectionStatus(observation, 31_000).text)
        assertTrue(fcmConnectionStatus(observation, 31_001).text.startsWith("⚪ 状态已过期"))
    }

    @Test fun bothOuterSampleAndServiceSourceMustBeFresh() {
        val freshOuterStaleSource = sample(source(elapsed = 1_000), elapsed = 31_001)
        val staleOuterFreshSource = sample(source(elapsed = 31_001), elapsed = 1_000)
        listOf(freshOuterStaleSource, staleOuterFreshSource).forEach { observation ->
            val status = fcmConnectionStatus(observation, 31_001)
            assertTrue(status.text.startsWith("⚪ 状态已过期"))
            assertFalse(status.text.contains("01:01:01"))
            assertFalse(status.accessibilityLabel.contains("已连接"))
        }
        assertEquals("🟢 01:01:01 · 更新于2秒前",
            fcmConnectionStatus(sample(source(elapsed = 1_000), elapsed = 2_000), 3_000).text)
    }

    @Test fun backwardElapsedTimesCannotPresentConnectedEvidence() {
        listOf(
            sample(source(elapsed = 1_000), elapsed = 2_000),
            sample(source(elapsed = 2_000), elapsed = 1_000),
        ).forEach { observation ->
            val status = fcmConnectionStatus(observation, 1_500)
            assertTrue(status.text.startsWith("⚪ 状态已过期"))
            assertFalse(status.text.contains("01:01:01"))
        }
    }

    @Test fun missingSourceTimeAndPartialSourcesRemainUnknownDespiteTcpMatches() {
        val incomplete = listOf(
            GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.COMPLETE),
            GcmServiceSource.unavailable(),
            GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.PARTIAL,
                observedAtMillis = 2_000, elapsedRealtimeMillis = 1_000, userId = 0),
            GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.COMPLETE,
                observedAtMillis = 2_000, elapsedRealtimeMillis = 1_000, userId = 0),
        )
        incomplete.forEach { service ->
            val status = fcmConnectionStatus(sample(service), 1_000)
            assertEquals("🟡 状态未知 · 更新于0秒前", status.text)
            assertFalse(status.accessibilityLabel.contains("已连接"))
        }
    }

    @Test fun disconnectedAndAbsentServiceStayRedDespiteTcpMatches() {
        listOf(
            GcmConnectionState.REPORTED_DISCONNECTED to "未连接",
            GcmConnectionState.SERVICE_ABSENT to "服务未运行",
        ).forEach { (state, label) ->
            val status = fcmConnectionStatus(sample(source(state)), 1_000)
            assertEquals("🔴 $label · 更新于0秒前", status.text)
            assertTrue(status.accessibilityLabel.contains(label))
            assertFalse(status.text.contains("🟢"))
        }
        // TCP is neither a substitute for the service report nor a required positive signal.
        assertTrue(fcmConnectionStatus(sample(tcpMatched = false, tcp6Matched = false), 1_000).text.startsWith("🟢"))
    }

    @Test fun networkChangesAndUnavailableTrackingNeverReuseAConnectedClock() {
        val observation = sample()
        val changed = fcmConnectionStatus(observation, 1_000, networkInvalid = true)
        val untracked = fcmConnectionStatus(observation, 1_000, networkTrackingUnavailable = true)
        assertEquals("⚪ 网络已变化 · 更新于0秒前", changed.text)
        assertEquals("⚪ 网络待确认 · 更新于0秒前", untracked.text)
        listOf(changed, untracked).forEach { status ->
            assertFalse(status.text.contains("🟢"))
            assertFalse(status.accessibilityLabel.contains("已连接"))
        }
    }

    @Test fun missingReadAndFailedReadAreDistinctFromConnectedOrDisconnected() {
        assertEquals(FcmConnectionStatus("⚪ 尚未读取", "FCM 尚未读取"), fcmConnectionStatus(null, 1_000))
        assertEquals(FcmConnectionStatus("🟡 无法读取", "FCM 当前状态无法读取"),
            fcmConnectionStatus(GmsConnectionObservation.Unknown(1, GmsUnknown.TIMED_OUT), 1_000))
    }

    @Test fun accessibilityDescribesSampleDurationWithoutClaimingMessageDelivery() {
        val known = fcmConnectionStatus(sample(), 1_000)
        val unknownDuration = fcmConnectionStatus(sample(source(duration = null)), 1_000)
        assertEquals("FCM 已连接，取样时连接时长01:01:01，取样更新于0秒前", known.accessibilityLabel)
        assertEquals("🟢 时长未知 · 更新于0秒前", unknownDuration.text)
        assertTrue(unknownDuration.accessibilityLabel.contains("取样时连接时长未知"))
        listOf(known, unknownDuration).forEach { status ->
            listOf("消息", "送达", "保证", "健康", "成功").forEach { claim ->
                assertFalse(status.text.contains(claim))
                assertFalse(status.accessibilityLabel.contains(claim))
            }
        }
    }
}
