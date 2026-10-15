package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** The production kernel with a deterministic authoritative port; no Binder/device claim is made. */
class DaemonOwnerControlTest {
    private val owner = DaemonOwnerKey("install-one", 7)
    private val installation = DaemonGuardianInstallation(
        SelectedTargetIdentity(GUARDIAN_PACKAGE_NAME, 10435, 100, listOf("c".repeat(64))), 79, 200)
    private val caller = DaemonCallerEvidence(10435, installation)
    private val digest = "a".repeat(64)
    private val otherDigest = "b".repeat(64)
    private fun record(mode: DaemonControlMode = DaemonControlMode.OFF, revision: Long = 1) =
        DaemonControlRecord(owner, revision, digest, mode, "migration-one")

    private class Store(initial: DaemonControlRecord) : DaemonControlPort {
        var read = DaemonControlRead(initial)
        var pointer = DaemonPointerCommitProof("initial-pointer", initial)
        var reconcileAuthenticated = true
        var failFence = false
        var failCommit = false
        var wrongReadback = false
        var fences = 0
        var commits = 0
        var afterFence: () -> Unit = {}
        private val receipts = mutableMapOf<Pair<DaemonOwnerKey, String>, DaemonControlReceipt>()
        private val leaseLock = Any()
        var leaseHeld = true
        fun close() = synchronized(leaseLock) { leaseHeld = false }
        override fun withOwnerLease(start: () -> Unit): Boolean = synchronized(leaseLock) {
            if (!leaseHeld) return@synchronized false
            start()
            true
        }
        override fun read() = read
        override fun receipt(owner: DaemonOwnerKey, requestId: String) = receipts[owner to requestId]
        override fun verifyPointerCommit(proof: DaemonPointerCommitProof) = proof == pointer
        override fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence? {
            fences++
            if (read.record != expected || read.interrupted) return null
            read = read.copy(interrupted = true)
            afterFence()
            if (failFence) error("fence outcome ambiguous")
            return DaemonHaltFence(request, expected.revision + 1)
        }
        override fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead {
            check(read.interrupted && fence.request == receipt.request && fence.nextRevision == receipt.committed.revision)
            commits++
            if (failCommit) error("commit outcome ambiguous")
            val previous = read
            read = DaemonControlRead(receipt.committed, pendingRecovery = previous.pendingRecovery)
            receipts[receipt.request.owner to receipt.request.requestId] = receipt
            return if (wrongReadback) previous else read
        }
        override fun reconcile(proof: DaemonReconcileProof): DaemonControlRead? =
            read.takeIf { reconcileAuthenticated && proof.reconciliationId.isNotBlank() && proof.pointer == pointer }
    }

    private inner class Fixture(initial: DaemonControlRecord = record()) {
        val store = Store(initial)
        var now = 10_000L
        var evidence: ((DaemonWriteTicket) -> DaemonWriteEvidence?)? = null
        val checks = AtomicInteger()
        val control = newControl()
        fun newControl() = DaemonOwnerControl(owner, installation, store, DaemonPrerequisitePort { ticket ->
            checks.incrementAndGet()
            evidence?.let { it(ticket) } ?: if (evidence == null) DaemonWriteEvidence(ticket, now, now + 100, true) else null
        }, { now })
        fun reconcile(control: DaemonOwnerControl = this.control): Boolean =
            control.reconcile(caller, DaemonReconcileProof(store.pointer, "authenticated-reconcile"))
        fun request(intent: DaemonControlIntent, id: String = "request-${store.read.record.revision}",
            revision: Long = store.read.record.revision) = DaemonControlRequest(id, owner, revision, intent)
        fun pointer(next: DaemonControlRecord): DaemonPointerCommitProof =
            DaemonPointerCommitProof("pointer-${next.revision}", next).also { store.pointer = it }
        fun update(mode: DaemonControlMode, nextDigest: String = store.read.record.authorityDigest,
            id: String = "request-${store.read.record.revision}"): DaemonControlRequest {
            val before = store.read.record
            return request(DaemonControlIntent.SetAuthority(pointer(before.copy(
                revision = before.revision + 1, mode = mode, authorityDigest = nextDigest))), id)
        }
        fun activate(): DaemonControlRequest {
            val before = store.read.record
            return request(DaemonControlIntent.Activate(pointer(before.copy(
                revision = before.revision + 1, mode = DaemonControlMode.ACTIVE))))
        }
    }

    @Test fun preparedPermitIsSingleUseAndOffBetweenPreparationAndStartWins() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val permit = f.control.prepareWrite(ticket)!!
        assertNull(f.control.prepareWrite(ticket))
        var writes = 0
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.OFF)) is DaemonControlReply.Acknowledged)
        assertFalse(f.control.startPrepared(permit) { writes++ })
        assertFalse(f.control.startPrepared(permit) { writes++ })
        assertEquals(0, writes)
        f.control.complete(ticket)
    }

    @Test fun stagingClosesDispatchBeforePointerIoWithoutDiscardingFlight() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val permit = f.control.prepareWrite(ticket)!!
        assertTrue(f.control.quiesceForControl(caller, f.store.read.record))
        assertFalse(f.control.isDispatchReady())
        assertNull(f.control.admit("second:unstop"))
        assertFalse(f.control.startPrepared(permit) { fail("Stage must invalidate old permit") })
        val update = f.update(DaemonControlMode.ACTIVE)
        assertTrue(f.control.apply(caller, update) is DaemonControlReply.Acknowledged)
        assertTrue(f.control.isDispatchReady())
        assertNull(f.control.admit("still-pending:unstop"))
        assertFalse(f.control.complete(ticket))
        assertNotNull(f.control.admit("next:unstop"))
    }

    @Test fun historicalReceiptReplayAndInvalidStageNeverReopenAdmission() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val update = f.update(DaemonControlMode.ACTIVE)
        assertTrue(f.control.apply(caller, update) is DaemonControlReply.Acknowledged)
        assertFalse(f.control.quiesceForControl(caller.copy(realUid = 10436), f.store.read.record))
        assertFalse(f.control.quiesceForControl(caller, record(DaemonControlMode.ACTIVE)))
        assertTrue(f.control.isDispatchReady())
        assertTrue(f.control.quiesceForControl(caller, f.store.read.record))
        assertTrue(f.control.query(caller, update) is DaemonControlReply.Acknowledged)
        assertTrue(f.control.apply(caller, update) is DaemonControlReply.Acknowledged)
        assertFalse(f.control.isDispatchReady())
        assertTrue(f.reconcile())
        assertTrue(f.control.isDispatchReady())
    }

    @Test fun expiredOrCompletedPermitCannotStartAndAnotherOwnerCannotBorrowIt() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val permit = f.control.prepareWrite(ticket)!!
        val another = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(another.reconcile())
        var writes = 0
        assertFalse(another.control.startPrepared(permit) { writes++ })
        f.now += 101
        assertFalse(f.control.startPrepared(permit) { writes++ })
        f.control.complete(ticket)
        val next = f.control.admit("target:unstop")!!
        val after = f.control.prepareWrite(next)!!
        f.control.complete(next)
        assertFalse(f.control.startPrepared(after) { writes++ })
        assertEquals(0, writes)
    }

    @Test fun preparedPermitStartsOnceWithoutRepeatingBlockingPrerequisites() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val permit = f.control.prepareWrite(ticket)!!
        f.evidence = { error("Must not read Binder or package state under inner launch monitor") }
        var writes = 0
        assertTrue(f.control.startPrepared(permit) { writes++ })
        assertFalse(f.control.startPrepared(permit) { writes++ })
        assertEquals(1, writes)
        assertEquals(1, f.checks.get())
    }

    @Test fun restartNeverRunsFromAnActiveSnapshotWithoutAuthenticatedReconciliation() {
        val f = Fixture(record(DaemonControlMode.ACTIVE, 10))
        assertEquals(DaemonOwnerStatus.UNRECONCILED, f.control.status())
        assertNull(f.control.admit("target:unstop"))
        f.store.reconcileAuthenticated = false
        assertFalse(f.reconcile())
        assertNull(f.control.admit("target:unstop"))
        f.store.reconcileAuthenticated = true
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        var writes = 0
        assertTrue(f.control.startWrite(ticket) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun preparedImportCannotWriteAndActivationRequiresExactCommittedPointer() {
        val f = Fixture()
        assertTrue(f.reconcile())
        assertTrue(f.control.apply(caller, f.request(DaemonControlIntent.Prepare("migration-two", otherDigest)))
            is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.PREPARED, f.control.status())
        assertNull(f.control.admit("target:unstop"))
        f.control.appDisconnected()
        assertEquals(DaemonOwnerStatus.PREPARED, f.control.status())
        val activate = f.activate()
        val committed = f.store.pointer
        f.store.pointer = committed.copy(commitId = "uncommitted-pointer")
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.PROOF), f.control.apply(caller, activate))
        f.store.pointer = committed
        val wrongMigration = committed.copy(record = committed.record.copy(migrationId = "other-migration"))
        f.store.pointer = wrongMigration
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.PROOF),
            f.control.apply(caller, activate.copy(intent = DaemonControlIntent.Activate(wrongMigration))))
        f.store.pointer = committed
        assertTrue(f.control.apply(caller, activate) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.ACTIVE, f.control.status())
        assertNotNull(f.control.admit("target:unstop"))
    }

    @Test fun offAndAppDeathRaceDoesNotRevokeOrReviveByDisconnection() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        f.control.appDisconnected()
        assertEquals(DaemonOwnerStatus.ACTIVE, f.control.status())
        val off = f.update(DaemonControlMode.OFF)
        val go = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val ack = arrayOfNulls<DaemonControlReply>(1)
        val disconnect = Thread { try { check(go.await(5, TimeUnit.SECONDS)); f.control.appDisconnected() } finally { finished.countDown() } }
        val stop = Thread { try { check(go.await(5, TimeUnit.SECONDS)); ack[0] = f.control.apply(caller, off) } finally { finished.countDown() } }
        disconnect.start(); stop.start(); go.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(ack[0] is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
        assertFalse(f.control.startWrite(ticket) { fail("OFF admitted a command") })
        assertFalse(f.control.complete(ticket))
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
    }

    @Test fun offInvalidatesBeforeDurableFenceAndAcknowledgesOnlyAfterReadback() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        f.store.afterFence = {
            assertEquals(DaemonOwnerStatus.UNRECONCILED, f.control.status())
            assertFalse(f.control.startWrite(ticket) { fail("Dispatch while persisting OFF") })
            assertNull(f.store.receipt(owner, "off"))
        }
        val off = f.update(DaemonControlMode.OFF, id = "off")
        val ack = f.control.apply(caller, off) as DaemonControlReply.Acknowledged
        assertEquals(f.store.read.record, ack.receipt.committed)
        assertFalse(f.store.read.interrupted)
        assertEquals(1, f.store.commits)
    }

    @Test fun delayedOnAndOldTicketsCannotUndoNewerOff() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val delayedOn = f.update(DaemonControlMode.ACTIVE, id = "delayed-on")
        val off = f.update(DaemonControlMode.OFF, id = "off")
        assertTrue(f.control.apply(caller, off) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.REVISION), f.control.apply(caller, delayedOn))
        assertFalse(f.control.startWrite(ticket) { fail("Stale ticket") })
        assertFalse(f.control.complete(ticket))
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
    }

    @Test fun targetExclusionInvalidatesFlightWithoutAllowingConcurrentReplacement() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val old = f.control.admit("excluded-target:unstop")!!
        assertTrue(f.control.startWrite(old) {})
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.ACTIVE, otherDigest)) is DaemonControlReply.Acknowledged)
        assertNull(f.control.admit("retained-target:unstop"))
        assertFalse(f.control.startWrite(old) { fail("Old target dispatched twice") })
        assertFalse(f.control.complete(old))
        val fresh = f.control.admit("retained-target:unstop")!!
        assertEquals(otherDigest, fresh.record.authorityDigest)
        assertFalse(f.control.complete(old))
        assertNull(f.control.admit("third-target:unstop"))
        assertTrue(f.control.startWrite(fresh) {})
        assertTrue(f.control.complete(fresh))
    }

    @Test fun offStorageFailureFaultsAndRestartCannotFallBackToOldActive() {
        val f = Fixture(record(DaemonControlMode.ACTIVE, 20))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val off = f.update(DaemonControlMode.OFF)
        f.store.failCommit = true
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.STORAGE), f.control.apply(caller, off))
        assertEquals(DaemonOwnerStatus.FAULT, f.control.status())
        assertFalse(f.control.startWrite(ticket) { fail("Storage fault") })
        assertFalse(f.control.complete(ticket))
        val restarted = f.newControl()
        assertEquals(DaemonOwnerStatus.FAULT, restarted.status())
        assertFalse(f.reconcile(restarted))
        assertNull(restarted.admit("target:unstop"))
        // The authenticated adapter explicitly reconciles the interrupted transaction to App OFF.
        f.store.read = DaemonControlRead(f.store.pointer.record)
        assertTrue(f.reconcile(restarted))
        assertEquals(DaemonOwnerStatus.OFF, restarted.status())
        assertNull(restarted.admit("target:unstop"))
    }

    @Test fun ambiguousFenceOrReadbackNeverAcknowledgesAndCannotResumeInMemory() {
        for (failFence in listOf(true, false)) {
            val f = Fixture(record(DaemonControlMode.ACTIVE))
            assertTrue(f.reconcile())
            f.store.failFence = failFence
            f.store.wrongReadback = !failFence
            assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.STORAGE),
                f.control.apply(caller, f.update(DaemonControlMode.OFF)))
            assertEquals(DaemonOwnerStatus.FAULT, f.control.status())
            assertNull(f.control.admit("target:unstop"))
            assertEquals(if (failFence) 0 else 1, f.store.commits)
        }
    }

    @Test fun ackLossSupportsHistoricalQueryAndDuplicateWithoutReapplying() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val off = f.update(DaemonControlMode.OFF, id = "lost-ack")
        val first = f.control.apply(caller, off) as DaemonControlReply.Acknowledged
        assertEquals(first, f.control.query(caller, off))
        assertEquals(first, f.control.apply(caller, off))
        assertEquals(1, f.store.fences)
        val restarted = f.newControl()
        assertEquals(first, restarted.query(caller, off))
        val conflicting = off.copy(intent = DaemonControlIntent.Prepare("another", digest))
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.REQUEST_CONFLICT), restarted.query(caller, conflicting))
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.REQUEST_CONFLICT), f.control.apply(caller, conflicting))
        assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.REVISION),
            f.control.apply(caller, off.copy(requestId = "new-id-old-revision")))
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
    }

    @Test fun ownerEpochAndInstallInstanceCannotBorrowAuthorityOrReceipts() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val off = f.update(DaemonControlMode.OFF)
        for (wrong in listOf(owner.copy(ownerEpoch = 8), owner.copy(installInstance = "another-install"))) {
            assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.OWNER), f.control.apply(caller, off.copy(owner = wrong)))
            val other = DaemonOwnerControl(wrong, installation, f.store, DaemonPrerequisitePort { error("No read") }, { 10_000 })
            assertEquals(DaemonOwnerStatus.FAULT, other.status())
            assertFalse(other.reconcile(caller, DaemonReconcileProof(f.store.pointer, "reconcile")))
            assertNull(other.admit("target:unstop"))
        }
        assertEquals(0, f.store.fences)
    }

    @Test fun eachWriteRequiresFreshExactEvidenceAndCanStartOnlyOnce() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        assertNull(f.control.admit("second:unstop"))
        var writes = 0
        f.evidence = { DaemonWriteEvidence(it, f.now - 1_001, f.now + 1, true) }
        assertFalse(f.control.startWrite(ticket) { writes++ })
        f.evidence = { DaemonWriteEvidence(it, f.now + 1, f.now + 2, true) }
        assertFalse(f.control.startWrite(ticket) { writes++ })
        f.evidence = { DaemonWriteEvidence(it, f.now, f.now - 1, true) }
        assertFalse(f.control.startWrite(ticket) { writes++ })
        f.evidence = { DaemonWriteEvidence(it, f.now, f.now + 1, false) }
        assertFalse(f.control.startWrite(ticket) { writes++ })
        f.evidence = { DaemonWriteEvidence(DaemonWriteTicket(it.sequence, it.record, it.operationKey), f.now, f.now + 1, true) }
        assertFalse(f.control.startWrite(ticket) { writes++ })
        f.evidence = null
        assertTrue(f.control.startWrite(ticket) { writes++ })
        assertFalse(f.control.startWrite(ticket) { writes++ })
        assertEquals(1, writes)
        assertEquals(6, f.checks.get())
    }

    @Test fun ambiguousCommandStartCannotBeRetriedAndLateCompletionCannotRestoreOn() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        assertFalse(f.control.startWrite(ticket) { error("write may have started") })
        assertFalse(f.control.startWrite(ticket) { fail("Replay") })
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.OFF)) is DaemonControlReply.Acknowledged)
        assertFalse(f.control.complete(ticket))
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
    }

    @Test fun oldPendingRecoveryRequiresReadOnlyResolutionAndCannotReplay() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        f.store.read = f.store.read.copy(pendingRecovery = true)
        assertFalse(f.reconcile())
        assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.control.status())
        assertNull(f.control.admit("old-pending:unstop"))
        assertEquals(0, f.checks.get())
        // Only the existing journal's read-only resolver may clear this external gate.
        f.store.read = f.store.read.copy(pendingRecovery = false)
        assertTrue(f.reconcile())
        assertNotNull(f.control.admit("new-observation:unstop"))
        assertEquals(0, f.checks.get())
    }

    @Test fun preparedMigrationCanBeExplicitlyCancelledByOff() {
        val f = Fixture(record(DaemonControlMode.PREPARED))
        assertTrue(f.reconcile())
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.OFF)) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
        assertNull(f.control.admit("target:unstop"))
    }

    @Test fun offProceedsWhilePrerequisiteReadBlocksAndLateEvidenceCannotDispatch() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val writes = AtomicInteger()
        val result = arrayOfNulls<Boolean>(1)
        f.evidence = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            DaemonWriteEvidence(it, f.now, f.now + 100, true)
        }
        val thread = Thread {
            try { result[0] = f.control.startWrite(ticket) { writes.incrementAndGet() } }
            finally { done.countDown() }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(f.control.apply(caller, f.update(DaemonControlMode.OFF)) is DaemonControlReply.Acknowledged)
            assertEquals(DaemonOwnerStatus.OFF, f.control.status())
            assertNull(f.control.admit("second:unstop"))
        } finally { release.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, result[0])
        assertEquals(0, writes.get())
        assertFalse(f.control.complete(ticket))
    }

    @Test fun lostLifetimeLeaseNeverCallsStart() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        f.store.close()
        assertFalse(f.control.startWrite(ticket) { fail("Owner lease was closed") })
        assertEquals(DaemonOwnerStatus.FAULT, f.control.status())
        assertNull(f.control.admit("second:unstop"))
    }

    @Test fun ownerCloseCannotReleaseLifetimeLeaseDuringCommandStart() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val ticket = f.control.admit("target:unstop")!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = arrayOfNulls<Boolean>(1)
        val writer = Thread {
            try {
                result[0] = f.control.startWrite(ticket) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertTrue(f.store.leaseHeld)
                }
            } finally { done.countDown() }
        }
        val closer = Thread {
            closeAttempted.countDown()
            f.store.close()
            closed.countDown()
        }
        writer.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
            assertEquals(1L, closed.count)
        } finally { release.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(true, result[0])
        assertFalse(f.store.leaseHeld)
    }

    @Test fun callerEvidenceMustMatchRealUidAndExactApprovedInstallation() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        val wrongCallers = listOf(caller.copy(realUid = 10436),
            caller.copy(installation = installation.copy(versionCode = 80)),
            caller.copy(installation = installation.copy(lastUpdateTime = 201)))
        for (wrong in wrongCallers) {
            assertFalse(f.control.reconcile(wrong, DaemonReconcileProof(f.store.pointer, "reconcile")))
        }
        assertTrue(f.reconcile())
        val off = f.update(DaemonControlMode.OFF)
        for (wrong in wrongCallers) {
            assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.AUTHENTICATION), f.control.apply(wrong, off))
            assertEquals(DaemonControlReply.Rejected(DaemonControlReply.Reason.AUTHENTICATION), f.control.query(wrong, off))
        }
        assertEquals(DaemonOwnerStatus.ACTIVE, f.control.status())
        assertEquals(0, f.store.fences)
    }

    @Test fun offAcknowledgesWhileOldRecoveryRemainsPendingAndOnCannotDispatch() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        f.store.read = f.store.read.copy(pendingRecovery = true)
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.OFF)) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
        assertTrue(f.store.read.pendingRecovery)
        assertTrue(f.reconcile())
        assertEquals(DaemonOwnerStatus.OFF, f.control.status())
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.ACTIVE)) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.control.status())
        assertNull(f.control.admit("new-target:unstop"))
        assertTrue(f.store.read.pendingRecovery)
        assertEquals(0, f.checks.get())
    }

    @Test fun targetExclusionAcknowledgesWithoutClearingPendingOrAdmittingNewWrites() {
        val f = Fixture(record(DaemonControlMode.ACTIVE))
        assertTrue(f.reconcile())
        val old = f.control.admit("excluded-target:unstop")!!
        assertTrue(f.control.startWrite(old) {})
        f.store.read = f.store.read.copy(pendingRecovery = true)
        assertTrue(f.control.apply(caller, f.update(DaemonControlMode.ACTIVE, otherDigest)) is DaemonControlReply.Acknowledged)
        assertEquals(DaemonOwnerStatus.PENDING_RECOVERY, f.control.status())
        assertTrue(f.store.read.pendingRecovery)
        assertFalse(f.control.complete(old))
        assertNull(f.control.admit("retained-target:unstop"))
        assertFalse(f.reconcile())
        f.store.read = f.store.read.copy(pendingRecovery = false) // Existing journal read-only resolver.
        assertTrue(f.reconcile())
        assertNotNull(f.control.admit("retained-target:unstop"))
        assertEquals(1, f.checks.get())
    }
}
