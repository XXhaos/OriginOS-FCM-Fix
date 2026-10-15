package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.daemon.*
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.*
import org.junit.Test

class DaemonDiagnosticsReportTest {
    private val origin = 1_700_000_000_000L
    private fun session(id: String = "private-session") = DaemonDiagnosticsSession(id, "private-boot", 123, 456,
        85, origin, 1000, origin + 300_000, 301_000, DaemonDiagnosticsState.READY)
    private fun sample(index: Int, id: String = "private-session") = DaemonDiagnosticsSample(id,
        origin + index * 300_000L, 1000 + index * 300_000L, index * 3000L, 20_000 + index.toLong(),
        threads = 5, batteryPercent = 80, chargingStatus = "DISCHARGING", interactive = false)
    private fun snapshot(samples: List<DaemonDiagnosticsSample>) = DaemonDiagnosticsSnapshot(
        DaemonDiagnosticsAvailability.AVAILABLE, origin + 1_000_000, 1_001_000, "private-session", 7,
        listOf(session()), samples = samples)
    private fun report(snapshot: DaemonDiagnosticsSnapshot) = buildCombinedDiagnosticReport(HomeUiState(),
        DiagnosticEnvironment(37, "17", "vivo", "fixture", "0.1.0", 85), origin + 2_000_000)
        .copy(daemonDiagnostics = snapshot)

    @Test fun sameSessionCpuAndRssCoverTheFullHistoryNotOnlyPreview() {
        val samples = (0..100).map(::sample)
        val summary = summarizeDaemonSamples(session(), samples)
        assertEquals(101, summary.sampleCount)
        assertEquals(300_000L, summary.cpuDeltaMillis)
        assertEquals(30_000_000L, summary.cpuMeasuredElapsedMillis)
        assertEquals(1.0, summary.cpuSingleCorePercent!!, 0.00001)
        assertEquals(100L, summary.rssDeltaKiB)
        val json = encodeCombinedDiagnosticReport(report(snapshot(samples))).decodeToString()
        assertTrue(json.contains("\"sampleCount\":101"))
        assertTrue(json.contains("\"omittedRawSamples\":85"))
        assertFalse(json.contains("private-session")); assertFalse(json.contains("private-boot"))
        assertTrue(json.contains("\"collectionScope\":\"GUARDIAN_PRIVILEGED_DAEMON_HISTORY\""))
        assertTrue(json.contains("\"daemonResourceAvailability\":\"AVAILABLE\""))
        assertTrue(json.contains("\"collectionScope\":\"GUARDIAN_APP_PROCESS_HISTORY\""))
        assertTrue(json.contains("\"retainedSampleCount\":101"))
    }

    @Test fun cpuNeverCrossesHostSessionsOrBootsAndSingleSampleIsUnknown() {
        val old = sample(0)
        val other = sample(1, "other-session").copy(cpuTimeMillis = 999_999)
        val result = summarizeDaemonSamples(session(), listOf(old, other))
        assertEquals(1, result.sampleCount); assertNull(result.cpuDeltaMillis); assertNull(result.cpuSingleCorePercent)
    }

    @Test fun rollbackClockAndCounterCannotCreateFalseCpuTrend() {
        val first = sample(0)
        val clockJump = sample(1).copy(atMillis = origin - 1)
        val result = summarizeDaemonSamples(session(), listOf(first, clockJump))
        assertEquals(1, result.invalidClockIntervals); assertNull(result.cpuDeltaMillis); assertNull(result.rssDeltaKiB)
        val cpuReset = summarizeDaemonSamples(session(), listOf(sample(1), sample(2).copy(cpuTimeMillis = 0)))
        assertNull(cpuReset.cpuDeltaMillis)
    }

    @Test fun gapsAreReportedWithoutInventingKillsOrContinuousActivity() {
        val value = snapshot(listOf(sample(0), sample(4))).copy(sessions = listOf(session().copy(
            state = DaemonDiagnosticsState.UNKNOWN_END)), unknownIntervals = 1)
        val json = encodeCombinedDiagnosticReport(report(value)).decodeToString()
        assertTrue(json.contains("\"gapIntervals\":1"))
        assertTrue(json.contains("\"exitReason\":\"UNKNOWN\""))
        assertTrue(json.contains("\"normalEndedAtEpochMillis\":null"))
        assertTrue(json.contains("\"continuousProtectionProven\":false"))
        assertFalse(json.contains("\"exitReason\":\"LOW_MEMORY\""))
    }

    @Test fun unavailableSnapshotDoesNotLeakStaleSamplesOrClaimZeroFailures() {
        val value = snapshot(listOf(sample(0))).copy(availability = DaemonDiagnosticsAvailability.UNAVAILABLE,
            failedOperations = 3)
        val json = StringBuilder().apply { appendDaemonDiagnosticsJson(value) }.toString()
        assertTrue(json.contains("\"retainedSampleCount\":null"))
        assertTrue(json.contains("\"sessions\":[]")); assertTrue(json.contains("\"recentSamples\":[]"))
        assertTrue(json.contains("\"failedOperations\":3"))
        val markdown = encodeCombinedDiagnosticMarkdown(report(value)).decodeToString()
        assertTrue(markdown.contains("UNAVAILABLE")); assertFalse(markdown.contains("### 核心会话"))
    }

    @Test fun multiDayBucketsAreBoundedAndDiscardedBucketsAreVisible() {
        val samples = (0 until 2016).map { sample(it).copy(atMillis = origin + it * 3_600_000L,
            elapsedMillis = 1000 + it * 3_600_000L) }
        val value = snapshot(samples)
        val json = StringBuilder().apply { appendDaemonDiagnosticsJson(value) }.toString()
        assertEquals(168, Regex("\"hourEpochMillis\":").findAll(json).count())
        assertEquals(16, Regex("\"batteryPercent\":").findAll(json).count())
        assertTrue(json.contains("\"omittedTimeBuckets\":1848"))
        assertTrue(json.contains("\"sampleCount\":2016"))
        assertTrue(encodeCombinedDiagnosticReport(report(value)).size < MAX_REPORT_BYTES)
        val markdown = encodeCombinedDiagnosticMarkdown(report(value))
        assertTrue(markdown.size < MAX_REPORT_BYTES)
        assertTrue(markdown.decodeToString().contains("未展示小时段：1848"))
    }

    @Test fun actualEncodedFixtureIsAvailableForOfflineAnalyzerContractCheck() {
        val value = snapshot((0..100).map(::sample))
        val output = java.io.File("build/reports/guardian-diagnostics/v85-synthetic.json")
        val directory = requireNotNull(output.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        output.writeBytes(encodeCombinedDiagnosticReport(report(value)))
        assertTrue(output.length() in 1..MAX_REPORT_BYTES.toLong())
    }
}
