package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DaemonFcmCommandResultTest {
    private val original = RootFixtures.snapshot()
    private val before = original.control.copy(mode = DaemonControlMode.ACTIVE)
    private val nextAuthority = byteArrayOf(4, 5, 6)
    private val next = before.copy(revision = before.revision + 1,
        authorityDigest = DaemonProtectionRootSnapshot.digest(nextAuthority))
    private val request = DaemonControlRequest("this-confirmation", before.owner, before.revision,
        DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("this-proof", next)))
    private val receipt = DaemonControlReceipt(request, next)
    private val patchDigest = "c".repeat(64)
    private fun root(receipts: List<DaemonControlReceipt> = listOf(receipt), control: DaemonControlRecord = next,
        halt: DaemonHaltFence? = null, extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()) =
        DaemonProtectionRootSnapshot(control, original.domains() + (DaemonDomainRole.AUTHORITY to nextAuthority) + extras,
            receipts, halt, 7)
    private fun status(root: DaemonProtectionRootSnapshot) = DaemonAppStatus(root.control,
        DaemonOwnerStatus.ACTIVE, root.dataRevision, false, false, sessionStartedElapsed = 1)
    private fun ui(fcm: FcmProtectionUiState = FcmProtectionUiState(configured = true), ready: Boolean = true) =
        DaemonUiSnapshot(AutomaticProtectionState(), fcm, emptyList(), RecoveryCountsUiState(), true, ready)
    private fun pending() = DaemonFcmCommandCompletion(request.requestId).also {
        assertTrue(it.expect(before)); it.staged(request, next, patchDigest)
    }

    @Test fun exactCommittedReceiptAndCoherentRereadCompleteEvenIfApplyReplyWasLost() = runBlocking {
        val completion = pending()
        val root = root()
        assertTrue(completion.observed(root, status(root), ui()))
        assertEquals(DaemonFcmCommandOutcome.APPLIED, completion.result.await().outcome)
        assertEquals(request.requestId, completion.result.await().requestId)
    }

    @Test fun lostStageReplyNeedsTheSameProposalBeforeAReceiptCanAcknowledgeIt() = runBlocking {
        val completion = DaemonFcmCommandCompletion(request.requestId)
        assertTrue(completion.expect(before))
        // restoreLocked resumes only this exact stored proposal; it does not mint a fresh request.
        completion.staged(request, next, patchDigest)
        val root = root()
        completion.observed(root, status(root), ui())
        assertEquals(DaemonFcmCommandOutcome.APPLIED, completion.result.await().outcome)
    }

    @Test fun resumedSameDurableProposalAcceptsFreshProofAndRequiresItsLatestReceipt() = runBlocking {
        val completion = pending()
        val renewed = request.copy(intent = DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("renewed-proof", next)))
        completion.staged(renewed, next, patchDigest)
        val root = root(listOf(DaemonControlReceipt(renewed, next)))
        completion.observed(root, status(root), ui())
        assertEquals(DaemonFcmCommandOutcome.APPLIED, completion.result.await().outcome)
        val stale = pending()
        stale.staged(renewed, next, patchDigest)
        val oldReceipt = root()
        stale.observed(oldReceipt, status(oldReceipt), ui())
        assertEquals(DaemonFcmCommandOutcome.UNKNOWN, stale.result.await().outcome)
    }

    @Test fun resumedProofCannotChangeTheProposalPatchRecordOrRequestIdentity() {
        val completion = pending()
        RootFixtures.fails { completion.staged(request, next, "d".repeat(64)) }
        val changed = next.copy(authorityDigest = "a".repeat(64))
        val changedRequest = request.copy(intent = DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("renewed", changed)))
        RootFixtures.fails { completion.staged(changedRequest, changed, patchDigest) }
        RootFixtures.fails { completion.staged(request.copy(requestId = "another-command"), next, patchDigest) }
        RootFixtures.fails { completion.staged(request.copy(expectedRevision = before.revision + 1), next, patchDigest) }
        RootFixtures.fails { completion.staged(request.copy(intent = DaemonControlIntent.Activate(
            DaemonPointerCommitProof("renewed-proof", next))), next, patchDigest) }
    }

    @Test fun receiptWithoutTheFullTrustedStagedRequestIsUnknown() = runBlocking {
        val completion = DaemonFcmCommandCompletion(request.requestId)
        assertTrue(completion.expect(before))
        val root = root()
        completion.observed(root, status(root), ui())
        assertEquals(DaemonFcmCommandOutcome.UNKNOWN, completion.result.await().outcome)
    }

    @Test fun alreadyConfiguredStateAndUnrelatedReceiptNeverAcknowledgeThisRequest() = runBlocking {
        val unrelated = receipt.copy(request = request.copy(requestId = "older-confirmation"))
        for (receipts in listOf(emptyList(), listOf(unrelated))) {
            val completion = pending()
            val root = root(receipts)
            completion.observed(root, status(root), ui())
            assertEquals(DaemonFcmCommandOutcome.UNKNOWN, completion.result.await().outcome)
        }
    }

    @Test fun sameRequestIdCannotHideDifferentOwnerRevisionPointerOrReceipt() = runBlocking {
        val otherOwner = before.owner.copy(ownerEpoch = before.owner.ownerEpoch + 1)
        val mismatches = listOf(
            receipt.copy(request = request.copy(owner = otherOwner)),
            receipt.copy(request = request.copy(expectedRevision = before.revision + 1)),
            receipt.copy(request = request.copy(intent = DaemonControlIntent.SetAuthority(
                DaemonPointerCommitProof("different-proof", next)))),
            receipt.copy(request = request.copy(intent = DaemonControlIntent.SetAuthority(
                DaemonPointerCommitProof("other-proof", next.copy(authorityDigest = "a".repeat(64)))))),
            receipt.copy(committed = next.copy(revision = next.revision + 1)),
        )
        for (mismatch in mismatches) {
            val completion = pending()
            val root = root(listOf(mismatch))
            completion.observed(root, status(root), ui())
            assertEquals(DaemonFcmCommandOutcome.UNKNOWN, completion.result.await().outcome)
        }
    }

    @Test fun historicalReceiptAfterNewerOffNeverClaimsCurrentSuccess() = runBlocking {
        val completion = pending()
        val root = root(control = next.copy(revision = next.revision + 1, mode = DaemonControlMode.OFF))
        completion.observed(root, status(root), ui())
        assertEquals(DaemonFcmCommandOutcome.UNKNOWN, completion.result.await().outcome)
    }

    @Test fun incoherentStatusOrPendingControlNeverCountsAsConfirmed() = runBlocking {
        val normal = root()
        val cases = listOf(
            normal to status(normal).copy(dataRevision = normal.dataRevision + 1),
            normal to status(normal).copy(record = before),
            root(halt = DaemonHaltFence(request, next.revision)) to status(normal),
            root(extras = mapOf(DaemonDomainRole.CONTROL_PROPOSAL to byteArrayOf(1))) to status(normal),
        )
        for ((root, status) in cases) {
            val completion = pending()
            completion.observed(root, status, ui())
            assertEquals(DaemonFcmCommandOutcome.UNKNOWN, completion.result.await().outcome)
        }
    }

    @Test fun appliedControlStillRequiringConfirmationIsATerminalFailure() = runBlocking {
        val root = root()
        for (projection in listOf(ui(FcmProtectionUiState(configured = false)),
            ui(FcmProtectionUiState(configured = true, requiresConfirmation = true)),
            ui(FcmProtectionUiState(configured = true, storageBlocked = true)), ui(ready = false))) {
            val completion = pending()
            completion.observed(root, status(root), projection)
            assertEquals(DaemonFcmCommandOutcome.FAILED, completion.result.await().outcome)
            assertEquals(DaemonFcmCommandReason.CONFIRMATION, completion.result.await().reason)
        }
    }

    @Test fun busyAdmissionRejectsImmediatelyRatherThanWatchingAnUnrelatedCommand() = runBlocking {
        val admission = DaemonAppCommandAdmission()
        admission.offer(DaemonAppCommand(DaemonWire.MASTER_ON))
        val completion = DaemonFcmCommandCompletion(request.requestId)
        assertEquals(DaemonAppCommandAdmission.Offer.REJECTED, admission.offer(DaemonAppCommand(DaemonWire.FCM_ON)))
        completion.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.BUSY)
        assertEquals(DaemonFcmCommandReason.BUSY, completion.result.await().reason)
        assertFalse(completion.expect(before))
    }

    @Test fun acceptedOffSettlesPendingConfirmationBeforeLateReadbackAndRemainsQueued() = runBlocking {
        val admission = DaemonAppCommandAdmission()
        assertEquals(DaemonAppCommandAdmission.Offer.START, admission.offer(DaemonAppCommand(DaemonWire.FCM_ON)))
        val completion = pending()
        val off = DaemonAppCommand(DaemonWire.MASTER_OFF)
        assertEquals(DaemonAppCommandAdmission.Offer.QUEUED_OFF, admission.offer(off))
        completion.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.STOP_REQUESTED)
        val root = root()
        assertFalse(completion.observed(root, status(root), ui()))
        assertEquals(DaemonFcmCommandReason.STOP_REQUESTED, completion.result.await().reason)
        assertEquals(off, admission.finishAndNext())
    }

    @Test fun offBeforePreparationPreventsStartingANewConfirmation() = runBlocking {
        val completion = DaemonFcmCommandCompletion(request.requestId)
        completion.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.STOP_REQUESTED)
        assertFalse(completion.expect(before))
        assertFalse(completion.hasExpectation())
        assertEquals(DaemonFcmCommandOutcome.REJECTED, completion.result.await().outcome)
    }

    @Test fun leavingUiCancelsOnlyItsWaitAndRemoteCompletionStillArrives() = runBlocking {
        val completion = pending()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { completion.result.await() }
        waiter.cancelAndJoin()
        assertFalse(completion.result.isCompleted)
        val root = root()
        completion.observed(root, status(root), ui())
        assertEquals(DaemonFcmCommandOutcome.APPLIED, completion.result.await().outcome)
    }

    @Test fun missingCompletionEndsAtDeadlineAndLateSuccessCannotReplaceUnknown() = runBlocking {
        val completion = pending()
        completion.bound(this, timeoutMillis = 1)
        val result = withTimeout(2_000) { completion.result.await() }
        assertEquals(DaemonFcmCommandOutcome.UNKNOWN, result.outcome)
        assertEquals(DaemonFcmCommandReason.TIMEOUT, result.reason)
        val root = root()
        assertFalse(completion.observed(root, status(root), ui()))
        assertEquals(result, completion.result.await())
    }

    @Test fun deadlineOffAndRpcRacesPublishExactlyOneResult() = runBlocking {
        repeat(40) {
            val completion = pending()
            val start = CountDownLatch(1)
            val winners = AtomicInteger()
            val root = root()
            val workers = listOf<() -> Boolean>(
                { completion.finish(DaemonFcmCommandOutcome.UNKNOWN, DaemonFcmCommandReason.TIMEOUT) },
                { completion.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.STOP_REQUESTED) },
                { completion.observed(root, status(root), ui()) },
            ).map { finish -> thread { start.await(); if (finish()) winners.incrementAndGet() } }
            start.countDown()
            workers.forEach { it.join(2_000); assertFalse(it.isAlive) }
            assertEquals(1, winners.get())
            assertEquals(request.requestId, completion.result.await().requestId)
        }
    }
}
