package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticHeartbeatStateTest {
    @Test fun defaultFlagsAndMissingResultRemainExplicitWithoutInferringARequest() {
        val report = build(GmsAutoHeartbeatUiState())
        assertEquals(DiagnosticHeartbeatState(), report.gmsAutoHeartbeat)
        assertNull(report.gmsAutoHeartbeat.lastStatus)
        val fields = heartbeatJson(report)
        assertTrue(fields.contains("\"configured\":false,\"active\":false,\"paused\":false"))
        assertTrue(fields.contains("\"lastStatus\":null"))
        assertFalse(fields.contains("REQUESTED"))
        val markdown = markdown(report)
        assertTrue(markdown.contains("心跳最近请求状态：未知或不可用（null）"))
        assertTrue(markdown.contains("不含请求次数和结果时间"))
    }

    @Test fun allHomeFlagsAreCapturedIndependentlyOfTheParentAndLaterHomeChanges() {
        var state = HomeUiState(gmsAutoHeartbeat = GmsAutoHeartbeatUiState(
            configured = true, active = false, paused = true, stopPending = true,
            durableConsent = true, resumeAuthorized = false, waitingForHost = true,
            resumeBlocked = true, requiresConfirmation = true, lastStatus = GmsHeartbeatStatus.UNKNOWN))
        val report = buildCombinedDiagnosticReport(state, environment(), 9_000)
        assertEquals(DiagnosticHeartbeatState(configured = true, active = false, paused = true,
            stopPending = true, durableConsent = true, resumeAuthorized = false, waitingForHost = true,
            resumeBlocked = true, requiresConfirmation = true, lastStatus = GmsHeartbeatStatus.UNKNOWN),
            report.gmsAutoHeartbeat)
        assertFalse(report.fcmProtection.configured)
        val json = encodeCombinedDiagnosticReport(report)
        val markdown = encodeCombinedDiagnosticMarkdown(report)
        state = state.copy(gmsAutoHeartbeat = GmsAutoHeartbeatUiState())
        assertFalse(diagnosticHeartbeatState(state.gmsAutoHeartbeat).configured)
        assertArrayEquals(json, encodeCombinedDiagnosticReport(report))
        assertArrayEquals(markdown, encodeCombinedDiagnosticMarkdown(report))
    }

    @Test fun armedWithoutResultAndHistoricalResultsDoNotBecomeDeliveryOrActivityProof() {
        val armed = build(GmsAutoHeartbeatUiState(configured = true, active = true,
            durableConsent = true, resumeAuthorized = true))
        assertTrue(armed.gmsAutoHeartbeat.active)
        assertTrue(armed.gmsAutoHeartbeat.resumeAuthorized)
        assertNull(armed.gmsAutoHeartbeat.lastStatus)
        assertTrue(heartbeatJson(armed).contains("\"active\":true"))
        assertTrue(heartbeatJson(armed).contains("\"lastStatus\":null"))

        for (status in GmsHeartbeatStatus.entries) {
            val report = build(GmsAutoHeartbeatUiState(configured = true, paused = true,
                waitingForHost = true, lastStatus = status))
            val fields = heartbeatJson(report)
            assertFalse(report.gmsAutoHeartbeat.active)
            assertFalse(report.gmsAutoHeartbeat.resumeAuthorized)
            assertTrue(fields.contains("\"lastStatus\":\"${status.name}\""))
            assertTrue(fields.contains("does not prove delivery or continuous maintenance"))
            assertTrue(markdown(report).contains("心跳最近请求状态：${status.name.replace("_", "\\_")}"))
            assertTrue(markdown(report).contains("REQUESTED 不代表消息送达"))
        }
    }

    @Test fun bothFormatsExposeOnlyFixedScalarsAndOmitUnstructuredHomeDetails() {
        val report = build(GmsAutoHeartbeatUiState(detail = "private-session-token " + "x".repeat(20_000),
            busy = true, consentVersion = 812_345, manualBlocked = false, lastStatus = GmsHeartbeatStatus.NOT_SENT))
        val fields = heartbeatJson(report)
        assertEquals(setOf("scope", "limitations", "configured", "active", "paused", "stopPending",
            "durableConsent", "resumeAuthorized", "waitingForHost", "resumeBlocked", "requiresConfirmation", "lastStatus"),
            Regex("\"([A-Za-z]+)\":").findAll(fields).map { it.groupValues[1] }.toSet())
        assertTrue(fields.contains("\"scope\":\"CURRENT_HOME_MEMORY_PROJECTION\""))
        for (output in listOf(json(report), markdown(report))) {
            assertFalse(output.contains("private-session-token"))
            assertFalse(output.contains("812345"))
        }
        val markdownFields = markdown(report).substringAfter("## 低频心跳内存快照").substringBefore("## FCM 恢复事件")
        for (output in listOf(fields, markdownFields)) {
            for (forbidden in listOf("private-session-token", "812345", "consentVersion", "manualBlocked",
                "sessionId", "bindingDigest", "callerIdentity")) {
                assertFalse(forbidden, output.contains(forbidden))
            }
        }
        assertTrue(encodeCombinedDiagnosticReport(report).size < MAX_REPORT_BYTES)
        assertTrue(encodeCombinedDiagnosticMarkdown(report).size < MAX_REPORT_BYTES)
        assertTrue(fields.length < 1_024)
    }

    private fun build(state: GmsAutoHeartbeatUiState) =
        buildCombinedDiagnosticReport(HomeUiState(gmsAutoHeartbeat = state), environment(), 9_000)
    private fun environment() = DiagnosticEnvironment(37, "17", "example", "example", "1.0.5", 105)
    private fun json(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticReport(report).decodeToString()
    private fun markdown(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticMarkdown(report).decodeToString()
    private fun heartbeatJson(report: CombinedDiagnosticReport) =
        json(report).substringAfter("\"gmsAutoHeartbeat\":{").substringBefore('}')
}
