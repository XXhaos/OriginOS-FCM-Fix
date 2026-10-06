package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.fcm.FcmUiState
import io.github.originosfcmguardian.fcm.SensitiveToken
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.StoppedRecoveryPhase
import io.github.originosfcmguardian.ui.StoppedRecoveryUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRecoveryReportTest {
    @Test
    fun unloadedAndBlockedSourcesSuppressStaleRecordsAndNeverLookEmpty() {
        listOf(
            historical().copy(loaded = false) to DiagnosticSourceAvailability.LOADING,
            historical().copy(storageBlocked = true) to DiagnosticSourceAvailability.UNAVAILABLE,
            historical().copy(loaded = false, storageBlocked = true) to DiagnosticSourceAvailability.UNAVAILABLE,
        ).forEach { (recovery, expected) ->
            val report = report(recovery)
            assertEquals(expected, report.sourceAvailability["stoppedRecovery"])
            assertNull(report.stoppedRecovery.latestRecord)
            val json = json(report)
            assertTrue(json.contains("\"latestRecord\":null"))
            assertFalse(json.contains(PACKAGE_A))
            assertFalse(json.contains("\"createdAtEpochMillis\":1200"))
        }
    }

    @Test
    fun loadedEmptyIncompleteAndPendingWithoutRecordStayDistinct() {
        val empty = StoppedRecoveryUiState(loaded = true)
        assertEquals(DiagnosticSourceAvailability.EMPTY, report(empty).sourceAvailability["stoppedRecovery"])
        listOf(
            empty.copy(recordIdentity = identity(PACKAGE_A)),
            empty.copy(recordedAtMillis = 1200),
            empty.copy(recordSource = StoppedRecoverySource.MANUAL),
            empty.copy(recordStage = StoppedRecoveryStage.FINISHED),
            empty.copy(recordOutcome = StoppedRecoveryOutcome.FLAG_CLEARED),
            empty.copy(receiptOutcome = StoppedRecoveryOutcome.NO_WRITE_NEEDED),
            empty.copy(needsReconcile = true),
            empty.copy(busy = true),
            empty.copy(phase = StoppedRecoveryPhase.RECOVERING),
            empty.copy(phase = StoppedRecoveryPhase.UNCONFIRMED),
            empty.copy(phase = StoppedRecoveryPhase.FAILED),
            empty.copy(phase = StoppedRecoveryPhase.SUCCESS),
            historical().copy(recordedAtMillis = 0),
            historical().copy(recordedAtMillis = -1),
            historical().copy(recordedAtMillis = null),
            historical().copy(recordSource = null),
            historical().copy(recordStage = null),
            historical().copy(recordIdentity = null),
        ).forEach { recovery ->
            val report = report(recovery)
            assertEquals(recovery.toString(), DiagnosticSourceAvailability.PARTIAL, report.sourceAvailability["stoppedRecovery"])
            assertNull(report.stoppedRecovery.latestRecord)
        }
    }

    @Test
    fun journalRecordAOutcomeIsNotReplacedByLatestUiOperationB() {
        val report = report(historical().copy(
            receiptOutcome = StoppedRecoveryOutcome.NO_WRITE_NEEDED,
            activeIdentity = identity(PACKAGE_B),
            confirmationIdentity = identity(PACKAGE_B),
            phase = StoppedRecoveryPhase.SUCCESS,
        ))
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, report.sourceAvailability["stoppedRecovery"])
        assertEquals(PACKAGE_A, report.stoppedRecovery.latestRecord?.packageName)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, report.stoppedRecovery.latestRecord?.outcome)
        val json = json(report)
        assertTrue(json.contains("\"outcome\":\"FLAG_CLEARED\""))
        assertFalse(json.contains(PACKAGE_B))
        assertFalse(json.contains("NO_WRITE_NEEDED"))
        assertNull(report(historical().copy(recordOutcome = null)).stoppedRecovery.latestRecord?.outcome)
    }

    @Test
    fun unresolvedRecordsNeverExportACompletionOutcome() {
        listOf(
            historical().copy(needsReconcile = true),
            historical().copy(recordStage = StoppedRecoveryStage.PREPARED),
            historical().copy(recordStage = StoppedRecoveryStage.CLAIMED),
        ).forEach { recovery ->
            val report = report(recovery)
            assertEquals(DiagnosticSourceAvailability.PARTIAL, report.sourceAvailability["stoppedRecovery"])
            assertNull(report.stoppedRecovery.latestRecord?.outcome)
            assertTrue(json(report).contains("\"outcome\":null"))
        }
        assertEquals(DiagnosticSourceAvailability.PARTIAL,
            report(historical().copy(recordOutcome = StoppedRecoveryOutcome.UNCONFIRMED)).sourceAvailability["stoppedRecovery"])
    }

    @Test
    fun reconciliationKeepsHistoricalStageAndCreationTimeWithFixedLimits() {
        val report = report(historical().copy(recordStage = StoppedRecoveryStage.RECONCILED))
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, report.sourceAvailability["stoppedRecovery"])
        assertEquals(StoppedRecoveryStage.RECONCILED, report.stoppedRecovery.latestRecord?.stage)
        assertEquals(1200L, report.stoppedRecovery.latestRecord?.createdAtEpochMillis)
        val json = json(report)
        assertTrue(json.contains("not command completion or readback time"))
        assertTrue(json.contains("not success of the original command"))
        assertTrue(json.contains("does not prove the current installation state or notification delivery"))
        assertFalse(json.contains("completedAt"))
    }

    @Test
    fun automaticFlagsAreAlwaysAnExplicitRuntimeSnapshot() {
        val automatic = AutomaticProtectionState(
            enabled = true, busy = true, savedAuthorization = true, revocationSaveFailed = true,
            waitingForPrivilege = true, waitingForReconciliation = true,
        )
        val report = build(HomeUiState(automaticProtection = automatic))
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, report.sourceAvailability["automaticProtection"])
        assertEquals(DiagnosticAutomaticProtectionEvidenceScope.APP_RUNTIME_SNAPSHOT, report.automaticProtection.evidenceScope)
        assertTrue(report.automaticProtection.enabled)
        assertTrue(report.automaticProtection.busy)
        assertTrue(report.automaticProtection.savedAuthorization)
        assertTrue(report.automaticProtection.revocationSaveFailed)
        assertTrue(report.automaticProtection.waitingForPrivilege)
        assertTrue(report.automaticProtection.waitingForReconciliation)
        val defaults = build(HomeUiState())
        assertEquals(DiagnosticSourceAvailability.AVAILABLE, defaults.sourceAvailability["automaticProtection"])
        assertFalse(defaults.automaticProtection.savedAuthorization)
        assertTrue(json(defaults).contains("without new durable authorization or privilege checks"))
    }

    @Test
    fun automaticRetainedOutcomeAndNewCheckTimeAreIndependent() {
        val automatic = AutomaticProtectionState(lastCheckedAtMillis = 8200, lastOutcome = "FLAG_CLEARED")
        val report = build(HomeUiState(automaticProtection = automatic))
        assertEquals(8200L, report.automaticProtection.lastCheckedAtEpochMillis)
        assertEquals(DiagnosticRecoveryOutcome.FLAG_CLEARED, report.automaticProtection.lastOutcome)
        val json = json(report)
        assertTrue(json.contains("\"lastCheckedAtEpochMillis\":8200"))
        assertTrue(json.contains("the check time is not an outcome timestamp"))
        assertFalse(json.contains("outcomeAt"))
        listOf<Long?>(null, 0, -1).forEach { invalidTime ->
            val invalid = build(HomeUiState(automaticProtection = automatic.copy(lastCheckedAtMillis = invalidTime)))
            assertNull(invalid.automaticProtection.lastCheckedAtEpochMillis)
            assertEquals(DiagnosticRecoveryOutcome.FLAG_CLEARED, invalid.automaticProtection.lastOutcome)
        }
    }

    @Test
    fun automaticOutcomeMappingAcceptsOnlyExactAllowlistedNamesAndDiscardsOtherText() {
        DiagnosticRecoveryOutcome.entries.forEach { outcome ->
            val report = build(HomeUiState(automaticProtection = AutomaticProtectionState(lastOutcome = outcome.name)))
            assertEquals(outcome, report.automaticProtection.lastOutcome)
            assertTrue(json(report).contains("\"lastOutcome\":\"${outcome.name}\""))
        }
        assertNull(build(HomeUiState()).automaticProtection.lastOutcome)
        listOf("flag_cleared", " FLAG_CLEARED", "FLAG_CLEARED ", "", "arbitrary-private-text-123", "<script>secret-outcome</script>")
            .forEach { arbitrary ->
                val report = build(HomeUiState(automaticProtection = AutomaticProtectionState(lastOutcome = arbitrary)))
                assertEquals(DiagnosticRecoveryOutcome.UNKNOWN, report.automaticProtection.lastOutcome)
                if (arbitrary.isNotEmpty()) assertFalse(json(report).contains(arbitrary))
            }
    }

    @Test
    fun privateIdentitiesMessagesTargetsAndTokensNeverEnterReport() {
        val state = HomeUiState(
            stoppedRecovery = historical().copy(message = "recovery-message-private",
                activeIdentity = identity("org.private.active"), confirmationIdentity = identity("org.private.confirmation")),
            automaticProtection = AutomaticProtectionState(detail = "automatic-detail-private",
                lastOutcome = "grant-id-private", targets = listOf(AutomaticProtectionTargetState(
                    identity = identity("org.private.authorized"), savedAuthorization = true,
                    detail = "target-detail-private", lastOutcome = "target-outcome-private", lastCheckedAtMillis = 773355,
                ))),
            fcm = FcmUiState(token = SensitiveToken("token-private")),
        )
        val json = json(build(state))
        assertTrue(json.contains(PACKAGE_A))
        listOf("recovery-message-private", "automatic-detail-private", "org.private.active", "org.private.confirmation",
            "org.private.authorized", "grant-id-private", "target-detail-private", "target-outcome-private", "773355",
            "token-private", UID.toString(), INSTALL_TIME.toString(), SIGNER, "recordIdentity", "confirmationIdentity",
            "activeIdentity", "requestId", "consentId", "authorizedIdentities", "recoveryCounts")
            .forEach { sentinel -> assertFalse(sentinel, json.contains(sentinel)) }
    }

    @Test
    fun jsonRetainsOldSectionsAndPreparedBytesCaptureTheNewSnapshotOnce() {
        val before = report(historical())
        val encoded = encodeCombinedDiagnosticReport(before)
        val prepared = PreparedCombinedDiagnosticExport()
        prepared.prepare(encoded)
        encoded.fill(0)
        val after = report(historical().copy(storageBlocked = true))
        assertNull(after.stoppedRecovery.latestRecord)
        val ready = prepared.consume(true) as CombinedDiagnosticExportResult.Ready
        assertArrayEquals(encodeCombinedDiagnosticReport(before), ready.bytes)
        assertEquals(CombinedDiagnosticExportResult.Missing, prepared.consume(true))
        val json = ready.bytes.toString(Charsets.UTF_8)
        listOf("environment", "gms", "shizukuStatus", "privilegedDetection", "targets", "fcm",
            "notificationFacts", "recentMonitor", "dailyProtection", "sourceAvailability", "unknowns", "limitations",
            "stoppedRecovery", "automaticProtection")
            .forEach { key -> assertTrue(key, json.contains("\"$key\":")) }
        assertTrue(json.startsWith("{\"schema\":\"guardian.combined-diagnostic.v3\""))
        assertTrue(ready.bytes.size <= MAX_REPORT_BYTES)
    }

    private fun report(recovery: StoppedRecoveryUiState) = build(HomeUiState(stoppedRecovery = recovery))
    private fun build(state: HomeUiState) = buildCombinedDiagnosticReport(state, environment, 9000)
    private fun json(report: CombinedDiagnosticReport) = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
    private fun historical() = StoppedRecoveryUiState(
        loaded = true, recordIdentity = identity(PACKAGE_A), recordedAtMillis = 1200,
        recordSource = StoppedRecoverySource.MANUAL, recordStage = StoppedRecoveryStage.FINISHED,
        recordOutcome = StoppedRecoveryOutcome.FLAG_CLEARED, receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED,
    )
    private fun identity(packageName: String) = SelectedTargetIdentity(packageName, UID, INSTALL_TIME, listOf(SIGNER))

    private companion object {
        const val PACKAGE_A = "org.example.recorda"
        const val PACKAGE_B = "org.example.operationb"
        const val UID = 16643
        const val INSTALL_TIME = 1781122334455L
        val SIGNER = "c".repeat(64)
        val environment = DiagnosticEnvironment(37, "17", "vivo", "model", "report-test", 1)
    }
}
