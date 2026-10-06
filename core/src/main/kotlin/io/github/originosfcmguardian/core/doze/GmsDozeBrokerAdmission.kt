package io.github.originosfcmguardian.core.doze

/** Host protocol only. This does not implement GmsGlobalDozeAdmissionPort or engine.disable(). */
/** Host key injection models a future fresh shell-owned nonce, not implemented nonce generation. */
data class GmsDozeBrokerKey(val nonce: String, val generation: Long)

/** Every legacy/global device mutation must use the same uniquely pinned shell broker. */
enum class GmsDozeBrokerOperation { LEGACY_ADD, LEGACY_REMOVE, GLOBAL_ADD, GLOBAL_REMOVE }

data class GmsDozeBrokerIntent(
    val key: GmsDozeBrokerKey,
    val transactionId: String,
    val operation: GmsDozeBrokerOperation,
    val revokeVersion: Long,
    /** Distinguishes preparations even if a faulty adapter reuses a transaction string. */
    val preparationRevision: Long,
)

data class GmsDozeBrokerState(
    val revision: Long,
    val lastBrokerGeneration: Long,
    val pin: GmsDozeBrokerKey?,
    val brokerClosed: Boolean,
    /** Explicit consent supplied by the future App authority adapter, never opened by a reply. */
    val preparationAllowed: Boolean,
    val revokeVersion: Long,
    val intent: GmsDozeBrokerIntent?,
    val completedResult: GmsDozeBrokerIntent?,
)

/** The adapter guarantees state is the validated decoding of these exact immutable bytes. */
class GmsDozeBrokerProof(bytes: ByteArray, val state: GmsDozeBrokerState) {
    private val value = bytes.clone()
    fun exactBytes(): ByteArray = value.clone()
}

interface GmsDozeBrokerDurablePort {
    /** Unknown, corrupt or interrupted storage must throw; absence is an explicit initial state. */
    fun readExact(): GmsDozeBrokerProof
    /**
     * App-UID atomic durable exact-byte CAS under the shared storage lock, across all App processes.
     * Validate the transition and persist interrupted-commit fences. Return exact committed bytes;
     * null is confirmed CAS rejection. Ambiguous commit/readback must remain fail-closed across
     * restart until reconciled. No private/default JVM implementation is supplied.
     */
    fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerState): ByteArray?
    /**
     * Durably fence ALL subsequent reads/CAS, including reconstructed processes, after an uncertain
     * commit/readback. This must retain a storage interruption marker; a local boolean is insufficient.
     * If marker storage fails, the adapter must remain intrinsically fail-closed, never return ON.
     */
    fun fenceUncertainCommit()
}

data class GmsDozeBrokerRevokeAck(val key: GmsDozeBrokerKey, val revokeVersion: Long)

/** Only an authenticated actual broker-process death observation can produce this evidence. */
data class GmsDozeBrokerDeath(val key: GmsDozeBrokerKey)

/** These facts come from the actual registered child; RPC return, timeout or death is insufficient. */
data class GmsDozeBrokerCleanup(
    val intent: GmsDozeBrokerIntent,
    val childExited: Boolean,
    val stdoutEofAndDrained: Boolean,
    val stderrEofAndDrained: Boolean,
    val handlesClosed: Boolean,
) {
    fun complete(): Boolean = childExited && stdoutEofAndDrained && stderrEofAndDrained && handlesClosed
}

/**
 * App-side short durable transitions. All RPC calls are explicitly outside this object.
 * OFF returns a pending proof: only acceptRevokeAck/acceptDeath closes future-start uncertainty.
 * Neither closure settles a child nor authorizes replacing a retained intent.
 */
class GmsDozeBrokerCoordinator(private val durable: GmsDozeBrokerDurablePort) {
    /**
     * Trusted adapter must establish fresh explicit authority before calling. This does not create
     * product consent, validate its origin, reopen an unclosed broker or replace retained intent.
     * The opt-in debug fixture may use it only for its independent no-system-write scratch record.
     */
    fun enableFromExplicitConsent(): GmsDozeBrokerProof? = guarded {
        val before = durable.readExact()
        if (before.state.preparationAllowed || !before.state.brokerClosed || before.state.intent != null)
            return@guarded null
        commit(before, before.state.copy(preparationAllowed = true))
    }

    fun pin(nonce: String): GmsDozeBrokerProof? = guarded {
        requireIdentifier(nonce)
        val before = durable.readExact()
        val state = before.state
        if (!state.preparationAllowed || state.intent != null ||
            (state.pin != null && (!state.brokerClosed || state.pin.nonce == nonce))) return@guarded null
        val generation = next(state.lastBrokerGeneration)
        commit(before, state.copy(pin = GmsDozeBrokerKey(nonce, generation),
            lastBrokerGeneration = generation, brokerClosed = false))
    }

    fun prepare(key: GmsDozeBrokerKey, transactionId: String,
        operation: GmsDozeBrokerOperation): GmsDozeBrokerProof? = guarded {
        requireIdentifier(transactionId)
        val before = durable.readExact()
        val state = before.state
        if (state.pin != key || state.brokerClosed || !state.preparationAllowed || state.intent != null ||
            state.completedResult?.transactionId == transactionId) return@guarded null
        commit(before, state.copy(intent = GmsDozeBrokerIntent(key, transactionId, operation,
            state.revokeVersion, next(state.revision))))
    }

    /** Persistent OFF first: bans new preparation and preserves any intent, without waiting for RPC. */
    fun disable(): GmsDozeBrokerProof? = guarded {
        val before = durable.readExact()
        commit(before, before.state.copy(preparationAllowed = false,
            revokeVersion = next(before.state.revokeVersion)))
    }

    fun acceptRevokeAck(ack: GmsDozeBrokerRevokeAck): GmsDozeBrokerProof? = guarded {
        val before = durable.readExact()
        val state = before.state
        if (state.pin != ack.key || state.preparationAllowed || state.revokeVersion != ack.revokeVersion)
            return@guarded null
        commit(before, state.copy(brokerClosed = true))
    }

    fun acceptDeath(death: GmsDozeBrokerDeath): GmsDozeBrokerProof? = guarded {
        val before = durable.readExact()
        if (before.state.pin != death.key) return@guarded null
        commit(before, before.state.copy(brokerClosed = true, preparationAllowed = false))
    }

    /** Fifth completion item is this matching transaction result CAS AND independent exact readback. */
    fun commitResult(cleanup: GmsDozeBrokerCleanup): GmsDozeBrokerProof? = guarded {
        if (!cleanup.complete()) return@guarded null
        val before = durable.readExact()
        if (before.state.pin != cleanup.intent.key || before.state.intent != cleanup.intent)
            return@guarded null
        commit(before, before.state.copy(intent = null, completedResult = cleanup.intent))
    }

    private fun commit(before: GmsDozeBrokerProof, value: GmsDozeBrokerState): GmsDozeBrokerProof? {
        val replacement = value.copy(revision = next(before.state.revision))
        try {
            val bytes = durable.compareAndSet(before.exactBytes(), replacement) ?: return null
            val after = durable.readExact()
            check(after.state == replacement && after.exactBytes().contentEquals(bytes)) { "Exact result readback differs" }
            return after
        } catch (failure: Exception) {
            durable.fenceUncertainCommit()
            throw failure
        }
    }

    private inline fun guarded(action: () -> GmsDozeBrokerProof?): GmsDozeBrokerProof? =
        try { action() } catch (_: Exception) { null }
    private fun next(value: Long): Long { require(value in 0 until Long.MAX_VALUE); return value + 1 }
    private fun requireIdentifier(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
}

/** Opaque actual child handle owned by the shell adapter, never a queued Future or RPC handle. */
interface GmsDozeBrokerChild

enum class GmsDozeBrokerStartStatus { STARTED, REJECTED, UNCERTAIN }
data class GmsDozeBrokerStartResult(val status: GmsDozeBrokerStartStatus, val child: GmsDozeBrokerChild? = null)

/**
 * One object in the single pinned shell process. The monitor coordinates that broker's own threads;
 * it is NOT App/shell or cross-process synchronization. New objects are INERT. Trusted transport must
 * authenticate the App, broker identity and durable proofs; no Android transport exists here.
 *
 * Only actual fixed start runs inside the short monitor. RPC/storage/child waits/drains stay outside.
 * If start itself hangs, OFF can remain pending STOPPING/UNKNOWN; callers must not claim an ACK.
 */
class GmsDozeBrokerAdmission(val key: GmsDozeBrokerKey) {
    // The real shell adapter must create its own never-reused nonce before accepting App IPC.
    // Reconstructing an object with an externally supplied old key is not a supported adapter.
    private val monitor = Any()
    private var activated = false
    private var activationConsumed = false
    private var revoked = false
    private var revokeVersion = 0L
    private var consumedRevision = -1L
    private var busy: GmsDozeBrokerIntent? = null
    private var child: GmsDozeBrokerChild? = null

    fun activate(proof: GmsDozeBrokerProof): Boolean = synchronized(monitor) {
        val state = proof.state
        if (activationConsumed || revoked || state.pin != key || state.brokerClosed ||
            !state.preparationAllowed || state.intent != null || proof.exactBytes().isEmpty()) return@synchronized false
        activationConsumed = true
        revokeVersion = state.revokeVersion
        consumedRevision = state.revision
        activated = true
        true
    }

    /** Compare nonce/generation/revoke and busy, start the actual child, then register its handle. */
    fun start(proof: GmsDozeBrokerProof,
        actualFixedStart: (GmsDozeBrokerOperation) -> GmsDozeBrokerChild): GmsDozeBrokerStartResult = synchronized(monitor) {
        val state = proof.state
        val intent = state.intent
        if (!activated || revoked || busy != null || state.pin != key || state.brokerClosed ||
            !state.preparationAllowed || intent == null || intent.key != key ||
            state.revision <= consumedRevision ||
            intent.preparationRevision != state.revision ||
            intent.revokeVersion != revokeVersion || state.revokeVersion != revokeVersion ||
            proof.exactBytes().isEmpty()) return@synchronized GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.REJECTED)
        busy = intent
        consumedRevision = state.revision
        try {
            val started = actualFixedStart(intent.operation)
            child = started
            GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.STARTED, started)
        } catch (_: Exception) {
            // A throwing start may have created a child. Keep busy and durable intent indefinitely.
            GmsDozeBrokerStartResult(GmsDozeBrokerStartStatus.UNCERTAIN)
        }
    }

    /** Acknowledges future start closure even while an already registered child remains busy. */
    fun revoke(expectedKey: GmsDozeBrokerKey, version: Long): GmsDozeBrokerRevokeAck? = synchronized(monitor) {
        if (expectedKey != key || version < revokeVersion || version < 0) return@synchronized null
        revoked = true
        activated = false
        revokeVersion = version
        GmsDozeBrokerRevokeAck(key, version)
    }

    /** Called after actual exit/drains/close outside the monitor, for the exact registered handle. */
    fun cleanupEvidence(expectedChild: GmsDozeBrokerChild, childExited: Boolean,
        stdoutEofAndDrained: Boolean, stderrEofAndDrained: Boolean,
        handlesClosed: Boolean): GmsDozeBrokerCleanup? = synchronized(monitor) {
        val intent = busy ?: return@synchronized null
        if (child !== expectedChild) return@synchronized null
        GmsDozeBrokerCleanup(intent, childExited, stdoutEofAndDrained, stderrEofAndDrained, handlesClosed)
    }

    /** Result persistence/readback is outside the monitor; no timeout/ACK/death path calls this. */
    fun release(expectedChild: GmsDozeBrokerChild, cleanup: GmsDozeBrokerCleanup,
        committedResult: GmsDozeBrokerProof): Boolean = synchronized(monitor) {
        val state = committedResult.state
        if (child !== expectedChild || busy != cleanup.intent || !cleanup.complete() ||
            state.pin != key || state.intent != null || state.completedResult != cleanup.intent ||
            committedResult.exactBytes().isEmpty()) return@synchronized false
        busy = null
        child = null
        consumedRevision = maxOf(consumedRevision, state.revision)
        true
    }

    fun isBusy(): Boolean = synchronized(monitor) { busy != null }
}
