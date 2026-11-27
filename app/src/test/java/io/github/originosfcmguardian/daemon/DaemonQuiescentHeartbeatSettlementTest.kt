package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmprotection.FcmProtectionConsent
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.privileged.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DaemonQuiescentHeartbeatSettlementTest {
    private val oldBoot = "a39d8cbe-8c63-411f-9a59-3405c40e0263"
    private val newBoot = "36dde7ee-6bf1-4a47-98b5-19044be5f412"
    private val epoch = "b26b736f-7f95-4fd0-a64f-5191019b83ee"
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10436, 120, listOf("b".repeat(64))), 120, 2_000)

    private fun sample(bootId: String, nowElapsed: Long = 10_000) = GmsConnectionObservation.Sample(
        1, 10208, 263737035, 1_000, 2_000, FcmRecoveryPolicy.SIGNER, bootId, 1_000_000 + nowElapsed, nowElapsed,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, true),
        true, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE,
            1_000_000 + nowElapsed, nowElapsed, 0))

    @Test fun revokedUnknownHeartbeatDuringOffSettlesCleanlyAndResumesOnMasterOnAcrossReboot() = runBlocking {
        val target = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
        val consent = TargetConsent.Recipient(RecipientConsentToken(UUID.randomUUID().toString(),
            1, 1_000, target, VERIFIED_AUTOSTART_FINGERPRINT))
        val pausedAuthority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.USER_PAUSED, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000_100), emptyList(), null), listOf(consent), 3, null)
        val oldSample = sample(oldBoot)
        val bundled = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(oldSample),
            FcmRecoveryPolicy.binding(oldSample), true)
        val attemptId = "6e9c8022-4a47-4a7d-9b30-7f0511099212"
        val sessionId = "f4b90495-2177-4090-9c7b-399640961234"
        val stuckHeartbeat = HeartbeatSnapshot(
            generation = 8,
            configured = true,
            pause = HeartbeatPause.UNKNOWN_RESULT,
            pendingAttempt = attemptId,
            bindingDigest = bundled.installationBinding,
            remoteSessionId = sessionId,
            lastStatus = GmsHeartbeatStatus.UNKNOWN,
            lastReason = GmsHeartbeatReason.SESSION_REVOKED,
            schemaVersion = 3,
            durableConsent = HeartbeatDurableConsent(3, epoch, 0, requireNotNull(bundled.installationBinding)),
            resumeBlock = HeartbeatResumeBlock.UNKNOWN_RESULT,
            lastResultEpoch = epoch,
            callerIdentity = GmsHeartbeatCallerIdentity(10436, 9999, 500, oldBoot),
        )
        val base = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(pausedAuthority), mapOf(
            DaemonDomainRole.SELECTION to TargetSelectionCodec.encode(listOf(target)).toByteArray(),
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(bundled),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, bundled.recoveryBinding)),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(stuckHeartbeat),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_PENDING to HeartbeatPendingCodec.encode(
                HeartbeatPending(HeartbeatPendingKind.HEARTBEAT, epoch, 8, attemptId)),
            DaemonDomainRole.FCM_EPISODES to FcmRecoveryEpisodeCodec.encode(
                FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, listOf(
                    FcmRecoveryEpisode(1, 555, 100, 1_000, 1_000, FcmRecoveryEpisodeKind.SERVICE_ABSENT,
                        1_000, 1_000, requestAtMillis = 1_100, requestElapsedMillis = 1_100,
                        requestCorrelationId = UUID.randomUUID().toString(), requestPending = true)))),
        ))
        val offRecord = base.control.copy(revision = 32, mode = DaemonControlMode.OFF)
        val stuckRoot = DaemonProtectionRootSnapshot(offRecord, base.domains(), dataRevision = 40)

        // Even before settlement, UI projection must never show "停止待确认" when OFF.
        val beforeUi = DaemonUiProjection.project(stuckRoot,
            DaemonAppStatus(offRecord, DaemonOwnerStatus.OFF, 40, false, false), 20_000, 1_020_000)
        assertFalse(beforeUi.automatic.masterEnabled)
        assertNotEquals(FcmMaintenanceState.STOP_PENDING, beforeUi.fcm.maintenance)
        assertFalse(beforeUi.fcm.detail.contains("停止待确认"))
        assertFalse(beforeUi.heartbeatUi.stopPending)

        assertTrue(DaemonQuiescentHeartbeatSettlement.needsSettlement(stuckRoot))
        val (root, _) = RootFixtures.root(stuckRoot)
        val caller = DaemonCallerEvidence(installation.identity.uid, installation)
        var arms = 0
        var workerQuiescent = false
        val envPort = object : DaemonOwnerEnvironmentReadPort {
            override fun environment() = DaemonOwnerEnvironment(2000, 0, newBoot, true)
            override fun guardianPackage() = DaemonGuardianPackage(installation, true, true, false, true)
            override fun packagesForUid(uid: Int) = listOf(GUARDIAN_PACKAGE_NAME)
            override fun shizukuRuntimePermission(uid: Int) = true
            override fun guardianExitHistory(uid: Int) = emptyList<DaemonGuardianExit>()
            override fun wallMillis() = 2_020_000L
        }
        val stoppedShell = object : DaemonStoppedShellPort {
            override fun observe(id: Long, identity: SelectedTargetIdentity, recipient: Boolean, interrupted: () -> Boolean) =
                StoppedTargetObservation.Known(id, identity, false, true, 2_020_000L, qualifiedRecipient = recipient)
            override fun prepareUnstop(identity: SelectedTargetIdentity, recipient: Boolean, active: () -> Boolean): DaemonUnstopLaunch? = null
            override fun close() = Unit
        }
        val fcmShell = object : DaemonFcmShellPort {
            override fun read(id: Long, cancelled: () -> Boolean) = sample(newBoot, 20_000).copy(requestId = id)
            override fun usableNetwork() = true
            override fun disconnectedNetwork() = true
            override fun previousHeartbeatOwnerGone(value: HeartbeatSnapshot, cancelled: () -> Boolean) = true
            override fun recover(id: Long, uid: Int, binding: String, authorize: () -> Boolean, cancelled: () -> Boolean,
                operation: FcmRecoveryOperation, fence: PrivilegedExecutionFence): FcmRecoveryResult = error("unused")
            override fun arm(id: Long, uid: Int, generation: Long, binding: String, lifetime: GmsHeartbeatLifetime,
                cancelled: () -> Boolean): GmsAutoArmResult {
                arms++
                return GmsAutoArmResult(GmsAutoSession(UUID.randomUUID().toString(), generation, binding), GmsAutoArmReason.ARMED)
            }
            override fun heartbeat(id: Long, uid: Int, session: GmsAutoSession, attempt: String,
                cancelled: () -> Boolean, fence: PrivilegedExecutionFence): GmsHeartbeatResult = error("unused")
            override fun revoke(uid: Int, session: GmsAutoSession) = false
            override fun freshHeartbeatEndpoint() = workerQuiescent
            override fun isQuiescent() = workerQuiescent
            override fun close() = Unit
        }

        DaemonProtectionHost(
            ownerKey = offRecord.owner,
            guardian = installation,
            bootId = newBoot,
            migrationId = offRecord.migrationId,
            root = root,
            ownerLease = { it(); true },
            releaseLease = {},
            environmentReadPort = envPort,
            stoppedShell = stoppedShell,
            fcmShell = fcmShell,
            elapsed = { 20_000L },
            wall = { 2_020_000L },
            processId = 555,
            fingerprint = { VERIFIED_AUTOSTART_FINGERPRINT },
            commandClock = { BudgetClock(2, 20_000, 2_020_000) },
            recoveryReady = { true },
            startScheduler = false,
        ).use { host ->
            // Non-quiescent workers must never settle prematurely.
            assertFalse(host.settleQuiescentRuntime(caller, offRecord, 40))
            workerQuiescent = true
            assertTrue(host.settleQuiescentRuntime(caller, offRecord, 40))
            val settled = root.snapshot()
            assertFalse(DaemonQuiescentHeartbeatSettlement.needsSettlement(settled))
            assertNull(settled.bytes(DaemonDomainRole.HEARTBEAT_PENDING))
            assertNull(settled.bytes(DaemonDomainRole.HEARTBEAT_HALT))
            val hb = HeartbeatSnapshotCodec.decode(requireNotNull(settled.bytes(DaemonDomainRole.HEARTBEAT)))
            assertEquals(9L, hb.generation)
            assertEquals(HeartbeatPause.WAITING_HOST, hb.pause)
            assertNull(hb.pendingAttempt)
            assertNull(hb.remoteSessionId)
            assertNull(hb.resumeBlock)
            assertNull(hb.lastStatus)

            // Reconcile succeeds cleanly into OFF.
            val reconChallenge = requireNotNull(host.currentPointerChallenge(caller))
            assertTrue(host.attest(caller, DaemonAppPointerAttestation(reconChallenge, reconChallenge.pointer)))
            assertTrue(host.reconcile(caller, DaemonReconcileProof(reconChallenge.pointer, "recon-1")))
            assertEquals(DaemonOwnerStatus.OFF, host.status(caller).status)

            // Toggling MasterOn transitions directly to ACTIVE (never PENDING_RECOVERY) and arms heartbeat on poll.
            val stageChallenge = requireNotNull(host.stage(caller, offRecord, "master-on-1", DaemonProtectionCommand.MasterOn))
            val request = requireNotNull(host.requestForChallenge(caller, stageChallenge))
            assertTrue(host.attest(caller, DaemonAppPointerAttestation(stageChallenge, stageChallenge.pointer)))
            val reply = host.apply(caller, request)
            assertTrue(reply is DaemonControlReply.Acknowledged)
            assertEquals(DaemonOwnerStatus.ACTIVE, host.status(caller).status)

            host.pollOnce()
            assertEquals(1, arms)
            val afterPoll = root.snapshot()
            val armedHb = HeartbeatSnapshotCodec.decode(requireNotNull(afterPoll.bytes(DaemonDomainRole.HEARTBEAT)))
            assertNull(armedHb.pause)
            assertNotNull(armedHb.remoteSessionId)
            val hs = host.status(caller)
            val appStatus = DaemonAppStatus(hs.record, hs.status, hs.dataRevision, hs.closing,
                hs.leaseReleased, hs.sessionStartedElapsed, hs.sessionStartedAtMillis,
                hs.lastCheckedAtMillis, hs.latestGmsObservation, hs.needsReconciliation, hs.recoveryReadiness)
            val activeUi = DaemonUiProjection.project(afterPoll, appStatus, 20_000, 2_020_000)
            assertEquals(FcmMaintenanceState.ACTIVE, activeUi.fcm.maintenance)
            assertTrue(activeUi.fcm.recoveryReady)
            assertEquals(listOf(target), activeUi.selectedIdentities)
        }
    }
}
