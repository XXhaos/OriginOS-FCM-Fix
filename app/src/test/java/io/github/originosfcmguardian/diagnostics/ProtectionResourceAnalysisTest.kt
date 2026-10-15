package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.*
import org.junit.Test

class ProtectionResourceAnalysisTest {
    @Test fun retainedResourceExportExplicitlyBelongsToAppHistoryNotDaemonCollection() {
        val report = buildCombinedDiagnosticReport(HomeUiState(automaticProtectionFromDaemon = true),
            DiagnosticEnvironment(37, "17", "vivo", "test", "0.1.0", 83), 9000)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"collectionScope\":\"GUARDIAN_APP_PROCESS_HISTORY\""))
        assertTrue(json.contains("\"daemonResourceAvailability\":\"NOT_COLLECTED\""))
        assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8).contains("独立核心采集状态见对应章节"))
    }
    private val instance = "00000000-0000-0000-0000-000000000001"
    private fun sample(at: Long = 400, rss: Long? = 100) = ProtectionResourceSample(instance, 11, 100, 100,
        at, at, versionCode = 70, rssKiB = rss, threads = 12, heapUsedBytes = 1000, cpuTimeMillis = at,
        fcmObservationAtMillis = at - 1, fcmObservationElapsedMillis = at - 1,
        fcmService = "REPORTED_CONNECTED", fcmCompleteness = "COMPLETE", fcmSocket = "OBSERVED",
        maintenance = "ACTIVE", fcmConfigured = true, recoveryReady = true)
    private fun snapshot(vararg samples: ProtectionResourceSample) = ProtectionResourceSnapshot(
        availability = ProtectionResourceAvailability.AVAILABLE, samples = samples.toList())
    private fun exit(at: Long = 500, process: Int = 11) = ProtectionResourceExit(process, at, 3, 0, 125)

    @Test fun `same retained lifetime produces factual resource deltas and nearest age`() {
        val data = snapshot(sample(200), sample(400, 120).copy(heapUsedBytes = 1400, threads = 15,
            systemLowMemory = true, readerTimeouts = 2))
        val result = analyzeResourceExit(data, exit())
        assertEquals(ResourceExitCorrelation.PID_AND_RETAINED_LIFETIME_MATCH, result.correlation)
        assertEquals(100L, result.nearestSampleAgeMillis)
        assertEquals(20L, result.rssDeltaKiB)
        assertEquals(400L, result.heapDeltaBytes)
        assertEquals(3L, result.threadDelta)
        assertEquals(true, result.sampledSystemLowMemory)
    }

    @Test fun `missing sparse stale and corrupt evidence are not healthy matches`() {
        assertEquals(ResourceExitCorrelation.NO_PRE_EXIT_SAMPLE, analyzeResourceExit(snapshot(), exit()).correlation)
        assertNull(analyzeResourceExit(snapshot(sample()), exit()).rssDeltaKiB)
        assertNull(analyzeResourceExit(snapshot(sample()), exit()).sampledSystemLowMemory)
        assertEquals(ResourceExitCorrelation.STALE_PRE_EXIT_SAMPLE, analyzeResourceExit(snapshot(sample()), exit(700_000)).correlation)
        assertEquals(ResourceExitCorrelation.RESOURCE_UNAVAILABLE,
            analyzeResourceExit(snapshot(sample()).copy(availability = ProtectionResourceAvailability.CORRUPT), exit()).correlation)
    }

    @Test fun `other PID future sample and surviving same instance cannot be joined to death`() {
        assertEquals(ResourceExitCorrelation.NO_PRE_EXIT_SAMPLE, analyzeResourceExit(snapshot(sample()), exit(process = 12)).correlation)
        assertEquals(ResourceExitCorrelation.NO_PRE_EXIT_SAMPLE, analyzeResourceExit(snapshot(sample(600)), exit()).correlation)
        assertEquals(ResourceExitCorrelation.AMBIGUOUS_PROCESS_LIFETIME,
            analyzeResourceExit(snapshot(sample(400), sample(600)), exit()).correlation)
    }

    @Test fun `ambiguous PID reuse and clock correction invalidate trends`() {
        val ambiguous = sample().copy(processInstance = "00000000-0000-0000-0000-000000000002")
        assertEquals(ResourceExitCorrelation.AMBIGUOUS_PROCESS_LIFETIME,
            analyzeResourceExit(snapshot(sample(), ambiguous), exit()).correlation)
        val discontinuity = sample(4000).copy(elapsedMillis = 500)
        assertEquals(ResourceExitCorrelation.CLOCK_DISCONTINUITY,
            analyzeResourceExit(snapshot(discontinuity), exit(5000)).correlation)
        val replacement = sample(800).copy(processInstance = "00000000-0000-0000-0000-000000000002",
            processStartedAtMillis = 700, processStartedElapsedMillis = 700)
        assertEquals(ResourceExitCorrelation.PID_AND_RETAINED_LIFETIME_MATCH,
            analyzeResourceExit(snapshot(sample(), replacement), exit(600)).correlation)
    }

    @Test fun `actual FCM time is independent of event state changes and old process reads`() {
        assertEquals(1L, actualFcmObservationAge(sample()))
        assertNull(actualFcmObservationAge(sample().copy(fcmObservationElapsedMillis = 50)))
        assertNull(actualFcmObservationAge(sample().copy(fcmObservationElapsedMillis = 500)))
        assertNull(actualFcmObservationAge(sample().copy(fcmObservationAtMillis = null, fcmObservationElapsedMillis = null)))
    }

    @Test fun `full bounded sidecar exports summary without an unbounded raw history`() {
        val samples = List(1024) { index -> sample(1000L + index * 300_000L).copy(
            processInstance = java.util.UUID(0, index + 1L).toString(), processId = index + 1,
            processStartedAtMillis = 100, processStartedElapsedMillis = 100,
            heapMaxBytes = Long.MAX_VALUE, nativeAllocatedBytes = Long.MAX_VALUE,
            systemAvailableBytes = Long.MAX_VALUE, systemThresholdBytes = Long.MAX_VALUE,
            automaticLastCheckedAtMillis = Long.MAX_VALUE, heartbeatLastStatus = "REQUESTED",
            recoveryAction = "READBACK", recoveryActionAtMillis = Long.MAX_VALUE) }
        val exits = samples.takeLast(32).map { ProtectionResourceExit(it.processId, it.atMillis + 100, 3, 0, 125, Long.MAX_VALUE, Long.MAX_VALUE) }
        val resource = ProtectionResourceSnapshot(ProtectionResourceAvailability.AVAILABLE, samples, exits)
        val base = buildCombinedDiagnosticReport(HomeUiState(), DiagnosticEnvironment(37, "17", "vivo", "test", "trial", 70), 400_000_000)
        val power = io.github.originosfcmguardian.fcmrecovery.FcmPowerReference(100,
            io.github.originosfcmguardian.fcmrecovery.FcmChargingStatus.DISCHARGING,
            io.github.originosfcmguardian.fcmrecovery.FcmPluggedSource.UNPLUGGED, true, true,
            Long.MAX_VALUE, Long.MAX_VALUE, instance)
        val fcmEvents = List(64) { index -> io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEvent(
            index + 1L, index.toLong(), io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction.REQUEST_RESULT,
            status = io.github.originosfcmguardian.core.gms.FcmRecoveryStatus.UNKNOWN,
            reason = io.github.originosfcmguardian.core.gms.FcmRecoveryReason.TIMED_OUT,
            observationUnknownReason = io.github.originosfcmguardian.core.gms.GmsUnknown.TIMED_OUT,
            power = power, operation = io.github.originosfcmguardian.core.gms.FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS) }
        val lifecycleEvents = List(64) { index -> ProtectionLifecycleEvent(index + 1L, index.toLong(), 123,
            ProtectionLifecycleEventKind.SYSTEM_PROCESS_EXIT, systemExitReason = 3, systemExitStatus = 0) }
        val report = base.copy(protectionResources = resource,
            targets = List(64) { DiagnosticTarget("org.example.target$it", "长".repeat(80)) },
            protectionLifecycle = ProtectionLifecycleSnapshot(ProtectionLifecycleAvailability.AVAILABLE, lifecycleEvents),
            fcmRecoveryTrial = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState(events = fcmEvents),
            fcmRecoveryEpisodes = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeSnapshot(
                availability = io.github.originosfcmguardian.fcmrecovery.FcmEpisodeAvailability.AVAILABLE,
                episodes = List(32) { index -> io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisode(
                    sequence = Long.MAX_VALUE - 32 + index, processId = Int.MAX_VALUE,
                    processStartedElapsedMillis = Long.MAX_VALUE - 10,
                    startedAtMillis = Long.MAX_VALUE - 9, startedElapsedMillis = Long.MAX_VALUE - 9,
                    kind = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeKind.SERVICE_ABSENT,
                    lastObservedAtMillis = Long.MAX_VALUE, lastObservedElapsedMillis = Long.MAX_VALUE,
                    requestAtMillis = Long.MAX_VALUE - 8, requestElapsedMillis = Long.MAX_VALUE - 8,
                    requestStatus = io.github.originosfcmguardian.core.gms.FcmRecoveryStatus.REQUESTED,
                    reason = io.github.originosfcmguardian.core.gms.FcmRecoveryReason.COMMAND_ACCEPTED,
                    endedAtMillis = Long.MAX_VALUE, endedElapsedMillis = Long.MAX_VALUE,
                    outcome = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST,
                    requestCorrelationId = java.util.UUID(0, index + 1L).toString(),
                    resultAtMillis = Long.MAX_VALUE - 7, resultElapsedMillis = Long.MAX_VALUE - 7,
                    timeoutObserved = true,
                    lastBlock = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEpisodeBlock.PRESENT_DISCONNECTED_NOT_ADMITTED,
                    requestOperation = io.github.originosfcmguardian.core.gms.FcmRecoveryOperation.SERVICE_ABSENT_SEND)
                }, evictedEpisodes = Long.MAX_VALUE, writeFailures = Long.MAX_VALUE, droppedUpdates = Long.MAX_VALUE))
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        val markdown = encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"retainedSampleCount\":1024"))
        assertTrue(json.contains("\"previewTruncated\":true"))
        assertTrue(json.contains("LAST_SYSTEM_MEMORY_SAMPLE_NOT_PRECISE_AT_DEATH"))
        assertTrue(json.toByteArray().size < MAX_REPORT_BYTES)
        assertTrue(markdown.toByteArray().size < MAX_REPORT_BYTES)
        assertEquals(16 + 32, Regex("\\\"processInstance\\\":").findAll(json).count())
        assertFalse(json.contains("LEAK_CONFIRMED"))
    }
}
