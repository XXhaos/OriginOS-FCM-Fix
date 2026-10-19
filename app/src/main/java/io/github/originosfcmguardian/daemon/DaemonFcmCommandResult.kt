package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounded, request-correlated UI receipt; these values carry no execution authority. */
internal enum class DaemonFcmCommandOutcome { APPLIED, REJECTED, FAILED, UNKNOWN }
internal enum class DaemonFcmCommandReason {
    NONE, NOT_REMOTE, BUSY, STOP_REQUESTED, PREPARATION, ACKNOWLEDGEMENT, CONFIRMATION, UNAVAILABLE, TIMEOUT,
}
internal data class DaemonFcmCommandResult(val requestId: String, val outcome: DaemonFcmCommandOutcome,
    val reason: DaemonFcmCommandReason)

/** One result, even when OFF, the deadline and a late RPC completion race. */
internal class DaemonFcmCommandCompletion(val requestId: String) {
    private val completion = CompletableDeferred<DaemonFcmCommandResult>()
    val result: Deferred<DaemonFcmCommandResult> get() = completion
    private var expected: DaemonControlRecord? = null
    private var stagedRequest: DaemonControlRequest? = null
    private var stagedRecord: DaemonControlRecord? = null
    private var stagedPatchDigest: String? = null

    fun bound(scope: CoroutineScope, timeoutMillis: Long = 90_000) {
        val deadline = scope.launch {
            delay(timeoutMillis)
            finish(DaemonFcmCommandOutcome.UNKNOWN, DaemonFcmCommandReason.TIMEOUT)
        }
        completion.invokeOnCompletion { deadline.cancel() }
    }

    @Synchronized fun hasExpectation(): Boolean = expected != null

    @Synchronized fun expect(record: DaemonControlRecord): Boolean {
        if (completion.isCompleted) return false
        check(expected == null && record.mode == DaemonControlMode.ACTIVE && record.revision < Long.MAX_VALUE)
        expected = record
        return true
    }

    @Synchronized fun finish(outcome: DaemonFcmCommandOutcome, reason: DaemonFcmCommandReason): Boolean =
        completion.complete(DaemonFcmCommandResult(requestId, outcome, reason))

    /** Also called when reconciliation resumes the same trusted proposal after a lost STAGE reply. */
    @Synchronized fun staged(request: DaemonControlRequest, record: DaemonControlRecord, patchDigest: String) {
        val before = checkNotNull(expected)
        check(request.requestId == requestId && request.owner == before.owner &&
            request.expectedRevision == before.revision && record.owner == before.owner &&
            record.revision == before.revision + 1 && record.mode == DaemonControlMode.ACTIVE &&
            record.migrationId == before.migrationId &&
            (request.intent as? DaemonControlIntent.SetAuthority)?.pointer?.record == record)
        require(patchDigest.matches(Regex("[0-9a-f]{64}")))
        // RESUME_STAGED attests the same durable plan with a fresh pointer-proof nonce. Pin all
        // semantic material and the complete patch, then bind readback to that latest proof.
        check(stagedRequest == null || stagedRecord == record && stagedPatchDigest == patchDigest)
        stagedRequest = request
        stagedRecord = record
        stagedPatchDigest = patchDigest
    }

    @Synchronized fun observed(root: DaemonProtectionRootSnapshot, status: DaemonAppStatus,
        ui: DaemonUiSnapshot): Boolean {
        val before = expected
        val receipt = root.receipts.singleOrNull { it.request.requestId == requestId }
        // An old configured switch, a different request or a historical receipt cannot satisfy ON.
        val exact = before != null && receipt != null && stagedRequest != null && stagedRecord != null &&
            receipt.request == stagedRequest && receipt.committed == stagedRecord && root.control == stagedRecord &&
            status.record == root.control &&
            status.dataRevision == root.dataRevision && root.halt == null &&
            root.bytes(DaemonDomainRole.CONTROL_PROPOSAL) == null
        if (!exact) return finish(DaemonFcmCommandOutcome.UNKNOWN, DaemonFcmCommandReason.ACKNOWLEDGEMENT)
        val confirmed = ui.hostReady && ui.fcm.configured && !ui.fcm.requiresConfirmation &&
            !ui.fcm.storageBlocked && ui.fcm.confirmationFailure == null
        return if (confirmed) finish(DaemonFcmCommandOutcome.APPLIED, DaemonFcmCommandReason.NONE)
            else finish(DaemonFcmCommandOutcome.FAILED, DaemonFcmCommandReason.CONFIRMATION)
    }
}
