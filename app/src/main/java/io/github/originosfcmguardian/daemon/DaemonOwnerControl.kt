package io.github.originosfcmguardian.daemon

/** Identity of one installation's one elected owner; an epoch is never reused after replacement. */
internal data class DaemonOwnerKey(val installInstance: String, val ownerEpoch: Long) {
    init { require(installInstance.isNotBlank() && installInstance.length <= 128 && ownerEpoch > 0) }
}

internal enum class DaemonControlMode { PREPARED, ACTIVE, OFF }
internal enum class DaemonOwnerStatus { UNRECONCILED, PREPARED, ACTIVE, OFF, PENDING_RECOVERY, FAULT }

/** A reference to existing authority bytes, not a replacement consent/configuration representation. */
internal data class DaemonControlRecord(
    val owner: DaemonOwnerKey,
    val revision: Long,
    val authorityDigest: String,
    val mode: DaemonControlMode,
    val migrationId: String,
) {
    init {
        require(revision > 0 && authorityDigest.matches(Regex("[0-9a-f]{64}")))
        require(migrationId.isNotBlank() && migrationId.length <= 128)
    }
}

/** Issued only after the App has durably committed and read back its remote-owner pointer. */
internal data class DaemonPointerCommitProof(val commitId: String, val record: DaemonControlRecord) {
    init { require(commitId.isNotBlank() && commitId.length <= 128) }
}

internal sealed interface DaemonControlIntent {
    data class Prepare(val migrationId: String, val authorityDigest: String) : DaemonControlIntent
    data class Activate(val pointer: DaemonPointerCommitProof) : DaemonControlIntent
    /** Includes master OFF and target exclusion; the existing authority writer supplies the digest. */
    data class SetAuthority(val pointer: DaemonPointerCommitProof) : DaemonControlIntent
}

internal data class DaemonControlRequest(
    val requestId: String,
    val owner: DaemonOwnerKey,
    val expectedRevision: Long,
    val intent: DaemonControlIntent,
) {
    init { require(requestId.isNotBlank() && requestId.length <= 128 && expectedRevision > 0) }
}

internal data class DaemonControlReceipt(val request: DaemonControlRequest, val committed: DaemonControlRecord)
internal data class DaemonControlRead(
    val record: DaemonControlRecord,
    val interrupted: Boolean = false,
    val pendingRecovery: Boolean = false,
)
internal data class DaemonHaltFence(val request: DaemonControlRequest, val nextRevision: Long)
internal data class DaemonReconcileProof(val pointer: DaemonPointerCommitProof, val reconciliationId: String)

/** Captured in the daemon from Binder UID + a fresh system package read, never App-supplied fields. */
internal data class DaemonCallerEvidence(val realUid: Int, val installation: DaemonGuardianInstallation)

/**
 * Authoritative, cross-process storage/authentication boundary, implemented by the daemon adapter.
 * All operations are bounded and synchronous. No implementation may reconstruct consent bytes.
 *
 * fence must CAS the exact old record/owner and durably block writes BEFORE returning a fence.
 * commitAndReadBack atomically commits the record AND its request-id receipt, clears that exact
 * fence, then independently reads them back. A failed/ambiguous operation must not report success.
 * Receipts survive restart. A bounded receipt ledger may forget old revisions: the exact-revision
 * gate still rejects their replay. Retained request IDs cannot be reused with a different payload.
 *
 * reconcile authenticates the App and independently verifies its current committed pointer, the
 * authority bytes/digest and any interrupted transaction. It may clear a halt only after explicit
 * reconciliation; it must never choose an older ACTIVE snapshot as a fallback. Old pending recovery
 * is resolved through the existing journal's READ-ONLY readback, never by replaying a command.
 * Until that resolution is durable, pendingRecovery remains true. Authentication cannot be inferred
 * from Binder caller death. withOwnerLease holds the global shell-storage lifetime lock throughout
 * the immediate callback; closing/replacing the owner cannot release that lease during the start.
 */
internal interface DaemonControlPort {
    fun read(): DaemonControlRead
    fun receipt(owner: DaemonOwnerKey, requestId: String): DaemonControlReceipt?
    fun verifyPointerCommit(proof: DaemonPointerCommitProof): Boolean
    fun fence(expected: DaemonControlRecord, request: DaemonControlRequest): DaemonHaltFence?
    fun commitAndReadBack(fence: DaemonHaltFence, receipt: DaemonControlReceipt): DaemonControlRead
    fun reconcile(proof: DaemonReconcileProof): DaemonControlRead?
    fun withOwnerLease(start: () -> Unit): Boolean
}

/** The prerequisite adapter repeats the existing identity/privilege/lease/journal/operation gates. */
internal fun interface DaemonPrerequisitePort {
    fun readFresh(ticket: DaemonWriteTicket): DaemonWriteEvidence?
}

internal class DaemonWriteTicket internal constructor(
    val sequence: Long,
    val record: DaemonControlRecord,
    /** Exact target identity AND allowlisted operation scope, supplied by the existing engine. */
    val operationKey: String,
)

internal data class DaemonWriteEvidence(
    val ticket: DaemonWriteTicket,
    val observedAtElapsed: Long,
    val expiresAtElapsed: Long,
    val prerequisitesSatisfied: Boolean,
)

/** Process-local capability: only the exact instance retained by its owner can start a command. */
internal class DaemonPreparedWrite internal constructor(internal val ticket: DaemonWriteTicket,
    internal val evidence: DaemonWriteEvidence)

internal sealed interface DaemonControlReply {
    /** A historical receipt is not a claim that its revision is still current. */
    data class Acknowledged(val receipt: DaemonControlReceipt, val currentRevision: Long) : DaemonControlReply
    data class Rejected(val reason: Reason) : DaemonControlReply
    enum class Reason { OWNER, AUTHENTICATION, REVISION, REQUEST_CONFLICT, STATE, PROOF, STORAGE, PENDING }
}

/**
 * Single owner control kernel. Production activation still requires the actual storage, authenticated
 * IPC, prerequisite and engine adapters. It owns no shell, Android lifecycle or consent serialization.
 * Every start/recheck/completion and control change shares one monitor. startWrite's callback MUST
 * perform the irreversible command start before returning, and MUST return promptly (no wait/join).
 * Thus OFF and command start have an unambiguous order; OFF cannot undo an already started command.
 */
internal class DaemonOwnerControl(
    private val owner: DaemonOwnerKey,
    private val approvedCaller: DaemonGuardianInstallation,
    private val storage: DaemonControlPort,
    private val prerequisites: DaemonPrerequisitePort,
    private val elapsed: () -> Long,
    private val maximumEvidenceAgeMillis: Long = 1_000,
) {
    private val lock = Any()
    private var record: DaemonControlRecord? = null
    private var status = DaemonOwnerStatus.UNRECONCILED
    private var minimumReconcileRevision = 1L
    private var sequence = 0L
    private var flight: DaemonWriteTicket? = null
    private var started = false
    private var prepared: DaemonPreparedWrite? = null
    private var probing = false
    private var completionRequested = false
    private var admissionClosed = true

    init {
        require(maximumEvidenceAgeMillis in 0..60_000)
        try {
            val read = storage.read()
            if (read.record.owner != owner || read.interrupted) status = DaemonOwnerStatus.FAULT
            else {
                record = read.record
                minimumReconcileRevision = read.record.revision
            }
            // Even an intact ACTIVE record never starts a restarted owner by itself.
        } catch (_: Exception) { status = DaemonOwnerStatus.FAULT }
    }

    fun status(): DaemonOwnerStatus = synchronized(lock) { status }
    fun snapshot(): DaemonControlRecord? = synchronized(lock) { record }
    fun isDispatchReady(): Boolean = synchronized(lock) { status == DaemonOwnerStatus.ACTIVE && !admissionClosed }

    /** Fixed child materialization only; no external IO or arbitrary mutation callback. */
    fun deriveBundledHeartbeat(root: DaemonProtectionRoot, expected: DaemonControlRecord, expectedDataRevision: Long,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample,
        bootId: String, observedAtElapsed: Long): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(lock) {
        if (status != DaemonOwnerStatus.ACTIVE || admissionClosed || record != expected || expected.owner != owner ||
            expected.mode != DaemonControlMode.ACTIVE || flight != null || probing || prepared != null) return@synchronized null
        val now = try { elapsed() } catch (_: Exception) { return@synchronized null }
        if (observedAtElapsed < 0 || now < observedAtElapsed || now - observedAtElapsed > maximumEvidenceAgeMillis) return@synchronized null
        try { root.deriveBundledHeartbeat(expected, expectedDataRevision, sample, bootId, observedAtElapsed, now,
            maximumEvidenceAgeMillis) } catch (_: Exception) { fault(); null }
    }

    /** Same-consent native continuation; OFF and any other admitted flight win this boundary. */
    fun renewRevokedHeartbeat(root: DaemonProtectionRoot, expected: DaemonControlRecord, expectedDataRevision: Long,
        closure: DaemonHeartbeatConfirmationClosure,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample,
        bootId: String, observedAtElapsed: Long): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(lock) {
        if (status != DaemonOwnerStatus.ACTIVE || admissionClosed || record != expected || expected.owner != owner ||
            expected.mode != DaemonControlMode.ACTIVE || flight != null || probing || prepared != null) return@synchronized null
        val now = try { elapsed() } catch (_: Exception) { return@synchronized null }
        if (observedAtElapsed < 0 || now < observedAtElapsed || now - observedAtElapsed > maximumEvidenceAgeMillis) return@synchronized null
        try { root.renewRevokedHeartbeat(expected, expectedDataRevision, closure, sample, bootId, observedAtElapsed, now,
            maximumEvidenceAgeMillis) } catch (_: Exception) { fault(); null }
    }

    /** Fixed boot-binding maintenance; fresh environment reads must finish before this boundary. */
    fun rebindBundledRecovery(root: DaemonProtectionRoot, expected: DaemonControlRecord, expectedDataRevision: Long,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample,
        bootId: String, observedAtElapsed: Long): Boolean = synchronized(lock) {
        if (status != DaemonOwnerStatus.ACTIVE || admissionClosed || record != expected || expected.owner != owner ||
            expected.mode != DaemonControlMode.ACTIVE || flight != null || probing || prepared != null) return@synchronized false
        val now = try { elapsed() } catch (_: Exception) { return@synchronized false }
        if (observedAtElapsed < 0 || now < observedAtElapsed || now - observedAtElapsed > maximumEvidenceAgeMillis) return@synchronized false
        try { root.rebindBundledRecovery(expected, expectedDataRevision, sample, bootId, observedAtElapsed, now,
            maximumEvidenceAgeMillis) } catch (_: Exception) { fault(); false }
    }

    /** Trusted reconciliation only: refresh an existing unused grant, never create/reissue one. */
    fun refreshUnusedHeartbeatPermit(root: DaemonProtectionRoot, caller: DaemonCallerEvidence,
        expected: DaemonControlRecord, expectedDataRevision: Long,
        freshCaller: io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity,
        sample: io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Sample,
        bootId: String, observedAtElapsed: Long): Boolean = synchronized(lock) {
        if (!authenticated(caller) || freshCaller.uid != approvedCaller.identity.uid ||
            status != DaemonOwnerStatus.ACTIVE || admissionClosed || record != expected || expected.owner != owner ||
            expected.mode != DaemonControlMode.ACTIVE || flight != null || probing || prepared != null) return@synchronized false
        val now = try { elapsed() } catch (_: Exception) { return@synchronized false }
        if (observedAtElapsed < 0 || now < observedAtElapsed || now - observedAtElapsed > maximumEvidenceAgeMillis) return@synchronized false
        try { root.refreshUnusedHeartbeatPermit(expected, expectedDataRevision, freshCaller, sample, bootId,
            observedAtElapsed, now, maximumEvidenceAgeMillis) } catch (_: Exception) { fault(); false }
    }

    /** Fixed negative cleanup only. Host validates fresh environment, actual drain and old-gone first. */
    fun reconcileInitialHeartbeatArm(root: DaemonProtectionRoot, caller: DaemonCallerEvidence,
        expected: DaemonControlRecord, expectedDataRevision: Long, source: DaemonInitialArmSource,
        observedAtElapsed: Long): io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshot? = synchronized(lock) {
        // Repair never grants readiness. A cold pending owner must reconcile its pointer first,
        // and remains PENDING_RECOVERY until a normal fresh reconciliation observes the cleanup.
        if (!authenticated(caller) || status !in setOf(DaemonOwnerStatus.ACTIVE, DaemonOwnerStatus.PENDING_RECOVERY) ||
            record != expected || expected.owner != owner || expected.mode != DaemonControlMode.ACTIVE ||
            flight != null || probing || prepared != null) return@synchronized null
        val now = try { elapsed() } catch (_: Exception) { return@synchronized null }
        if (observedAtElapsed < 0 || now < observedAtElapsed || now - observedAtElapsed > maximumEvidenceAgeMillis) return@synchronized null
        try { root.reconcileInitialHeartbeatArm(expected, expectedDataRevision, source) }
        catch (_: Exception) { fault(); null }
    }

    /** Close before staging/pointer IO. The caller must hold no port/root/staging monitor. */
    fun quiesceForControl(caller: DaemonCallerEvidence, expected: DaemonControlRecord): Boolean = synchronized(lock) {
        if (!authenticated(caller) || record != expected || expected.owner != owner ||
            status in setOf(DaemonOwnerStatus.FAULT, DaemonOwnerStatus.UNRECONCILED)) return@synchronized false
        admissionClosed = true
        prepared = null
        // Never discard an already admitted/running worker or its unsettled result.
        true
    }

    /** An ordinary App disconnect has no authority semantics, including while a write is pending. */
    fun appDisconnected() = Unit

    fun reconcile(caller: DaemonCallerEvidence, proof: DaemonReconcileProof): Boolean = synchronized(lock) {
        // A live callback cannot be abandoned to make room for a second command.
        if (!authenticated(caller) || flight != null || proof.pointer.record.owner != owner) return@synchronized false
        admissionClosed = true
        status = DaemonOwnerStatus.UNRECONCILED
        try {
            val read = storage.reconcile(proof) ?: return@synchronized false
            if (read.interrupted || read.record != proof.pointer.record || read.record.owner != owner ||
                read.record.revision < minimumReconcileRevision) return@synchronized fault()
            record = read.record
            minimumReconcileRevision = read.record.revision
            status = read.status()
            admissionClosed = false
            status != DaemonOwnerStatus.PENDING_RECOVERY
        } catch (_: Exception) { fault() }
    }

    /** Query after ACK loss; this cannot execute a transition or re-enable an old revision. */
    fun query(caller: DaemonCallerEvidence, request: DaemonControlRequest): DaemonControlReply = synchronized(lock) {
        if (request.owner != owner) return@synchronized reject(DaemonControlReply.Reason.OWNER)
        try {
            if (!authenticated(caller)) return@synchronized reject(DaemonControlReply.Reason.AUTHENTICATION)
            val saved = storage.receipt(owner, request.requestId)
                ?: return@synchronized reject(DaemonControlReply.Reason.REVISION)
            if (saved.request != request) return@synchronized reject(DaemonControlReply.Reason.REQUEST_CONFLICT)
            ack(saved)
        } catch (_: Exception) { storageFailure() }
    }

    fun apply(caller: DaemonCallerEvidence, request: DaemonControlRequest): DaemonControlReply =
        applyInternal(caller, request, false)

    /**
     * After owner restart the App may already point at the next revision. Fresh attestation of the
     * exact durable proposal can finish that control commit, never open the old ACTIVE revision.
     * An interrupted/failed commit or an occupied old flight cannot enter this path.
     */
    fun resumeAttestedTransition(caller: DaemonCallerEvidence, request: DaemonControlRequest): DaemonControlReply = synchronized(lock) {
        if (!authenticated(caller)) return@synchronized reject(DaemonControlReply.Reason.AUTHENTICATION)
        val before = record ?: return@synchronized reject(DaemonControlReply.Reason.STATE)
        if (status != DaemonOwnerStatus.UNRECONCILED || !admissionClosed || flight != null || probing ||
            before.revision < minimumReconcileRevision || request.intent is DaemonControlIntent.Prepare)
            return@synchronized reject(DaemonControlReply.Reason.STATE)
        val read = try { storage.read() } catch (_: Exception) { return@synchronized storageFailure() }
        if (read.record != before || read.interrupted) return@synchronized reject(DaemonControlReply.Reason.STATE)
        applyInternal(caller, request, true)
    }

    private fun applyInternal(caller: DaemonCallerEvidence, request: DaemonControlRequest,
        resumingAttested: Boolean): DaemonControlReply = synchronized(lock) {
        if (request.owner != owner) return@synchronized reject(DaemonControlReply.Reason.OWNER)
        try {
            if (!authenticated(caller)) return@synchronized reject(DaemonControlReply.Reason.AUTHENTICATION)
            storage.receipt(owner, request.requestId)?.let { saved ->
                return@synchronized if (saved.request == request) ack(saved)
                else reject(DaemonControlReply.Reason.REQUEST_CONFLICT)
            }
            val before = record ?: return@synchronized reject(DaemonControlReply.Reason.STATE)
            if (status == DaemonOwnerStatus.FAULT || status == DaemonOwnerStatus.UNRECONCILED && !resumingAttested)
                return@synchronized reject(DaemonControlReply.Reason.STATE)
            if (request.expectedRevision != before.revision || before.revision == Long.MAX_VALUE)
                return@synchronized reject(DaemonControlReply.Reason.REVISION)
            // A replacement may exclude a target or turn master OFF. Close before pointer/storage IO.
            val replacesAuthority = request.intent is DaemonControlIntent.SetAuthority
            if (replacesAuthority) status = DaemonOwnerStatus.UNRECONCILED
            val next = nextRecord(before, request.intent) ?: run {
                if (replacesAuthority) fault()
                return@synchronized reject(DaemonControlReply.Reason.PROOF)
            }

            // Revoke dispatch synchronously, BEFORE any durable operation that could fail or block.
            status = DaemonOwnerStatus.UNRECONCILED
            admissionClosed = true
            minimumReconcileRevision = next.revision
            val fence = storage.fence(before, request)
            if (fence != DaemonHaltFence(request, next.revision)) return@synchronized storageFailure()
            val receipt = DaemonControlReceipt(request, next)
            val read = storage.commitAndReadBack(fence, receipt)
            if (read.record != next || read.interrupted ||
                storage.receipt(owner, request.requestId) != receipt) return@synchronized storageFailure()
            record = next
            // A pending old command does not prevent acknowledging durable OFF/target exclusion.
            // It only prevents new writes; control commits never clear or replay that old journal.
            status = read.status()
            admissionClosed = false
            ack(receipt)
        } catch (_: Exception) { storageFailure() }
    }

    /** Reservation alone grants no right to write; startWrite repeats all external gates. */
    fun admit(operationKey: String): DaemonWriteTicket? = synchronized(lock) {
        if (status != DaemonOwnerStatus.ACTIVE || admissionClosed || flight != null || sequence == Long.MAX_VALUE ||
            operationKey.isBlank() || operationKey.length > 512) return@synchronized null
        DaemonWriteTicket(++sequence, checkNotNull(record), operationKey).also {
            flight = it; started = false; prepared = null; completionRequested = false
        }
    }

    /** Binder IO is outside every command-start monitor, including the heartbeat capability gate. */
    fun prepareWrite(ticket: DaemonWriteTicket): DaemonPreparedWrite? {
        synchronized(lock) {
            if (!current(ticket) || started || probing || prepared != null) return null
            probing = true
        }
        // Binder/package/exit-history reads may block. OFF must close its gate during this IO.
        val evidence = try { prerequisites.readFresh(ticket) } catch (_: Exception) { null }
        return synchronized(lock) {
            probing = false
            if (completionRequested) { flight = null; prepared = null; completionRequested = false; return@synchronized null }
            val now = try { elapsed() } catch (_: Exception) { return@synchronized null }
            if (!current(ticket) || started || evidence == null || evidence.ticket !== ticket ||
                !evidence.prerequisitesSatisfied || now < 0 || evidence.observedAtElapsed < 0 ||
                evidence.observedAtElapsed > now || now - evidence.observedAtElapsed > maximumEvidenceAgeMillis ||
                evidence.expiresAtElapsed < now) return@synchronized null
            DaemonPreparedWrite(ticket, evidence).also { prepared = it }
        }
    }

    /** No package/Binder IO or process wait is allowed inside this short start/lease boundary. */
    fun startPrepared(permit: DaemonPreparedWrite, start: () -> Unit): Boolean = synchronized(lock) {
            if (prepared !== permit) return@synchronized false
            prepared = null // A failed, expired or ambiguous launch never reuses the permit.
            val ticket = permit.ticket
            val evidence = permit.evidence
            val now = try { elapsed() } catch (_: Exception) { return@synchronized false }
            if (!current(ticket) || started || now < evidence.observedAtElapsed ||
                now - evidence.observedAtElapsed > maximumEvidenceAgeMillis || evidence.expiresAtElapsed < now)
                return@synchronized false
            started = true // A failed/ambiguous command start is never retried with this ticket.
            try {
                var startFailed = false
                val held = storage.withOwnerLease {
                    try { start() } catch (_: Exception) { startFailed = true }
                }
                if (!held) fault()
                held && !startFailed
            } catch (_: Exception) { fault(); false }
    }

    fun startWrite(ticket: DaemonWriteTicket, start: () -> Unit): Boolean =
        prepareWrite(ticket)?.let { startPrepared(it, start) } ?: false

    /** Late completion only releases its own flight. It can never restore ACTIVE or change authority. */
    fun complete(ticket: DaemonWriteTicket): Boolean = synchronized(lock) {
        if (flight !== ticket) return@synchronized false
        val wasCurrent = current(ticket)
        if (probing) { completionRequested = true; return@synchronized wasCurrent }
        flight = null
        started = false
        prepared = null
        wasCurrent
    }

    private fun current(ticket: DaemonWriteTicket) = flight === ticket &&
        status == DaemonOwnerStatus.ACTIVE && !admissionClosed && record == ticket.record

    private fun authenticated(caller: DaemonCallerEvidence) = caller.realUid == approvedCaller.identity.uid &&
        caller.installation == approvedCaller

    private fun nextRecord(before: DaemonControlRecord, intent: DaemonControlIntent): DaemonControlRecord? {
        val revision = before.revision + 1
        return when (intent) {
            is DaemonControlIntent.Prepare -> {
                if (before.mode != DaemonControlMode.OFF) null
                else DaemonControlRecord(owner, revision, intent.authorityDigest, DaemonControlMode.PREPARED, intent.migrationId)
            }
            is DaemonControlIntent.Activate -> intent.pointer.record.takeIf {
                before.mode == DaemonControlMode.PREPARED && it.mode == DaemonControlMode.ACTIVE &&
                    it.owner == owner && it.revision == revision && it.migrationId == before.migrationId &&
                    it.authorityDigest == before.authorityDigest && storage.verifyPointerCommit(intent.pointer)
            }
            is DaemonControlIntent.SetAuthority -> intent.pointer.record.takeIf {
                (before.mode != DaemonControlMode.PREPARED || it.mode == DaemonControlMode.OFF) &&
                    it.mode != DaemonControlMode.PREPARED &&
                    it.owner == owner && it.revision == revision && it.migrationId == before.migrationId &&
                    storage.verifyPointerCommit(intent.pointer)
            }
        }
    }

    private fun ack(receipt: DaemonControlReceipt) = DaemonControlReply.Acknowledged(receipt, record?.revision ?: 0)
    private fun reject(reason: DaemonControlReply.Reason) = DaemonControlReply.Rejected(reason)
    private fun fault(): Boolean { admissionClosed = true; status = DaemonOwnerStatus.FAULT; return false }
    private fun storageFailure(): DaemonControlReply { fault(); return reject(DaemonControlReply.Reason.STORAGE) }
    private fun DaemonControlRead.status() = if (pendingRecovery && record.mode == DaemonControlMode.ACTIVE)
        DaemonOwnerStatus.PENDING_RECOVERY else record.mode.status()
    private fun DaemonControlMode.status() = when (this) {
        DaemonControlMode.PREPARED -> DaemonOwnerStatus.PREPARED
        DaemonControlMode.ACTIVE -> DaemonOwnerStatus.ACTIVE
        DaemonControlMode.OFF -> DaemonOwnerStatus.OFF
    }
}
