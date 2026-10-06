package io.github.originosfcmguardian.core.doze

import java.util.UUID

/**
 * Source-only finite ledger coordinator, never an Android executor or restart permission adapter.
 * The required store rejects unknown/interrupted/old material and enforces its permanent marker.
 * Trusted caller supplies fresh complete observations and real versioned UI consent. Trusted
 * executor supplies the exact registered child's exit, dual EOF/drains, collector termination,
 * close, exit/output success, actual release reply and exact linked Binder death/revoke reply.
 * Codec validation cannot establish those external facts. No RPC/wait runs inside storage calls.
 */
class GmsDozeCycleCoordinator(private val port: GmsDozeCycleLedgerPort, private val expectedFingerprint: String) {
    private val control = port.control
    private val startGate = control.monitor
    private var terminal: Boolean
        get() = control.terminal
        set(value) { control.terminal = value }
    private var addsClosed: Boolean
        get() = control.addsClosed
        set(value) { control.addsClosed = value }
    private val preparedHere = control.prepared
    private val pendingStartHere = control.pendingStarts
    private val consumedStarts = control.consumedStarts
    private val closedGrants = control.closedGrants

    init { require(expectedFingerprint.isNotBlank()) }

    /** Older authority/material must all be confirmed absent by the trusted store/caller. */
    fun initializeExplicitConsent(consent: GmsDozeCycleConsent, identity: GmsGlobalDozeIdentity,
        oldMaterialsConfirmedAbsent: Boolean, expectedLocalOffSequence: Long? = null): GmsDozeCycleLedger? = guarded {
        val sequence = captureLocalConsentSequence() ?: return@guarded null
        if (!oldMaterialsConfirmedAbsent || expectedLocalOffSequence == null && locallyClosed() ||
            expectedLocalOffSequence != null && expectedLocalOffSequence != sequence || read() != null) return@guarded null
        GmsGlobalDozeCodec.validateIdentity(identity, expectedFingerprint)
        val binding = GmsDozeCycleBinding(identity.gms, identity.guardian, identity.environment)
        val after = commit(null, GmsDozeCycleLedger(uuid(), 1, true, 1, consent, binding, emptyList())) ?: return@guarded null
        synchronized(startGate) {
            control.activeRunningGeneration = after.runningGeneration
            if (!terminal && control.outstandingOff == 0 && control.offSequence == sequence) addsClosed = false
        }
        after
    }

    /** Capture for a NEW explicit confirmed-absence dialog before its fresh probe; no disk authority. */
    fun captureLocalConsentSequence(): Long? = synchronized(startGate) {
        if (terminal || control.outstandingOff != 0) null else control.offSequence
    }

    /** Existing CLEAN ON only; finite UI consent and heartbeat consent never manufacture this. */
    fun captureAutomaticTarget(): GmsDozeCycleAutomaticTarget? = guarded {
        if (locallyClosed() || synchronized(startGate) { control.outstandingOff != 0 }) return@guarded null
        val stored = read() ?: return@guarded null
        if (!stored.first.runningIntent || !GmsDozeCycleLedgerCodec.cleanForReenable(stored.first)) return@guarded null
        automaticTarget(stored)
    }

    /**
     * NEW explicit ADD-only automatic confirmation for the exact captured snapshot and local OFF
     * sequence. Trusted App supplies actual user confirmation/fresh qualification; hashes do not.
     * No initialization, re-enable, grant, REMOVE or host authority is created by this CAS.
     */
    fun confirmAutomaticExplicit(consent: GmsDozeCycleAutomaticConsent, fresh: GmsGlobalDozeObservation,
        expectedLocalOffSequence: Long): GmsDozeCycleLedger? = guarded {
        if (synchronized(startGate) { terminal || addsClosed || control.outstandingOff != 0 || control.offSequence != expectedLocalOffSequence }) return@guarded null
        val stored = read() ?: return@guarded null
        val ledger = stored.first
        if (!ledger.runningIntent || !GmsDozeCycleLedgerCodec.cleanForReenable(ledger) || consent.version != 1 ||
            consent.target != automaticTarget(stored) || !canonicalUuid(consent.confirmationId) || consent.confirmationId in retainedIds(ledger) ||
            consent.confirmedAtMillis <= maxOf(GmsDozeCycleLedgerCodec.newestEnableTime(ledger), ledger.automaticSessionConsent?.confirmedAtMillis ?: 0)) return@guarded null
        val rows = observation(fresh, ledger)
        if (!cleanRawMatches(fresh, ledger, rows)) return@guarded null
        if (ledger.automaticSessionConsent == null && stored.second.size.toLong() + 256 > GmsDozeCycleLedgerCodec.MAX_BYTES) return@guarded null
        commit(stored, ledger.copy(automaticSessionConsent = consent))
    }

    /** Clean idle snapshot only. Existing execution keeps its original scope while settling. */
    fun captureAutomaticScope(): GmsDozeCycleAutomaticScope? = guarded {
        if (locallyClosed() || synchronized(startGate) { control.outstandingOff != 0 }) return@guarded null
        val ledger = read()?.first ?: return@guarded null
        val consent = ledger.automaticSessionConsent ?: return@guarded null
        if (!ledger.runningIntent || consent.target.runningGeneration != ledger.runningGeneration ||
            !GmsDozeCycleLedgerCodec.cleanForReenable(ledger)) return@guarded null
        GmsDozeCycleAutomaticScope(ledger.ledgerId, consent)
    }

    /** Current full consent only; host eligibility is independently checked by the trusted adapter. */
    fun prepareAutomaticAdd(scope: GmsDozeCycleAutomaticScope, fresh: GmsGlobalDozeObservation,
        freshShellNonce: String): GmsDozeCycleGrant? = guarded {
        if (locallyClosed() || synchronized(startGate) { control.outstandingOff != 0 }) return@guarded null
        val ledger = read()?.first ?: return@guarded null
        if (!ledger.runningIntent || ledger.ledgerId != scope.ledgerId || ledger.automaticSessionConsent != scope.consent ||
            scope.runningGeneration != ledger.runningGeneration || !GmsDozeCycleLedgerCodec.cleanForReenable(ledger)) return@guarded null
        // Reentrant same-ledger operation retains the exact consent validation through grant CAS.
        prepareAdd(fresh, freshShellNonce)
    }

    fun inspectFresh(observation: GmsGlobalDozeObservation?): GmsDozeCycleDecision {
        return try {
        val stored = read() ?: return blocked("Explicit new automatic consent missing")
        val ledger = stored.first
        val fresh = observation ?: return blocked("Fresh observation missing")
        val rows = observation(fresh, ledger)
        val last = ledger.cycles.lastOrNull()
        if (last == null) {
            if (fullGms(rows)) return GmsDozeCycleDecision(GmsDozeCycleDecisionKind.NO_WRITE, "Existing full exemption; no ownership")
            if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }) return blocked("Except-idle membership is insufficient")
            return if (ledger.runningIntent && !locallyClosed()) ready() else blocked("Running intent is OFF")
        }
        if (last.phase == GmsDozeCyclePhase.APPLIED_SETTLED) {
            return when (fresh.rawWhitelist) {
                last.applied -> GmsDozeCycleDecision(GmsDozeCycleDecisionKind.APPLIED_RETAINED, "Clean APPLIED retained; no replay")
                last.baseline -> GmsDozeCycleDecision(GmsDozeCycleDecisionKind.NATURAL_BASELINE_AVAILABLE, "Exact original baseline requires separate retirement CAS")
                else -> blocked("Complete owned whitelist drifted")
            }
        }
        if (GmsDozeCycleLedgerCodec.terminal(last) && fresh.rawWhitelist == last.baseline && ledger.runningIntent &&
            !locallyClosed() && ledger.cycles.size < GmsDozeCycleLedgerCodec.MAX_CYCLES) return ready()
        blocked("Intent, retirement, drift, capacity or OFF blocks a new cycle")
    } catch (_: Exception) {
        failClosed()
        blocked("Storage or identity evidence is unknown")
    }
    }

    /** New grant is a separate CAS after any old cycle's OBSERVED_RESTORED/RESTORED CAS. */
    fun prepareAdd(fresh: GmsGlobalDozeObservation, freshShellNonce: String): GmsDozeCycleGrant? = guarded {
        if (locallyClosed()) return@guarded null
        val stored = read() ?: return@guarded null
        val ledger = stored.first
        if (!ledger.runningIntent || ledger.cycles.size >= GmsDozeCycleLedgerCodec.MAX_CYCLES) return@guarded null
        val rows = observation(fresh, ledger)
        if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }) return@guarded null
        val last = ledger.cycles.lastOrNull()
        if (last != null && (!GmsDozeCycleLedgerCodec.terminal(last) || fresh.rawWhitelist != last.baseline)) return@guarded null
        if (!completionCapacityAvailable(stored.second.size, fresh)) return@guarded null
        val cycleId = uuid(); val transactionId = uuid(); val epoch = next(last?.transactionEpoch ?: 0)
        val ref = GmsDozeCycleAttemptRef(ledger.ledgerId, cycleId, transactionId, epoch, uuid(), GmsGlobalDozeOperation.ADD,
            GmsDozeBrokerKey(freshShellNonce, nextGrantGeneration(ledger)))
        val grant = GmsDozeCycleGrant(ref, fresh.identity, GmsDozeBrokerV2Codec.rawSha256(fresh.rawWhitelist), null)
        val cycle = GmsDozeCycleRecord(cycleId, transactionId, epoch, fresh.rawWhitelist, null, GmsDozeCyclePhase.ADD_INTENT, GmsDozeCycleAttempt(grant))
        val after = commit(stored, ledger.copy(cycles = ledger.cycles + cycle)) ?: return@guarded null
        synchronized(startGate) { preparedHere.add(ref); control.preparedGenerations[ref] = after.runningGeneration; control.activeRunningGeneration = after.runningGeneration }
        after.cycles.last().add.grant
    }

    /** OFF alone never grants REMOVE. A fresh independently named action is consumed atomically. */
    fun prepareRemove(consent: GmsDozeCycleRestoreConsent, fresh: GmsGlobalDozeObservation,
        freshShellNonce: String): GmsDozeCycleGrant? = guarded {
        val stored = read() ?: return@guarded null
        val ledger = stored.first
        val cycle = ledger.cycles.lastOrNull() ?: return@guarded null
        observation(fresh, ledger)
        if (ledger.runningIntent || cycle.phase != GmsDozeCyclePhase.APPLIED_SETTLED || cycle.remove != null ||
            fresh.rawWhitelist != cycle.applied || consent.ledgerId != ledger.ledgerId || consent.cycleId != cycle.cycleId ||
            consent.transactionId != cycle.transactionId || consent.transactionEpoch != cycle.transactionEpoch ||
            consent.confirmedAtMillis <= GmsDozeCycleLedgerCodec.newestEnableTime(ledger)) return@guarded null
        val ref = GmsDozeCycleAttemptRef(ledger.ledgerId, cycle.cycleId, cycle.transactionId, cycle.transactionEpoch,
            uuid(), GmsGlobalDozeOperation.REMOVE, GmsDozeBrokerKey(freshShellNonce, nextGrantGeneration(ledger)))
        val grant = GmsDozeCycleGrant(ref, fresh.identity, GmsDozeBrokerV2Codec.rawSha256(cycle.baseline),
            GmsDozeBrokerV2Codec.rawSha256(requireNotNull(cycle.applied)))
        val updated = cycle.copy(remove = GmsDozeCycleAttempt(grant), restoreConsent = consent, phase = GmsDozeCyclePhase.RESTORE_INTENT)
        val after = commit(stored, replace(ledger, updated)) ?: return@guarded null
        synchronized(startGate) { preparedHere.add(ref); control.preparedGenerations[ref] = after.runningGeneration; control.activeRunningGeneration = after.runningGeneration }
        requireNotNull(after.cycles.last().remove).grant
    }

    /** Only a grant freshly prepared by this owner can acquire a one-use actual-start token. */
    fun recordStartIntent(ref: GmsDozeCycleAttemptRef): GmsDozeCycleLedger? = guarded {
        synchronized(startGate) {
            if (terminal || ref !in preparedHere || ref in closedGrants || !currentLocalGeneration(ref) ||
                ref.operation == GmsGlobalDozeOperation.ADD && addsClosed) return@guarded null
        }
        val stored = read() ?: return@guarded null
        val attempt = attempt(stored.first, ref) ?: return@guarded null
        if (attempt.startIntent || attempt.requestedRevoke != 0L || attempt.closure != null ||
            ref.operation == GmsGlobalDozeOperation.ADD && !stored.first.runningIntent) return@guarded null
        val after = commit(stored, replaceAttempt(stored.first, ref, attempt.copy(startIntent = true))) ?: return@guarded null
        synchronized(startGate) { pendingStartHere.add(ref) }
        after
    }

    /**
     * Actual fixed start runs in the same short gate as local OFF. It must synchronously enter the
     * real executor, not enqueue work. Identity/storage checks and child waits stay outside.
     * This host gate is not cross-process permission. The future authenticated executor must
     * enforce the exact durable attempt/revoke boundary for every writer in the same actual broker.
     * A throwing start may have created a child: retain durable intent and close this owner.
     */
    fun admitActualStart(ref: GmsDozeCycleAttemptRef, actualFixedStart: (GmsGlobalDozeOperation) -> Unit): Boolean = synchronized(startGate) {
        if (terminal || ref !in pendingStartHere || ref in consumedStarts || ref in closedGrants || !currentLocalGeneration(ref) ||
            ref.operation == GmsGlobalDozeOperation.ADD && addsClosed) return@synchronized false
        consumedStarts.add(ref)
        try { actualFixedStart(ref.operation); true } catch (_: Exception) { terminal = true; addsClosed = true; false }
    }

    /**
     * Reserve one exact outbound attempt and RETURN before Binder/RPC. This token is not proof of
     * callee start; the shell's own short start/revoke gate provides that boundary. Lost/uncertain
     * RPC retains the durable intent and consumed token, never authorizes a replay or replacement.
     */
    fun reserveOutboundStart(ref: GmsDozeCycleAttemptRef): Boolean = synchronized(startGate) {
        if (terminal || ref !in pendingStartHere || ref in consumedStarts || ref in closedGrants || !currentLocalGeneration(ref) ||
            ref.operation == GmsGlobalDozeOperation.ADD && addsClosed) return@synchronized false
        consumedStarts.add(ref)
        true
    }

    /** Synchronous local closure precedes durable OFF; no remote call or business wait here. */
    fun beginOff(): GmsDozeCycleLocalOffTicket? = control.beginOff()

    fun persistOff(): GmsDozeCycleLedger? {
        val ticket = beginOff() ?: return null
        return persistOff(ticket)
    }

    /** Consume the exact synchronously registered ticket; no start gate is held over disk/RPC. */
    fun persistOff(ticket: GmsDozeCycleLocalOffTicket): GmsDozeCycleLedger? {
        synchronized(startGate) {
            if (ticket.owner !== control || control.offTickets[ticket] != false) return null
            control.offTickets[ticket] = true
        }
        return try { guarded {
            val stored = read() ?: return@guarded null
            val after = if (!stored.first.runningIntent) stored.first
            else commit(stored, stored.first.copy(runningIntent = false, runningGeneration = next(stored.first.runningGeneration)))
            synchronized(startGate) { addsClosed = true; after?.let { control.activeRunningGeneration = it.runningGeneration } }
            after
        } } finally { synchronized(startGate) { control.offTickets.remove(ticket); control.outstandingOff-- } }
    }

    /** Snapshot for a NEW explicit dialog; neither original consent nor a heartbeat supplies this. */
    fun captureOffTarget(): GmsDozeCycleOffTarget? = guarded {
        if (synchronized(startGate) { terminal || control.outstandingOff != 0 }) return@guarded null
        val stored = read() ?: return@guarded null
        if (stored.first.runningIntent) return@guarded null
        GmsDozeCycleOffTarget(stored.first.ledgerId, stored.first.revision, GmsDozeCycleLedgerCodec.sha256(stored.second), stored.first.runningGeneration)
    }

    /** Same ledger/history/binding only. Successful ON is intent, not a grant or a revived token. */
    fun reenableExplicit(confirmation: GmsDozeCycleReenableConfirmation, fresh: GmsGlobalDozeObservation,
        expectedLocalOffSequence: Long?): GmsDozeCycleLedger? = guarded {
        val sequence = synchronized(startGate) {
            if (terminal || control.outstandingOff != 0 || expectedLocalOffSequence == null || expectedLocalOffSequence != control.offSequence) return@guarded null
            control.offSequence
        }
        val stored = read() ?: return@guarded null
        val ledger = stored.first
        val target = GmsDozeCycleOffTarget(ledger.ledgerId, ledger.revision, GmsDozeCycleLedgerCodec.sha256(stored.second), ledger.runningGeneration)
        if (ledger.runningIntent || confirmation.version != 1 || confirmation.target != target ||
            !canonicalUuid(confirmation.confirmationId) || confirmation.confirmationId in retainedIds(ledger) ||
            ledger.runningGeneration == Long.MAX_VALUE || confirmation.toRunningGeneration != ledger.runningGeneration + 1 ||
            confirmation.confirmedAtMillis <= GmsDozeCycleLedgerCodec.newestEnableTime(ledger) ||
            confirmation.confirmationId == ledger.latestReenable?.confirmationId ||
            !GmsDozeCycleLedgerCodec.cleanForReenable(ledger)) return@guarded null
        val rows = observation(fresh, ledger)
        if (!cleanRawMatches(fresh, ledger, rows)) return@guarded null
        // A first confirmation adds a bounded fixed header; capacity refusal never poisons CLEAN.
        if (ledger.latestReenable == null && stored.second.size.toLong() + 256 > GmsDozeCycleLedgerCodec.MAX_BYTES) return@guarded null
        val after = commit(stored, ledger.copy(runningIntent = true, runningGeneration = confirmation.toRunningGeneration,
            latestReenable = confirmation)) ?: return@guarded null
        synchronized(startGate) {
            // Retain old exact tuples/maps as closed evidence; generation changes cannot revive them.
            closedGrants.addAll(preparedHere)
            control.activeRunningGeneration = after.runningGeneration
            // An OFF already waiting for this ledger gate keeps local starts closed permanently
            // until its own durable operation completes. Never clear terminal or consumed refs.
            if (!terminal && control.outstandingOff == 0 && control.offSequence == sequence) addsClosed = false
        }
        after
    }

    /** Late results merge only into CURRENT intent; current OFF/generation cannot be overwritten. */
    fun recordResult(result: GmsDozeCycleResult): GmsDozeCycleLedger? = guarded {
        val stored = read() ?: return@guarded null
        val attempt = attempt(stored.first, result.ref) ?: return@guarded null
        if (!attempt.startIntent || attempt.result != null || result.cleanup.ref != result.ref || !result.cleanup.complete() ||
            result.exitCode != 0 || !result.commandOutputValid || result.identity != attempt.grant.identity) return@guarded null
        commit(stored, replaceAttempt(stored.first, result.ref, attempt.copy(result = result)))
    }

    fun recordReleaseAck(ack: GmsDozeCycleReleaseAck): GmsDozeCycleLedger? = guarded {
        val stored = read() ?: return@guarded null
        val attempt = attempt(stored.first, ack.ref) ?: return@guarded null
        if (attempt.result == null || attempt.releaseAck != null) return@guarded null
        commit(stored, replaceAttempt(stored.first, ack.ref, attempt.copy(releaseAck = ack)))
    }

    /** Only a request, never an ACK: the future adapter delivers revoke outside the storage lock. */
    fun requestRevoke(ref: GmsDozeCycleAttemptRef): GmsDozeCycleLedger? {
        closeGrantNow(ref)
        return guarded {
            val stored = read() ?: return@guarded null
            val attempt = attempt(stored.first, ref) ?: return@guarded null
            if (attempt.closure != null) return@guarded null
            commit(stored, replaceAttempt(stored.first, ref, attempt.copy(requestedRevoke = next(attempt.requestedRevoke))))
        }
    }

    /** Exact local automatic-lease loss hook; no disk/RPC, no global OFF, no cleanup inference. */
    fun closeGrantNow(ref: GmsDozeCycleAttemptRef) = synchronized(startGate) { closedGrants.add(ref); Unit }

    fun recordClosure(closure: GmsDozeCycleClosure): GmsDozeCycleLedger? = guarded {
        val stored = read() ?: return@guarded null
        val attempt = attempt(stored.first, closure.ref) ?: return@guarded null
        if (attempt.closure != null || closure.revokeVersion != attempt.requestedRevoke ||
            closure.kind == GmsDozeCycleClosureKind.REVOKE_ACK && attempt.requestedRevoke == 0L) return@guarded null
        val after = commit(stored, replaceAttempt(stored.first, closure.ref, attempt.copy(closure = closure))) ?: return@guarded null
        synchronized(startGate) { closedGrants.add(closure.ref) }
        after
    }

    /** Fresh reads alone cannot retire an unfinished child; only clean APPLIED_SETTLED is eligible. */
    fun observeNaturalRestoration(fresh: GmsGlobalDozeObservation): GmsDozeCycleLedger? = guarded {
        val stored = read() ?: return@guarded null
        val cycle = stored.first.cycles.lastOrNull() ?: return@guarded null
        observation(fresh, stored.first)
        if (cycle.phase != GmsDozeCyclePhase.APPLIED_SETTLED || fresh.rawWhitelist != cycle.baseline) return@guarded null
        commit(stored, replace(stored.first, cycle.copy(phase = GmsDozeCyclePhase.OBSERVED_RESTORED)))
    }

    fun localWritesClosed(): Boolean = synchronized(startGate) { terminal || addsClosed }
    fun isTerminal(): Boolean = synchronized(startGate) { terminal }

    private fun observation(value: GmsGlobalDozeObservation, ledger: GmsDozeCycleLedger): Set<Membership> {
        GmsGlobalDozeCodec.validateIdentity(value.identity, expectedFingerprint)
        require(value.identity.gms == ledger.binding.gms && value.identity.guardian == ledger.binding.guardian &&
            value.identity.environment == ledger.binding.environment)
        return StrictWhitelist.parse(value.rawWhitelist).also { rows ->
            require(rows.none { it.packageName == GMS_GLOBAL_DOZE_PACKAGE && it.appId != ledger.binding.gms.appId })
        }
    }
    private fun fullGms(rows: Set<Membership>) = rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE &&
        (it.source == MembershipSource.SYSTEM || it.source == MembershipSource.USER) }
    private fun cleanRawMatches(fresh: GmsGlobalDozeObservation, ledger: GmsDozeCycleLedger, rows: Set<Membership>): Boolean {
        val last = ledger.cycles.lastOrNull()
        return if (last == null) rows.none { it.packageName == GMS_GLOBAL_DOZE_PACKAGE } || fullGms(rows)
        else fresh.rawWhitelist == last.baseline || last.phase == GmsDozeCyclePhase.APPLIED_SETTLED && fresh.rawWhitelist == last.applied
    }
    private fun automaticTarget(stored: Pair<GmsDozeCycleLedger, ByteArray>) = GmsDozeCycleAutomaticTarget(
        stored.first.ledgerId, stored.first.revision, GmsDozeCycleLedgerCodec.sha256(stored.second), stored.first.runningGeneration)
    private fun canonicalUuid(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun retainedIds(ledger: GmsDozeCycleLedger) = listOfNotNull(ledger.ledgerId, ledger.automaticConsent.consentId,
        ledger.latestReenable?.confirmationId, ledger.automaticSessionConsent?.confirmationId) + ledger.cycles.flatMap {
        listOfNotNull(it.cycleId, it.transactionId, it.add.grant.ref.grantId, it.remove?.grant?.ref?.grantId, it.restoreConsent?.consentId)
    }
    private fun ready() = GmsDozeCycleDecision(GmsDozeCycleDecisionKind.READY_FOR_NEW_CYCLE, "Fresh qualification; a separate new grant CAS is required")
    private fun blocked(reason: String) = GmsDozeCycleDecision(GmsDozeCycleDecisionKind.BLOCKED, reason)
    private fun locallyClosed() = synchronized(startGate) { terminal || addsClosed }
    private fun currentLocalGeneration(ref: GmsDozeCycleAttemptRef) =
        control.preparedGenerations[ref] != null && control.preparedGenerations[ref] == control.activeRunningGeneration
    private fun failClosed() = synchronized(startGate) { terminal = true; addsClosed = true }
    private fun next(value: Long): Long { require(value in 0 until Long.MAX_VALUE); return value + 1 }
    private fun uuid() = UUID.randomUUID().toString()
    private fun nextGrantGeneration(ledger: GmsDozeCycleLedger) = next(ledger.cycles.flatMap {
        listOfNotNull(it.add.grant.ref.key.generation, it.remove?.grant?.ref?.key?.generation)
    }.maxOrNull() ?: 0)

    /**
     * Before any grant/CAS/start, reserve the complete new ADD plus future named REMOVE and OFF.
     * From the fixed wire layout: 552 fixed bytes plus permanent 256-byte re-enable and
     * 256-byte automatic-session-consent headroom,
     * baseline twice (cycle + REMOVE result), five
     * full refs per operation, four qualified identities, and two MAX_RAW_BYTES applied strings
     * (cycle + ADD result). Future REMOVE nonce uses its maximum 128 ASCII bytes. Numeric fields,
     * enum/boolean tags and checksum have fixed width even at maximum legal revoke/revision values.
     * This is conservative for every legal raw row ordering, not just baseline + the one GMS row.
     * A refusal is a predicate, never an uncertain commit and never poisons the valid ledger.
     */
    private fun completionCapacityAvailable(currentBytes: Int, fresh: GmsGlobalDozeObservation): Boolean {
        val identity = fresh.identity
        val fingerprintBytes = identity.environment.fingerprint.toByteArray(Charsets.UTF_8).size
        val identityBytes = 156L + fingerprintBytes + 68L *
            (identity.gms.signerSha256.size + identity.guardian.installation.signerSha256.size)
        // Both refs deliberately use maximum nonce length, including the actual ADD nonce.
        val refBytes = 181L + 128
        val reserve = 552L + 256L + 256L + 2L * fresh.rawWhitelist.toByteArray(Charsets.UTF_8).size +
            10L * refBytes + 4L * identityBytes + 2L * StrictWhitelist.MAX_RAW_BYTES
        return currentBytes.toLong() + reserve <= GmsDozeCycleLedgerCodec.MAX_BYTES
    }
    private fun attempt(ledger: GmsDozeCycleLedger, ref: GmsDozeCycleAttemptRef): GmsDozeCycleAttempt? {
        val cycle = ledger.cycles.lastOrNull() ?: return null
        val attempt = if (ref.operation == GmsGlobalDozeOperation.ADD) cycle.add else cycle.remove
        return attempt?.takeIf { it.grant.ref == ref }
    }
    private fun replace(ledger: GmsDozeCycleLedger, cycle: GmsDozeCycleRecord) = ledger.copy(cycles = ledger.cycles.dropLast(1) + cycle)
    private fun replaceAttempt(ledger: GmsDozeCycleLedger, ref: GmsDozeCycleAttemptRef, attempt: GmsDozeCycleAttempt): GmsDozeCycleLedger {
        val old = ledger.cycles.last()
        val updated = if (ref.operation == GmsGlobalDozeOperation.ADD) old.copy(add = attempt, applied = attempt.result?.rawWhitelist)
            else old.copy(remove = attempt)
        return replace(ledger, updated.copy(phase = GmsDozeCycleLedgerCodec.phase(updated)))
    }
    private fun read(): Pair<GmsDozeCycleLedger, ByteArray>? {
        check(!isTerminal()) { "Cycle owner terminal; no read/retry rescue" }
        val bytes = port.load()?.clone() ?: return null
        val ledger = GmsDozeCycleLedgerCodec.decode(bytes)
        require(ledger.binding.environment.fingerprint == expectedFingerprint)
        return ledger to bytes
    }
    private fun commit(before: Pair<GmsDozeCycleLedger, ByteArray>?, value: GmsDozeCycleLedger): GmsDozeCycleLedger? {
        val replacement = value.copy(revision = before?.first?.revision?.let(::next) ?: 1)
        val bytes = GmsDozeCycleLedgerCodec.encode(replacement)
        GmsDozeCycleLedgerCodec.validateTransition(before?.second, bytes)
        if (!port.compareAndSet(before?.second?.clone(), bytes.clone())) return null
        val readback = port.load()?.clone() ?: error("Cycle commit disappeared")
        require(readback.contentEquals(bytes)) { "Cycle exact commit readback differs" }
        return GmsDozeCycleLedgerCodec.decode(readback)
    }
    private fun <T> guarded(action: () -> T?): T? = control.withLedgerOperation {
        try { action() } catch (_: Exception) { failClosed(); null }
    }
}
