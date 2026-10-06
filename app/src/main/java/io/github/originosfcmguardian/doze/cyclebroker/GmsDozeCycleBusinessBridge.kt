package io.github.originosfcmguardian.doze.cyclebroker

import android.content.Context
import io.github.originosfcmguardian.core.doze.*
import io.github.originosfcmguardian.doze.GmsDozeCycleLedgerStore
import io.github.originosfcmguardian.doze.gmsbroker.GMS_MANUAL_BROKER_FINGERPRINT
import java.util.concurrent.atomic.AtomicBoolean

internal enum class GmsDozeCycleOutcomeCode { NO_WRITE, APPLIED_RETAINED, APPLIED_SETTLED, RESTORED, OFF, WRITE_NOT_APPROVED, BLOCKED, UNCERTAIN }
internal data class GmsDozeCycleOutcome(val code: GmsDozeCycleOutcomeCode, val ref: GmsDozeCycleAttemptRef? = null)

/** The shipping binary has no preference, UI, debug or runtime mechanism to open this gate. */
internal object GmsDozeCycleShippingWriteGate { fun approved(): Boolean = false }

internal data class GmsDozeCycleSavedSnapshot(
    val loadState: GmsDozeCycleUiLoadState,
    val ledgerId: String? = null, val revision: Long = 0, val sha256: String? = null,
    val runningIntent: Boolean = false, val runningGeneration: Long = 0,
    val consentVersion: Int? = null, val phase: GmsDozeCyclePhase? = null,
    val unfinished: Boolean = false, val terminal: Boolean = false, val localAddsClosed: Boolean = false,
    val offTarget: GmsDozeCycleOffTarget? = null, val restoreTarget: GmsDozeCycleRestoreTarget? = null,
    val localConsentSequence: Long? = null,
)
internal data class GmsDozeCycleAutomaticSavedSnapshot(
    val scope: GmsDozeCycleAutomaticScope?,
    val confirmation: GmsDozeCycleAutomaticConfirmationTarget?,
    val blocked: Boolean,
    val runningIntent: Boolean,
    val runningGeneration: Long,
    val confirmedOff: Boolean,
    val ledgerId: String?,
    val revision: Long,
    val terminal: Boolean,
)

/**
 * Finite actual executor bridge. No loop, scheduler, boot takeover or V2 conversion.
 * Storage is only short exact ledger work; every probe/RPC/wait is outside storage/control gates.
 * Unknown execution preserves its durable intent and poisons this shared owner, never retries.
 */
internal class GmsDozeCycleBusinessBridge internal constructor(
    private val port: GmsDozeCycleLedgerPort,
    private val transport: GmsDozeCycleTransport,
    private val fingerprint: String,
    /** Required explicit host-test injection; production get() always uses the closed shipping gate. */
    private val writeApproved: () -> Boolean,
) {
    private val coordinator = GmsDozeCycleCoordinator(port, fingerprint)
    private val businessBusy = AtomicBoolean(false)

    /** Independent automatic consent is read only from this strict current ledger. No takeover. */
    fun automaticSnapshot(): GmsDozeCycleAutomaticSavedSnapshot = port.control.withLedgerOperation {
        val saved = savedSnapshot()
        val blocked = saved.loadState != GmsDozeCycleUiLoadState.CLEAN || saved.terminal ||
            saved.unfinished || saved.localAddsClosed || !saved.runningIntent
        val sequence = saved.localConsentSequence
        GmsDozeCycleAutomaticSavedSnapshot(if (blocked) null else coordinator.captureAutomaticScope(),
            if (blocked || sequence == null) null else coordinator.captureAutomaticTarget()?.let {
                GmsDozeCycleAutomaticConfirmationTarget(it, sequence)
            }, blocked, saved.runningIntent, saved.runningGeneration,
            saved.loadState == GmsDozeCycleUiLoadState.ABSENT ||
                saved.loadState == GmsDozeCycleUiLoadState.CLEAN && !saved.runningIntent,
            saved.ledgerId, saved.revision, saved.terminal)
    }

    /** A separate current ON confirmation; never initializes, re-enables, or grants a write. */
    fun confirmAutomatic(consent: GmsDozeCycleAutomaticConsent, expectedLocalOffSequence: Long): Boolean {
        if (!businessBusy.compareAndSet(false, true)) return false
        return try {
            val expected = GmsDozeCycleAutomaticConfirmationTarget(consent.target, expectedLocalOffSequence)
            if (automaticSnapshot().confirmation != expected) return false
            val connection = transport.bindInert(GmsGlobalDozeOperation.ADD)
            require(connection.hello().operation == GmsGlobalDozeOperation.ADD)
            val fresh = connection.probe()
            require(connection.probe() == fresh)
            val accepted = coordinator.confirmAutomaticExplicit(consent, fresh, expectedLocalOffSequence) != null
            connection.destroyOwnAndObserveDeath()
            accepted
        } catch (_: Exception) { port.control.failClosed(); false }
        finally { businessBusy.set(false) }
    }

    fun automaticLease(hostId: String, generation: Long, eligible: () -> Boolean) =
        GmsDozeCycleAutomaticLease(hostId, generation, eligible, coordinator::closeGrantNow)

    /** Short same-process OFF boundary only; no store read, consent or writer authority. */
    fun captureAutomaticOffSequence(): Long? = coordinator.captureLocalConsentSequence()

    /** Loss closes exactly its captured automatic ref; this does not close finite UI admission. */
    fun revokeAutomatic(ref: GmsDozeCycleAttemptRef) {
        coordinator.closeGrantNow(ref)
        try { closeExact(ref) } catch (_: Exception) { port.control.failClosed() }
    }

    /** Same business gate as finite UI. Cadence is charged only after winning it, before probe. */
    fun applyAutomaticOnce(scope: GmsDozeCycleAutomaticScope, lease: GmsDozeCycleAutomaticLease,
        chargeBeforeProbe: () -> Boolean): GmsDozeCycleOutcome = business {
        if (!lease.checkEligible()) return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (!chargeBeforeProbe()) return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (automaticSnapshot().scope != scope || !lease.checkEligible())
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        val connection = transport.bindInert(GmsGlobalDozeOperation.ADD)
        val hello = connection.hello()
        require(hello.operation == GmsGlobalDozeOperation.ADD)
        if (!lease.checkEligible()) return@business abandonInert(connection)
        val first = connection.probe()
        if (!lease.checkEligible()) return@business abandonInert(connection)
        require(connection.probe() == first)
        if (!lease.checkEligible()) return@business abandonInert(connection)
        applyFromFresh(connection, hello, first, scope, lease)
    }

    private fun abandonInert(connection: GmsDozeCycleConnection): GmsDozeCycleOutcome {
        connection.destroyOwnAndObserveDeath()
        return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
    }

    /** A missing ledger requires fresh versioned explicit consent; the store proves old absence. */
    fun applyOnce(consentForConfirmedAbsence: GmsDozeCycleConsent? = null,
        expectedLocalOffSequence: Long? = null): GmsDozeCycleOutcome = business {
        if (port.control.isTerminal() || port.control.localWritesClosed() &&
            !(consentForConfirmedAbsence != null && expectedLocalOffSequence != null))
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        val previous = load()
        if (previous == null && consentForConfirmedAbsence == null) return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (previous != null && (!previous.runningIntent || unfinished(previous)))
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        val connection = transport.bindInert(GmsGlobalDozeOperation.ADD)
        val hello = connection.hello()
        require(hello.operation == GmsGlobalDozeOperation.ADD)
        val first = connection.probe()
        require(connection.probe() == first)
        if (previous == null && coordinator.initializeExplicitConsent(requireNotNull(consentForConfirmedAbsence),
                first.identity, oldMaterialsConfirmedAbsent = true, expectedLocalOffSequence = expectedLocalOffSequence) == null) {
            connection.destroyOwnAndObserveDeath()
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        }
        applyFromFresh(connection, hello, first)
    }

    /** New independently confirmed exact OFF target; no reuse of the original consent as authority. */
    fun reenableOnce(confirmation: GmsDozeCycleReenableConfirmation,
        expectedLocalOffSequence: Long): GmsDozeCycleOutcome = business {
        if (port.control.isTerminal() || coordinator.captureOffTarget() != confirmation.target)
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (!settledHistory(load() ?: return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)))
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        val connection = transport.bindInert(GmsGlobalDozeOperation.ADD)
        val hello = connection.hello()
        require(hello.operation == GmsGlobalDozeOperation.ADD)
        val first = connection.probe()
        require(connection.probe() == first)
        if (coordinator.reenableExplicit(confirmation, first, expectedLocalOffSequence) == null) {
            connection.destroyOwnAndObserveDeath()
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        }
        applyFromFresh(connection, hello, first)
    }

    private fun applyFromFresh(connection: GmsDozeCycleConnection, hello: GmsManualBrokerHello,
        first: GmsGlobalDozeObservation, automaticScope: GmsDozeCycleAutomaticScope? = null,
        lease: GmsDozeCycleAutomaticLease? = null): GmsDozeCycleOutcome {
        if (lease != null && !lease.checkEligible()) return abandonInert(connection)
        var decision = coordinator.inspectFresh(first)
        if (decision.kind == GmsDozeCycleDecisionKind.NATURAL_BASELINE_AVAILABLE) {
            requireNotNull(coordinator.observeNaturalRestoration(first)) // Separate retirement CAS.
            decision = coordinator.inspectFresh(first)
        }
        if (decision.kind != GmsDozeCycleDecisionKind.READY_FOR_NEW_CYCLE) {
            connection.destroyOwnAndObserveDeath()
            return GmsDozeCycleOutcome(when (decision.kind) {
                GmsDozeCycleDecisionKind.NO_WRITE -> GmsDozeCycleOutcomeCode.NO_WRITE
                GmsDozeCycleDecisionKind.APPLIED_RETAINED -> GmsDozeCycleOutcomeCode.APPLIED_RETAINED
                else -> GmsDozeCycleOutcomeCode.BLOCKED
            })
        }
        if (!writeApproved()) {
            connection.destroyOwnAndObserveDeath()
            return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.WRITE_NOT_APPROVED)
        }
        connection.reserveGrantPreparation() // Irreversible before any possibly committed grant CAS.
        if (lease != null && !lease.checkEligible()) return abandonInert(connection)
        val grant = if (automaticScope == null) coordinator.prepareAdd(first, hello.nonce)
            else coordinator.prepareAutomaticAdd(automaticScope, first, hello.nonce)
        if (grant == null) {
            connection.destroyOwnAndObserveDeath()
            return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        }
        // Even loss before CAS returned must register/close its actual immutable ref.
        connection.pin(grant.ref)
        val stillEligible = lease?.attach(grant.ref) ?: true
        if (!stillEligible) return abandonGrant(connection, grant.ref)
        return execute(connection, grant, lease)
    }

    /** Requires fresh separately named restore consent; durable OFF alone cannot issue REMOVE. */
    fun restoreOnce(consent: GmsDozeCycleRestoreConsent, expected: GmsDozeCycleRestoreTarget? = null): GmsDozeCycleOutcome = business {
        val ledger = load() ?: return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (ledger.runningIntent || ledger.cycles.lastOrNull()?.phase != GmsDozeCyclePhase.APPLIED_SETTLED)
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        if (expected != null && savedSnapshot().restoreTarget != expected)
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        val connection = transport.bindInert(GmsGlobalDozeOperation.REMOVE)
        val hello = connection.hello()
        require(hello.operation == GmsGlobalDozeOperation.REMOVE)
        val first = connection.probe()
        require(connection.probe() == first)
        if (!writeApproved()) {
            connection.destroyOwnAndObserveDeath()
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.WRITE_NOT_APPROVED)
        }
        val grant = port.control.withLedgerOperation {
            if (expected != null && savedSnapshot().restoreTarget != expected) null
            else {
                connection.reserveGrantPreparation()
                coordinator.prepareRemove(consent, first, hello.nonce)
            }
        }
        if (grant == null) {
            connection.destroyOwnAndObserveDeath()
            return@business GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        }
        connection.pin(grant.ref)
        execute(connection, grant)
    }

    /** Main-thread safe: local admission only. Persistence/revoke are submitted independently. */
    fun closeAddsNow(): GmsDozeCycleLocalOffTicket? = coordinator.beginOff()

    /** Strict existing store only: no Binder, observation, migration or write/replay on load. */
    fun savedSnapshot(): GmsDozeCycleSavedSnapshot = try {
        port.control.withLedgerOperation {
            val terminal = port.control.isTerminal()
            val closed = port.control.localWritesClosed()
            if (terminal) return@withLedgerOperation GmsDozeCycleSavedSnapshot(GmsDozeCycleUiLoadState.BLOCKED,
                terminal = true, localAddsClosed = closed)
            val bytes = port.load()?.clone() ?: return@withLedgerOperation GmsDozeCycleSavedSnapshot(
                GmsDozeCycleUiLoadState.ABSENT, localAddsClosed = closed,
                localConsentSequence = coordinator.captureLocalConsentSequence())
            val ledger = GmsDozeCycleLedgerCodec.decode(bytes)
            require(ledger.binding.environment.fingerprint == fingerprint)
            val hash = GmsDozeCycleLedgerCodec.sha256(bytes)
            val last = ledger.cycles.lastOrNull()
            val sequence = coordinator.captureLocalConsentSequence()
            val restore = if (sequence != null && !ledger.runningIntent && last?.phase == GmsDozeCyclePhase.APPLIED_SETTLED)
                GmsDozeCycleRestoreTarget(ledger.ledgerId, ledger.revision, hash, ledger.runningGeneration,
                    last.cycleId, last.transactionId, last.transactionEpoch) else null
            GmsDozeCycleSavedSnapshot(GmsDozeCycleUiLoadState.CLEAN, ledger.ledgerId, ledger.revision, hash,
                ledger.runningIntent, ledger.runningGeneration, ledger.automaticConsent.version, last?.phase,
                unfinished(ledger), false, closed,
                if (settledHistory(ledger)) coordinator.captureOffTarget() else null, restore,
                sequence)
        }
    } catch (_: Exception) {
        port.control.failClosed()
        GmsDozeCycleSavedSnapshot(GmsDozeCycleUiLoadState.BLOCKED, terminal = true, localAddsClosed = true)
    }

    /** Immediate local closure + durable OFF precede all outbound revoke; no business-busy wait. */
    fun off(): GmsDozeCycleOutcome = off(closeAddsNow())

    /** Consume the exact synchronously registered OFF, never register a replacement after enqueue. */
    fun off(ticket: GmsDozeCycleLocalOffTicket?): GmsDozeCycleOutcome {
        return try {
            requireNotNull(ticket)
            val ledger = coordinator.persistOff(ticket)
            if (ledger == null) {
                // Confirmed absence is already OFF; failed/unknown storage is not confirmed OFF.
                require(!port.control.isTerminal() && port.load() == null)
                return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.OFF)
            }
            val last = ledger.cycles.lastOrNull()
            if (last != null) for (attempt in listOfNotNull(last.add, last.remove)) {
                if (attempt.closure == null) closeExact(attempt.grant.ref)
            }
            GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.OFF)
        } catch (_: Exception) {
            port.control.failClosed()
            GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.UNCERTAIN)
        }
    }

    private fun abandonGrant(connection: GmsDozeCycleConnection, ref: GmsDozeCycleAttemptRef): GmsDozeCycleOutcome {
        closeExact(ref)
        connection.destroyOwnAndObserveDeath()
        // ACK/death is not fabricated child cleanup. Pre-start ADD_INTENT remains blocked.
        return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED, ref)
    }

    private fun execute(connection: GmsDozeCycleConnection, grant: GmsDozeCycleGrant,
        lease: GmsDozeCycleAutomaticLease? = null): GmsDozeCycleOutcome {
        val ref = grant.ref
        if (lease == null) connection.activate(ticket(ref))
        else if (!lease.checkEligible() || !connection.activateAutomatic(ticket(ref), lease))
            return abandonGrant(connection, ref)
        val intent = coordinator.recordStartIntent(ref)
        if (intent == null && lease?.isClosed() == true && !port.control.isTerminal())
            return abandonGrant(connection, ref)
        requireNotNull(intent)
        val prepared = ticket(ref)
        // Consume the same-path one-use token and LEAVE its gate before the 40s outbound RPC.
        if (lease == null) {
            require(coordinator.reserveOutboundStart(ref))
            connection.start(prepared)
        } else {
            if (!lease.checkEligible() || !lease.reserveOutbound(ref) { coordinator.reserveOutboundStart(ref) })
                return abandonGrant(connection, ref)
            if (connection.startAutomatic(prepared, lease) != GmsDozeCycleAutomaticDispatch.STARTED)
                return abandonGrant(connection, ref)
        }
        val evidence = connection.awaitCleanup(prepared)
        require(evidence.ref == ref && evidence.cleanup.ref == ref && evidence.cleanup.complete() &&
            evidence.exitCode == 0 && evidence.outputValid && evidence.identity == grant.identity)
        val after = connection.probe()
        require(after.identity == grant.identity)
        // The strict ledger codec checks the exact GMS-only delta/full baseline before result CAS.
        val result = GmsDozeCycleResult(ref, evidence.cleanup, requireNotNull(evidence.exitCode), evidence.outputValid,
            after.identity, after.rawWhitelist)
        requireNotNull(coordinator.recordResult(result))
        val committedResult = ticket(ref)
        val release = connection.release(committedResult) // Matching historic result remains valid across OFF.
        require(release.ref == ref)
        requireNotNull(coordinator.recordReleaseAck(release))
        val current = ticket(ref)
        if (current.closure == null) closeExact(ref)
        connection.destroyOwnAndObserveDeath() // Release already proved genuine cleanup; death is separate.
        val finished = ticket(ref)
        require(finished.resultSummary != null && finished.releaseAck?.ref == ref && finished.closure?.ref == ref)
        return GmsDozeCycleOutcome(if (ref.operation == GmsGlobalDozeOperation.ADD)
            GmsDozeCycleOutcomeCode.APPLIED_SETTLED else GmsDozeCycleOutcomeCode.RESTORED, ref)
    }

    private fun closeExact(ref: GmsDozeCycleAttemptRef) {
        val requested = port.control.withLedgerOperation {
            val current = ticket(ref)
            if (current.closure != null) null else {
                if (current.requestedRevoke == 0L) requireNotNull(coordinator.requestRevoke(ref))
                ticket(ref)
            }
        } ?: return
        val connection = transport.findOwn(ref)
        val closure = try {
            requireNotNull(connection).revoke(requested)
        } catch (failure: Exception) {
            transport.observedDeath(ref, requested.requestedRevoke) ?: throw failure
        }
        require(closure.ref == ref && closure.revokeVersion == requested.requestedRevoke)
        // Another exact OFF/settlement may already have committed this same closure.
        port.control.withLedgerOperation {
            val current = ticket(ref)
            val existing = current.closure
            if (existing == null) requireNotNull(coordinator.recordClosure(closure))
            else require(existing.ref == ref &&
                current.requestedRevoke == requested.requestedRevoke &&
                existing.revokeVersion == requested.requestedRevoke &&
                existing.kind in listOf(GmsDozeCycleClosureKind.REVOKE_ACK, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH))
        }
    }
    private fun ticket(ref: GmsDozeCycleAttemptRef): GmsDozeCycleTicket =
        GmsDozeCycleTicketCodec.fromCommittedLedger(requireNotNull(port.load()), ref)
    private fun load(): GmsDozeCycleLedger? = port.load()?.let(GmsDozeCycleLedgerCodec::decode)
    private fun unfinished(value: GmsDozeCycleLedger) = value.cycles.lastOrNull()?.phase?.let {
        it !in listOf(GmsDozeCyclePhase.APPLIED_SETTLED, GmsDozeCyclePhase.RESTORED, GmsDozeCyclePhase.OBSERVED_RESTORED)
    } == true
    /** Decoded strict ledger phases require the complete matching result/release/closure facts. */
    private fun settledHistory(value: GmsDozeCycleLedger) = value.cycles.all {
        it.phase in listOf(GmsDozeCyclePhase.APPLIED_SETTLED, GmsDozeCyclePhase.RESTORED, GmsDozeCyclePhase.OBSERVED_RESTORED)
    }
    private inline fun business(action: () -> GmsDozeCycleOutcome): GmsDozeCycleOutcome {
        if (!businessBusy.compareAndSet(false, true)) return GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
        return try { action() } catch (_: Exception) {
            port.control.failClosed()
            GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.UNCERTAIN)
        } finally { businessBusy.set(false) }
    }
    companion object {
        private val instances = HashMap<String, GmsDozeCycleBusinessBridge>()
        fun get(context: Context): GmsDozeCycleBusinessBridge = synchronized(instances) {
            val canonical = context.applicationContext.noBackupFilesDir.canonicalPath
            instances.getOrPut(canonical) {
                val store = GmsDozeCycleLedgerStore(context)
                GmsDozeCycleBusinessBridge(store, GmsDozeCycleBrokerTransport.get(context, store.control),
                    GMS_MANUAL_BROKER_FINGERPRINT, GmsDozeCycleShippingWriteGate::approved)
            }
        }
    }
}
