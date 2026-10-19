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
    private fun metrics() = DaemonCycleMetrics(startedAtMillis = origin, startedElapsedMillis = 1000,
        lastCheckpointAtMillis = origin + 300_000, lastCheckpointElapsedMillis = 301_000,
        stopped = DaemonCycleStageMetrics(1, 120, 120, DaemonCycleOutcomes(checked = 1)),
        fcm = DaemonCycleStageMetrics(2, 350, 250, DaemonCycleOutcomes(waiting = 1, pending = 1)))
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

    @Test fun sampledContextCountsMixedAndUnknownPointsWithoutFillingGaps() {
        val samples = listOf(sample(4).copy(batteryPercent = 78, chargingStatus = "CHARGING", interactive = true, deviceIdle = false),
            sample(0).copy(batteryPercent = null, chargingStatus = null, interactive = null),
            sample(1).copy(batteryPercent = 80, deviceIdle = true))
        val summary = summarizeDaemonSamples(session(), samples + sample(2, "other-session"))
        val c = summary.sampledContext
        assertEquals(3, summary.sampleCount); assertEquals(1, summary.gapIntervals)
        assertEquals(2, c.batteryKnownCount); assertEquals(1, c.batteryUnknownCount)
        assertNull(c.batteryFirstPercent); assertEquals(78, c.batteryLastPercent)
        assertEquals(78, c.batteryMinPercent); assertEquals(80, c.batteryMaxPercent)
        assertEquals(mapOf("CHARGING" to 1, "FULL" to 0, "DISCHARGING" to 1, "NOT_CHARGING" to 0, "UNKNOWN" to 1), c.chargingStatusCounts)
        assertEquals(DaemonSampledStates(1, 1, 1), c.interactive)
        assertEquals(DaemonSampledStates(1, 1, 1), c.deviceIdle)
        val json = StringBuilder().apply { appendDaemonDiagnosticsJson(snapshot(samples)) }.toString()
        assertTrue(json.contains("\"sampledContextScope\":\"RETAINED_SAMPLE_POINTS_NOT_CONTINUOUS_STATE_OR_GUARDIAN_ENERGY\""))
        assertTrue(json.contains("\"batteryFirstPercent\":null"))
        listOf("batteryDelta", "dischargeRate", "energyMillis", "screenOffDuration", "guardianEnergy").forEach { assertFalse(json.contains(it)) }
        val markdown = encodeCombinedDiagnosticMarkdown(report(snapshot(samples))).decodeToString()
        assertTrue(markdown.contains("不推断空档内连续状态"))
        assertTrue(markdown.contains("电量首/末/最小/最大%=未知/78/78/80"))
    }

    @Test fun emptyAndUnknownEndpointsStayUnknownIncludingWithinHourlyBuckets() {
        val empty = summarizeDaemonSamples(session(), emptyList()).sampledContext
        assertEquals(0, empty.batteryKnownCount); assertEquals(0, empty.batteryUnknownCount)
        assertNull(empty.batteryFirstPercent); assertNull(empty.batteryLastPercent); assertNull(empty.batteryMinPercent)
        val unknown = sample(0).copy(batteryPercent = null, chargingStatus = "UNKNOWN", interactive = null)
        val c = summarizeDaemonSamples(session(), listOf(unknown)).sampledContext
        assertEquals(1, c.batteryUnknownCount); assertNull(c.batteryLastPercent); assertNull(c.batteryMaxPercent)
        assertEquals(1, c.chargingStatusCounts.getValue("UNKNOWN"))
        val hourStart = origin / 3_600_000L * 3_600_000L
        val json = StringBuilder().apply { appendDaemonDiagnosticsJson(snapshot(listOf(unknown,
            sample(12).copy(atMillis = hourStart + 3_600_000L, batteryPercent = 79)))) }.toString()
        val buckets = json.substringAfter("\"timeBuckets\":[").substringBefore("],\"events\"")
        assertEquals(2, Regex("\"sampledContext\":").findAll(buckets).count())
        assertTrue(buckets.contains("\"batteryKnownCount\":0,\"batteryUnknownCount\":1"))
        assertTrue(buckets.contains("\"batteryKnownCount\":1,\"batteryUnknownCount\":0"))
    }

    @Test fun cycleTotalsRemainPerSessionWithLegacyUnknownAndExplicitZeroDistinct() {
        val legacy = StringBuilder().apply { appendDaemonDiagnosticsJson(snapshot(listOf(sample(0)))) }.toString()
        assertTrue(legacy.contains("\"cycleMetrics\":null"))
        val value = snapshot(listOf(sample(0), sample(1))).copy(sessions = listOf(session().copy(cycleMetrics = metrics())))
        val json = StringBuilder().apply { appendDaemonDiagnosticsJson(value) }.toString()
        assertTrue(json.contains("\"stopped\":{\"completedCount\":1,\"totalElapsedMillis\":120,\"maxElapsedMillis\":120"))
        assertTrue(json.contains("\"fcm\":{\"completedCount\":2,\"totalElapsedMillis\":350,\"maxElapsedMillis\":250"))
        assertTrue(json.contains("CYCLE_RESULT_NOT_TARGET_CHECK_WRITE_OR_FCM_DELIVERY_COUNTS"))
        assertTrue(json.contains("\"cycleMetricsPersistenceScope\":\"LIVE_COUNTERS_PERIODIC_PERSISTENCE_UNCOMMITTED_TAIL_MAY_BE_LOST\""))
        val buckets = json.substringAfter("\"timeBuckets\":[").substringBefore("],\"events\"")
        assertFalse(buckets.contains("cycleMetrics"))
        val zero = value.copy(sessions = listOf(session().copy(cycleMetrics = metrics().copy(stopped = DaemonCycleStageMetrics()))))
        val zeroJson = StringBuilder().apply { appendDaemonDiagnosticsJson(zero) }.toString()
        assertTrue(zeroJson.contains("\"stopped\":{\"completedCount\":0,\"totalElapsedMillis\":0,\"maxElapsedMillis\":0"))
        val markdown = encodeCombinedDiagnosticMarkdown(report(value)).decodeToString()
        assertTrue(markdown.contains("stopped周期完成/总等待ms/最长等待ms=1/120/120"))
        assertTrue(markdown.contains("不是CPU或子进程耗时"))
        assertTrue(markdown.contains("突然终止可能丢失尚未提交的尾段"))
        assertTrue(markdown.contains("周期检查点是最近完成时间，不是保护覆盖或持久化提交证明"))
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

    @Test fun maximumSessionBucketSampleAndEventExportsRemainBelowReportCap() {
        val saturated = DaemonCycleStageMetrics(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            DaemonCycleOutcomes(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        val sessions = (0 until 32).map { session("session-$it").copy(cycleMetrics = metrics().copy(stopped = saturated, fcm = saturated)) }
        val samples = (0 until 2016).map { index -> sample(index, sessions[index % 32].sessionId)
            .copy(atMillis = origin + index * 3_600_000L, elapsedMillis = 1000 + index * 3_600_000L,
                batteryPercent = if (index % 3 == 0) null else index % 101,
                chargingStatus = listOf(null, "CHARGING", "FULL", "DISCHARGING", "NOT_CHARGING", "UNKNOWN")[index % 6],
                interactive = if (index % 3 == 0) null else index % 3 == 1, deviceIdle = index % 2 == 0) }
        val value = snapshot(samples).copy(currentSessionId = sessions.last().sessionId, sessions = sessions,
            events = (0 until 256).map { DaemonDiagnosticsEvent(sessions[it % 32].sessionId, origin + it, 1000 + it.toLong(), DaemonDiagnosticsState.READY) })
        val json = encodeCombinedDiagnosticReport(report(value))
        assertEquals(200, Regex("\"sampledContext\":").findAll(json.decodeToString()).count())
        assertEquals(32, Regex("\"cycleMetrics\":").findAll(json.decodeToString()).count())
        assertTrue(json.size < MAX_REPORT_BYTES)
        val markdown = encodeCombinedDiagnosticMarkdown(report(value))
        assertTrue(markdown.size < MAX_REPORT_BYTES)
        val directory = java.io.File("build/reports/guardian-diagnostics")
        check(directory.mkdirs() || directory.isDirectory)
        java.io.File(directory, "maximum-synthetic.json").writeBytes(json)
        java.io.File(directory, "maximum-synthetic.md").writeBytes(markdown)
    }

    @Test fun actualEncodedFixtureIsAvailableForOfflineAnalyzerContractCheck() {
        val value = snapshot((0..100).map(::sample)).copy(sessions = listOf(session().copy(cycleMetrics = metrics())))
        val output = java.io.File("build/reports/guardian-diagnostics/v85-synthetic.json")
        val directory = requireNotNull(output.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        output.writeBytes(encodeCombinedDiagnosticReport(report(value)))
        assertTrue(output.length() in 1..MAX_REPORT_BYTES.toLong())
    }
}
