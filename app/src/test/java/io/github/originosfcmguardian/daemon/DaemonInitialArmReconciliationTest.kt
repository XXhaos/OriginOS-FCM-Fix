package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import org.junit.Assert.*
import org.junit.Test

class DaemonInitialArmReconciliationTest {
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val operation = "c5d0cccb-48b4-490b-8568-a2accadfd632"
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val guardian = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10436, 100, listOf("b".repeat(64))), 81, 200)
    private val caller = DaemonCallerEvidence(10436, guardian)
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null), schemaVersion = 3, masterEnabledAtMillis = 1_000)
    private val original = HeartbeatSnapshot(5, true, HeartbeatPause.WAITING_HOST,
        bindingDigest = "d".repeat(64), lastStatus = GmsHeartbeatStatus.REQUESTED,
        lastReason = GmsHeartbeatReason.COMMAND_ACCEPTED, schemaVersion = 3,
        durableConsent = HeartbeatDurableConsent(3, epoch, 0, "d".repeat(64)),
        lastResultEpoch = epoch, callerIdentity = GmsHeartbeatCallerIdentity(10436, 123, 100, boot))
    private val parent = FcmProtectionConsent(true, epoch, original.bindingDigest, "e".repeat(64), true)
    private val prepared = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
        DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(parent),
        DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding)),
        DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(original),
        DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
        DaemonDomainRole.RECOVERY_COUNTS to byteArrayOf(9, 8, 7))).let {
        DaemonProtectionRootSnapshot(it.control.copy(mode = DaemonControlMode.PREPARED,
            migrationId = "7f231d03-499d-4dc7-863a-07599b9b38c4"), it.domains())
    }
    private val raw = DaemonProtectionRootCodec.encode(prepared)
    private val digest = DaemonProtectionRootSnapshot.digest(raw)
    private val active = prepared.control.copy(mode = DaemonControlMode.ACTIVE, revision = 2)
    private val pointer = DaemonPointerCommitProof("original-pointer", active)
    private val receipt = DaemonControlReceipt(DaemonControlRequest("activate", active.owner, 1,
        DaemonControlIntent.Activate(pointer)), active)
    private val failedArm = original.copy(generation = 6, pause = HeartbeatPause.RECONFIRM,
        pendingArm = operation, resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER)
    private val current = DaemonProtectionRootSnapshot(active, prepared.domains() + mapOf(
        DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(failedArm),
        DaemonDomainRole.HEARTBEAT_PENDING to HeartbeatPendingCodec.encode(HeartbeatPending(
            HeartbeatPendingKind.ARM, epoch, failedArm.generation, operation)),
        DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1)), listOf(receipt), dataRevision = 9)
    private fun changed(base: DaemonProtectionRootSnapshot, role: DaemonDomainRole, bytes: ByteArray?) =
        DaemonProtectionRootSnapshot(base.control, base.domains() + (role to bytes), base.receipts, base.halt, base.dataRevision)
    private fun project(value: DaemonProtectionRootSnapshot = current) = DaemonInitialArmReconciliation.project(value, raw, digest)

    @Test fun exactFirstArmFailureCleansOnlyThreeFieldsAndExactTwoBarriersWithoutArming() {
        val result = requireNotNull(project())
        assertEquals(failedArm.copy(pendingArm = null, resumeBlock = null, pause = HeartbeatPause.WAITING_HOST), result.first)
        assertEquals(original.copy(generation = original.generation + 1), result.first)
        assertNull(result.first.remoteSessionId)
        assertEquals(current.control, result.second.control)
        assertEquals(current.receipts, result.second.receipts)
        assertEquals(current.dataRevision + 1, result.second.dataRevision)
        assertNull(result.second.bytes(DaemonDomainRole.HEARTBEAT_PENDING))
        assertNull(result.second.bytes(DaemonDomainRole.HEARTBEAT_HALT))
        for (role in DaemonDomainRole.entries - setOf(DaemonDomainRole.HEARTBEAT,
            DaemonDomainRole.HEARTBEAT_PENDING, DaemonDomainRole.HEARTBEAT_HALT))
            assertArrayEquals(role.name, current.bytes(role), result.second.bytes(role))
        assertNull(DaemonInitialArmReconciliation.project(result.second, raw, digest))
    }

    @Test fun sourceDigestReceiptAndExactInitialRevisionAreRequired() {
        assertNull(DaemonInitialArmReconciliation.project(current, raw, "f".repeat(64)))
        val alteredRaw = raw.copyOf(); alteredRaw[20] = (alteredRaw[20].toInt() xor 1).toByte()
        assertNull(DaemonInitialArmReconciliation.project(current, alteredRaw, DaemonProtectionRootSnapshot.digest(alteredRaw)))
        assertNull(project(DaemonProtectionRootSnapshot(active.copy(revision = 3), current.domains(), current.receipts)))
        assertNull(project(DaemonProtectionRootSnapshot(active, current.domains())))
        val notActivation = receipt.copy(request = receipt.request.copy(intent = DaemonControlIntent.SetAuthority(pointer)))
        assertNull(project(DaemonProtectionRootSnapshot(active, current.domains(), listOf(notActivation))))
        val sourceUnknown = changed(prepared, DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(
            original.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.TIMED_OUT)))
        val unknownRaw = DaemonProtectionRootCodec.encode(sourceUnknown)
        assertNull(DaemonInitialArmReconciliation.project(current, unknownRaw, DaemonProtectionRootSnapshot.digest(unknownRaw)))
    }

    @Test fun changedHistoryConsentGenerationCallerOrAnyHeartbeatRequestIsNotInitialArmProof() {
        val invalid = listOf(
            failedArm.copy(generation = 7), failedArm.copy(remoteSessionId = operation),
            failedArm.copy(pendingAttempt = epoch, remoteSessionId = operation), failedArm.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN,
                lastReason = GmsHeartbeatReason.TIMED_OUT),
            failedArm.copy(callerIdentity = original.callerIdentity!!.copy(pid = 124)),
            failedArm.copy(durableConsent = original.durableConsent!!.copy(epoch = operation)),
            failedArm.copy(resumeBlock = HeartbeatResumeBlock.UNKNOWN_RESULT),
            failedArm.copy(pause = HeartbeatPause.OFF_PENDING),
        )
        for (heartbeat in invalid) assertNull(heartbeat.toString(), project(changed(current,
            DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(heartbeat))))
        assertNull(project(changed(current, DaemonDomainRole.HEARTBEAT_PENDING, HeartbeatPendingCodec.encode(
            HeartbeatPending(HeartbeatPendingKind.HEARTBEAT, epoch, failedArm.generation, operation)))))
        assertNull(project(changed(current, DaemonDomainRole.HEARTBEAT_PENDING, HeartbeatPendingCodec.encode(
            HeartbeatPending(HeartbeatPendingKind.ARM, epoch, failedArm.generation + 1, operation)))))
        assertNull(project(changed(current, DaemonDomainRole.HEARTBEAT_HALT, byteArrayOf(0))))
    }

    @Test fun unrelatedBarriersOrParentChangesCannotBeClearedAsArmUncertainty() {
        for (role in listOf(DaemonDomainRole.CONTROL_PROPOSAL, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED,
            DaemonDomainRole.COUNTS_UNAVAILABLE, DaemonDomainRole.FCM_PROTECTION_HALT,
            DaemonDomainRole.FCM_RECOVERY_HALT, DaemonDomainRole.HEARTBEAT_DAEMON_OWNER,
            DaemonDomainRole.HEARTBEAT_DERIVATION))
            assertNull(role.name, project(changed(current, role, byteArrayOf(1))))
        assertNull(project(changed(current, DaemonDomainRole.FCM_PROTECTION,
            FcmProtectionCodec.encode(parent.copy(recoveryBinding = "c".repeat(64))))))
        assertNull(project(changed(current, DaemonDomainRole.FCM_RECOVERY,
            FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding, events = listOf(
                FcmRecoveryEvent(1_000, 100, FcmRecoveryEventAction.REQUEST_RESULT,
                    status = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.TIMED_OUT)))))))
    }

    @Test fun rootCasRejectsStaleEvidenceAndCommitsExactlyOnce() {
        val (root, disk) = RootFixtures.root(current)
        assertNull(root.reconcileInitialHeartbeatArm(active, current.dataRevision + 1, raw, digest))
        assertNull(root.reconcileInitialHeartbeatArm(active.copy(revision = 3), current.dataRevision, raw, digest))
        assertEquals(1, disk.writes)
        assertNotNull(root.reconcileInitialHeartbeatArm(active, current.dataRevision, raw, digest))
        assertEquals(2, disk.writes)
        assertNull(root.reconcileInitialHeartbeatArm(active, current.dataRevision + 1, raw, digest))
        assertEquals(2, disk.writes)
    }

    @Test fun coldPendingKernelMayRepairButDoesNotAcquireReadinessWithoutNewPointerReconcile() {
        val root = RootFixtures.root(current).first
        val control = object : DaemonControlPort {
            override fun read() = DaemonControlRead(active, pendingRecovery = root.bytes(DaemonDomainRole.HEARTBEAT_PENDING) != null)
            override fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt? = null
            override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = proof == pointer
            override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? = null
            override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead = error("unused")
            override fun reconcile(proof: DaemonReconcileProof) = read().takeIf { proof.pointer == pointer }
            override fun withOwnerLease(start: () -> Unit): Boolean { start(); return true }
        }
        val owner = DaemonOwnerControl(active.owner, guardian, control, DaemonPrerequisitePort { null }, { 10_000L })
        val source = requireNotNull(DaemonInitialArmReconciliation.capture(active, raw, digest))
        assertNull(owner.reconcileInitialHeartbeatArm(root, caller, active, current.dataRevision, source, 10_000))
        assertFalse(owner.reconcile(caller, DaemonReconcileProof(pointer, "before")))
        assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, owner.status())
        assertTrue(owner.quiesceForControl(caller, active))
        assertNull(owner.reconcileInitialHeartbeatArm(root, caller.copy(realUid = 10437), active, current.dataRevision, source, 10_000))
        assertNull(owner.reconcileInitialHeartbeatArm(root, caller, active, current.dataRevision, source, 0))
        assertNotNull(owner.reconcileInitialHeartbeatArm(root, caller, active, current.dataRevision, source, 10_000))
        assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, owner.status())
        assertFalse(owner.isDispatchReady())
        assertTrue(owner.reconcile(caller, DaemonReconcileProof(pointer, "after")))
        assertTrue(owner.isDispatchReady())
    }
}
