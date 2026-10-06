package io.github.originosfcmguardian.core.doze

/** New versioned explicit automatic consent; old global enabled values are not accepted as input. */
enum class GmsDozeCycleConsentSource { EXPLICIT_AUTOMATIC_V1 }

data class GmsDozeCycleConsent(
    val consentId: String,
    val confirmedAtMillis: Long,
    val source: GmsDozeCycleConsentSource = GmsDozeCycleConsentSource.EXPLICIT_AUTOMATIC_V1,
    val version: Int = 1,
)

data class GmsDozeCycleBinding(
    val gms: GmsGlobalInstallation,
    val guardian: GmsGlobalGuardianBinding,
    val environment: GmsGlobalEnvironment,
)

/** Exact persisted OFF target, not authority to restore or start a child. */
data class GmsDozeCycleOffTarget(val ledgerId: String, val revision: Long, val sha256: String, val runningGeneration: Long)

/** New Doze-specific user confirmation; never reconstructed from the original enable/heartbeat. */
data class GmsDozeCycleReenableConfirmation(
    val confirmationId: String,
    val confirmedAtMillis: Long,
    val target: GmsDozeCycleOffTarget,
    val toRunningGeneration: Long,
    val version: Int = 1,
)

/** Exact existing CLEAN ON dialog snapshot; its hash is a confirmation CAS target, not a lease. */
data class GmsDozeCycleAutomaticTarget(
    val ledgerId: String, val revision: Long, val sha256: String, val runningGeneration: Long,
)

/** Fixed version1 scope: automatic ADD only within an independently qualified existing host. */
data class GmsDozeCycleAutomaticConsent(
    val confirmationId: String,
    val confirmedAtMillis: Long,
    val target: GmsDozeCycleAutomaticTarget,
    val version: Int = 1,
)

/** Bind the full consent, not merely ID/generation; fields cannot authenticate a live host. */
data class GmsDozeCycleAutomaticScope(val ledgerId: String, val consent: GmsDozeCycleAutomaticConsent) {
    val confirmationId: String get() = consent.confirmationId
    val runningGeneration: Long get() = consent.target.runningGeneration
}

enum class GmsDozeCyclePhase {
    ADD_INTENT, APPLIED_PENDING, APPLIED_SETTLED, RESTORE_INTENT, RESTORED_PENDING, RESTORED, OBSERVED_RESTORED,
}

/** Every executor receipt names this full immutable tuple, including the actual shell key. */
data class GmsDozeCycleAttemptRef(
    val ledgerId: String,
    val cycleId: String,
    val transactionId: String,
    val transactionEpoch: Long,
    val grantId: String,
    val operation: GmsGlobalDozeOperation,
    val key: GmsDozeBrokerKey,
)

data class GmsDozeCycleGrant(
    val ref: GmsDozeCycleAttemptRef,
    val identity: GmsGlobalDozeIdentity,
    val baselineSha256: String,
    /** REMOVE retains the original applied hash; ADD has no applied value yet. */
    val appliedSha256: String?,
)

/** Trusted actual-child facts; fields/checksums do not authenticate execution or cleanup. */
data class GmsDozeCycleCleanup(
    val ref: GmsDozeCycleAttemptRef,
    val childExited: Boolean,
    val stdoutEofAndDrained: Boolean,
    val stderrEofAndDrained: Boolean,
    val collectorsTerminated: Boolean,
    val handlesClosed: Boolean,
) {
    fun complete() = childExited && stdoutEofAndDrained && stderrEofAndDrained && collectorsTerminated && handlesClosed
}

data class GmsDozeCycleResult(
    val ref: GmsDozeCycleAttemptRef,
    val cleanup: GmsDozeCycleCleanup,
    val exitCode: Int,
    val commandOutputValid: Boolean,
    val identity: GmsGlobalDozeIdentity,
    val rawWhitelist: String,
)

data class GmsDozeCycleReleaseAck(val ref: GmsDozeCycleAttemptRef)
enum class GmsDozeCycleClosureKind { REVOKE_ACK, ACTUAL_BINDER_DEATH }
data class GmsDozeCycleClosure(
    val ref: GmsDozeCycleAttemptRef,
    val kind: GmsDozeCycleClosureKind,
    val revokeVersion: Long,
)

data class GmsDozeCycleAttempt(
    val grant: GmsDozeCycleGrant,
    val startIntent: Boolean = false,
    val result: GmsDozeCycleResult? = null,
    val releaseAck: GmsDozeCycleReleaseAck? = null,
    val requestedRevoke: Long = 0,
    val closure: GmsDozeCycleClosure? = null,
)

/** Independent fresh action naming exactly one ledger/cycle/transaction; consumed by one REMOVE grant. */
data class GmsDozeCycleRestoreConsent(
    val consentId: String,
    val ledgerId: String,
    val cycleId: String,
    val transactionId: String,
    val transactionEpoch: Long,
    val confirmedAtMillis: Long,
)

data class GmsDozeCycleRecord(
    val cycleId: String,
    val transactionId: String,
    val transactionEpoch: Long,
    val baseline: String,
    val applied: String?,
    val phase: GmsDozeCyclePhase,
    val add: GmsDozeCycleAttempt,
    val restoreConsent: GmsDozeCycleRestoreConsent? = null,
    val remove: GmsDozeCycleAttempt? = null,
)

/** Append-only finite history. Running intent is not writer authority. Capacity is never pruned. */
data class GmsDozeCycleLedger(
    val ledgerId: String,
    val revision: Long,
    val runningIntent: Boolean,
    val runningGeneration: Long,
    val automaticConsent: GmsDozeCycleConsent,
    val binding: GmsDozeCycleBinding,
    val cycles: List<GmsDozeCycleRecord>,
    val latestReenable: GmsDozeCycleReenableConfirmation? = null,
    val automaticSessionConsent: GmsDozeCycleAutomaticConsent? = null,
)

interface GmsDozeCycleLedgerPort {
    /** Required shared current-process owner for the canonical ledger path; no per-instance gate. */
    val control: GmsDozeCycleControl
    /** null is confirmed absence. Corruption, interrupted files, old material and unknown reads throw. */
    fun load(): ByteArray?
    /**
     * Required App-UID exact-byte atomic durable CAS under one cross-process storage lock. Validate
     * transition inside that lock, enforce permanent ledger marker and interruption fences, and
     * establish exact commit/readback. Confirmed rejection alone returns false; uncertainty throws.
     * No default or production memory port. All executor RPC/waits occur outside this storage call.
     */
    fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean
}

/** One-use process-local OFF registration; never a persisted grant or cross-process authority. */
class GmsDozeCycleLocalOffTicket internal constructor(internal val owner: GmsDozeCycleControl, val sequence: Long)

/**
 * Every same-path store/coordinator in one process shares this owner. Reconstruction gets no old
 * one-use tokens. This short local gate is not a cross-process executor permit. Never call storage,
 * Binder/RPC or child waits while holding it. The store may failClosed after any uncertain failure.
 */
class GmsDozeCycleControl {
    private val ledgerMonitor = Any()
    internal val monitor = Any()
    internal var terminal = false
    internal var addsClosed = false
    internal var offSequence = 0L
    internal var outstandingOff = 0
    internal val offTickets = mutableMapOf<GmsDozeCycleLocalOffTicket, Boolean>()
    internal var activeRunningGeneration: Long? = null
    internal val preparedGenerations = mutableMapOf<GmsDozeCycleAttemptRef, Long>()
    internal val prepared = mutableSetOf<GmsDozeCycleAttemptRef>()
    internal val pendingStarts = mutableSetOf<GmsDozeCycleAttemptRef>()
    /** Shared consumption for either an actual local start or one outbound dispatch reservation. */
    internal val consumedStarts = mutableSetOf<GmsDozeCycleAttemptRef>()
    internal val closedGrants = mutableSetOf<GmsDozeCycleAttemptRef>()

    /**
     * Serialize the full load/CAS/exact-readback operation among same-path coordinators. Distinct
     * from the short start gate, reentrant for the bridge's request-or-existing-closure decision.
     * No executor RPC, fresh probe or child wait belongs here; never acquire it from the start gate.
     */
    fun <T> withLedgerOperation(action: () -> T): T = synchronized(ledgerMonitor) { action() }

    fun closeAdds() = synchronized(monitor) { addsClosed = true }
    /** Register synchronously BEFORE enqueueing OFF persistence; re-enable sees the pending intent. */
    fun beginOff(): GmsDozeCycleLocalOffTicket? = synchronized(monitor) {
        addsClosed = true
        if (terminal || offSequence == Long.MAX_VALUE) { terminal = true; return@synchronized null }
        val ticket = GmsDozeCycleLocalOffTicket(this, ++offSequence)
        offTickets[ticket] = false
        outstandingOff++
        ticket
    }
    fun failClosed() = synchronized(monitor) { terminal = true; addsClosed = true }
    fun isTerminal() = synchronized(monitor) { terminal }
    fun localWritesClosed() = synchronized(monitor) { terminal || addsClosed }
}

enum class GmsDozeCycleDecisionKind { NO_WRITE, APPLIED_RETAINED, NATURAL_BASELINE_AVAILABLE, READY_FOR_NEW_CYCLE, BLOCKED }
data class GmsDozeCycleDecision(val kind: GmsDozeCycleDecisionKind, val reason: String)
