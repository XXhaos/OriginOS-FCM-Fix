package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GcmServiceSource
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.ReconnectReceiver
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.pushactivation.PushActivationReason
import io.github.originosfcmguardian.pushactivation.PushActivationUiState
import io.github.originosfcmguardian.ui.GmsConnectionUiState
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFcmConnectionReadTest {
    @Test fun neverReadAndAutomaticOffAreExplicitRatherThanReportedDisconnected() {
        val report = build(HomeUiState())
        assertFalse(report.fcmConnectionRead.attempted)
        assertFalse(report.fcmConnectionRead.busy)
        assertFalse(report.fcmConnectionRead.networkKnown)
        assertNull(report.fcmConnectionRead.readCompletedAtElapsedMillis)
        assertEquals(DiagnosticFcmObservationKind.NONE, report.fcmConnectionRead.foreground.kind)
        assertEquals(DiagnosticFcmObservationKind.NONE, report.fcmConnectionRead.automatic.kind)
        assertNull(report.fcmConnectionRead.foreground.serviceState)
        assertFalse(report.fcmProtection.configured)
        val json = json(report)
        assertTrue(json.contains("\"scope\":\"CURRENT_HOME_UI_MEMORY_ONLY\",\"attempted\":false,\"busy\":false"))
        assertTrue(json.contains("\"foreground\":{\"kind\":\"NONE\",\"unknownReason\":null"))
        assertTrue(json.contains("\"automatic\":{\"kind\":\"NONE\",\"unknownReason\":null"))
        val markdown = markdown(report)
        assertTrue(markdown.contains("前台读取已尝试：否（false）"))
        assertTrue(markdown.contains("观察类型：NONE"))
        assertTrue(markdown.contains("统一 FCM 保护授权：否（false）"))
        assertFalse(json.contains("REPORTED_DISCONNECTED"))
    }

    @Test fun connectedWithoutDurationKeepsServiceEvidenceAndForegroundNetworkFences() {
        val report = build(HomeUiState(gmsConnection = GmsConnectionUiState(
            readAttempted = true, busy = true, observation = sample(duration = null),
            readCompletedAtElapsedMillis = 120, networkKey = "private-network-handle",
            networkChanged = true, networkTrackingAvailable = true)))
        val snapshot = report.fcmConnectionRead
        assertTrue(snapshot.attempted)
        assertTrue(snapshot.busy)
        assertEquals(120L, snapshot.readCompletedAtElapsedMillis)
        assertTrue(snapshot.networkKnown)
        assertTrue(snapshot.networkChanged)
        assertTrue(snapshot.networkTrackingAvailable)
        with(snapshot.foreground) {
            assertEquals(DiagnosticFcmObservationKind.SAMPLE, kind)
            assertEquals(GcmConnectionState.REPORTED_CONNECTED, serviceState)
            assertEquals(SourceCompleteness.COMPLETE, serviceCompleteness)
            assertNull(connectedDurationMillis)
            assertNull(unknownReason)
            assertEquals(263635035L, gmsVersionCode)
            assertEquals(8_100L, sampleObservedAtEpochMillis)
            assertEquals(80L, sampleElapsedRealtimeMillis)
            assertEquals(8_120L, serviceObservedAtEpochMillis)
            assertEquals(100L, serviceElapsedRealtimeMillis)
        }
        val json = json(report)
        assertTrue(json.contains("\"serviceState\":\"REPORTED_CONNECTED\",\"serviceCompleteness\":\"COMPLETE\""))
        assertTrue(json.contains("\"serviceElapsedRealtimeMillis\":100,\"connectedDurationMillis\":null"))
        val markdown = markdown(report)
        assertTrue(markdown.contains("服务报告状态：REPORTED\\_CONNECTED"))
        assertTrue(markdown.contains("GMS 报告的连接时长（ms）：未知或不可用（null）"))
        assertFalse(json.contains("private-network-handle"))
    }

    @Test fun failedForegroundDoesNotReplaceOrMasqueradeAsAutomaticObservation() {
        val report = build(HomeUiState(
            gmsConnection = GmsConnectionUiState(readAttempted = true,
                observation = GmsConnectionObservation.Unknown(71, GmsUnknown.TIMED_OUT),
                readCompletedAtElapsedMillis = 150, networkKey = "unknown"),
            fcmProtection = FcmProtectionUiState(configured = false, latestObservation = sample(duration = 61_000))))
        assertFalse(report.fcmConnectionRead.networkKnown)
        with(report.fcmConnectionRead.foreground) {
            assertEquals(DiagnosticFcmObservationKind.UNKNOWN, kind)
            assertEquals(GmsUnknown.TIMED_OUT, unknownReason)
            assertNull(serviceState)
            assertNull(gmsVersionCode)
            assertNull(connectedDurationMillis)
        }
        assertEquals(61_000L, report.fcmConnectionRead.automatic.connectedDurationMillis)
        assertNull(report.fcmProtection.latestObservation)
        val json = json(report)
        assertTrue(json.contains("\"foreground\":{\"kind\":\"UNKNOWN\",\"unknownReason\":\"TIMED_OUT\""))
        assertTrue(json.contains("\"automatic\":{\"kind\":\"SAMPLE\",\"unknownReason\":null"))
        assertTrue(json.contains("\"connectedDurationMillis\":61000"))
        assertTrue(markdown(report).contains("读取未知原因：TIMED\\_OUT"))
        assertFalse(report.fcmProtection.configured)
    }

    @Test fun unavailableAutomaticSourceAndForegroundTrackingRemainUnknownWithoutAFalseConnection() {
        val report = build(HomeUiState(
            gmsConnection = GmsConnectionUiState(readAttempted = true, observation = sample().copy(
                gcmService = GcmServiceSource(GcmConnectionState.UNKNOWN, SourceCompleteness.DENIED,
                    observedAtMillis = 8_120, elapsedRealtimeMillis = 100, userId = 0))),
            fcmProtection = FcmProtectionUiState(latestObservation =
                GmsConnectionObservation.Unknown(72, GmsUnknown.BRIDGE_UNAVAILABLE))))
        assertFalse(report.fcmConnectionRead.networkKnown)
        assertFalse(report.fcmConnectionRead.networkTrackingAvailable)
        assertEquals(SourceCompleteness.DENIED, report.fcmConnectionRead.foreground.serviceCompleteness)
        assertEquals(GmsUnknown.BRIDGE_UNAVAILABLE, report.fcmConnectionRead.automatic.unknownReason)
        assertNull(report.fcmConnectionRead.foreground.connectedDurationMillis)
        assertTrue(json(report).contains("\"serviceState\":\"UNKNOWN\",\"serviceCompleteness\":\"DENIED\""))
    }

    @Test fun bothFormatsExcludeIdentityNetworkAndUnstructuredTextAndKeepTheCapturedSnapshot() {
        var state = HomeUiState(
            gmsConnection = GmsConnectionUiState(readAttempted = true, observation = sample(),
                networkKey = "network-handle-123456789:address-secret",
                networkBackground = "private raw dump token=secret-fcm-token 192.0.2.42",
                automaticObservationAtReadCompletion = sample()),
            fcmProtection = FcmProtectionUiState(latestObservation = sample(duration = null),
                detail = "private exception message"))
        val report = build(state)
        val encoded = encodeCombinedDiagnosticReport(report)
        val encodedMarkdown = encodeCombinedDiagnosticMarkdown(report)
        state = state.copy(gmsConnection = GmsConnectionUiState(), fcmProtection = FcmProtectionUiState())
        assertEquals(DiagnosticFcmObservationKind.NONE, diagnosticFcmConnectionRead(state).foreground.kind)
        assertArrayEquals(encoded, encodeCombinedDiagnosticReport(report))
        assertArrayEquals(encodedMarkdown, encodeCombinedDiagnosticMarkdown(report))
        val outputs = listOf(encoded.decodeToString(), encodedMarkdown.decodeToString())
        outputs.forEach { output ->
            listOf("12299", "87123456789", "89123456789", "741258963", "ab".repeat(32),
                "11223344-5566-7788-99aa-bbccddeeff00", "org.example.private.Receiver", "private.permission.READ",
                "network-handle-123456789", "address-secret", "secret-fcm-token", "192.0.2.42",
                "private raw dump", "private exception message", "signerSha256", "bootId", "networkKey",
                "requestId", "firstInstallTime", "lastUpdateTime")
                .forEach {
                    assertFalse(it, output.contains(it))
                    assertFalse(it, output.replace("\\", "").contains(it))
                }
        }
        assertTrue(encoded.size < MAX_REPORT_BYTES)
        assertTrue(encodedMarkdown.size < MAX_REPORT_BYTES)
    }

    @Test fun activationBlockedReasonUsesOnlyItsFixedEnumInBothFormats() {
        val report = build(HomeUiState(pushActivation = PushActivationUiState(blocked = true,
            reason = PushActivationReason.UNKNOWN_RESULT)))
        assertTrue(json(report).contains("\"storageFailed\":false,\"reason\":\"UNKNOWN_RESULT\""))
        assertTrue(markdown(report).contains("统一开启请求原因：UNKNOWN\\_RESULT"))
        assertTrue(json(build(HomeUiState())).contains("\"storageFailed\":false,\"reason\":\"NONE\""))
    }

    private fun sample(duration: Long? = 12_000) = GmsConnectionObservation.Sample(
        requestId = 741258963, uid = 12299, versionCode = 263635035,
        firstInstallTime = 87123456789, lastUpdateTime = 89123456789,
        signerSha256 = "ab".repeat(32), bootId = "11223344-5566-7788-99aa-bbccddeeff00",
        observedAtMillis = 8_100, elapsedRealtimeMillis = 80,
        tcp = SocketSource(SourceCompleteness.COMPLETE, true),
        tcp6 = SocketSource(SourceCompleteness.COMPLETE, false), receiverInspectionComplete = true,
        receivers = listOf(ReconnectReceiver("org.example.private.Receiver", true, true, "private.permission.READ")),
        gcmService = GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE,
            observedAtMillis = 8_120, elapsedRealtimeMillis = 100, userId = 0,
            connectedDurationMillis = duration))

    private fun build(state: HomeUiState) = buildCombinedDiagnosticReport(state,
        DiagnosticEnvironment(37, "17", "example", "example", "1.0.3", 102), 9_000)
    private fun json(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticReport(report).decodeToString()
    private fun markdown(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticMarkdown(report).decodeToString()
}
