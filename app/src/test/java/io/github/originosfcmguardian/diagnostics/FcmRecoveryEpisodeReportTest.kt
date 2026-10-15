package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.gms.FcmRecoveryReason
import io.github.originosfcmguardian.core.gms.FcmRecoveryStatus
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.*
import org.junit.Test

class FcmRecoveryEpisodeReportTest {
    private fun episode(sequence: Long = 1) = FcmRecoveryEpisode(
        sequence, 123, 10, 100, 20, FcmRecoveryEpisodeKind.SERVICE_ABSENT, 200, 40,
        requestAtMillis = 150, requestElapsedMillis = 30,
        requestStatus = FcmRecoveryStatus.REQUESTED, reason = FcmRecoveryReason.COMMAND_ACCEPTED,
        endedAtMillis = 200, endedElapsedMillis = 40,
        outcome = FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST,
        requestCorrelationId = java.util.UUID(0, sequence).toString(),
        resultAtMillis = 160, resultElapsedMillis = 35,
    )
    private fun report(snapshot: FcmRecoveryEpisodeSnapshot) = buildCombinedDiagnosticReport(
        HomeUiState(fcmRecovery = FcmRecoveryUiState(episodesSnapshot = snapshot)),
        DiagnosticEnvironment(37, "17", "vivo", "fixture", "trial", 72), 9000)
    private fun json(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)

    @Test fun pendingStartAndReturnedResultAreSeparateAndCorrelationNeverExports() {
        val correlation = java.util.UUID(0, 1).toString()
        val pending = episode().copy(requestStatus = null, reason = null, requestPending = true,
            resultAtMillis = null, resultElapsedMillis = null,
            endedAtMillis = null, endedElapsedMillis = null, outcome = FcmRecoveryEpisodeOutcome.OBSERVING)
        val pendingReport = report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(pending)))
        val pendingJson = json(pendingReport)
        assertTrue(pendingJson.contains("\"requestAtEpochMillis\":150"))
        assertTrue(pendingJson.contains("\"requestPending\":true"))
        assertTrue(pendingJson.contains("\"requestStatus\":null"))
        assertTrue(pendingJson.contains("\"resultAtEpochMillis\":null"))
        assertFalse(pendingJson.contains(correlation))
        assertFalse(pendingJson.contains("requestCorrelationId"))
        val resultReport = report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(episode())))
        val resultJson = json(resultReport)
        assertTrue(resultJson.contains("\"requestPending\":false"))
        assertTrue(resultJson.contains("\"resultAtEpochMillis\":160"))
        assertTrue(resultJson.contains("\"resultElapsedMillis\":35"))
        assertFalse(resultJson.contains(correlation))
        val markdown = encodeCombinedDiagnosticMarkdown(resultReport).toString(Charsets.UTF_8)
        assertTrue(markdown.contains("请求返回时间（Unix 毫秒）：160"))
        assertFalse(markdown.contains(correlation))
        val historical = episode().copy(requestCorrelationId = null, resultAtMillis = null, resultElapsedMillis = null)
        assertTrue(json(report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(historical))))
            .contains("\"resultAtEpochMillis\":null"))
    }

    @Test fun projectionCopiesMutableListAndExportsOnlyTheLatestEight() {
        val original = (1L..32L).map(::episode).toMutableList()
        val projected = report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE,
            original, evictedEpisodes = 7, writeFailures = 0))
        original.clear()
        assertEquals(32, projected.fcmRecoveryEpisodes.episodes.size)
        assertEquals(1L, projected.fcmRecoveryEpisodes.episodes.first().sequence)
        val encoded = json(projected)
        assertEquals(8, Regex("\"sequence\":").findAll(encoded).count())
        assertTrue(encoded.contains("\"sequence\":25,"))
        assertFalse(encoded.contains("\"sequence\":24,"))
        assertTrue(encoded.contains("\"retainedEpisodeCount\":32"))
        assertTrue(encoded.contains("\"omittedFromPreview\":24"))
        assertTrue(encoded.contains("\"evictedEpisodes\":7"))
        assertTrue(encoded.contains("\"previewTruncated\":true"))
        assertTrue(encodeCombinedDiagnosticMarkdown(projected).toString(Charsets.UTF_8)
            .contains("未在报告预览显示的片段数：24"))
    }

    @Test fun requestedOnlyAndLaterConnectionNeverBecomeRepairOrDeliverySuccess() {
        val requested = episode().copy(endedAtMillis = null, endedElapsedMillis = null,
            outcome = FcmRecoveryEpisodeOutcome.OBSERVING)
        val observing = json(report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(requested))))
        assertTrue(observing.contains("\"requestStatus\":\"REQUESTED\""))
        assertTrue(observing.contains("\"outcome\":\"OBSERVING\""))
        assertTrue(observing.contains("\"endedAtEpochMillis\":null"))
        val connected = json(report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(episode()))))
        assertTrue(connected.contains("\"outcome\":\"CONNECTION_AFTER_REQUEST\""))
        assertTrue(connected.contains("not unique recovery causality or notification delivery"))
        assertTrue(connected.contains("\"fcmToNotificationCausality\":\"UNKNOWN\""))
        assertFalse(connected.contains("\"repaired\":true"))
        assertFalse(connected.contains("\"delivered\":true"))
        val markdown = encodeCombinedDiagnosticMarkdown(report(
            FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(episode())))).toString(Charsets.UTF_8)
        assertTrue(markdown.contains("请求接受不等于恢复成功"))
        assertTrue(markdown.contains("不证明唯一原因或通知送达"))
    }

    @Test fun unavailableSourcesNeverDonateRetainedEpisodesOrClaimEmptyHistory() {
        val expected = mapOf(FcmEpisodeAvailability.NOT_LOADED to DiagnosticSourceAvailability.LOADING,
            FcmEpisodeAvailability.CORRUPT to DiagnosticSourceAvailability.CORRUPT,
            FcmEpisodeAvailability.UNAVAILABLE to DiagnosticSourceAvailability.UNAVAILABLE)
        expected.forEach { (availability, source) ->
            val projected = report(FcmRecoveryEpisodeSnapshot(availability, listOf(episode()), writeFailures = 1))
            assertEquals(source, projected.sourceAvailability["fcmRecoveryEpisodes"])
            assertTrue(projected.fcmRecoveryEpisodes.episodes.isEmpty())
            val encoded = json(projected)
            assertTrue(encoded.contains("\"retainedEpisodeCount\":null"))
            assertTrue(encoded.contains("\"writeFailures\":1"))
            assertFalse(encoded.contains("\"sequence\":"))
            assertTrue(encoded.contains("no incident record is not proof of uninterrupted connection"))
        }
        assertEquals(DiagnosticSourceAvailability.EMPTY,
            report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE)).sourceAvailability["fcmRecoveryEpisodes"])
        assertEquals(DiagnosticSourceAvailability.PARTIAL,
            report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, writeFailures = 1))
                .sourceAvailability["fcmRecoveryEpisodes"])
    }

    @Test fun droppedDiagnosticUpdatesStayVisibleWithoutImplyingAuthorityFailureOrCompleteCoverage() {
        val projected = report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE,
            listOf(episode()), droppedUpdates = 3))
        assertEquals(DiagnosticSourceAvailability.PARTIAL, projected.sourceAvailability["fcmRecoveryEpisodes"])
        assertTrue(json(projected).contains("\"droppedUpdates\":3"))
        assertFalse(projected.fcmRecoveryTrial.storageBlocked)
        assertTrue(encodeCombinedDiagnosticMarkdown(projected).toString(Charsets.UTF_8)
            .contains("片段更新丢弃数：3"))
    }

    @Test fun presentDisconnectedBlockAndCoverageGapArePreservedWithoutAuthorityOrPrivateData() {
        val disconnected = FcmRecoveryEpisode(1, 123, 10, 100, 20,
            FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED, 200, 40,
            endedAtMillis = 200, endedElapsedMillis = 40, outcome = FcmRecoveryEpisodeOutcome.COVERAGE_GAP,
            timeoutObserved = true, lastBlock = FcmRecoveryEpisodeBlock.PRESENT_DISCONNECTED_NOT_ADMITTED)
        val encoded = json(report(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(disconnected))))
        listOf("PRESENT_DISCONNECTED", "COVERAGE_GAP", "PRESENT_DISCONNECTED_NOT_ADMITTED",
            "\"timeoutObserved\":true", "\"requestStatus\":null", "\"removedCapabilities\"")
            .forEach { assertTrue(it, encoded.contains(it)) }
        listOf("\"binding\"", "\"bootId\"", "\"signerSha256\"", "\"token\"", "\"endpoint\"",
            "\"authorized\"", "\"consentId\"").forEach { assertFalse(it, encoded.contains(it)) }
        assertTrue(encoded.contains("\"schema\":\"guardian.combined-diagnostic.v13\""))
    }
}
