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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DaemonHeartbeatDerivationTest {
    private val boot = "01234567-89ab-cdef-0123-456789abcdef"
    private val epoch = "12345678-1234-1234-1234-123456789abc"
    private val historical = GmsHeartbeatCallerIdentity(10436, 123, 100, boot)
    private val guardian = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, historical.uid, 100, listOf("b".repeat(64))), 79, 200)
    private val sample = GmsConnectionObservation.Sample(1, 10208, 263737035, 1_000, 2_000,
        FcmRecoveryPolicy.SIGNER, boot, 1_010_000, 10_000, SocketSource(SourceCompleteness.COMPLETE, true),
        SocketSource(SourceCompleteness.COMPLETE, false), true, emptyList(),
        GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1_010_000, 10_000, 0))
    private val parent = FcmProtectionConsent(true, epoch, gmsHeartbeatBindingDigest(sample), FcmRecoveryPolicy.binding(sample), true)
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null), schemaVersion = 3, masterEnabledAtMillis = 1_000)

    private fun baseline(old: HeartbeatSnapshot? = null, halt: ByteArray? = null): DaemonProtectionRootSnapshot =
        RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), buildMap {
            put(DaemonDomainRole.FCM_PROTECTION, FcmProtectionCodec.encode(parent))
            put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(FcmRecoverySnapshot(true, parent.recoveryBinding)))
            put(DaemonDomainRole.HEARTBEAT, old?.let(HeartbeatSnapshotCodec::encode))
            put(DaemonDomainRole.HEARTBEAT_INITIALIZED, old?.let { byteArrayOf(1) })
            put(DaemonDomainRole.HEARTBEAT_HALT, halt)
        }).let { DaemonProtectionRootSnapshot(it.control.copy(mode = DaemonControlMode.ACTIVE), it.domains()) }

    private fun permitted(base: DaemonProtectionRootSnapshot = baseline()): DaemonProtectionRootSnapshot {
        val roles = base.domains().toMutableMap()
        roles[DaemonDomainRole.HEARTBEAT_DERIVATION] = requireNotNull(DaemonHeartbeatDerivation.prepare(base, base.control, historical))
        return DaemonProtectionRootSnapshot(base.control, roles, base.receipts, base.halt, base.dataRevision)
    }

    private fun changed(base: DaemonProtectionRootSnapshot, role: DaemonDomainRole, bytes: ByteArray?): DaemonProtectionRootSnapshot =
        DaemonProtectionRootSnapshot(base.control, base.domains().toMutableMap().apply { put(role, bytes) },
            base.receipts, base.halt, base.dataRevision)

    private fun plan(base: DaemonProtectionRootSnapshot, mode: DaemonControlMode = DaemonControlMode.ACTIVE,
        patch: Map<DaemonDomainRole, ByteArray?> = emptyMap(), native: ByteArray? = null): DaemonControlTransitionPlan {
        val authorityBytes = patch[DaemonDomainRole.AUTHORITY] ?: base.bytes(DaemonDomainRole.AUTHORITY)!!
        return DaemonControlTransitionPlan("control-${base.control.revision}", base.control,
            base.control.copy(revision = base.control.revision + 1, mode = mode,
                authorityDigest = DaemonProtectionRootSnapshot.digest(authorityBytes)),
            DaemonControlTransition.SET_AUTHORITY, patch, native)
    }

    private fun commit(root: DaemonProtectionRoot, plan: DaemonControlTransitionPlan): DaemonProtectionRootSnapshot {
        val caller = DaemonCallerEvidence(guardian.identity.uid, guardian)
        val registry = DaemonPointerAttestationRegistry(plan.expected.owner, guardian)
        val port = DaemonRootControlPort(root, plan.expected.owner, registry, { true }, { it(); true })
        val challenge = requireNotNull(port.stage(caller, plan) { true })
        check(port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        val request = plan.request(challenge.pointer)
        val fence = requireNotNull(port.fence(plan.expected, request))
        port.commitAndReadBack(fence, DaemonControlReceipt(request, plan.next))
        return root.snapshot()
    }

    @Test fun typedControlPermitCommitsAndMaterializesOnlyItsProjectedExactBaseline() {
        val base = baseline()
        val plain = plan(base)
        val native = requireNotNull(DaemonHeartbeatDerivation.prepare(projectDaemonControl(base, plain), plain.next, historical))
        val proposed = plan(base, native = native)
        val (root, disk) = RootFixtures.root(base)
        val committed = commit(root, proposed)
        assertEquals(proposed.next, committed.control)
        assertArrayEquals(native, committed.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))
        assertEquals(4, disk.writes)
        assertNotNull(DaemonHeartbeatDerivation.materialize(committed, committed.control, sample, boot, 10_000, 10_000, 1_000))

        val tampered = changed(base, DaemonDomainRole.HEARTBEAT_HALT, byteArrayOf(1))
        val (other, _) = RootFixtures.root(tampered)
        RootFixtures.fails { commit(other, proposed) }
        assertNull(other.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        val valid = DaemonHeartbeatDerivationPermitCodec.decode(native)
        for (invalid in listOf(valid.copy(used = true), valid.copy(epoch = "aaaaaaaa-1234-1234-1234-123456789abc"),
            valid.copy(heartbeatBinding = "d".repeat(64)), valid.copy(recoveryBinding = "e".repeat(64)),
            valid.copy(record = valid.record.copy(revision = valid.record.revision + 1)))) {
            RootFixtures.fails { commit(RootFixtures.root(base).first,
                plan(base, native = DaemonHeartbeatDerivationPermitCodec.encode(invalid))) }
        }
    }

    @Test fun nativeBaselineIsRevalidatedAtFenceAndCommitAfterStaging() {
        for (mutateAfterFence in listOf(false, true)) {
            val base = baseline(); val plain = plan(base)
            val native = requireNotNull(DaemonHeartbeatDerivation.prepare(projectDaemonControl(base, plain), plain.next, historical))
            val proposed = plan(base, native = native)
            val (root, _) = RootFixtures.root(base)
            val caller = DaemonCallerEvidence(guardian.identity.uid, guardian)
            val port = DaemonRootControlPort(root, base.control.owner,
                DaemonPointerAttestationRegistry(base.control.owner, guardian), { true }, { it(); true })
            val challenge = requireNotNull(port.stage(caller, proposed) { true })
            assertTrue(port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            val request = proposed.request(challenge.pointer)
            val fence = if (mutateAfterFence) requireNotNull(port.fence(base.control, request)) else null
            root.put(DaemonDomainRole.HEARTBEAT_PENDING, HeartbeatPendingCodec.encode(
                HeartbeatPending(HeartbeatPendingKind.ARM, epoch, 1, epoch)))
            RootFixtures.fails {
                if (fence == null) port.fence(base.control, request)
                else port.commitAndReadBack(fence, DaemonControlReceipt(request, proposed.next))
            }
            assertEquals(base.control, root.snapshot().control)
            assertNull(root.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))
        }
    }

    @Test fun unusedPermitSurvivesMasterOffOnWithModeAndBaselineAndCannotReissueItsEpoch() {
        val base = permitted(); val (root, _) = RootFixtures.root(base)
        val original = DaemonHeartbeatDerivationPermitCodec.decode(base.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!)
        val pausedBytes = ProtectionAuthorityCodec.encode(authority.copy(generation = 2, mode = ProtectionConsentMode.USER_PAUSED))
        val off = commit(root, plan(base, DaemonControlMode.OFF, mapOf(DaemonDomainRole.AUTHORITY to pausedBytes)))
        val paused = DaemonHeartbeatDerivationPermitCodec.decode(off.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!)
        assertEquals(original.copy(record = off.control), paused)
        assertEquals(DaemonControlMode.OFF, paused.record.mode)
        assertNull(DaemonHeartbeatDerivation.materialize(off, off.control, sample, boot, 10_000, 10_000, 1_000))
        val onBytes = ProtectionAuthorityCodec.encode(authority.copy(generation = 3))
        val on = commit(root, plan(off, patch = mapOf(DaemonDomainRole.AUTHORITY to onBytes)))
        val resumed = DaemonHeartbeatDerivationPermitCodec.decode(on.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!)
        assertEquals(original.copy(record = on.control), resumed)
        assertNull(DaemonHeartbeatDerivation.prepare(on, on.control, historical))
        val derived = requireNotNull(DaemonHeartbeatDerivation.materialize(on, on.control, sample, boot, 10_000, 10_000, 1_000))
        assertEquals(epoch, derived.first.durableConsent?.epoch)
        assertTrue(DaemonHeartbeatDerivationPermitCodec.decode(derived.second.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!).used)
        assertNull(DaemonHeartbeatDerivation.prepare(derived.second, derived.second.control, historical))
    }

    @Test fun cleanExactOwnerRetargetsAcrossControlButAlteredDigestOrGenerationNeverDoes() {
        val permitted = permitted()
        val derived = requireNotNull(DaemonHeartbeatDerivation.materialize(permitted, permitted.control, sample, boot, 10_000, 10_000, 1_000)).second
        val original = DaemonHeartbeatOwnerCodec.decode(derived.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)!!)
        val (root, _) = RootFixtures.root(derived)
        val next = commit(root, plan(derived))
        assertEquals(original.copy(record = next.control),
            DaemonHeartbeatOwnerCodec.decode(next.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)!!))
        assertArrayEquals(derived.bytes(DaemonDomainRole.HEARTBEAT), next.bytes(DaemonDomainRole.HEARTBEAT))
        assertTrue(DaemonHeartbeatDerivationPermitCodec.decode(next.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)!!).used)
        val pausedBytes = ProtectionAuthorityCodec.encode(authority.copy(generation = 2, mode = ProtectionConsentMode.USER_PAUSED))
        val off = commit(root, plan(next, DaemonControlMode.OFF, mapOf(DaemonDomainRole.AUTHORITY to pausedBytes)))
        assertEquals(original.copy(record = off.control),
            DaemonHeartbeatOwnerCodec.decode(off.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)!!))
        val activeBytes = ProtectionAuthorityCodec.encode(authority.copy(generation = 3))
        val on = commit(root, plan(off, patch = mapOf(DaemonDomainRole.AUTHORITY to activeBytes)))
        assertEquals(original.copy(record = on.control),
            DaemonHeartbeatOwnerCodec.decode(on.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)!!))
        for (bad in listOf(original.copy(generation = original.generation + 1), original.copy(heartbeatBytesDigest = "f".repeat(64)))) {
            val base = changed(derived, DaemonDomainRole.HEARTBEAT_DAEMON_OWNER, DaemonHeartbeatOwnerCodec.encode(bad))
            val saved = commit(RootFixtures.root(base).first, plan(base))
            assertEquals(bad, DaemonHeartbeatOwnerCodec.decode(saved.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)!!))
        }
    }

    @Test fun drainedFcmOffCanBeConfirmedOnAgainAndIssueOnlyTheNewParentEpoch() {
        val initial = permitted()
        val child = requireNotNull(DaemonHeartbeatDerivation.materialize(initial, initial.control, sample, boot, 10_000, 10_000, 1_000)).second
        val planner = DaemonControlCommandPlanner { _, _ -> DaemonControlTargetValidation.MATCH }
        val context = DaemonControlCommandContext(BudgetClock(1, 10_000, 1_010_000),
            "vivo/device/product:16/BUILD/123:user/release-keys", 10_000, true, true)
        val (root, _) = RootFixtures.root(child)
        val off = commit(root, requireNotNull(planner.plan(child, child.control, "fcm-off", DaemonProtectionCommand.FcmOff, context)))
        val oldChild = HeartbeatSnapshotCodec.decode(off.bytes(DaemonDomainRole.HEARTBEAT)!!)
        assertFalse(oldChild.configured); assertNull(oldChild.pause)
        val onPlan = requireNotNull(planner.plan(off, off.control, "fcm-on", DaemonProtectionCommand.FcmOn(sample, historical), context))
        val projected = projectDaemonControl(off, onPlan)
        val newEpoch = FcmProtectionCodec.decode(projected.bytes(DaemonDomainRole.FCM_PROTECTION)!!).epoch
        assertNotEquals(epoch, newEpoch)
        val permit = requireNotNull(DaemonHeartbeatDerivation.prepare(projected, onPlan.next, historical))
        val on = commit(root, DaemonControlTransitionPlan(onPlan.requestId, onPlan.expected, onPlan.next,
            onPlan.transition, onPlan.patch(), permit))
        val result = requireNotNull(DaemonHeartbeatDerivation.materialize(on, on.control, sample, boot, 10_000, 10_000, 1_000))
        assertEquals(newEpoch, result.first.durableConsent?.epoch)
        assertEquals(oldChild.generation + 1, result.first.generation)
    }

    private inner class Fixture(initial: DaemonProtectionRootSnapshot = permitted()) {
        var now = 10_000L
        var permission = true
        var beforeEnvironment: () -> Unit = {}
        val disk = MemoryDaemonRootStorage()
        val root = RootFixtures.root(initial, disk).first
        val record = initial.control
        val pointer = DaemonPointerCommitProof("initial", record)
        val control = object : DaemonControlPort {
            override fun read() = DaemonControlRead(root.snapshot().control)
            override fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt? = null
            override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = proof == pointer
            override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? = null
            override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead = error("unused")
            override fun reconcile(proof: DaemonReconcileProof) = read().takeIf { proof.pointer == pointer }
            override fun withOwnerLease(start: () -> Unit): Boolean { start(); return true }
        }
        val owner = DaemonOwnerControl(record.owner, guardian, control, DaemonPrerequisitePort { ticket ->
            DaemonWriteEvidence(ticket, now, now + 1_000, true)
        }, { now })
        val environment = DaemonOwnerEnvironmentProbe(object : DaemonOwnerEnvironmentReadPort {
            override fun environment(): DaemonOwnerEnvironment {
                beforeEnvironment(); return DaemonOwnerEnvironment(2000, 0, boot, true)
            }
            override fun guardianPackage() = DaemonGuardianPackage(guardian, true, true, false, true)
            override fun packagesForUid(uid: Int) = listOf(GUARDIAN_PACKAGE_NAME)
            override fun shizukuRuntimePermission(uid: Int) = permission
            override fun guardianExitHistory(uid: Int) = emptyList<DaemonGuardianExit>()
            override fun wallMillis() = 1_010_000L
        })
        val port = DaemonBundledHeartbeatDerivation(owner, root, environment, guardian, boot) { now }
        fun activate() = owner.reconcile(DaemonCallerEvidence(guardian.identity.uid, guardian), DaemonReconcileProof(pointer, "reconcile"))
        fun derive() = port.derive(record, sample)
    }

    @Test fun absentChildMaterializesWithSameControlAndNativeOwnershipInOneCommit() {
        val f = Fixture(); assertTrue(f.activate())
        val before = f.root.snapshot()
        val child = requireNotNull(f.derive())
        val after = f.root.snapshot()
        assertEquals(before.control, after.control); assertEquals(before.receipts, after.receipts)
        assertEquals(before.halt, after.halt); assertEquals(before.dataRevision + 1, after.dataRevision)
        assertEquals(2, f.disk.writes)
        assertEquals(1L, child.generation); assertEquals(3, child.schemaVersion)
        assertEquals(HeartbeatPause.WAITING_HOST, child.pause); assertNull(child.remoteSessionId)
        assertEquals(historical, child.callerIdentity); assertEquals(epoch, child.durableConsent?.epoch)
        assertEquals(3, child.durableConsent?.version)
        val owner = DaemonHeartbeatOwnerCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)))
        assertEquals(after.control, owner.record); assertEquals(daemonHeartbeatOwnerDigest(child), owner.heartbeatBytesDigest)
        assertTrue(DaemonHeartbeatDerivationPermitCodec.decode(requireNotNull(after.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))).used)
        val mutable = setOf(DaemonDomainRole.HEARTBEAT, DaemonDomainRole.HEARTBEAT_INITIALIZED,
            DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_DERIVATION, DaemonDomainRole.HEARTBEAT_DAEMON_OWNER)
        for (role in DaemonDomainRole.entries.filterNot { it in mutable }) assertArrayEquals(before.bytes(role), after.bytes(role))
        assertNull(f.derive()); assertEquals(2, f.disk.writes)
        // Restart cannot turn used consent into a second child, even if the old heartbeat was lost.
        val restarted = Fixture(changed(after, DaemonDomainRole.HEARTBEAT, null).let {
            changed(it, DaemonDomainRole.HEARTBEAT_INITIALIZED, null)
        })
        assertTrue(restarted.activate()); assertNull(restarted.derive())
        assertNull(DaemonHeartbeatDerivation.prepare(after, after.control, historical))
    }

    @Test fun capturedCleanOffAndExactOldHaltCanBeMaterializedPreservingDiagnostics() {
        val old = HeartbeatSnapshot(8, false, lastStatus = GmsHeartbeatStatus.REQUESTED,
            lastReason = GmsHeartbeatReason.COMMAND_ACCEPTED, schemaVersion = 3,
            lastResultEpoch = "aaaaaaaa-1234-1234-1234-123456789abc")
        val f = Fixture(permitted(baseline(old, byteArrayOf(1))))
        assertTrue(f.activate())
        val result = requireNotNull(f.derive())
        assertEquals(9L, result.generation); assertEquals(old.lastStatus, result.lastStatus)
        assertEquals(old.lastReason, result.lastReason); assertEquals(old.lastResultEpoch, result.lastResultEpoch)
        assertNull(f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
    }

    @Test fun changedHaltOffPendingUnknownAndPartiallyLostBaselineNeverDerive() {
        val base = permitted()
        for ((role, bytes) in listOf(
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_PENDING to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(HeartbeatSnapshot(2, false)),
        )) {
            val f = Fixture(changed(base, role, bytes)); assertTrue(f.activate())
            assertNull(f.derive()); assertEquals(1, f.disk.writes)
        }
        for (old in listOf(HeartbeatSnapshot(2, false, HeartbeatPause.OFF_PENDING),
            HeartbeatSnapshot(2, false, lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.TIMED_OUT),
            HeartbeatSnapshot(2, false, schemaVersion = 3, resumeBlock = HeartbeatResumeBlock.STORAGE_FAILURE),
            HeartbeatSnapshot(2, false, schemaVersion = 3, lastResultEpoch = epoch))) {
            val initial = baseline(old)
            assertNull(DaemonHeartbeatDerivation.prepare(initial, initial.control, historical))
        }
    }

    @Test fun orphanHaltCannotBecomeAnAbsentChildPermitOrBeClearedAtMaterialization() {
        val orphan = baseline(halt = byteArrayOf(1))
        assertNull(DaemonHeartbeatDerivation.prepare(orphan, orphan.control, historical))
        // Even an internally constructed exact-baseline permit cannot bypass the root's own check.
        val permit = DaemonHeartbeatDerivationPermitCodec.decode(requireNotNull(permitted().bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)))
            .copy(baselineDigest = DaemonHeartbeatDerivation.baselineDigest(orphan))
        val f = Fixture(changed(orphan, DaemonDomainRole.HEARTBEAT_DERIVATION, DaemonHeartbeatDerivationPermitCodec.encode(permit)))
        assertTrue(f.activate()); assertNull(f.derive()); assertEquals(1, f.disk.writes)
        assertArrayEquals(byteArrayOf(1), f.root.bytes(DaemonDomainRole.HEARTBEAT_HALT))
    }

    @Test fun typedKernelBoundaryRejectsDisconnectedUnknownAndIncompleteSamplesWithoutBurningPermit() {
        val f = Fixture(); assertTrue(f.activate())
        val source = sample.gcmService
        assertThrows(IllegalArgumentException::class.java) { source.copy(userId = 1) }
        val rejected = listOf(
            sample.copy(gcmService = source.copy(state = GcmConnectionState.REPORTED_DISCONNECTED)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.UNKNOWN)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.SERVICE_ABSENT)),
            sample.copy(tcp = SocketSource(SourceCompleteness.COMPLETE, false)),
            sample.copy(tcp6 = SocketSource(SourceCompleteness.PARTIAL, false)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.UNKNOWN, completeness = SourceCompleteness.PARTIAL)),
            sample.copy(gcmService = source.copy(elapsedRealtimeMillis = 10_001)),
        )
        for (candidate in rejected) {
            assertNull(f.owner.deriveBundledHeartbeat(f.root, f.record, f.root.snapshot().dataRevision,
                candidate, boot, f.now))
            assertEquals(1, f.disk.writes)
            assertFalse(DaemonHeartbeatDerivationPermitCodec.decode(requireNotNull(
                f.root.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))).used)
        }
        assertNotNull(f.owner.deriveBundledHeartbeat(f.root, f.record, f.root.snapshot().dataRevision, sample, boot, f.now))
    }

    @Test fun exactParentBothBindingsAndRecordAreRequired() {
        val base = permitted()
        for (newParent in listOf(parent.copy(configured = false), parent.copy(recoveryPrepared = false),
            parent.copy(epoch = "aaaaaaaa-1234-1234-1234-123456789abc"),
            parent.copy(installationBinding = "c".repeat(64)), parent.copy(recoveryBinding = "d".repeat(64)))) {
            val f = Fixture(changed(base, DaemonDomainRole.FCM_PROTECTION, FcmProtectionCodec.encode(newParent)))
            assertTrue(f.activate()); assertNull(f.derive()); assertEquals(1, f.disk.writes)
        }
        val f = Fixture(); assertTrue(f.activate())
        assertNull(f.port.derive(f.record.copy(revision = 2), sample))
        assertNull(f.port.derive(f.record, sample.copy(firstInstallTime = 2_000)))
        assertNull(f.port.derive(f.record, sample.copy(bootId = "aaaaaaaa-89ab-cdef-0123-456789abcdef")))
    }

    @Test fun invalidatedRecipientDoesNotRevokeIndependentBundledParent() {
        val id = "bbbbbbbb-1234-1234-1234-123456789abc"
        val recipient = TargetConsent.Recipient(RecipientConsentToken(id, 1, 100,
            SelectedTargetIdentity("org.telegram.messenger", 10437, 100, listOf("c".repeat(64))), "vendor/device/build:16/release"))
        val bytes = ProtectionAuthorityCodec.encode(authority.copy(targetConsents = listOf(recipient), invalidatedConsentIds = listOf(id)))
        val base = changed(baseline(), DaemonDomainRole.AUTHORITY, bytes).let {
            DaemonProtectionRootSnapshot(it.control.copy(authorityDigest = DaemonProtectionRootSnapshot.digest(bytes)), it.domains())
        }
        val f = Fixture(permitted(base)); assertTrue(f.activate()); assertNotNull(f.derive())
    }

    @Test fun interruptionAuthorityOffAndRecoveryPendingBlockMaterialization() {
        val base = permitted()
        for (role in listOf(DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.AUTHORITY_STOP_PENDING,
            DaemonDomainRole.STOPPED_BLOCKED, DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT)) {
            val f = Fixture(changed(base, role, byteArrayOf(1))); assertTrue(f.activate())
            assertNull(f.derive()); assertEquals(1, f.disk.writes)
        }
        val offAuthority = ProtectionAuthorityCodec.encode(authority.copy(mode = ProtectionConsentMode.USER_PAUSED))
        val off = changed(base, DaemonDomainRole.AUTHORITY, offAuthority)
        val offRoot = DaemonProtectionRootSnapshot(off.control.copy(authorityDigest = DaemonProtectionRootSnapshot.digest(offAuthority)), off.domains())
        val f = Fixture(offRoot); assertTrue(f.activate()); assertNull(f.derive())
    }

    @Test fun unreconciledQuiescedAndInFlightKernelRefuseDerivation() {
        val f = Fixture(); assertNull(f.derive()); assertTrue(f.activate())
        val ticket = requireNotNull(f.owner.admit("pending-operation"))
        assertNull(f.derive()); assertTrue(f.owner.complete(ticket))
        assertTrue(f.owner.quiesceForControl(DaemonCallerEvidence(guardian.identity.uid, guardian), f.record))
        assertNull(f.derive()); assertEquals(1, f.disk.writes)
    }

    @Test fun permissionAndFreshnessCannotBeBypassed() {
        val f = Fixture(); assertTrue(f.activate()); f.permission = false
        assertNull(f.derive()); f.permission = true
        f.beforeEnvironment = { f.now += 1_001 }
        assertNull(f.derive()); assertEquals(1, f.disk.writes)
        f.beforeEnvironment = {}; f.now = 40_001
        assertNull(f.derive()); assertEquals(1, f.disk.writes)
    }

    @Test fun environmentIoHoldsNeitherKernelNorRootAndConcurrentOffClosureWins() {
        val f = Fixture(); assertTrue(f.activate())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.beforeEnvironment = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        var result: HeartbeatSnapshot? = null
        val thread = Thread { result = f.derive() }.also { it.start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            assertEquals(f.record, f.root.snapshot().control)
            assertTrue(f.owner.quiesceForControl(DaemonCallerEvidence(guardian.identity.uid, guardian), f.record))
        } finally { release.countDown(); thread.join(3_000) }
        assertFalse(thread.isAlive); assertNull(result); assertEquals(1, f.disk.writes)
    }

    @Test fun ordinaryAndSameRevisionControlWritesCannotMintPermit() {
        val f = Fixture(baseline())
        val bytes = requireNotNull(DaemonHeartbeatDerivation.prepare(f.root.snapshot(), f.record, historical))
        RootFixtures.fails { f.root.put(DaemonDomainRole.HEARTBEAT_DERIVATION, bytes) }
        RootFixtures.fails { f.root.controlTransaction(f.root.snapshot().dataRevision) {
            it.put(DaemonDomainRole.HEARTBEAT_DERIVATION, bytes)
        } }
        assertEquals(1, f.disk.writes)
    }

    @Test fun failedReadbackPoisonsKernelAndUsedGrantIsNotReplayed() {
        val f = Fixture(); assertTrue(f.activate()); f.disk.loseReadback = true
        assertNull(f.derive()); assertEquals(DaemonOwnerStatus.FAULT, f.owner.status())
        assertNull(f.derive())
        val committed = f.root.inspectForReconciliation()
        assertTrue(DaemonHeartbeatDerivationPermitCodec.decode(requireNotNull(committed.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION))).used)
        RootFixtures.fails { f.root.snapshot() }
    }

    @Test fun preparedImportRejectsActiveAndPerformsAuthenticationBeforeRootLock() {
        val storage = MemoryDaemonRootStorage(); val root = DaemonProtectionRoot(storage)
        RootFixtures.fails { root.initializePreparedImport(baseline()) { true } }
        assertEquals(0, storage.writes)
        val prepared = baseline().let { DaemonProtectionRootSnapshot(it.control.copy(mode = DaemonControlMode.PREPARED), it.domains()) }
        RootFixtures.fails { root.initializePreparedImport(prepared) { false } }
        assertEquals(0, storage.writes)
        root.initializePreparedImport(prepared) { true }
        assertEquals(DaemonControlMode.PREPARED, root.snapshot().control.mode)
    }

    @Test fun bootRebindStillRequiresReconciledOwnerFreshPermissionAndNoSharedFlight() {
        val cold = Fixture()
        assertFalse(cold.port.rebindRecovery(cold.record, sample))
        val revoked = Fixture(); assertTrue(revoked.activate()); revoked.permission = false
        assertFalse(revoked.port.rebindRecovery(revoked.record, sample))
        val busy = Fixture(); assertTrue(busy.activate())
        val ticket = requireNotNull(busy.owner.admit("stopped:in-flight"))
        assertFalse(busy.port.rebindRecovery(busy.record, sample))
        busy.owner.complete(ticket)
        assertTrue(busy.port.rebindRecovery(busy.record, sample))
        for (f in listOf(cold, revoked, busy)) assertEquals(1, f.disk.writes)
    }

    @Test fun controlQuiescenceDuringFreshEnvironmentReadDeniesBootRebindWithoutHoldingKernelLock() {
        val f = Fixture(); assertTrue(f.activate())
        f.beforeEnvironment = {
            assertTrue(f.owner.quiesceForControl(DaemonCallerEvidence(guardian.identity.uid, guardian), f.record))
        }
        assertFalse(f.port.rebindRecovery(f.record, sample))
        assertEquals(1, f.disk.writes)
    }
}
