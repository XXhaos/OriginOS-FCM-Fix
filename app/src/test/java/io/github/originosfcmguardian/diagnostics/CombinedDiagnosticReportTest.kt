package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
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
        assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8).contains("FCM 恢复事件"))
    }
    @Test
    fun fcmFocusedExportPreservesPrivilegeStatusAndRetiredInspectionTombstone() {
        val base = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9000)
        listOf("READY_SHELL", "NOT_RUNNING").forEach { status ->
            val json = encodeCombinedDiagnosticReport(base.copy(shizukuStatus = status)).toString(Charsets.UTF_8)
            assertTrue(json.contains("\"shizukuStatus\":\"$status\""))
            assertTrue(json.contains("\"scope\":\"SELECTED_APP_INSTALLATION_IDENTITY_ONLY\""))
            assertTrue(json.contains("\"retired\":true,\"status\":null,\"checkedAtEpochMillis\":null"))
            assertFalse(json.contains("\"gms\":"))
            assertFalse(json.contains("\"privilegedDetection\":"))
        }
    }

    @Test
    fun retiredInspectionHasNoCurrentDataAndStaysUnavailable() {
        val projected = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9000)
        assertEquals(DiagnosticSourceAvailability.UNAVAILABLE, projected.sourceAvailability["dailyProtection"])
        val json = encodeCombinedDiagnosticReport(projected).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"retired\":true,\"status\":null,\"checkedAtEpochMillis\":null"))
        assertTrue(json.contains("SCHEDULED_READ_ONLY_INSPECTION"))
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

        )

        val report = buildCombinedDiagnosticReport(state, environment(), 9_000)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)

        assertTrue(json.contains("guardian.combined-diagnostic.v13"))
        assertFalse(json.contains("guardianAutostartStatus"))
        assertFalse(json.contains("autostartStatus"))
        assertFalse(json.contains("ALLOWED"))
        assertTrue(json.contains("Private \\\"App\\\"\\nName"))
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

        )

        val report = buildCombinedDiagnosticReport(state, environment(), 9_000)

        assertEquals(DiagnosticSourceAvailability.CORRUPT, report.sourceAvailability["targetSelection"])
        assertNull(report.targets)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("must not be interpreted as empty or healthy"))
    }

    @Test
    fun pendingAndFailedLoadsAreNotReportedAsEmpty() {
        val pending = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9_000)

        assertEquals(DiagnosticSourceAvailability.LOADING, pending.sourceAvailability["targetSelection"])
        assertEquals(DiagnosticSourceAvailability.UNAVAILABLE, pending.sourceAvailability["dailyProtection"])
        assertNull(pending.targets)

        val confirmedEmpty = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.LOADED,

            ),
            environment(),
            9_000,
        )
        assertEquals(DiagnosticSourceAvailability.EMPTY, confirmedEmpty.sourceAvailability["targetSelection"])
        assertEquals(DiagnosticSourceAvailability.UNAVAILABLE, confirmedEmpty.sourceAvailability["dailyProtection"])
        assertEquals(emptyList<DiagnosticTarget>(), confirmedEmpty.targets)

        val failed = buildCombinedDiagnosticReport(
            HomeUiState(
                targetSelectionLoadState = HomeSourceLoadState.FAILED,

            ),
            environment(),
            9_000,
        )
        assertTrue(failed.sourceAvailability.filterKeys {
            it != "stoppedRecovery" && it != "automaticProtection" && it != "fcmRecoveryEpisodes"
        }.values.all { it == DiagnosticSourceAvailability.UNAVAILABLE || it == DiagnosticSourceAvailability.MISSING })
        assertEquals(DiagnosticSourceAvailability.LOADING, failed.sourceAvailability["stoppedRecovery"])
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, failed.sourceAvailability["automaticProtection"])
        val json = encodeCombinedDiagnosticReport(failed).toString(Charsets.UTF_8)
        assertFalse(json.contains("stale-gms-version"))
        assertTrue(json.contains("\"targets\":null"))
        assertTrue(json.contains("sampled independently and may be older"))
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
        val selected = (0 until MAX_REPORT_BYTES / 100 + 100).map { index ->
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
    fun removedCollectorsAreExplicitAndCannotBeReportedAsEmptyOrHealthy() {
        val report = buildCombinedDiagnosticReport(HomeUiState(), environment(), 9_000)
        assertEquals(setOf("targetSelection", "dailyProtection", "stoppedRecovery", "automaticProtection", "fcmRecoveryEpisodes"),
            report.sourceAvailability.keys)
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"schema\":\"guardian.combined-diagnostic.v13\""))
        assertTrue(json.contains("\"removedCapabilities\":[\"DEEP_MONITORING\",\"NOTIFICATION_FACT_COLLECTION\",\"GUARDIAN_FIREBASE_SELF_TEST\",\"SCHEDULED_READ_ONLY_INSPECTION\"]"))
        listOf("fcm", "notificationFacts", "recentMonitor").forEach { name ->
            assertFalse(json.contains("\"$name\":"))
        }
        assertTrue(json.contains("\"fcmRecoveryTrial\":"))
        assertTrue(json.contains("\"fcmProtection\":"))
        assertTrue(json.contains("\"protectionLifecycle\":"))
        assertTrue(json.contains("\"protectionResources\":"))
        assertTrue(json.contains("No collection does not prove absence"))
        assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
            .contains("未采集不代表没有事件"))
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
