package io.github.originosfcmguardian.core.doze

import java.util.Collections
import java.util.UUID

/** A single immutable capability. Hashes cover full raw UTF-8 values, without normalization. */
data class GmsDozeBrokerV2Grant(
    val grantId: String,
    val operation: GmsGlobalDozeOperation,
    val globalTransactionId: String,
    val transactionEpoch: Long,
    val identity: GmsGlobalDozeIdentity,
    val baselineSha256: String,
    /** ADD: null; REMOVE: the original APPLIED raw whitelist hash. */
    val appliedSha256: String?,
    val businessIntentSha256: String,
)

data class GmsDozeBrokerV2Preparation(
    val key: GmsDozeBrokerKey,
    val grantId: String,
    val preparationRevision: Long,
    val revokeVersion: Long,
)

data class GmsDozeBrokerV2Result(
    val preparation: GmsDozeBrokerV2Preparation,
    val businessResultSha256: String,
    /** ADD: verified APPLIED raw hash; REMOVE: verified exact baseline raw hash. */
    val appliedSha256: String,
)

data class GmsDozeBrokerV2Attempt(
    val grant: GmsDozeBrokerV2Grant,
    val pin: GmsDozeBrokerKey? = null,
    val revokeVersion: Long = 0,
    val acknowledgedRevoke: Long? = null,
    val actualDeathObserved: Boolean = false,
    val preparation: GmsDozeBrokerV2Preparation? = null,
    val result: GmsDozeBrokerV2Result? = null,
    val releaseAcknowledged: Boolean = false,
)

/** Fixed retained slots; neither slot can ever be cleared, replaced or retried. */
data class GmsDozeBrokerV2State(
    val revision: Long,
    val add: GmsDozeBrokerV2Attempt?,
    val restore: GmsDozeBrokerV2Attempt?,
) {
    fun attempt(grantId: String): GmsDozeBrokerV2Attempt? =
        add?.takeIf { it.grant.grantId == grantId } ?: restore?.takeIf { it.grant.grantId == grantId }
}

/** Constructed only by validated canonical decoding. Byte arrays never escape without cloning. */
class GmsDozeBrokerV2Proof internal constructor(bytes: ByteArray, val state: GmsDozeBrokerV2State) {
    private val bytes = bytes.clone()
    fun exactBytes(): ByteArray = bytes.clone()
}

interface GmsDozeBrokerV2DurablePort {
    /** Confirmed absence alone may initialize V2; unknown, corrupt or interrupted storage throws. */
    fun readExact(): GmsDozeBrokerV2Proof
    /**
     * Required App-UID cross-process atomic durable exact-byte CAS. Validate V2 transition inside
     * the storage lock, persist interruption fences, and return exact committed bytes. null means
     * confirmed CAS rejection. No default or in-memory production implementation is supplied.
     */
    fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerV2State): ByteArray?
    /** Durable across reconstructed processes; any ambiguous commit/readback must remain closed. */
    fun fenceUncertainCommit()
}

data class GmsDozeBrokerV2ReleaseAck(
    val key: GmsDozeBrokerKey,
    val grantId: String,
    val preparationRevision: Long,
)

/** Trusted actual-child evidence; a timeout, RPC return or Binder death cannot create it. */
data class GmsDozeBrokerV2Cleanup(
    val preparation: GmsDozeBrokerV2Preparation,
    val childExited: Boolean,
    val stdoutEofAndDrained: Boolean,
    val stderrEofAndDrained: Boolean,
    val collectorsTerminated: Boolean,
    val handlesClosed: Boolean,
) {
    fun complete() = childExited && stdoutEofAndDrained && stderrEofAndDrained && collectorsTerminated && handlesClosed
}

/**
 * Finite host protocol, with no production transport or business-engine adapter.
 *
 * Trusted adapter obligations: issue ADD only after fresh ADD_INTENT CAS/readback and verified
 * explicit ON; issue REMOVE only after fresh explicit restore authorization, current OFF and the
 * original APPLIED transaction. Result input requires actual child exit, both EOFs, collector
 * termination and handle close, followed by matching business APPLIED/RESTORED CAS/readback.
 * Release ACK must be an actual matching shell reply; death must come from the exact linked Binder.
 * Fields/checksums cannot authenticate these facts. OBSERVED_RESTORED requires no REMOVE grant or
 * write and remains the business adapter's responsibility. All RPC and long waits are outside here.
 */
class GmsDozeBrokerV2Coordinator(private val durable: GmsDozeBrokerV2DurablePort) {
    fun issueAddGrant(identity: GmsGlobalDozeIdentity, exactBusinessDocument: ByteArray): GmsDozeBrokerV2Proof? = guarded {
        val bytes = exactBusinessDocument.clone()
        val document = GmsGlobalDozeCodec.decode(bytes)
        val transaction = document.transaction ?: return@guarded null
        if (!document.consent.enabled || transaction.phase != DozePhase.ADD_INTENT ||
            transaction.epoch != document.consent.epoch) return@guarded null
        val grant = grant(identity, document, bytes, GmsGlobalDozeOperation.ADD)
        val before = read()
        if (before.state.add != null || before.state.restore != null) return@guarded null
        commit(before, before.state.copy(add = GmsDozeBrokerV2Attempt(grant)))
    }

    fun issueRestoreGrant(identity: GmsGlobalDozeIdentity, exactBusinessDocument: ByteArray): GmsDozeBrokerV2Proof? = guarded {
        val bytes = exactBusinessDocument.clone()
        val document = GmsGlobalDozeCodec.decode(bytes)
        val transaction = document.transaction ?: return@guarded null
        if (document.consent.enabled || transaction.phase != DozePhase.RESTORE_INTENT) return@guarded null
        val grant = grant(identity, document, bytes, GmsGlobalDozeOperation.REMOVE)
        val before = read()
        val add = before.state.add ?: return@guarded null
        if (before.state.restore != null || !GmsDozeBrokerV2Codec.retiredAdd(add) ||
            !GmsDozeBrokerV2Codec.restoreMatches(add, grant)) return@guarded null
        commit(before, before.state.copy(restore = GmsDozeBrokerV2Attempt(grant)))
    }

    fun pin(grantId: String, nonce: String): GmsDozeBrokerV2Proof? = change(grantId) { attempt, _ ->
        if (attempt.pin != null || attempt.revokeVersion != 0L || attempt.actualDeathObserved) null
        else attempt.copy(pin = GmsDozeBrokerKey(nonce, if (attempt.grant.operation == GmsGlobalDozeOperation.ADD) 1 else 2))
    }

    fun prepare(key: GmsDozeBrokerKey, grantId: String): GmsDozeBrokerV2Proof? = change(grantId) { attempt, state ->
        if (attempt.pin != key || attempt.preparation != null || attempt.revokeVersion != 0L ||
            attempt.actualDeathObserved) null
        else attempt.copy(preparation = GmsDozeBrokerV2Preparation(key, grantId, next(state.revision), 0))
    }

    /** Persistent revocation retains preparation/result and never grants subsequent starts. */
    fun requestRevoke(key: GmsDozeBrokerKey): GmsDozeBrokerV2Proof? = changeKey(key) { attempt ->
        attempt.copy(revokeVersion = next(attempt.revokeVersion))
    }

    fun acceptRevokeAck(ack: GmsDozeBrokerRevokeAck): GmsDozeBrokerV2Proof? = changeKey(ack.key) { attempt ->
        if (ack.revokeVersion <= 0 || ack.revokeVersion != attempt.revokeVersion ||
            attempt.acknowledgedRevoke == ack.revokeVersion) null
        else attempt.copy(acknowledgedRevoke = ack.revokeVersion)
    }

    fun acceptDeath(death: GmsDozeBrokerDeath): GmsDozeBrokerV2Proof? = changeKey(death.key) { attempt ->
        if (attempt.actualDeathObserved) null else attempt.copy(actualDeathObserved = true)
    }

    /** Results may arrive after revocation; completion never reopens the grant. */
    fun commitResult(cleanup: GmsDozeBrokerV2Cleanup, result: GmsDozeBrokerV2Result,
        exactBusinessDocument: ByteArray): GmsDozeBrokerV2Proof? = guarded {
        if (!cleanup.complete() || cleanup.preparation != result.preparation) return@guarded null
        val bytes = exactBusinessDocument.clone()
        val document = GmsGlobalDozeCodec.decode(bytes)
        val before = read()
        val attempt = before.state.attempt(result.preparation.grantId) ?: return@guarded null
        if (attempt.result != null || attempt.preparation != result.preparation ||
            result.businessResultSha256 != GmsDozeBrokerV2Codec.sha256(bytes)) return@guarded null
        val tx = document.transaction ?: return@guarded null
        val grant = attempt.grant
        if (!matchesDocument(grant.identity, document) || tx.id != grant.globalTransactionId ||
            tx.epoch != grant.transactionEpoch || GmsDozeBrokerV2Codec.rawSha256(tx.baseline) != grant.baselineSha256)
            return@guarded null
        when (grant.operation) {
            GmsGlobalDozeOperation.ADD -> if (tx.phase != DozePhase.APPLIED || tx.applied == null ||
                result.appliedSha256 != GmsDozeBrokerV2Codec.rawSha256(tx.applied)) return@guarded null
            GmsGlobalDozeOperation.REMOVE -> if (document.consent.enabled || tx.phase != DozePhase.RESTORED ||
                tx.applied == null || GmsDozeBrokerV2Codec.rawSha256(tx.applied) != grant.appliedSha256 ||
                result.appliedSha256 != grant.baselineSha256) return@guarded null
        }
        commit(before, replace(before.state, attempt.copy(result = result)))
    }

    fun acceptReleaseAck(ack: GmsDozeBrokerV2ReleaseAck): GmsDozeBrokerV2Proof? = change(ack.grantId) { attempt, _ ->
        if (attempt.pin != ack.key || attempt.result == null || attempt.releaseAcknowledged ||
            attempt.preparation?.preparationRevision != ack.preparationRevision) null
        else attempt.copy(releaseAcknowledged = true)
    }

    private fun grant(identity: GmsGlobalDozeIdentity, document: GmsGlobalDozeDocument,
        bytes: ByteArray, operation: GmsGlobalDozeOperation): GmsDozeBrokerV2Grant {
        GmsGlobalDozeCodec.validateIdentity(identity, document.consent.pinnedFingerprint)
        require(matchesDocument(identity, document))
        val tx = requireNotNull(document.transaction)
        return GmsDozeBrokerV2Grant(UUID.randomUUID().toString(), operation, tx.id, tx.epoch, identity,
            GmsDozeBrokerV2Codec.rawSha256(tx.baseline), tx.applied?.let(GmsDozeBrokerV2Codec::rawSha256),
            GmsDozeBrokerV2Codec.sha256(bytes))
    }

    private fun matchesDocument(identity: GmsGlobalDozeIdentity, document: GmsGlobalDozeDocument): Boolean =
        identity.gms == document.consent.gms && identity.guardian == document.consent.guardian &&
            identity.environment.fingerprint == document.consent.pinnedFingerprint &&
            document.transaction?.gms == identity.gms

    private fun changeKey(key: GmsDozeBrokerKey, transform: (GmsDozeBrokerV2Attempt) -> GmsDozeBrokerV2Attempt?):
        GmsDozeBrokerV2Proof? = guarded {
        val before = read()
        val attempt = before.state.add?.takeIf { it.pin == key } ?: before.state.restore?.takeIf { it.pin == key }
            ?: return@guarded null
        val after = transform(attempt) ?: return@guarded null
        commit(before, replace(before.state, after))
    }

    private fun change(grantId: String,
        transform: (GmsDozeBrokerV2Attempt, GmsDozeBrokerV2State) -> GmsDozeBrokerV2Attempt?): GmsDozeBrokerV2Proof? = guarded {
        val before = read()
        val attempt = before.state.attempt(grantId) ?: return@guarded null
        val after = transform(attempt, before.state) ?: return@guarded null
        commit(before, replace(before.state, after))
    }

    private fun replace(state: GmsDozeBrokerV2State, attempt: GmsDozeBrokerV2Attempt) =
        if (attempt.grant.operation == GmsGlobalDozeOperation.ADD) state.copy(add = attempt) else state.copy(restore = attempt)

    private fun read(): GmsDozeBrokerV2Proof = durable.readExact().also {
        check(GmsDozeBrokerV2Codec.decode(it.exactBytes()) == it.state)
    }

    private fun commit(before: GmsDozeBrokerV2Proof, value: GmsDozeBrokerV2State): GmsDozeBrokerV2Proof? {
        val replacement = value.copy(revision = next(before.state.revision))
        GmsDozeBrokerV2Codec.validateTransition(before.exactBytes(), GmsDozeBrokerV2Codec.encode(replacement))
        try {
            val bytes = durable.compareAndSet(before.exactBytes(), replacement) ?: return null
            val after = read()
            check(after.state == replacement && after.exactBytes().contentEquals(bytes)) { "V2 exact readback differs" }
            return after
        } catch (failure: Exception) {
            durable.fenceUncertainCommit()
            throw failure
        }
    }

    private inline fun guarded(action: () -> GmsDozeBrokerV2Proof?): GmsDozeBrokerV2Proof? =
        try { action() } catch (_: Exception) { null }
    private fun next(value: Long): Long { require(value in 0 until Long.MAX_VALUE); return value + 1 }
}

/**
 * One inert shell object for exactly one grant/key, with one activation and one actual start.
 * The short monitor coordinates this shell object's threads only. Trusted transport authenticates
 * the App/proofs and supplies a fresh shell-owned nonce; no cross-process admission or Android
 * adapter is supplied. Storage, identity probes, waits/drains and result persistence happen outside
 * the monitor. The fixed actual start and child registration share the revoke monitor.
 */
class GmsDozeBrokerV2Admission(val key: GmsDozeBrokerKey, grant: GmsDozeBrokerV2Grant) {
    /** Public model constructors may receive mutable caller lists; freeze the bound capability. */
    val grant = grant.copy(identity = grant.identity.copy(
        gms = immutableInstallation(grant.identity.gms),
        guardian = grant.identity.guardian.copy(installation = immutableInstallation(grant.identity.guardian.installation)),
    ))
    private val monitor = Any()
    private var activated = false
    private var activationConsumed = false
    private var revoked = false
    private var revokeVersion = 0L
    private var activationRevision = -1L
    private var preparation: GmsDozeBrokerV2Preparation? = null
    private var child: GmsDozeBrokerChild? = null
    private var busy = false
    private var startConsumed = false

    fun activate(proof: GmsDozeBrokerV2Proof): Boolean {
        val state = validated(proof) ?: return false
        return synchronized(monitor) {
            val attempt = state.attempt(grant.grantId)
            if (activationConsumed || revoked || attempt?.grant != grant || attempt.pin != key ||
                attempt.revokeVersion != 0L || attempt.actualDeathObserved || attempt.preparation != null)
                return@synchronized false
            activationConsumed = true
            activated = true
            activationRevision = state.revision
            true
        }
    }

    fun start(proof: GmsDozeBrokerV2Proof,
        actualFixedStart: (GmsGlobalDozeOperation) -> GmsDozeBrokerChild): GmsDozeBrokerStartResult {
        val state = validated(proof) ?: return GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.REJECTED)
        return synchronized(monitor) {
            val attempt = state.attempt(grant.grantId)
            val prep = attempt?.preparation
            if (!activated || revoked || startConsumed || attempt?.grant != grant || attempt.pin != key ||
                attempt.revokeVersion != revokeVersion || attempt.actualDeathObserved || attempt.result != null ||
                prep == null || prep.key != key || prep.grantId != grant.grantId || prep.revokeVersion != revokeVersion ||
                state.revision <= activationRevision || prep.preparationRevision != state.revision)
                return@synchronized GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.REJECTED)
            startConsumed = true
            busy = true
            preparation = prep
            try {
                val actualChild = actualFixedStart(grant.operation)
                child = actualChild
                GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.STARTED, actualChild)
            } catch (_: Exception) {
                // A throwing actual start may have created a child; uncertainty retains busy forever.
                GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.UNCERTAIN)
            }
        }
    }

    fun revoke(expectedKey: GmsDozeBrokerKey, version: Long): GmsDozeBrokerRevokeAck? = synchronized(monitor) {
        if (expectedKey != key || version <= 0 || version < revokeVersion) return@synchronized null
        revoked = true
        activated = false
        revokeVersion = version
        GmsDozeBrokerRevokeAck(key, version)
    }

    /** Trusted actual-child facts are gathered outside the monitor. Wrong handles are rejected. */
    fun cleanupEvidence(expectedChild: GmsDozeBrokerChild, childExited: Boolean,
        stdoutEofAndDrained: Boolean, stderrEofAndDrained: Boolean,
        collectorsTerminated: Boolean, handlesClosed: Boolean): GmsDozeBrokerV2Cleanup? = synchronized(monitor) {
        val prep = preparation ?: return@synchronized null
        if (!busy || child !== expectedChild) return@synchronized null
        GmsDozeBrokerV2Cleanup(prep, childExited, stdoutEofAndDrained, stderrEofAndDrained, collectorsTerminated, handlesClosed)
    }

    /** Exact result CAS/readback happened outside the monitor. Release never resets consumption. */
    fun release(expectedChild: GmsDozeBrokerChild, cleanup: GmsDozeBrokerV2Cleanup,
        committedResult: GmsDozeBrokerV2Proof): GmsDozeBrokerV2ReleaseAck? {
        val state = validated(committedResult) ?: return null
        return synchronized(monitor) {
            val attempt = state.attempt(grant.grantId)
            if (!busy || child !== expectedChild || !cleanup.complete() || preparation != cleanup.preparation ||
                attempt?.grant != grant || attempt.pin != key || attempt.preparation != preparation ||
                attempt.result?.preparation != preparation || attempt.releaseAcknowledged) return@synchronized null
            busy = false
            child = null
            GmsDozeBrokerV2ReleaseAck(key, grant.grantId, cleanup.preparation.preparationRevision)
        }
    }

    fun isBusy() = synchronized(monitor) { busy }

    /** Full decoding is deliberately outside the short revoke/start monitor. */
    private fun validated(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerV2State? = try {
        GmsDozeBrokerV2Codec.decode(proof.exactBytes()).takeIf { it == proof.state }
    } catch (_: Exception) { null }

    private fun immutableInstallation(value: GmsGlobalInstallation) =
        value.copy(signerSha256 = Collections.unmodifiableList(ArrayList(value.signerSha256)))
}
