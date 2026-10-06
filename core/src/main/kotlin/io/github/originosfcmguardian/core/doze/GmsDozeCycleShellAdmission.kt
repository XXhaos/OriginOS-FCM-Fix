package io.github.originosfcmguardian.core.doze

/**
 * One INERT shell lifetime for one shell-created nonce/fixed operation. HELLO grants no authority.
 * First activation or revoke binds one immutable grant/full ref with its dynamic generation.
 * Trusted transport authenticates ticket source and committed current ledger readback; hashes
 * alone do not. Full fresh identity/qualification, RPC, storage and waits run outside this object.
 * Actual fixed ProcessBuilder.start and handle registration share the short revoke monitor.
 */
class GmsDozeCycleShellAdmission(val ownNonce: String, val fixedOperation: GmsGlobalDozeOperation) {
    private val monitor = Any()
    private var boundGrant: GmsDozeCycleGrant? = null
    private var activationConsumed = false
    private var activated = false
    private var revoked = false
    private var revokeVersion = 0L
    private var activationRevision = -1L
    private var activationRunningGeneration = -1L
    private var startRevision = -1L
    private var startConsumed = false
    private var busy = false
    private var child: GmsDozeBrokerChild? = null

    init { require(ownNonce.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }

    fun activate(ticket: GmsDozeCycleTicket): Boolean {
        val exact = validated(ticket) ?: return false
        return synchronized(monitor) {
            if (activationConsumed || revoked || !own(exact) || exact.startIntent || exact.requestedRevoke != 0L ||
                exact.resultSummary != null || exact.releaseAck != null || exact.closure != null || !runningForOperation(exact))
                return@synchronized false
            if (boundGrant != null && boundGrant != exact.grant) return@synchronized false
            boundGrant = exact.grant
            activationConsumed = true
            activated = true
            activationRevision = exact.ledgerRevision
            activationRunningGeneration = exact.runningGeneration
            true
        }
    }

    fun start(ticket: GmsDozeCycleTicket,
        actualFixedStart: (GmsGlobalDozeOperation) -> GmsDozeBrokerChild): GmsDozeBrokerStartResult {
        val exact = validated(ticket) ?: return GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.REJECTED)
        return synchronized(monitor) {
            if (!activated || revoked || startConsumed || busy || !own(exact) || boundGrant != exact.grant || !exact.startIntent ||
                exact.requestedRevoke != revokeVersion || exact.closure != null || exact.resultSummary != null || exact.releaseAck != null ||
                !runningForOperation(exact) || exact.runningGeneration != activationRunningGeneration || exact.ledgerRevision <= activationRevision)
                return@synchronized GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.REJECTED)
            startConsumed = true
            busy = true
            startRevision = exact.ledgerRevision
            try {
                val actualChild = actualFixedStart(fixedOperation)
                child = actualChild
                GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.STARTED, actualChild)
            } catch (_: Exception) {
                // Start may already have created an unregistered child. No timeout clears busy.
                GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.UNCERTAIN)
            }
        }
    }

    /** Revoke-first binds the exact grant and permanently prevents activation/start. */
    fun revoke(ticket: GmsDozeCycleTicket): GmsDozeCycleClosure? {
        val exact = validated(ticket) ?: return null
        return synchronized(monitor) {
            if (!own(exact) || exact.requestedRevoke <= 0 || exact.requestedRevoke < revokeVersion ||
                boundGrant != null && boundGrant != exact.grant) return@synchronized null
            if (boundGrant == null) boundGrant = exact.grant
            activationConsumed = true
            revoked = true
            activated = false
            revokeVersion = exact.requestedRevoke
            GmsDozeCycleClosure(exact.ref, GmsDozeCycleClosureKind.REVOKE_ACK, revokeVersion)
        }
    }

    /** Actual child/stream/collector facts are obtained outside the monitor for this exact handle. */
    fun cleanupEvidence(expectedChild: GmsDozeBrokerChild, childExited: Boolean,
        stdoutEofAndDrained: Boolean, stderrEofAndDrained: Boolean,
        collectorsTerminated: Boolean, handlesClosed: Boolean): GmsDozeCycleCleanup? = synchronized(monitor) {
        val grant = boundGrant ?: return@synchronized null
        if (!busy || child !== expectedChild) return@synchronized null
        GmsDozeCycleCleanup(grant.ref, childExited, stdoutEofAndDrained, stderrEofAndDrained, collectorsTerminated, handlesClosed)
    }

    /**
     * Historical exact result tickets remain releasable across later OFF/revoke delivery. Current
     * intent/revoke versions are start gates, not reasons to reject the completed original child.
     */
    fun release(expectedChild: GmsDozeBrokerChild, cleanup: GmsDozeCycleCleanup,
        resultTicket: GmsDozeCycleTicket): GmsDozeCycleReleaseAck? {
        val exact = validated(resultTicket) ?: return null
        return synchronized(monitor) {
            val grant = boundGrant ?: return@synchronized null
            val result = exact.resultSummary ?: return@synchronized null
            if (!busy || child !== expectedChild || !cleanup.complete() || cleanup.ref != grant.ref ||
                !own(exact) || exact.grant != grant || result.ref != grant.ref || result.cleanup != cleanup ||
                exact.releaseAck != null || exact.ledgerRevision <= startRevision) return@synchronized null
            busy = false
            child = null
            GmsDozeCycleReleaseAck(grant.ref)
        }
    }

    fun isBusy(): Boolean = synchronized(monitor) { busy }
    private fun own(ticket: GmsDozeCycleTicket) = ticket.ref.key.nonce == ownNonce && ticket.ref.operation == fixedOperation
    private fun runningForOperation(ticket: GmsDozeCycleTicket) =
        if (fixedOperation == GmsGlobalDozeOperation.ADD) ticket.runningIntent else !ticket.runningIntent && ticket.restoreConsent != null

    /** Validate and freeze caller-owned nested lists before taking the short callee gate. */
    private fun validated(ticket: GmsDozeCycleTicket): GmsDozeCycleTicket? = try {
        GmsDozeCycleTicketCodec.decode(GmsDozeCycleTicketCodec.encode(ticket))
    } catch (_: Exception) { null }
}
