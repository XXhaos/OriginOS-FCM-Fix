package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import org.junit.Assert.*
import org.junit.Test

class DaemonHeartbeatConfirmationTest {
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val sample = GmsConnectionObservation.Sample(1, 10208, 263737035, 1000, 2000,
        FcmRecoveryPolicy.SIGNER, boot, 1_010_000, 10_000,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
        true, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE,
            1_010_000, 10_000, 0))
    private val caller = GmsHeartbeatCallerIdentity(10436, 123, 100, boot)
    private val parent = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample), FcmRecoveryPolicy.binding(sample), true)
    private val old = HeartbeatSnapshot(181, true, bindingDigest = parent.installationBinding, remoteSessionId = epoch,
        lastStatus = GmsHeartbeatStatus.NOT_SENT, lastReason = GmsHeartbeatReason.SESSION_REVOKED, schemaVersion = 3,
        durableConsent = HeartbeatDurableConsent(3, epoch, 0, requireNotNull(parent.installationBinding)),
        resumeBlock = HeartbeatResumeBlock.SECURITY_FAILURE, lastResultEpoch = epoch, callerIdentity = caller)
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1_000_100), emptyList(), null), schemaVersion = 3, masterEnabledAtMillis = 1000)
    private val context = DaemonControlCommandContext(BudgetClock(1, 10_000, 1_010_000),
        "vivo/device/product:16/BUILD/123:user/release-keys", 10_000, true, true)
    private val planner = DaemonControlCommandPlanner(DaemonControlTargetValidationPort { _, _ -> DaemonControlTargetValidation.MATCH })
    private fun snapshot(child: HeartbeatSnapshot = old, extra: Map<DaemonDomainRole, ByteArray?> = emptyMap()): DaemonProtectionRootSnapshot {
        val recovery = FcmRecoverySnapshot(true, parent.recoveryBinding, consumed = true)
        val base = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.FCM_CONSUMED to FcmRecoveryStoreCodec.encode(recovery),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(child),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1),
        ) + extra)
        return DaemonProtectionRootSnapshot(base.control.copy(mode = DaemonControlMode.ACTIVE), base.domains())
    }
    private fun closure(base: DaemonProtectionRootSnapshot) = DaemonHeartbeatConfirmationClosure(base.control,
        DaemonProtectionRootSnapshot.digest(requireNotNull(base.bytes(DaemonDomainRole.HEARTBEAT))))
    private fun plan(base: DaemonProtectionRootSnapshot, proof: DaemonHeartbeatConfirmationClosure? = closure(base)) =
        planner.plan(base, base.control, "confirm", DaemonProtectionCommand.FcmOn(sample, caller), context.copy(fcmHeartbeatClosure = proof))

    @Test fun exactSettledRevocationNeedsClosureAndRetiresAtomicallyWithoutErasingHistory() {
        val before = snapshot()
        val encoded = DaemonProtectionRootCodec.encode(before)
        assertEquals(old, daemonHeartbeatConfirmationCandidate(before))
        assertNull(plan(before, null))
        assertNull(plan(before, closure(before).copy(heartbeatDigest = "a".repeat(64))))
        val proposal = requireNotNull(plan(before))
        val next = projectDaemonControl(before, proposal)
        val child = HeartbeatSnapshotCodec.decode(requireNotNull(next.bytes(DaemonDomainRole.HEARTBEAT)))
        assertEquals(old.retiredForExplicitConfirmation(), child)
        assertEquals(old.lastReason, child.lastReason)
        assertEquals(old.lastResultEpoch, child.lastResultEpoch)
        assertEquals(old.generation, child.generation)
        assertNull(next.bytes(DaemonDomainRole.HEARTBEAT_HALT))
        assertArrayEquals(before.bytes(DaemonDomainRole.AUTHORITY), next.bytes(DaemonDomainRole.AUTHORITY))
        assertArrayEquals(before.bytes(DaemonDomainRole.FCM_CONSUMED), next.bytes(DaemonDomainRole.FCM_CONSUMED))
        assertEquals(FcmRecoveryStoreCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.FCM_RECOVERY))),
            FcmRecoveryStoreCodec.decode(requireNotNull(next.bytes(DaemonDomainRole.FCM_RECOVERY))))
        val fresh = FcmProtectionCodec.decode(requireNotNull(next.bytes(DaemonDomainRole.FCM_PROTECTION)))
        assertNotEquals(parent.epoch, fresh.epoch)
        assertTrue(fresh.configured && fresh.recoveryPrepared)
        assertNotNull(DaemonHeartbeatDerivation.prepare(next, proposal.next, caller))
        assertArrayEquals(encoded, DaemonProtectionRootCodec.encode(before))
    }

    @Test fun unknownPendingOtherSecurityFailuresMalformedHaltAndOffNeverBecomeReplacement() {
        val blocked = listOf(
            snapshot(old.copy(pendingAttempt = epoch)), snapshot(old.copy(pendingArm = epoch)),
            snapshot(old.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN)),
            snapshot(old.copy(resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER)),
            snapshot(old.copy(lastReason = GmsHeartbeatReason.IDENTITY_CHANGED)),
            snapshot(old.copy(pause = HeartbeatPause.OFF_PENDING)),
            snapshot(old.copy(configured = false, durableConsent = null)),
            snapshot(extra = mapOf(DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(2))),
            snapshot(extra = mapOf(DaemonDomainRole.HEARTBEAT_PENDING to byteArrayOf(1))),
            snapshot(extra = mapOf(DaemonDomainRole.FCM_EPISODES to byteArrayOf(1))),
        )
        blocked.forEach {
            assertNull(daemonHeartbeatConfirmationCandidate(it)); assertNull(plan(it))
            assertNull(daemonHeartbeatRenewal(it, closure(it), sample, boot, 10_000, 10_000, 8_000))
        }
        val before = snapshot()
        assertNull(planner.plan(before, before.control, "not-explicit", DaemonProtectionCommand.FcmOn(sample, caller),
            context.copy(explicitConfirmation = false, fcmHeartbeatClosure = closure(before))))
        val off = DaemonProtectionRootSnapshot(before.control.copy(mode = DaemonControlMode.OFF), before.domains())
        assertNull(plan(off))
    }

    @Test fun settledCleanConfiguredChildCanReconfirmButChangedChildCannotUseStagedRetirement() {
        val clean = old.copy(resumeBlock = null)
        val before = snapshot(clean, mapOf(DaemonDomainRole.HEARTBEAT_HALT to null))
        assertNotNull(plan(before))
        val original = snapshot()
        val proposal = requireNotNull(plan(original))
        val changed = snapshot(old.copy(pendingArm = epoch))
        assertNull(daemonHeartbeatConfirmationRetirement(changed, proposal))
        assertTrue(runCatching { projectDaemonControl(changed, proposal) }.isFailure)
    }

    @Test fun automaticContinuationPreservesEpochHistoryGenerationAndUsedDerivation() {
        val original = snapshot()
        val used = DaemonHeartbeatDerivationPermit(original.control, epoch, requireNotNull(parent.installationBinding),
            requireNotNull(parent.recoveryBinding), DaemonHeartbeatDerivation.baselineDigest(original), caller, used = true)
        val before = snapshot(extra = mapOf(DaemonDomainRole.HEARTBEAT_DERIVATION to DaemonHeartbeatDerivationPermitCodec.encode(used)))
        val result = requireNotNull(daemonHeartbeatRenewal(before, closure(before), sample, boot, 10_000, 10_000, 8_000))
        assertEquals(old.copy(pause = HeartbeatPause.WAITING_HOST, remoteSessionId = null, resumeBlock = null), result.first)
        assertEquals(before.control, result.second.control)
        assertEquals(before.dataRevision + 1, result.second.dataRevision)
        for (role in DaemonDomainRole.entries.filter { it !in setOf(DaemonDomainRole.HEARTBEAT,
            DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_DAEMON_OWNER) })
            assertArrayEquals(role.name, before.bytes(role), result.second.bytes(role))
        val proof = DaemonHeartbeatOwnerCodec.decode(requireNotNull(result.second.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)))
        assertEquals(epoch, proof.epoch); assertEquals(old.generation, proof.generation)
        assertEquals(daemonHeartbeatOwnerDigest(result.first), proof.heartbeatBytesDigest)
        assertNotNull(daemonHeartbeatRenewal(before, closure(before), sample.copy(versionCode = sample.versionCode + 1), boot, 10_000, 10_000, 8_000))
        assertNull(daemonHeartbeatRenewal(before, closure(before), sample.copy(signerSha256 = "a".repeat(64)), boot, 10_000, 10_000, 8_000))
        assertNull(daemonHeartbeatRenewal(before, closure(before), sample, boot, 0, 10_000, 8_000))
        assertNull(daemonHeartbeatRenewal(before, closure(before).copy(heartbeatDigest = "a".repeat(64)), sample, boot, 10_000, 10_000, 8_000))
    }

    @Test fun trustedTargetUpdateRetargetsOnlyBindingsWithoutResettingConsentOrConsumption() {
        val updated = sample.copy(versionCode = sample.versionCode + 1, lastUpdateTime = sample.lastUpdateTime + 1)
        for (terminal in listOf<GmsHeartbeatReason?>(null, GmsHeartbeatReason.SESSION_REVOKED, GmsHeartbeatReason.IDENTITY_CHANGED)) {
            val child = old.copy(resumeBlock = if (terminal == null) null else HeartbeatResumeBlock.SECURITY_FAILURE,
                lastReason = terminal ?: old.lastReason)
            val before = snapshot(child, if (terminal == null) mapOf(DaemonDomainRole.HEARTBEAT_HALT to null) else emptyMap())
            val (resumed, after) = requireNotNull(daemonHeartbeatRenewal(before, closure(before), updated, boot, 10_000, 10_000, 8_000))
            assertEquals(child.copy(pause = HeartbeatPause.WAITING_HOST, remoteSessionId = null, resumeBlock = null,
                bindingDigest = gmsHeartbeatBindingDigest(updated), durableConsent = child.durableConsent!!.copy(
                    bindingDigest = gmsHeartbeatBindingDigest(updated))), resumed)
            val nextParent = FcmProtectionCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.FCM_PROTECTION)))
            assertEquals(parent.copy(installationBinding = gmsHeartbeatBindingDigest(updated),
                recoveryBinding = FcmRecoveryPolicy.binding(updated)), nextParent)
            assertEquals(FcmRecoveryStoreCodec.decode(requireNotNull(before.bytes(DaemonDomainRole.FCM_RECOVERY)))
                .copy(binding = nextParent.recoveryBinding), FcmRecoveryStoreCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.FCM_RECOVERY))))
            for (role in DaemonDomainRole.entries.filter { it !in setOf(DaemonDomainRole.HEARTBEAT,
                DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_DAEMON_OWNER,
                DaemonDomainRole.FCM_PROTECTION, DaemonDomainRole.FCM_RECOVERY) })
                assertArrayEquals(role.name, before.bytes(role), after.bytes(role))
            if (terminal != GmsHeartbeatReason.SESSION_REVOKED)
                assertNull(daemonHeartbeatRenewal(before, closure(before), sample, boot, 10_000, 10_000, 8_000))
        }
        for (child in listOf(old.copy(pendingArm = epoch), old.copy(pendingAttempt = epoch),
            old.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN), old.copy(resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER),
            old.copy(pause = HeartbeatPause.OFF_PENDING), old.copy(configured = false, durableConsent = null))) {
            val before = snapshot(child)
            assertNull(daemonHeartbeatRenewal(before, closure(before), updated, boot, 10_000, 10_000, 8_000))
        }
    }
}
