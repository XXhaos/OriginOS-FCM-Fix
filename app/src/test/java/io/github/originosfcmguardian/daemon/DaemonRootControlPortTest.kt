package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DaemonRootControlPortTest {
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10435, 100, listOf("c".repeat(64))), 79, 200)
    private val caller = DaemonCallerEvidence(10435, installation)
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.DISABLED, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1000), emptyList(), null))
    private val fcm = FcmRecoverySnapshot(true, "a".repeat(64))
    private val hb = HeartbeatSnapshot(1, true, bindingDigest = "b".repeat(64), schemaVersion = 2,
        durableConsent = HeartbeatDurableConsent(1, "96fbde55-15e2-45b6-8bea-0de37312dd94", 0, "b".repeat(64)))

    private inner class Fixture(mode: DaemonControlMode = DaemonControlMode.ACTIVE,
        extras: Map<DaemonDomainRole, ByteArray?> = emptyMap(), failCommitReadback: Boolean = false) {
        val storage = MemoryDaemonRootStorage()
        val initial = RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), mapOf(
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(fcm),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(hb),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1)) + extras).let {
            DaemonProtectionRootSnapshot(it.control.copy(mode = mode), it.domains())
        }
        val rootStorage = if (!failCommitReadback) storage else object : DaemonProtectionRootStorage by storage {
            override fun write(bytes: ByteArray) {
                val snapshot = DaemonProtectionRootCodec.decode(bytes)
                if (snapshot.control.revision > 1 && snapshot.halt == null) storage.loseReadback = true
                storage.write(bytes)
            }
        }
        val root = DaemonProtectionRoot(rootStorage).also { it.initializeImported(initial) }
        val registry = DaemonPointerAttestationRegistry(initial.control.owner, installation)
        var validate: (DaemonControlTransitionPlan) -> Boolean = { true }
        var leaseHeld = true
        val port = newPort(root, registry)
        val kernel = newKernel(port)
        fun newPort(root: DaemonProtectionRoot, registry: DaemonPointerAttestationRegistry) = DaemonRootControlPort(
            root, initial.control.owner, registry, { validate(it) }, { start -> if (!leaseHeld) false else { start(); true } })
        fun newKernel(port: DaemonRootControlPort) = DaemonOwnerControl(initial.control.owner, installation, port,
            DaemonPrerequisitePort { DaemonWriteEvidence(it, 100, 200, true) }, { 100 })
        fun reconcile(kernel: DaemonOwnerControl = this.kernel, port: DaemonRootControlPort = this.port): Boolean {
            val challenge = port.challengeCurrentPointer(caller) { true } ?: return false
            check(port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            return kernel.reconcile(caller, DaemonReconcileProof(challenge.pointer, "current-app-pointer"))
        }
        fun plan(mode: DaemonControlMode = DaemonControlMode.OFF, id: String = "request-${root.snapshot().control.revision}",
            patch: Map<DaemonDomainRole, ByteArray?> = emptyMap()): DaemonControlTransitionPlan {
            val before = root.snapshot().control
            val authorityBytes = if (patch.containsKey(DaemonDomainRole.AUTHORITY)) requireNotNull(patch[DaemonDomainRole.AUTHORITY])
                else requireNotNull(root.bytes(DaemonDomainRole.AUTHORITY))
            return DaemonControlTransitionPlan(id, before, before.copy(revision = before.revision + 1, mode = mode,
                authorityDigest = DaemonProtectionRootSnapshot.digest(authorityBytes)),
                DaemonControlTransition.SET_AUTHORITY, patch)
        }
        fun stage(plan: DaemonControlTransitionPlan): Pair<DaemonPointerChallenge, DaemonControlRequest> {
            val challenge = requireNotNull(port.stage(caller, plan) { kernel.quiesceForControl(caller, plan.expected) })
            check(port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            return challenge to plan.request(challenge.pointer)
        }
    }

    @Test fun atomicOffCommitsConfigurationExactReceiptAndPreservesEveryJournalAndCountByte() {
        val counts = RecoveryCountsCodec.encode(RecoveryCountsLedger(100, countingZoneId = "UTC"))
        val f = Fixture(extras = mapOf(DaemonDomainRole.RECOVERY_COUNTS to counts))
        assertTrue(f.reconcile())
        val ticket = f.kernel.admit("target:unstop")!!
        val prepared = f.kernel.prepareWrite(ticket)!!
        val nextAuthority = ProtectionAuthorityCodec.encode(authority.copy(generation = 2))
        val plan = f.plan(patch = mapOf(DaemonDomainRole.AUTHORITY to nextAuthority,
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(fcm.copy(configured = false)),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(hb.copy(configured = false, durableConsent = null)),
            DaemonDomainRole.FCM_RECOVERY_HALT to byteArrayOf(1), DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1)))
        val (_, request) = f.stage(plan)
        assertFalse(f.kernel.isDispatchReady())
        assertFalse(f.kernel.startPrepared(prepared) { fail("Quiesced command started") })
        val reply = f.kernel.apply(caller, request) as DaemonControlReply.Acknowledged
        val saved = f.root.snapshot()
        assertEquals(plan.next, saved.control)
        assertEquals(listOf(reply.receipt), saved.receipts)
        assertNull(saved.halt)
        assertNull(saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        assertArrayEquals(nextAuthority, saved.bytes(DaemonDomainRole.AUTHORITY))
        assertArrayEquals(counts, saved.bytes(DaemonDomainRole.RECOVERY_COUNTS))
        assertFalse(FcmRecoveryStoreCodec.decode(saved.bytes(DaemonDomainRole.FCM_RECOVERY)!!).configured)
        assertFalse(HeartbeatSnapshotCodec.decode(saved.bytes(DaemonDomainRole.HEARTBEAT)!!).configured)
        assertEquals(4, f.storage.writes) // import, exact proposal, durable fence, atomic config+receipt commit
        assertEquals(DaemonOwnerStatus.OFF, f.kernel.status())
    }

    @Test fun closurePrecedesTrustedCallbackAndAppAttestationAndFailureNeverReopensAdmission() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan()
        var validated = false
        f.validate = {
            validated = true
            assertFalse(f.kernel.isDispatchReady())
            assertNull(f.kernel.admit("target:unstop"))
            false
        }
        assertNull(f.port.stage(caller, plan) { f.kernel.quiesceForControl(caller, plan.expected) })
        assertTrue(validated)
        assertFalse(f.kernel.isDispatchReady())
        assertEquals(1, f.storage.writes)
    }

    @Test fun untrustedCallerCannotQuiesceValidateAttestOrBorrowAnotherOwnerChallenge() {
        val f = Fixture(); assertTrue(f.reconcile())
        val impostor = caller.copy(realUid = caller.realUid + 1)
        assertNull(f.port.stage(impostor, f.plan()) { fail("Unauthenticated quiesce"); true })
        val challenge = f.port.stage(caller, f.plan()) { f.kernel.quiesceForControl(caller, f.root.snapshot().control) }!!
        assertFalse(f.port.attest(impostor, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertFalse(f.port.verifyPointerCommit(challenge.pointer))
        assertTrue(f.port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertFalse(f.port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        val freshRegistry = DaemonPointerAttestationRegistry(f.initial.control.owner, installation)
        val freshPort = f.newPort(DaemonProtectionRoot(f.storage), freshRegistry)
        assertFalse(freshPort.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertFalse(freshPort.verifyPointerCommit(challenge.pointer))
    }

    @Test fun lostAcknowledgementIsIdempotentAfterRestartButIdReuseAndOldRevisionCannotMutate() {
        val f = Fixture(); assertTrue(f.reconcile())
        val (challenge, request) = f.stage(f.plan())
        assertTrue(f.kernel.apply(caller, request) is DaemonControlReply.Acknowledged)
        assertFalse(f.port.verifyPointerCommit(challenge.pointer))
        val writes = f.storage.writes
        val port = f.newPort(DaemonProtectionRoot(f.storage), DaemonPointerAttestationRegistry(f.initial.control.owner, installation))
        val restarted = f.newKernel(port)
        assertEquals(DaemonOwnerStatus.UNRECONCILED, restarted.status())
        assertTrue(restarted.query(caller, request) is DaemonControlReply.Acknowledged)
        assertTrue(restarted.apply(caller, request) is DaemonControlReply.Acknowledged)
        val conflict = request.copy(intent = DaemonControlIntent.Prepare("different", request.owner.installInstance.padEnd(64, 'a').take(64)))
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.REQUEST_CONFLICT), restarted.apply(caller, conflict))
        assertEquals(writes, f.storage.writes)
        assertFalse(restarted.isDispatchReady())
    }

    @Test fun mutatedChallengeCannotCommitTheDurablyStagedProposal() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan()
        val challenge = f.port.stage(caller, plan) { f.kernel.quiesceForControl(caller, plan.expected) }!!
        val changed = challenge.copy(patchDigest = "0".repeat(64))
        assertFalse(f.port.attest(caller, DaemonAppPointerAttestation(changed, changed.pointer)))
        assertFalse(f.port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
        assertFalse(f.port.verifyPointerCommit(challenge.pointer))
        assertTrue(f.kernel.apply(caller, plan.request(challenge.pointer)) is DaemonControlReply.Rejected)
        assertEquals(2, f.storage.writes)
    }

    @Test fun onlyNewestStagedProposalAtTheSameExpectedRevisionCanCommit() {
        val f = Fixture(); assertTrue(f.reconcile())
        val (old, oldRequest) = f.stage(f.plan(id = "older-proposal"))
        assertTrue(f.port.verifyPointerCommit(old.pointer))
        val newestPlan = f.plan(id = "newer-proposal")
        val newest = requireNotNull(f.port.stage(caller, newestPlan) {
            f.kernel.quiesceForControl(caller, newestPlan.expected)
        })
        assertEquals(old.pointer.record, newest.pointer.record)
        assertFalse(f.port.verifyPointerCommit(old.pointer))
        assertFalse(f.port.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        assertNull(f.port.fence(newestPlan.expected, oldRequest))
        assertEquals(3, f.storage.writes)
        assertTrue(f.port.attest(caller, DaemonAppPointerAttestation(newest, newest.pointer)))
        assertTrue(f.kernel.apply(caller, newestPlan.request(newest.pointer)) is DaemonControlReply.Acknowledged)
        assertEquals("newer-proposal", f.root.snapshot().receipts.single().request.requestId)
    }

    @Test fun stagingRevokesOlderReconciliationAndRejectedNewProposalDoesNotRestoreOldProof() {
        val f = Fixture(); assertTrue(f.reconcile())
        val old = requireNotNull(f.port.challengeCurrentPointer(caller) {
            f.kernel.quiesceForControl(caller, f.initial.control)
        })
        assertTrue(f.port.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        f.validate = { false }
        assertNull(f.port.stage(caller, f.plan()) { f.kernel.quiesceForControl(caller, f.initial.control) })
        assertNull(f.port.reconcile(DaemonReconcileProof(old.pointer, "stale-current-pointer")))
        assertFalse(f.kernel.isDispatchReady())
        assertEquals(1, f.storage.writes)
        f.validate = { true }
        assertTrue(f.reconcile())
        assertTrue(f.kernel.isDispatchReady())
    }

    @Test fun closureAndTrustedValidationCallbacksRunOutsideKernelRootAndRegistryMonitors() {
        val f = Fixture(); assertTrue(f.reconcile())
        val other = Executors.newSingleThreadExecutor()
        try {
            fun checkOtherThreadCanRead() {
                val observed = other.submit<Boolean> {
                    f.registry.invalidateCurrent()
                    f.root.snapshot().control == f.kernel.snapshot()
                }
                assertTrue(observed.get(2, TimeUnit.SECONDS))
            }
            f.validate = { checkOtherThreadCanRead(); assertFalse(f.kernel.isDispatchReady()); true }
            val plan = f.plan()
            assertNotNull(f.port.stage(caller, plan) {
                checkOtherThreadCanRead()
                f.kernel.quiesceForControl(caller, plan.expected)
            })
        } finally { other.shutdownNow() }
    }

    @Test fun abandonedStagingAndReconciliationChallengesCannotExhaustTheOwnerRegistry() {
        val f = Fixture(); assertTrue(f.reconcile())
        val abandoned = ArrayList<DaemonPointerChallenge>()
        repeat(40) {
            abandoned += requireNotNull(f.port.challengeCurrentPointer(caller) {
                f.kernel.quiesceForControl(caller, f.initial.control)
            })
        }
        var newestPlan: DaemonControlTransitionPlan? = null
        repeat(40) { index ->
            val plan = f.plan(id = "abandoned-$index")
            newestPlan = plan
            abandoned += requireNotNull(f.port.stage(caller, plan) {
                f.kernel.quiesceForControl(caller, plan.expected)
            })
        }
        for (challenge in abandoned.dropLast(1)) {
            assertFalse(f.port.attest(caller, DaemonAppPointerAttestation(challenge, challenge.pointer)))
            assertFalse(f.port.verifyPointerCommit(challenge.pointer))
        }
        val newest = abandoned.last()
        assertTrue(f.port.attest(caller, DaemonAppPointerAttestation(newest, newest.pointer)))
        assertTrue(f.kernel.apply(caller, requireNotNull(newestPlan).request(newest.pointer)) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.OFF, f.kernel.status())
        assertEquals(43, f.storage.writes)
    }

    @Test fun currentBookkeepingWinsWhenConfigWasStagedBeforeConcurrentDomainCommit() {
        val f = Fixture(); assertTrue(f.reconcile())
        val (_, request) = f.stage(f.plan(patch = mapOf(
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(fcm.copy(configured = false)),
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(hb.copy(configured = false, durableConsent = null)))))
        val stores = DaemonDomainStores(f.root)
        val event = FcmRecoveryEvent(1000, 100, FcmRecoveryEventAction.POWER_SAMPLE)
        val newestFcm = fcm.copy(events = listOf(event), powerSampleAtMillis = 1000)
        val newestHb = hb.copy(generation = 3, pause = HeartbeatPause.HOST_LOST,
            lastStatus = GmsHeartbeatStatus.NOT_SENT, lastReason = GmsHeartbeatReason.CALLER_UNAVAILABLE)
        stores.fcmRecovery.write(newestFcm)
        stores.heartbeat.write(newestHb)
        val countBytes = RecoveryCountsCodec.encode(RecoveryCountsLedger(100, countingZoneId = "UTC"))
        stores.recoveryCounts.write(countBytes)
        assertTrue(f.kernel.apply(caller, request) is DaemonControlReply.Acknowledged)
        assertEquals(newestFcm.copy(configured = false), stores.fcmRecovery.read())
        assertEquals(newestHb.copy(configured = false, durableConsent = null), stores.heartbeat.read())
        assertArrayEquals(countBytes, stores.recoveryCounts.read())
        RootFixtures.fails { stores.fcmRecovery.write(newestFcm.copy(powerSampleAtMillis = 2000)) }
        assertFalse(stores.fcmRecovery.read().configured)
    }

    @Test fun restartResumesExactSavedProposalWithFreshAttestationAndKeepsNewerBookkeeping() {
        val f = Fixture(); assertTrue(f.reconcile())
        val nextConsent = hb.durableConsent!!.copy(epoch = "470130f6-6518-44fb-8ab6-c76daee78ffb")
        val plan = f.plan(DaemonControlMode.ACTIVE, "exact-before-crash", mapOf(
            DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(hb.copy(durableConsent = nextConsent)),
            DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(fcm.copy(configured = false))))
        val (old, _) = f.stage(plan) // App has committed/read back next pointer, daemon apply has not run.
        val stagedBytes = requireNotNull(f.root.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        assertArrayEquals(DaemonControlProposalCodec.encode(plan), stagedBytes)
        assertEquals(plan.expected, f.root.snapshot().control)
        val stores = DaemonDomainStores(f.root)
        val latestHb = hb.copy(generation = 3, pause = HeartbeatPause.HOST_LOST,
            lastStatus = GmsHeartbeatStatus.NOT_SENT, lastReason = GmsHeartbeatReason.CALLER_UNAVAILABLE)
        val latestFcm = fcm.copy(events = listOf(FcmRecoveryEvent(1000, 100, FcmRecoveryEventAction.POWER_SAMPLE)),
            powerSampleAtMillis = 1000)
        stores.heartbeat.write(latestHb); stores.fcmRecovery.write(latestFcm)
        val restartedRoot = DaemonProtectionRoot(f.storage)
        val port = f.newPort(restartedRoot, DaemonPointerAttestationRegistry(plan.expected.owner, installation))
        val restarted = f.newKernel(port)
        assertEquals(DaemonOwnerStatus.UNRECONCILED, restarted.status())
        assertFalse(restarted.isDispatchReady())
        assertFalse(port.verifyPointerCommit(old.pointer))
        assertNull(port.challengeCurrentPointer(caller) { true }) // Cannot fall back to the old ACTIVE record.
        f.validate = { recovered ->
            assertArrayEquals(stagedBytes, DaemonControlProposalCodec.encode(recovered))
            assertFalse(restarted.isDispatchReady())
            true
        }
        val fresh = requireNotNull(port.resumeStaged(caller, old.pointer.record) {
            restarted.status() == DaemonOwnerStatus.UNRECONCILED && !restarted.isDispatchReady()
        })
        assertNotEquals(old.nonce, fresh.nonce)
        assertEquals(old.pointer.record, fresh.pointer.record)
        assertEquals(old.patchDigest, fresh.patchDigest)
        assertFalse(port.attest(caller, DaemonAppPointerAttestation(old, old.pointer)))
        assertFalse(port.verifyPointerCommit(fresh.pointer))
        assertFalse(restarted.isDispatchReady())
        assertTrue(port.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        val request = plan.request(fresh.pointer)
        assertTrue(restarted.resumeAttestedTransition(caller, request) is DaemonControlReply.Acknowledged)
        val saved = restartedRoot.snapshot()
        assertEquals(plan.next, saved.control)
        assertEquals(request, saved.receipts.single().request)
        assertNull(saved.halt)
        assertNull(saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        val resumedStores = DaemonDomainStores(restartedRoot)
        assertEquals(latestHb.copy(durableConsent = nextConsent), resumedStores.heartbeat.read())
        assertEquals(latestFcm.copy(configured = false), resumedStores.fcmRecovery.read())
        assertTrue(restarted.isDispatchReady())
        assertNull(port.resumeStaged(caller, plan.next) { true }) // An already committed root reconciles its current pointer.
    }

    @Test fun resumeRejectsUntrustedStaleOrDifferentPointerAndNeverChangesSavedProposal() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan()
        f.stage(plan)
        val root = DaemonProtectionRoot(f.storage)
        val port = f.newPort(root, DaemonPointerAttestationRegistry(plan.expected.owner, installation))
        val original = f.storage.saved!!.copyOf()
        val impostor = caller.copy(realUid = caller.realUid + 1)
        assertNull(port.resumeStaged(impostor, plan.next) { fail("Untrusted closure"); true })
        assertNull(port.resumeStaged(caller, plan.next.copy(revision = plan.next.revision + 1)) { true })
        assertNull(port.resumeStaged(caller, plan.next.copy(migrationId = "different")) { true })
        assertArrayEquals(original, f.storage.saved)
        val stale = DaemonControlTransitionPlan("stale", plan.next,
            plan.next.copy(revision = plan.next.revision + 1), DaemonControlTransition.SET_AUTHORITY)
        root.put(DaemonDomainRole.CONTROL_PROPOSAL, DaemonControlProposalCodec.encode(stale))
        val staleBytes = f.storage.saved!!.copyOf()
        assertNull(port.resumeStaged(caller, stale.next) { true })
        assertArrayEquals(staleBytes, f.storage.saved)
        assertNull(port.challengeCurrentPointer(caller) { true })
    }

    @Test fun changedPlanCannotReuseStagedOrCommittedRequestIdAndCommitClearsTheSavedPlan() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan(id = "one-request")
        val (old, _) = f.stage(plan)
        val savedProposal = requireNotNull(f.root.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        val conflicting = f.plan(DaemonControlMode.ACTIVE, plan.requestId)
        assertNull(f.port.stage(caller, conflicting) { f.kernel.quiesceForControl(caller, plan.expected) })
        assertArrayEquals(savedProposal, f.root.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        assertFalse(f.port.verifyPointerCommit(old.pointer))
        val fresh = requireNotNull(f.port.resumeStaged(caller, plan.next) {
            f.kernel.quiesceForControl(caller, plan.expected)
        })
        assertTrue(f.port.attest(caller, DaemonAppPointerAttestation(fresh, fresh.pointer)))
        assertTrue(f.kernel.apply(caller, plan.request(fresh.pointer)) is DaemonControlReply.Acknowledged)
        assertNull(f.root.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        val writes = f.storage.writes
        val reused = f.plan(id = plan.requestId)
        assertNull(f.port.stage(caller, reused) { f.kernel.quiesceForControl(caller, reused.expected) })
        assertEquals(writes, f.storage.writes)
        assertNull(f.root.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
    }

    @Test fun commitRequiresTheExactSavedProposalAndCannotClearAChangedOne() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan()
        val (_, request) = f.stage(plan)
        val fence = requireNotNull(f.port.fence(plan.expected, request))
        val other = f.plan(id = "changed-after-fence")
        val otherBytes = DaemonControlProposalCodec.encode(other)
        f.root.put(DaemonDomainRole.CONTROL_PROPOSAL, otherBytes)
        RootFixtures.fails { f.port.commitAndReadBack(fence, DaemonControlReceipt(request, plan.next)) }
        val saved = f.root.snapshot()
        assertEquals(plan.expected, saved.control)
        assertEquals(fence, saved.halt)
        assertTrue(saved.receipts.isEmpty())
        assertArrayEquals(otherBytes, saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
    }

    @Test fun proposalReadbackFailureNeverIssuesAChallengeOrReopensAdmission() {
        val f = Fixture(); assertTrue(f.reconcile())
        val plan = f.plan()
        f.storage.loseReadback = true
        RootFixtures.fails { f.port.stage(caller, plan) { f.kernel.quiesceForControl(caller, plan.expected) } }
        assertFalse(f.kernel.isDispatchReady())
        RootFixtures.fails { f.port.read() }
        val saved = f.port.inspectForReconciliation()
        assertEquals(plan.expected, saved.control)
        assertNull(saved.halt)
        assertTrue(saved.receipts.isEmpty())
        assertArrayEquals(DaemonControlProposalCodec.encode(plan), saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
    }

    @Test fun failedDurableFenceNeverAcknowledgesOffAndRestartRetainsExactInterruptedRequest() {
        val f = Fixture(); assertTrue(f.reconcile())
        val (_, request) = f.stage(f.plan())
        f.storage.loseReadback = true
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.STORAGE), f.kernel.apply(caller, request))
        assertEquals(DaemonOwnerStatus.FAULT, f.kernel.status())
        val saved = f.port.inspectForReconciliation()
        assertEquals(f.initial.control, saved.control)
        assertEquals(DaemonHaltFence(request, 2), saved.halt)
        assertTrue(saved.receipts.isEmpty())
        val port = f.newPort(DaemonProtectionRoot(f.storage), DaemonPointerAttestationRegistry(f.initial.control.owner, installation))
        val restarted = f.newKernel(port)
        assertEquals(DaemonOwnerStatus.FAULT, restarted.status())
        assertNull(port.challengeCurrentPointer(caller) { true })
        assertNull(port.resumeStaged(caller, (request.intent as DaemonControlIntent.SetAuthority).pointer.record) { true })
        assertFalse(restarted.isDispatchReady())
    }

    @Test fun fenceChecksExactPayloadAndCommitCannotClearAChangedFence() {
        val f = Fixture(); assertTrue(f.reconcile())
        val (_, request) = f.stage(f.plan())
        assertNull(f.port.fence(f.initial.control, request.copy(expectedRevision = 2)))
        val fence = f.port.fence(f.initial.control, request)!!
        val other = fence.copy(request = request.copy(requestId = "not-the-halted-request"))
        RootFixtures.fails { f.port.commitAndReadBack(other, DaemonControlReceipt(other.request,
            f.initial.control.copy(revision = 2, mode = DaemonControlMode.OFF))) }
        assertEquals(fence, f.root.snapshot().halt)
        assertEquals(3, f.storage.writes)
    }

    @Test fun lostCommitReadbackReturnsStorageFailureEvenWhenOffAndReceiptWereDurablyWritten() {
        val f = Fixture(failCommitReadback = true); assertTrue(f.reconcile())
        val (_, request) = f.stage(f.plan())
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.STORAGE), f.kernel.apply(caller, request))
        assertFalse(f.kernel.isDispatchReady())
        assertEquals(DaemonOwnerStatus.FAULT, f.kernel.status())
        val persisted = f.port.inspectForReconciliation()
        assertEquals(DaemonControlMode.OFF, persisted.control.mode)
        assertNull(persisted.halt)
        assertNull(persisted.bytes(DaemonDomainRole.CONTROL_PROPOSAL))
        assertEquals(request, persisted.receipts.single().request)
        RootFixtures.fails { f.port.read() }
        val port = f.newPort(DaemonProtectionRoot(f.storage), DaemonPointerAttestationRegistry(f.initial.control.owner, installation))
        val restarted = f.newKernel(port)
        assertTrue(restarted.query(caller, request) is DaemonControlReply.Acknowledged)
        assertFalse(restarted.isDispatchReady())
        assertTrue(f.reconcile(restarted, port))
        assertEquals(DaemonOwnerStatus.OFF, restarted.status())
    }

    @Test fun prepareThenActivationRequiresAppAttestationAndPreservesImportedAuthority() {
        val f = Fixture(DaemonControlMode.OFF); assertTrue(f.reconcile())
        val before = f.root.snapshot().control
        val prepare = DaemonControlRequest("prepare", before.owner, before.revision,
            DaemonControlIntent.Prepare("migration-next", before.authorityDigest))
        assertTrue(f.kernel.apply(caller, prepare) is DaemonControlReply.Acknowledged)
        val prepared = f.root.snapshot().control
        val plan = DaemonControlTransitionPlan("activate", prepared, prepared.copy(revision = 3, mode = DaemonControlMode.ACTIVE),
            DaemonControlTransition.ACTIVATE)
        val (challenge, request) = f.stage(plan)
        assertTrue(f.kernel.apply(caller, request) is DaemonControlReply.Acknowledged)
        assertTrue(f.kernel.isDispatchReady())
        assertFalse(f.port.verifyPointerCommit(challenge.pointer))
        assertArrayEquals(ProtectionAuthorityCodec.encode(authority), f.root.bytes(DaemonDomainRole.AUTHORITY))
    }

    @Test fun pendingStoppedHeartbeatAndFcmCodecsBlockWritesWithoutMutatingAnyImportedByte() {
        val target = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
        val stopped = RecoveryJournalCodec.encode(RecoveryJournalState(1, StoppedRecoveryRecord(1, target, 1000, 900,
            VERIFIED_AUTOSTART_FINGERPRINT, 0, true, true, true, StoppedRecoveryStage.CLAIMED)))
        val heartbeat = HeartbeatPendingCodec.encode(HeartbeatPending(HeartbeatPendingKind.ARM,
            "96fbde55-15e2-45b6-8bea-0de37312dd94", 1, "470130f6-6518-44fb-8ab6-c76daee78ffb"))
        val fcmPending = FcmRecoveryEpisode(1, 1, 0, 900, 0, FcmRecoveryEpisodeKind.SERVICE_ABSENT,
            1000, 100, requestAtMillis = 1000, requestElapsedMillis = 100,
            requestCorrelationId = "470130f6-6518-44fb-8ab6-c76daee78ffb", requestPending = true)
        val cases = listOf(mapOf(DaemonDomainRole.STOPPED_JOURNAL to stopped),
            mapOf(DaemonDomainRole.HEARTBEAT_PENDING to heartbeat),
            mapOf(DaemonDomainRole.FCM_EPISODES to FcmRecoveryEpisodeCodec.encode(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, episodes = listOf(fcmPending)))))
        for (extras in cases) {
            val f = Fixture(extras = extras)
            val original = f.storage.saved!!.copyOf()
            assertTrue(f.port.read().pendingRecovery)
            assertFalse(f.reconcile())
            assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.kernel.status())
            assertNull(f.kernel.admit("target:unstop"))
            assertArrayEquals(original, f.storage.saved)
            val (_, request) = f.stage(f.plan())
            assertTrue(f.kernel.apply(caller, request) is DaemonControlReply.Acknowledged)
            assertEquals(DaemonOwnerStatus.OFF, f.kernel.status())
            for ((role, bytes) in extras) assertArrayEquals(bytes, f.root.bytes(role))
        }
    }

    @Test fun consumedAttemptAndEndedUnknownFcmHistoryAreNotAnUnsettledExecutionBarrier() {
        val consumed = fcm.copy(consumed = true, consumedBoot = "12345678-1234-1234-1234-123456789abc",
            consumedAt = 1000, consumedElapsed = 100)
        val historical = FcmRecoveryEpisode(1, 1, 0, 900, 0, FcmRecoveryEpisodeKind.SERVICE_ABSENT,
            1000, 100, requestAtMillis = 1000, requestElapsedMillis = 100,
            requestStatus = FcmRecoveryStatus.UNKNOWN, reason = FcmRecoveryReason.BRIDGE_UNAVAILABLE,
            endedAtMillis = 1000, endedElapsedMillis = 100, outcome = FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN)
        val extras = mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(consumed),
            DaemonDomainRole.FCM_CONSUMED to FcmRecoveryStoreCodec.encode(consumed, legacyMarker = true),
            DaemonDomainRole.FCM_EPISODES to FcmRecoveryEpisodeCodec.encode(FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, episodes = listOf(historical))))
        val f = Fixture(extras = extras)
        val original = f.storage.saved!!.copyOf()
        assertFalse(f.port.read().pendingRecovery)
        assertTrue(f.reconcile())
        assertArrayEquals(original, f.storage.saved)
        for ((role, bytes) in extras) assertArrayEquals(bytes, f.root.bytes(role))
    }

    @Test fun heartbeatUnknownBlocksItsCurrentConsentEpochButPreservesOlderEpochHistory() {
        val unknown = hb.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.TIMED_OUT,
            lastResultEpoch = hb.durableConsent!!.epoch)
        val current = Fixture(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(unknown)))
        assertTrue(current.port.read().pendingRecovery)
        assertFalse(current.reconcile())
        val historical = unknown.copy(lastResultEpoch = "470130f6-6518-44fb-8ab6-c76daee78ffb")
        val historicalBytes = HeartbeatSnapshotCodec.encode(historical)
        val old = Fixture(extras = mapOf(DaemonDomainRole.HEARTBEAT to historicalBytes))
        assertFalse(old.port.read().pendingRecovery)
        assertTrue(old.reconcile())
        assertArrayEquals(historicalBytes, old.root.bytes(DaemonDomainRole.HEARTBEAT))
    }

    @Test fun onlyExplicitConfigurationRolesAreAcceptedAndHistoricalBudgetCannotBeReset() {
        val f = Fixture(); assertTrue(f.reconcile())
        for (role in listOf(DaemonDomainRole.STOPPED_JOURNAL, DaemonDomainRole.RECOVERY_COUNTS,
            DaemonDomainRole.FCM_EPISODES, DaemonDomainRole.HEARTBEAT_PENDING, DaemonDomainRole.AUTHORITY_INITIALIZED,
            DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.CONTROL_PROPOSAL)) RootFixtures.fails { f.plan(patch = mapOf(role to null)) }
        val reset = authority.copy(budget = authority.budget.copy(clock = BudgetClock(1, 0, 0)))
        RootFixtures.fails { f.stage(f.plan(patch = mapOf(DaemonDomainRole.AUTHORITY to ProtectionAuthorityCodec.encode(reset)))) }
        val fcmReset = fcm.copy(events = listOf(FcmRecoveryEvent(1000, 100, FcmRecoveryEventAction.OBSERVATION)))
        RootFixtures.fails { f.stage(f.plan(patch = mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(fcmReset)))) }
        assertEquals(1, f.storage.writes)
        assertFalse(f.kernel.isDispatchReady())
    }

    @Test fun cleanDrainedWaitingHostOffNormalizesOnlyPauseAndPreservesOtherHistory() {
        val historical = GmsHeartbeatCallerIdentity(installation.identity.uid, 123, 100,
            "01234567-89ab-cdef-0123-456789abcdef")
        val waiting = hb.copy(pause = HeartbeatPause.WAITING_HOST, schemaVersion = 3, callerIdentity = historical)
        val f = Fixture(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(waiting)))
        assertTrue(f.reconcile())
        val plan = f.plan(DaemonControlMode.ACTIVE, patch = mapOf(DaemonDomainRole.HEARTBEAT to
            HeartbeatSnapshotCodec.encode(waiting.copy(configured = false, durableConsent = null))))
        val projected = projectDaemonControl(f.root.snapshot(), plan)
        val expected = waiting.copy(configured = false, durableConsent = null, pause = null)
        assertEquals(expected, HeartbeatSnapshotCodec.decode(projected.bytes(DaemonDomainRole.HEARTBEAT)!!))
        val (_, request) = f.stage(plan)
        assertTrue(f.kernel.apply(caller, request) is DaemonControlReply.Acknowledged)
        assertEquals(expected, HeartbeatSnapshotCodec.decode(f.root.bytes(DaemonDomainRole.HEARTBEAT)!!))
        // A new epoch's trusted native factory sees a clean OFF child, retaining the old history.
        assertEquals(waiting.generation, expected.generation)
        assertEquals(waiting.callerIdentity, expected.callerIdentity)
    }

    @Test fun offNeverNormalizesUnknownPendingBlockedOrLiveSessionAndRuntimeReplacementIsRejected() {
        val waiting = hb.copy(pause = HeartbeatPause.WAITING_HOST)
        val session = "12345678-1234-1234-1234-123456789abc"
        for (old in listOf(waiting.copy(remoteSessionId = session),
            waiting.copy(resumeBlock = HeartbeatResumeBlock.STORAGE_FAILURE),
            waiting.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.TIMED_OUT))) {
            val f = Fixture(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(old)))
            val disabled = old.copy(configured = false, durableConsent = null)
            val plan = f.plan(patch = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(disabled)))
            assertEquals(disabled, HeartbeatSnapshotCodec.decode(projectDaemonControl(f.root.snapshot(), plan)
                .bytes(DaemonDomainRole.HEARTBEAT)!!))
            val forged = disabled.copy(pause = null, remoteSessionId = null, resumeBlock = null,
                lastStatus = null, lastReason = null)
            RootFixtures.fails { f.stage(f.plan(patch = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(forged)))) }
        }
        val f = Fixture(extras = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(waiting),
            DaemonDomainRole.HEARTBEAT_PENDING to HeartbeatPendingCodec.encode(HeartbeatPending(HeartbeatPendingKind.ARM,
                session, 1, session))))
        val disabled = waiting.copy(configured = false, durableConsent = null)
        val plan = f.plan(patch = mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(disabled)))
        assertEquals(HeartbeatPause.WAITING_HOST, HeartbeatSnapshotCodec.decode(projectDaemonControl(f.root.snapshot(), plan)
            .bytes(DaemonDomainRole.HEARTBEAT)!!).pause)
    }
}
