package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import io.github.originosfcmguardian.fcm.FcmReceipt
import io.github.originosfcmguardian.fcm.FcmUiState
import io.github.originosfcmguardian.fcm.MessagePriority
import io.github.originosfcmguardian.fcm.NetworkAvailability
import io.github.originosfcmguardian.fcm.NotificationOutcome
import io.github.originosfcmguardian.fcm.ObservedBoolean
import io.github.originosfcmguardian.fcm.SensitiveToken
import io.github.originosfcmguardian.monitoring.DeepMonitoringUiState
import io.github.originosfcmguardian.monitoring.MonitorSession
import io.github.originosfcmguardian.monitoring.MonitorSessionStatus
import io.github.originosfcmguardian.notification.NotificationFact
import io.github.originosfcmguardian.notification.NotificationFactKind
import io.github.originosfcmguardian.notification.NotificationFactsUiState
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.ProtectionCheckReason
import io.github.originosfcmguardian.protection.ProtectionCheckStatus
import io.github.originosfcmguardian.protection.TargetCheckResult
import io.github.originosfcmguardian.protection.TargetCheckStatus
import io.github.originosfcmguardian.ui.HomeSourceLoadState
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDiagnosticReportTest {
    @Test fun unifiedProtectionSnapshotsAreExportedWithoutAuthoritySecrets() {
        val protection = io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState(configured = true,
            maintenance = io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState.WAITING_CONNECTED,
            recoveryReady = true, confirmationFailure = io.github.originosfcmguardian.fcmprotection.FcmProtectionConfirmationFailure.NETWORK_UNAVAILABLE)
        val report = buildCombinedDiagnosticReport(HomeUiState(fcmProtection = protection), environment(), 9000)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"fcmProtection\":{\"configured\":true"))
        assertTrue(json.contains("\"maintenance\":\"WAITING_CONNECTED\""))
        assertTrue(json.contains("\"recoveryReady\":true"))
        assertTrue(json.contains("\"confirmationFailure\":\"NETWORK_UNAVAILABLE\""))
        assertFalse(json.contains("consentEpoch")); assertFalse(json.contains("installationBinding"))
        assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8).contains("统一 FCM 保护授权"))
    }
    @Test fun powerReferenceExportIsNullableBoundedAndNeverAttributedEnergy() {
        val power = io.github.originosfcmguardian.fcmrecovery.FcmPowerReference(80,
            io.github.originosfcmguardian.fcmrecovery.FcmChargingStatus.DISCHARGING,
            io.github.originosfcmguardian.fcmrecovery.FcmPluggedSource.UNPLUGGED, false, true,
            123, 999, "12345678-1234-1234-1234-123456789abc")
        val events = List(64) { index -> io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEvent(
            index + 1L, index.toLong(), io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction.POWER_SAMPLE,
            observationUnknownReason = io.github.originosfcmguardian.core.gms.GmsUnknown.TIMED_OUT,
            power = if (index == 0) null else if (index == 1) power.copy(batteryPercent = null,
                mainProcessCpuMillis = null) else power) }
        val report = buildCombinedDiagnosticReport(HomeUiState(fcmRecovery =
            io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState(events = events)), environment(), 9000)
        val encoded = encodeCombinedDiagnosticReport(report)
        assertTrue(encoded.size <= MAX_REPORT_BYTES)
        val json = encoded.toString(Charsets.UTF_8)
        listOf("\"powerReference\":null", "\"wholePhoneBatteryPercent\":null",
            "\"wholePhoneBatteryPercent\":80", "\"guardianMainProcessCpuMillis\":null",
            "\"guardianMainProcessCpuMillis\":123", "\"observationUnknownReason\":\"TIMED_OUT\"",
            "not Guardian-attributed mAh or energy", "CPU excludes Shizuku/shell/GMS/radio",
            "identical processInstanceId").forEach { assertTrue(it, json.contains(it)) }
        assertFalse(json.contains("cpuDelta")); assertFalse(json.contains("attributedEnergy\":"))
        val markdown = encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
        assertTrue(markdown.contains("整机参考")); assertTrue(markdown.contains("字段缺失为未知"))
        assertTrue(markdown.contains("主进程CPUms=未知")); assertTrue(markdown.contains("读取未知原因=TIMED_OUT"))
    }
    @Test fun fcmTrialExportRetainsBoundedMetadataAndSeparatesRequestFromObservedConnection() {
        val events = List(90) { index -> io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEvent(index + 1L,
            index.toLong(), io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction.REQUEST_RESULT,
            status = io.github.originosfcmguardian.core.gms.FcmRecoveryStatus.UNKNOWN,
            reason = io.github.originosfcmguardian.core.gms.FcmRecoveryReason.TIMED_OUT) }
        val report = buildCombinedDiagnosticReport(HomeUiState(fcmRecovery =
            io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState(configured = true, events = events)), environment(), 9000)
        assertEquals(64, report.fcmRecoveryTrial.events.size)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"fcmRecoveryTrial\""))
        assertTrue(json.contains("\"requestStatus\":\"UNKNOWN\""))
        assertTrue(json.contains("\"requestReason\":\"TIMED_OUT\""))
        assertFalse(json.contains("binding")); assertFalse(json.contains("SenderProxy"))
        assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8).contains("FCM 恢复试用事件"))
    }
    @Test
    fun fcmFocusedExportPreservesPrivilegeStatusAndNamesTheNarrowDailyScope() {
        val base = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9000)
        listOf("READY_SHELL", "NOT_RUNNING").forEach { status ->
            val json = encodeCombinedDiagnosticReport(base.copy(shizukuStatus = status)).toString(Charsets.UTF_8)
            assertTrue(json.contains("\"shizukuStatus\":\"$status\""))
            assertTrue(json.contains("\"scope\":\"SELECTED_APP_INSTALLATION_IDENTITY_ONLY\""))
            assertTrue(json.contains("not stopped flags, FCM connectivity, or notification delivery"))
            assertFalse(json.contains("\"gms\":"))
            assertFalse(json.contains("\"privilegedDetection\":"))
        }
    }

    @Test
    fun retiredAutostartHistoryCannotExportCurrentHealthyOrNeedsActionStatus() {
        listOf(ProtectionCheckStatus.HEALTHY_READ_ONLY, ProtectionCheckStatus.NEEDS_USER_ACTION).forEach { status ->
            val historical = DailyProtectionReport(1000, ProtectionCheckReason.MANUAL, "known", status,
                "historical", listOf(TargetCheckResult(PACKAGE_NAME, TargetCheckStatus.ALLOWED)))
            val projected = buildCombinedDiagnosticReport(HomeUiState(
                dailyProtectionLoadState = HomeSourceLoadState.LOADED,
                dailyProtectionReport = historical,
            ), environment(), 9000)
            assertEquals("UNKNOWN", projected.dailyProtectionStatus)
            assertEquals(1000L, projected.dailyProtectionCheckedAtEpochMillis)
            assertEquals(DiagnosticSourceAvailability.PARTIAL, projected.sourceAvailability["dailyProtection"])
            val json = encodeCombinedDiagnosticReport(projected).toString(Charsets.UTF_8)
            assertFalse(json.contains("ALLOWED"))
            assertFalse(json.contains(status.name))
            assertEquals(status, historical.status)
        }
    }

    @Test
    fun reportIsRedactedAndContainsOnlyBoundedSummaries() {
        val identity = identity()
        val state = HomeUiState(
            targetSelectionLoadState = HomeSourceLoadState.LOADED,
            selectedTargets = listOf(
                SelectedTargetUiState(
                    identity = identity,
                    label = "Private \"App\"\nName",
                    status = TargetAutostartStatus.ALLOWED,
                ),
            ),
            fcm = FcmUiState(
                token = SensitiveToken("secret-fcm-token"),
                latestReceipt = FcmReceipt(
                    testId = "sensitive-test-id",
                    sentAtEpochMillis = 1_000,
                    receivedAtEpochMillis = 2_000,
                    receivedAtElapsedMillis = 8_000,
                    messagePriority = MessagePriority.HIGH,
                    deviceInteractive = ObservedBoolean.NO,
                    deviceIdleMode = ObservedBoolean.YES,
                    networkAvailability = NetworkAvailability.AVAILABLE,
                    notificationOutcome = NotificationOutcome.POST_REQUESTED,
                    handlerDurationMillis = 20,
                    processStartedAtElapsedMillis = 7_500,
                ),
            ),
            fcmEvidenceLoadState = HomeSourceLoadState.LOADED,
            notificationFacts = NotificationFactsUiState(
                enabled = true,
                accessGranted = true,
                connected = true,
                targetCount = 1,
                events = listOf(
                    NotificationFact(PACKAGE_NAME, 4_000, NotificationFactKind.POSTED_OR_UPDATED),
                    NotificationFact(PACKAGE_NAME, 3_000, NotificationFactKind.REMOVED),
                ),
            ),
            notificationFactsLoadState = HomeSourceLoadState.LOADED,
            deepMonitoring = DeepMonitoringUiState(
                session = MonitorSession(
                    id = "sensitive-session-id",
                    identity = identity,
                    startedAtEpochMillis = 2_500,
                    deadlineEpochMillis = 32_500,
                    status = MonitorSessionStatus.STOPPED,
                    events = listOf(
                        ProcessTraceEvent(
                            timestampEpochMillis = 2_600,
                            target = ProcessTraceTarget(PACKAGE_NAME, SENSITIVE_UID),
                            kind = ProcessTraceKind.PROCESS_START,
                            pid = SENSITIVE_PID,
                        ),
                        ProcessTraceEvent(2_601, ProcessTraceTarget(PACKAGE_NAME, SENSITIVE_UID), ProcessTraceKind.UID_RUNNING),
                        ProcessTraceEvent(2_602, ProcessTraceTarget(PACKAGE_NAME, SENSITIVE_UID), ProcessTraceKind.UID_STOPPED),
                    ),
                    pollCount = 2,
                ),
            ),
            deepMonitoringLoadState = HomeSourceLoadState.LOADED,
        )

        val report = buildCombinedDiagnosticReport(state, environment(), 9_000)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)

        assertTrue(json.contains("guardian.combined-diagnostic.v7"))
        assertFalse(json.contains("guardianAutostartStatus"))
        assertFalse(json.contains("autostartStatus"))
        assertFalse(json.contains("ALLOWED"))
        assertTrue(json.contains("Private \\\"App\\\"\\nName"))
        assertTrue(json.contains("\"retainedCount\":2"))
        assertTrue(json.contains("\"processStartCount\":1"))
        assertTrue(json.contains("\"uidRunningCount\":1"))
        assertTrue(json.contains("\"uidStoppedCount\":1"))
        assertTrue(json.contains("\"retainedEventCount\":3"))
        assertTrue(json.contains("\"fcmToNotificationCausality\":\"UNKNOWN\""))
        assertFalse(json.contains("secret-fcm-token"))
        assertFalse(json.contains("sensitive-test-id"))
        assertFalse(json.contains("sensitive-session-id"))
        assertFalse(json.contains(SENSITIVE_UID.toString()))
        assertFalse(json.contains(SENSITIVE_PID.toString()))
        assertFalse(json.contains(SENSITIVE_SIGNER))
        assertFalse(json.contains(SENSITIVE_INSTALL_TIME.toString()))
    }

    @Test
    fun corruptAndMissingSourcesStayDistinctFromEmptySources() {
        val state = HomeUiState(
            targetSelectionStoreCorrupt = true,
            fcm = FcmUiState(evidenceCorrupt = true),
            notificationFacts = NotificationFactsUiState(
                events = listOf(NotificationFact(PACKAGE_NAME, 4_000, NotificationFactKind.REMOVED)),
                historyCorrupt = true,
            ),
            deepMonitoring = DeepMonitoringUiState(historyCorrupt = true),
        )

        val report = buildCombinedDiagnosticReport(state, environment(), 9_000)

        assertEquals(DiagnosticSourceAvailability.CORRUPT, report.sourceAvailability["targetSelection"])
        assertEquals(DiagnosticSourceAvailability.CORRUPT, report.sourceAvailability["fcmEvidence"])
        assertEquals(DiagnosticSourceAvailability.CORRUPT, report.sourceAvailability["notificationFacts"])
        assertEquals(DiagnosticSourceAvailability.CORRUPT, report.sourceAvailability["recentMonitor"])
        assertNull(report.targets)
        assertNull(report.latestFcmReceipt)
        assertNull(report.notificationFacts)
        assertNull(report.monitor)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"notificationFacts\":\"CORRUPT\""))
        assertTrue(json.contains("must not be interpreted as empty or healthy"))
    }

    @Test
    fun pendingAndFailedLoadsAreNotReportedAsEmpty() {
        val pending = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9_000)

        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["targetSelection"])
        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["fcmEvidence"])
        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["notificationFacts"])
        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["recentMonitor"])
        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["dailyProtection"])
        assertNull(pending.targets)
        assertNull(pending.notificationFacts)
        assertNull(pending.monitor)

        val confirmedEmpty = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.LOADED,
                fcmEvidenceLoadState = HomeSourceLoadState.LOADED,
                notificationFactsLoadState = HomeSourceLoadState.LOADED,
                deepMonitoringLoadState = HomeSourceLoadState.LOADED,
                dailyProtectionLoadState = HomeSourceLoadState.LOADED,
            ),
            environment(),
            9_000,
        )
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["targetSelection"])
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["fcmEvidence"])
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["notificationFacts"])
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["recentMonitor"])
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["dailyProtection"])
        assertEquals(emptyList<DiagnosticTarget>(), confirmedEmpty.targets)
        assertEquals(0, confirmedEmpty.notificationFacts?.retainedCount)

        val failed = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.FAILED,
                fcmEvidenceLoadState = HomeSourceLoadState.FAILED,
                notificationFactsLoadState = HomeSourceLoadState.FAILED,
                deepMonitoringLoadState = HomeSourceLoadState.FAILED,
                dailyProtectionLoadState = HomeSourceLoadState.FAILED,
            ),
            environment(),
            9_000,
        )
        assertTrue(failed.sourceAvailability.filterKeys {
            it != "stoppedRecovery" && it != "automaticProtection"
        }.values.all { it == DiagnosticSourceAvailability.UNAVAILABLE || it == DiagnosticSourceAvailability.MISSING })
        assertEquals(DiagnosticSourceAvailability.LOADING, failed.sourceAvailability["stoppedRecovery"])
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, failed.sourceAvailability["automaticProtection"])
        val json = encodeCombinedDiagnosticReport(failed).toString(Charsets.UTF_8)
        assertFalse(json.contains("stale-gms-version"))
        assertTrue(json.contains("\"targets\":null"))
        assertTrue(json.contains("\"notificationFacts\":null"))
        assertTrue(json.contains("sampled independently and may be older"))
    }

    @Test
    fun incompleteMonitorStatusesAreAlwaysPartial() {
        val identity = identity()
        val incomplete = setOf(
            MonitorSessionStatus.RUNNING,
            MonitorSessionStatus.INTERRUPTED,
            MonitorSessionStatus.UNAVAILABLE,
            MonitorSessionStatus.IDENTITY_CHANGED,
            MonitorSessionStatus.LIMIT_REACHED,
        )
        incomplete.forEach { status ->
            val state = HomeUiState(
                deepMonitoringLoadState = HomeSourceLoadState.LOADED,
                deepMonitoring = DeepMonitoringUiState(
                    session = MonitorSession(
                        id = "session-${status.name.lowercase()}",
                        identity = identity,
                        startedAtEpochMillis = 2_500,
                        deadlineEpochMillis = 32_500,
                        status = status,
                        events = emptyList(),
                    ),
                ),
            )
            val report = buildCombinedDiagnosticReport(state, environment(), 9_000)
            assertEquals(status.name, DiagnosticSourceAvailability.PARTIAL, report.sourceAvailability["recentMonitor"])
        }
    }

    @Test
    fun moreThanTenSelectedTargetsAreAllIncludedWithoutTruncation() {
        val selected = (0 until 12).map { index ->
            val packageName = "org.example.privateapp.p$index"
            SelectedTargetUiState(
                identity = identity().copy(
                    packageName = packageName,
                    uid = SENSITIVE_UID + index,
                    firstInstallTime = SENSITIVE_INSTALL_TIME + index,
                ),
                label = "App $index",
                status = TargetAutostartStatus.ALLOWED,
            )
        }
        val report = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.LOADED,
                selectedTargets = selected,
            ),
            environment(),
            9_000,
        )

        assertEquals(12, report.targets?.size)
        assertEquals(selected.map { it.identity.packageName }, report.targets?.map { it.packageName })
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        selected.forEach { target -> assertTrue(json.contains(target.identity.packageName)) }
    }

    @Test
    fun exportFailsAtByteSafetyBoundInsteadOfSilentlyOmittingTargets() {
        val selected = (0 until 1_000).map { index ->
            SelectedTargetUiState(
                identity = identity().copy(
                    packageName = "org.example.privateapp.p$index",
                    uid = SENSITIVE_UID + index,
                    firstInstallTime = SENSITIVE_INSTALL_TIME + index,
                ),
                label = "L".repeat(80),
                status = TargetAutostartStatus.ALLOWED,
            )
        }
        val report = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.LOADED,
                selectedTargets = selected,
            ),
            environment(),
            9_000,
        )

        assertEquals(selected.size, report.targets?.size)
        assertThrows(IllegalArgumentException::class.java) {
            encodeCombinedDiagnosticReport(report)
        }
    }

    @Test
    fun preparedSnapshotIsCopiedConsumedOnceAndFailsClosedAfterProcessLoss() {
        val pending = PreparedCombinedDiagnosticExport()
        val input = byteArrayOf(1, 2, 3)
        pending.prepare(input)
        input[0] = 9

        val ready = pending.consume(documentSelected = true) as CombinedDiagnosticExportResult.Ready
        assertArrayEquals(byteArrayOf(1, 2, 3), ready.bytes)
        assertEquals(CombinedDiagnosticExportResult.Missing, pending.consume(documentSelected = true))

        pending.prepare(byteArrayOf(4))
        assertEquals(CombinedDiagnosticExportResult.Cancelled, pending.consume(documentSelected = false))
        assertEquals(CombinedDiagnosticExportResult.Missing, pending.consume(documentSelected = true))
        assertEquals(
            CombinedDiagnosticExportResult.Missing,
            PreparedCombinedDiagnosticExport().consume(documentSelected = true),
        )
    }

    private fun environment() = DiagnosticEnvironment(
        sdkInt = 37,
        androidRelease = "17",
        manufacturer = "vivo",
        model = "PD2609",
        guardianVersionName = "0.1.0",
        guardianVersionCode = 1,
    )

    private fun identity() = SelectedTargetIdentity(
        packageName = PACKAGE_NAME,
        uid = SENSITIVE_UID,
        firstInstallTime = SENSITIVE_INSTALL_TIME,
        signerSha256 = listOf(SENSITIVE_SIGNER),
    )

    private companion object {
        const val PACKAGE_NAME = "org.example.privateapp"
        const val SENSITIVE_UID = 10_123
        const val SENSITIVE_PID = 31_181
        const val SENSITIVE_INSTALL_TIME = 1_735_689_600_000L
        const val SENSITIVE_SIGNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
