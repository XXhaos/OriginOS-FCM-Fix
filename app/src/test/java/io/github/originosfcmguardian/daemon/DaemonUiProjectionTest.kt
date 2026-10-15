package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class DaemonUiProjectionTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private val consent = TargetConsent.Recipient(RecipientConsentToken("00000000-0000-0000-0000-000000000001",
        1, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1000), emptyList(), null), listOf(consent), 3, 1000)
    private fun root(extras: Map<DaemonDomainRole, ByteArray?> = emptyMap(), value: ProtectionAuthoritySnapshot = authority): DaemonProtectionRootSnapshot {
        val original = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(value),
            mapOf(DaemonDomainRole.SELECTION to TargetSelectionCodec.encode(listOf(identity)).toByteArray()) + extras)
        return DaemonProtectionRootSnapshot(original.control.copy(mode = DaemonControlMode.ACTIVE), original.domains(), dataRevision = 7)
    }
    private fun status(root: DaemonProtectionRootSnapshot) = DaemonAppStatus(root.control,
        DaemonOwnerStatus.ACTIVE, root.dataRevision, false, false, 100, 1000, 2000)
    private fun project(root: DaemonProtectionRootSnapshot, status: DaemonAppStatus? = status(root), fresh: Boolean = true) =
        DaemonUiProjection.project(root, status, 1000, 3000, ZoneId.of("UTC"), fresh)

    @Test fun remoteSessionOriginSurvivesAppRecreationWithoutInventingAppUptime() {
        val root = root()
        val first = project(root)
        val second = DaemonUiProjection.project(root, status(root), 5000, 7000, ZoneId.of("UTC"))
        assertTrue(first.automatic.enabled)
        assertEquals(100L, first.automatic.coreSessionStartedAtElapsedMillis)
        assertEquals(first.automatic.coreSessionStartedAtElapsedMillis, second.automatic.coreSessionStartedAtElapsedMillis)
        assertEquals(listOf(identity), first.selectedIdentities)
    }

    @Test fun unavailableStaleIncoherentClosingAndWaitingHostPreserveChoicesButNeverClaimRunning() {
        val root = root()
        for (status in listOf(null, status(root).copy(dataRevision = 6), status(root).copy(closing = true),
            status(root).copy(released = true), status(root).copy(sessionStartedElapsed = null),
            status(root).copy(sessionStartedElapsed = 1001), status(root).copy(status = DaemonOwnerStatus.UNRECONCILED))) {
            val ui = project(root, status)
            assertTrue(ui.automatic.masterEnabled)
            assertTrue(ui.automatic.savedAuthorization)
            assertEquals(listOf(identity), ui.automatic.configuredIdentities)
            assertFalse(ui.hostReady)
            assertFalse(ui.automatic.enabled)
            assertNull(ui.automatic.coreSessionStartedAtElapsedMillis)
        }
        assertFalse(project(root, fresh = false).hostReady)
    }

    @Test fun absentCorruptAndUnavailableCountsNeverBecomeZero() {
        for (extras in listOf(emptyMap(), mapOf(DaemonDomainRole.RECOVERY_COUNTS to byteArrayOf(0)),
            mapOf(DaemonDomainRole.RECOVERY_COUNTS to RecoveryCountsCodec.encode(RecoveryCountsLedger(100)),
                DaemonDomainRole.COUNTS_UNAVAILABLE to byteArrayOf(1)))) {
            val ui = project(root(extras))
            assertFalse(ui.counts.available)
            assertTrue(ui.counts.byIdentity.isEmpty())
        }
    }

    @Test fun countsClockMigrationIsPrivateRamAndOriginalRootRemainsByteIdentical() {
        val ledger = RecoveryCountsLedger(100, 1, 2000, receipts = listOf(RecoveryCountReceipt(1, identity, 2000)))
        val root = root(mapOf(DaemonDomainRole.RECOVERY_COUNTS to RecoveryCountsCodec.encode(ledger)))
        val before = DaemonProtectionRootCodec.encode(root)
        val ui = project(root)
        assertTrue(ui.counts.available)
        assertEquals(1, ui.counts.byIdentity[identity]?.today)
        assertFalse(ui.counts.byIdentity[identity]!!.todayComplete)
        assertArrayEquals(before, DaemonProtectionRootCodec.encode(root))
        val backwards = DaemonUiProjection.project(root, status(root), 1000, 1500, ZoneId.of("UTC"))
        assertTrue(backwards.counts.detail.contains("时间发生变化"))
        assertArrayEquals(before, DaemonProtectionRootCodec.encode(root))
    }

    @Test fun interruptionAndRetirementMarkersKeepSelectionsAndBlockActualProtection() {
        for (marker in listOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.AUTHORITY_STOP_PENDING)) {
            val ui = project(root(mapOf(marker to byteArrayOf(1))))
            assertEquals(listOf(identity), ui.selectedIdentities)
            assertEquals(listOf(identity), ui.automatic.configuredIdentities)
            assertFalse(ui.automatic.enabled)
            assertNull(ui.automatic.coreSessionStartedAtElapsedMillis)
        }
    }

    @Test fun masterOffRetainsPerAppSelectionButNeverActualReadiness() {
        val ui = project(root(value = authority.copy(mode = ProtectionConsentMode.USER_PAUSED)))
        assertFalse(ui.automatic.masterEnabled)
        assertFalse(ui.automatic.savedAuthorization)
        assertEquals(listOf(identity), ui.automatic.configuredIdentities)
        assertFalse(ui.hostReady)
    }

    @Test fun fcmSavedConsentIsSeparateFromReadyAndPendingUncertaintySurvives() {
        val consent = FcmProtectionConsent(true, "00000000-0000-0000-0000-000000000002", "a".repeat(64), "b".repeat(64), true)
        val saved = mapOf(DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(consent))
        val waiting = project(root(saved), null)
        assertTrue(waiting.fcm.configured)
        assertEquals(FcmMaintenanceState.WAITING_HOST, waiting.fcm.maintenance)
        assertFalse(waiting.fcm.recoveryReady)
        val blocked = project(root(saved + (DaemonDomainRole.HEARTBEAT_PENDING to byteArrayOf(1))))
        assertEquals(FcmMaintenanceState.STOP_PENDING, blocked.fcm.maintenance)
        assertFalse(blocked.fcm.recoveryReady)
        val unknown = HeartbeatSnapshot(1, true, resumeBlock = HeartbeatResumeBlock.UNKNOWN_RESULT, schemaVersion = 2)
        val uncertain = project(root(saved + mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(unknown),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1))))
        assertTrue(uncertain.fcm.requiresConfirmation)
        assertEquals(FcmMaintenanceState.RECONFIRM, uncertain.fcm.maintenance)
        assertTrue(uncertain.heartbeatUi.resumeBlocked)
        assertFalse(uncertain.heartbeatUi.active)
    }

    @Test fun diagnosticProjectionCarriesExactJournalWithoutCreatingNewReceipts() {
        val record = StoppedRecoveryRecord(4, identity, 2000, 1900, VERIFIED_AUTOSTART_FINGERPRINT,
            0, true, true, true, StoppedRecoveryStage.CLAIMED)
        val ui = project(root(mapOf(DaemonDomainRole.STOPPED_JOURNAL to
            RecoveryJournalCodec.encode(RecoveryJournalState(4, record)))))
        assertTrue(ui.stoppedRecordAvailable)
        assertEquals(record, ui.stoppedRecord)
        assertTrue(ui.automatic.waitingForReconciliation)
        assertFalse(ui.automatic.enabled)
        assertFalse(ui.counts.available)
    }

    @Test fun damagedHeartbeatDoesNotEraseKnownParentConsentOrClaimActiveDiagnostics() {
        val consent = FcmProtectionConsent(true, "00000000-0000-0000-0000-000000000002", "a".repeat(64), "b".repeat(64), true)
        val ui = project(root(mapOf(DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(consent),
            DaemonDomainRole.HEARTBEAT to byteArrayOf(1), DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1))))
        assertTrue(ui.fcm.configured)
        assertTrue(ui.fcm.storageBlocked)
        assertFalse(ui.heartbeatUi.active)
        assertTrue(ui.heartbeatUi.resumeBlocked)
        assertTrue(ui.recoveryUi.storageBlocked)
    }

    private fun readinessFixture(): Triple<DaemonProtectionRootSnapshot, GmsConnectionObservation.Sample, DaemonAppStatus> {
        val sample = GmsConnectionObservation.Sample(19, 10208, 263737035, 1000, 2000,
            FcmRecoveryPolicy.SIGNER, "01234567-89ab-cdef-0123-456789abcdef", 100_000, 89_999,
            SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
            true, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE,
                100_000, 89_999, 0))
        val parent = FcmProtectionConsent(true, "00000000-0000-0000-0000-000000000002",
            gmsHeartbeatBindingDigest(sample), FcmRecoveryPolicy.binding(sample), true)
        val root = root(mapOf(DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding,
                events = listOf(FcmRecoveryEvent(1000, 1000, FcmRecoveryEventAction.OBSERVATION, eligible = true))))))
        val status = status(root).copy(latestGmsObservation = sample,
            recoveryReadiness = DaemonRecoveryReadinessEvidence(root.control, sample.requestId, sample.elapsedRealtimeMillis, 90_000, true))
        return Triple(root, sample, status)
    }

    @Test fun currentCycleReadinessDoesNotExpireWithTheDeduplicatedHistoricalJournal() {
        val (root, _, status) = readinessFixture()
        val before = DaemonProtectionRootCodec.encode(root)
        assertTrue(DaemonUiProjection.project(root, status, 90_000, 100_000).fcm.recoveryReady)
        assertArrayEquals(before, DaemonProtectionRootCodec.encode(root))
        assertFalse(DaemonUiProjection.project(root, status.copy(recoveryReadiness = null), 90_000, 100_000).fcm.recoveryReady)
    }

    @Test fun recoveryReadinessRequiresExactRecordRequestSampleAndMonotonicSessionTimes() {
        val (root, sample, status) = readinessFixture()
        val evidence = requireNotNull(status.recoveryReadiness)
        val invalid = listOf(evidence.copy(record = root.control.copy(revision = root.control.revision + 1)),
            evidence.copy(sampleRequestId = sample.requestId + 1), evidence.copy(sampleElapsedMillis = 89_998),
            evidence.copy(checkedAtElapsedMillis = 89_998), evidence.copy(checkedAtElapsedMillis = 90_001),
            evidence.copy(admitted = false))
        invalid.forEach { assertFalse(it.toString(), DaemonUiProjection.project(root,
            status.copy(recoveryReadiness = it), 90_000, 100_000).fcm.recoveryReady) }
        assertFalse(DaemonUiProjection.project(root, status.copy(sessionStartedElapsed = 90_000), 90_000, 100_000).fcm.recoveryReady)
        assertFalse(DaemonUiProjection.project(root, status, 120_001, 130_001).fcm.recoveryReady)
        assertFalse(DaemonUiProjection.project(root, status.copy(latestGmsObservation =
            GmsConnectionObservation.Unknown(sample.requestId, GmsUnknown.TIMED_OUT)), 90_000, 100_000).fcm.recoveryReady)
    }

    @Test fun trueDisplayEvidenceCannotOverrideClosedUnreconciledHaltOrPendingState() {
        val (root, _, status) = readinessFixture()
        for (changed in listOf(status.copy(closing = true), status.copy(released = true),
            status.copy(needsReconciliation = true), status.copy(status = DaemonOwnerStatus.PENDING_RECOVERY)))
            assertFalse(DaemonUiProjection.project(root, changed, 90_000, 100_000).fcm.recoveryReady)
        for (role in listOf(DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT,
            DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING)) {
            val marked = DaemonProtectionRootSnapshot(root.control, root.domains() + (role to byteArrayOf(1)), dataRevision = root.dataRevision)
            assertFalse(role.name, DaemonUiProjection.project(marked, status, 90_000, 100_000).fcm.recoveryReady)
        }
    }

    @Test fun pendingRecoveryBlocksReadinessButEndedHistoricalUnknownDoesNotPermanentlyBlockIt() {
        val (root, _, status) = readinessFixture()
        val pending = FcmRecoveryEpisode(1, 123, 100, 1000, 1000, FcmRecoveryEpisodeKind.SERVICE_ABSENT,
            1000, 1000, requestAtMillis = 1000, requestElapsedMillis = 1000,
            requestCorrelationId = "00000000-0000-0000-0000-000000000003", requestPending = true)
        fun withEpisode(value: FcmRecoveryEpisode) = DaemonProtectionRootSnapshot(root.control,
            root.domains() + (DaemonDomainRole.FCM_EPISODES to FcmRecoveryEpisodeCodec.encode(
                FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(value)))), dataRevision = root.dataRevision)
        assertFalse(DaemonUiProjection.project(withEpisode(pending), status, 90_000, 100_000).fcm.recoveryReady)
        val ended = pending.copy(requestPending = false, requestStatus = FcmRecoveryStatus.UNKNOWN,
            reason = FcmRecoveryReason.TIMED_OUT, endedAtMillis = 2000, endedElapsedMillis = 2000,
            outcome = FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN)
        assertTrue(DaemonUiProjection.project(withEpisode(ended), status, 90_000, 100_000).fcm.recoveryReady)
    }
}
