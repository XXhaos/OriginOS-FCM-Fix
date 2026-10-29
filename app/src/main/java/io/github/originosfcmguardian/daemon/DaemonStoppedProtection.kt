package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.privileged.*
import io.github.originosfcmguardian.recovery.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Retain read workers across the host's post-cycle 30-second wait; idle workers still expire.
internal const val DAEMON_READ_WORKER_KEEP_ALIVE_MILLIS = 90_000L

/** Only the fixed package-unstop operation; process waiting must occur outside owner.startWrite. */
internal interface DaemonStartedUnstop : AutoCloseable {
    fun awaitExit(active: () -> Boolean): Int
    /** False/unknown retains the owner's occupied flight even after caller cancellation/timeout. */
    fun terminated(): Boolean
}

internal interface DaemonStoppedShellPort : AutoCloseable {
    fun isQuiescent(): Boolean = true
    fun observe(id: Long, identity: SelectedTargetIdentity, recipient: Boolean,
        interrupted: () -> Boolean): StoppedTargetObservation
    /** Preparation is preclaim and outside owner locks; start must never choose another transport. */
    fun prepareUnstop(identity: SelectedTargetIdentity, recipient: Boolean, active: () -> Boolean): DaemonUnstopLaunch?
}

internal fun interface DaemonUnstopLaunch { fun start(): DaemonStartedUnstop }

/** Fixed four bounded system-read lanes survive poll timeouts; no App Context or Binder callbacks. */
internal class SystemDaemonStoppedShellPort : DaemonStoppedShellPort {
    private val readers = Array(4) { StoppedIdentityProbeRunner(::SystemStoppedIdentityReadPort, 4_000,
        keepAliveMillis = DAEMON_READ_WORKER_KEEP_ALIVE_MILLIS) }
    private val cursor = AtomicInteger()
    override fun isQuiescent(): Boolean = readers.all { it.isQuiescent() } && PackageUnstopCapability.isQuiescent()
    override fun observe(id: Long, identity: SelectedTargetIdentity, recipient: Boolean,
        interrupted: () -> Boolean): StoppedTargetObservation {
        val lane = readers[Math.floorMod(cursor.getAndIncrement(), readers.size)]
        return if (recipient) lane.readTarget(id, identity, interrupted) else lane.read(id, interrupted)
    }
    override fun prepareUnstop(identity: SelectedTargetIdentity, recipient: Boolean, active: () -> Boolean): DaemonUnstopLaunch? {
        val prepared = preparePackageUnstop(identity, recipient, active) ?: return null
        return DaemonUnstopLaunch {
            val process = prepared.start()
            object : DaemonStartedUnstop {
                override fun awaitExit(active: () -> Boolean): Int {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                    while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                        check(active() && System.nanoTime() < deadline)
                    }
                    check(active())
                    return process.exitValue()
                }
                override fun close() {
                    if (process.isAlive) process.destroyForcibly()
                    runCatching { process.waitFor(1_000, TimeUnit.MILLISECONDS) }
                    runCatching { process.outputStream.close() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
                override fun terminated() = !process.isAlive
            }
        }
    }
    override fun close() { readers.forEach { it.close() } }
}

/**
 * Route stopped:* owner prerequisites to THIS gate when constructing the shared owner kernel.
 * The engine installs one exact ticket's fresh checks before entering startWrite; these execute
 * outside the owner's monitor, then the kernel rechecks revision and holds its lifetime lease.
 * A host combining this branch with FCM must explicitly route both operation scopes.
 */
internal class DaemonStoppedWriteGate(private val elapsed: () -> Long) : DaemonPrerequisitePort {
    private class Check(val ticket: DaemonWriteTicket, val ready: () -> Boolean)
    private val current = AtomicReference<Check?>()
    fun install(ticket: DaemonWriteTicket, ready: () -> Boolean): AutoCloseable {
        val session = Check(ticket, ready)
        check(current.compareAndSet(null, session))
        return AutoCloseable { current.compareAndSet(session, null) }
    }
    override fun readFresh(ticket: DaemonWriteTicket): DaemonWriteEvidence? {
        val check = current.get()?.takeIf { it.ticket === ticket && ticket.operationKey.startsWith("stopped:") }
            ?: return null
        val ready = runCatching(check.ready).getOrDefault(false)
        val at = elapsed()
        return DaemonWriteEvidence(ticket, at, Math.addExact(at, 1_000), ready && current.get() === check)
    }
}

internal enum class DaemonStoppedCycleStatus { CHECKED, WAITING, BUSY, THROTTLED, RECONCILED, PENDING, UNAVAILABLE }
internal data class DaemonStoppedCycle(
    val status: DaemonStoppedCycleStatus,
    val checkedTargets: Int = 0,
    val results: List<StoppedRecoveryResult> = emptyList(),
)

/**
 * Real stopped-target execution component, pending authenticated daemon-host integration.
 * One finite best-effort cycle; the outer host owns scheduling. Only read-only target observations
 * overlap (four at most). All authorization, journal transactions, recovery and counts stay serial.
 * The supplied stores and digest reader MUST all refer to the same daemon authoritative root.
 */
internal class DaemonStoppedProtection(
    private val owner: DaemonOwnerControl,
    private val writeGate: DaemonStoppedWriteGate,
    private val consent: ProtectionConsentStore,
    private val selection: TargetSelectionStore,
    private val journal: StoppedRecoveryJournal,
    private val counts: RecoveryCountsStore,
    private val authorityDigest: () -> String?,
    private val environment: DaemonOwnerEnvironmentProbe,
    private val guardian: DaemonGuardianInstallation,
    private val bootId: String,
    private val shell: DaemonStoppedShellPort,
    private val elapsed: () -> Long,
    private val wall: () -> Long = System::currentTimeMillis,
    private val targetReader: BoundedAutomaticTargetRead = BoundedAutomaticTargetRead(
        keepAliveMillis = DAEMON_READ_WORKER_KEEP_ALIVE_MILLIS),
    private val readbackPause: () -> Unit = { Thread.sleep(1_000) },
) : AutoCloseable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var nextPollAt = 0L
    private var targetCursor = 0
    private class Unfinished(val ticket: DaemonWriteTicket, val process: DaemonStartedUnstop?)
    @Volatile private var unfinished: Unfinished? = null

    suspend fun pollAsync(): DaemonStoppedCycle {
        if (!busy.compareAndSet(false, true)) return DaemonStoppedCycle(DaemonStoppedCycleStatus.BUSY)
        return try {
            withContext(Dispatchers.IO) {
                val now = elapsed()
                when {
                    !reapTerminatedCommand() -> DaemonStoppedCycle(DaemonStoppedCycleStatus.PENDING)
                    closed.get() -> DaemonStoppedCycle(DaemonStoppedCycleStatus.UNAVAILABLE)
                    now < nextPollAt -> DaemonStoppedCycle(DaemonStoppedCycleStatus.THROTTLED)
                    else -> try { cycle() } finally {
                        val completed = elapsed()
                        nextPollAt = if (completed > Long.MAX_VALUE - POLL_MILLIS) Long.MAX_VALUE else completed + POLL_MILLIS
                    }
                }
            }
        } catch (_: Exception) { DaemonStoppedCycle(DaemonStoppedCycleStatus.UNAVAILABLE) }
        finally { busy.set(false) }
    }

    private suspend fun cycle(): DaemonStoppedCycle {
        val pending = journal.load().latest?.takeIf { it.requiresReconcile }
        if (pending != null) return reconcilePending(pending)
        val pinned = owner.snapshot() ?: return unavailable()
        if (!sameOwner(pinned)) return unavailable()
        val leases = consent.desiredLeases()
        if (leases.isEmpty()) return DaemonStoppedCycle(DaemonStoppedCycleStatus.WAITING)
        val results = mutableListOf<StoppedRecoveryResult>()
        var checked = 0
        try {
            for (offset in leases.indices step BoundedAutomaticTargetRead.CHUNK_SIZE) {
                val chunk = (offset until minOf(offset + BoundedAutomaticTargetRead.CHUNK_SIZE, leases.size))
                    .map { leases[(targetCursor + it) % leases.size] }
                val observations = targetReader.readChunk(chunk) { lease ->
                    val observed = observe(1, lease) { closed.get() || !sameOwnerMemory(pinned) }
                    val known = observed as? StoppedTargetObservation.Known
                    AutomaticTargetObservation(
                        qualified = known?.let { it.hasVerifiedPackageUid && (lease.consent.policyVersion != 4 || it.qualifiedRecipient) },
                        stopped = TargetStoppedSnapshot(when {
                            known == null -> TargetStoppedStatus.UNKNOWN
                            known.identity != lease.consent.identity -> TargetStoppedStatus.IDENTITY_CHANGED
                            known.stopped -> TargetStoppedStatus.STOPPED
                            else -> TargetStoppedStatus.NOT_STOPPED
                        }, known?.observedAtMillis),
                    )
                }
                for ((index, lease) in chunk.withIndex()) {
                    if (!sameOwner(pinned)) return DaemonStoppedCycle(DaemonStoppedCycleStatus.UNAVAILABLE, checked, results)
                    val observation = observations[index]
                    checked++
                    val decision = automaticCheckDecision(consent.verifyAuthorized(lease),
                        observation.qualified == true || observation.locallyUnavailable,
                        journal.load().latest?.requiresReconcile == true, false,
                        observation.stopped?.status ?: TargetStoppedStatus.UNKNOWN)
                    if (decision == AutomaticCheckDecision.Reconcile)
                        return DaemonStoppedCycle(DaemonStoppedCycleStatus.PENDING, checked, results)
                    if (decision == AutomaticCheckDecision.Recover) {
                        results += recover(lease, pinned)
                        if (journal.load().latest?.requiresReconcile == true)
                            return DaemonStoppedCycle(DaemonStoppedCycleStatus.PENDING, checked, results)
                    }
                }
            }
        } finally { targetCursor = (targetCursor + 1) % leases.size }
        return DaemonStoppedCycle(DaemonStoppedCycleStatus.CHECKED, checked, results)
    }

    private fun recover(lease: TargetConsentLease, pinned: DaemonControlRecord): StoppedRecoveryResult {
        val ticket = owner.admit("stopped:${lease.consent.identity.packageName}:${lease.consent.id}")
            ?: return StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "owner unavailable or busy")
        var gate: AutoCloseable? = null
        var command: DaemonStartedUnstop? = null
        var preparedLaunch: DaemonUnstopLaunch? = null
        var startEntered = false
        try {
            val authorization = AutomaticRecoveryAuthorization {
                ticket.record == pinned && sameOwner(pinned) && consent.verifyAuthorized(lease) &&
                    selection.load().contains(lease.consent.identity) && environmentReady()
            }
            fun localActive() = !closed.get() && !Thread.currentThread().isInterrupted && sameOwnerMemory(pinned)
            fun active() = localActive() && authorization.active()
            check(active())
            val before = journal.load()
            check(before.latest?.requiresReconcile != true)
            val id = Math.addExact(maxOf(before.claimedHighWater, before.latest?.requestId ?: 0), 1)
            fun read(request: Long): StoppedTargetObservation {
                check(active())
                val observed = observe(request, lease) { !localActive() }
                check(active())
                return observed
            }
            val baseline = read(id) as? StoppedTargetObservation.Known
                ?: return StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "fresh target read unavailable")
            check(active() && baseline.identity == lease.consent.identity && baseline.hasVerifiedPackageUid)
            check(lease.consent.policyVersion != 4 || baseline.qualifiedRecipient)
            if (!baseline.stopped) return StoppedRecoveryResult(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "flag already clear")
            val prepared = journal.prepareAutomatically(lease.consent.identity, baseline,
                AutomaticRecoveryConsent(lease.consent.id, lease.consent.consentedAtMillis, lease.consent.policyVersion))
            check(prepared.requestId == id && active())
            gate = writeGate.install(ticket) {
                val current = journal.load().latest
                val fresh = if (active() && current?.requestId == id && current.stage == StoppedRecoveryStage.CLAIMED &&
                    current.identity == lease.consent.identity && current.consentId == lease.consent.id) read(id) else null
                fresh is StoppedTargetObservation.Known && fresh.identity == lease.consent.identity && fresh.stopped &&
                    fresh.hasVerifiedPackageUid && (lease.consent.policyVersion != 4 || fresh.qualifiedRecipient) && active()
            }
            val engine = StoppedRecoveryEngine(::read, { request ->
                active() && journal.claim(request) && active()
            }, ::active, unstop = {
                var process: DaemonStartedUnstop? = null
                try {
                    check(active())
                    check(owner.startWrite(ticket) {
                        startEntered = true
                        process = checkNotNull(preparedLaunch).start()
                        command = process
                    })
                    requireNotNull(process).awaitExit(::localActive).also { check(active()) }
                } finally { process?.close() }
            })
            val initial = engine.recover(id, lease.consent.identity) {
                check(active())
                preparedLaunch = shell.prepareUnstop(lease.consent.identity, lease.consent.policyVersion == 4, ::localActive)
                active() && preparedLaunch != null
            }
            val result = awaitStoppedRecoveryReadback(lease.consent.identity, initial,
                read = { read(id) }, active = ::active, pause = readbackPause)
            check(active())
            val finished = if (result.outcome == StoppedRecoveryOutcome.UNCONFIRMED)
                result.copy(record = journal.load().latest)
            else result.copy(record = journal.finish(id, result.outcome, result.detail))
            runCatching { confirmedRecoveryReceipt(finished, wall())?.let { counts.record(it) } }
            return finished
        } catch (_: Exception) {
            val latest = runCatching { journal.load().latest }.getOrNull()
            return StoppedRecoveryResult(if (latest?.requiresReconcile == true) StoppedRecoveryOutcome.UNCONFIRMED
                else StoppedRecoveryOutcome.BLOCKED, "authority, prerequisites or result unavailable", latest)
        } finally {
            gate?.close()
            val process = command
            if (!startEntered || process != null && runCatching { process.terminated() }.getOrDefault(false)) owner.complete(ticket)
            else unfinished = Unfinished(ticket, process)
        }
    }

    private fun reconcilePending(pending: StoppedRecoveryRecord): DaemonStoppedCycle {
        if (closed.get() || !environmentReady()) return unavailable()
        val targetAware = pending.consentVersion == 4 || pending.identity.packageName != "org.telegram.messenger"
        val observed = shell.observe(pending.requestId, pending.identity, targetAware) { closed.get() }
        if (closed.get() || !environmentReady() || journal.load().latest != pending) return unavailable()
        if (observed.requestId != pending.requestId) return DaemonStoppedCycle(DaemonStoppedCycleStatus.PENDING)
        val outcome = when {
            observed is StoppedTargetObservation.Unknown && observed.reason == StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED ->
                StoppedRecoveryOutcome.BLOCKED
            observed !is StoppedTargetObservation.Known || !observed.hasVerifiedPackageUid ||
                targetAware && !observed.qualifiedRecipient -> return DaemonStoppedCycle(DaemonStoppedCycleStatus.PENDING)
            observed.identity != pending.identity -> StoppedRecoveryOutcome.BLOCKED
            observed.stopped -> StoppedRecoveryOutcome.STILL_STOPPED
            else -> StoppedRecoveryOutcome.FLAG_CLEARED
        }
        val record = journal.finish(pending.requestId, outcome, "read-only reconciliation; command not replayed", reconciled = true)
        return DaemonStoppedCycle(DaemonStoppedCycleStatus.RECONCILED,
            results = listOf(StoppedRecoveryResult(outcome, record.detail, record)))
    }

    private fun observe(id: Long, lease: TargetConsentLease, interrupted: () -> Boolean): StoppedTargetObservation {
        val observed = shell.observe(id, lease.consent.identity, lease.consent.policyVersion == 4, interrupted)
        if (observed.requestId != id) return StoppedTargetObservation.Unknown(id, StoppedObservationUnknown.INVALID_REQUEST)
        return if (observed is StoppedTargetObservation.Known && !lease.consent.acceptsRuntimeFingerprint(observed.fingerprint))
            StoppedTargetObservation.Unknown(id, StoppedObservationUnknown.UNSUPPORTED_BUILD) else observed
    }
    private fun sameOwnerMemory(pinned: DaemonControlRecord) = !closed.get() &&
        owner.status() == DaemonOwnerStatus.ACTIVE && owner.snapshot() == pinned
    private fun sameOwner(pinned: DaemonControlRecord) = sameOwnerMemory(pinned) && authorityDigest() == pinned.authorityDigest
    private fun environmentReady(): Boolean {
        val state = consent.readSnapshot() ?: return false
        val enabledAt = state.masterEnabledAtMillis ?: state.consents().minOfOrNull { it.consentedAtMillis } ?: return false
        return environment.read(guardian, bootId, enabledAt, closed::get) == DaemonOwnerEnvironmentStatus.READY
    }
    private fun unavailable() = DaemonStoppedCycle(DaemonStoppedCycleStatus.UNAVAILABLE)
    private fun reapTerminatedCommand(): Boolean {
        val old = unfinished ?: return true
        if (old.process == null || !runCatching { old.process.terminated() }.getOrDefault(false)) return false
        owner.complete(old.ticket)
        unfinished = null
        return true
    }
    /** Drain proof for fixed control maintenance without permanently closing this engine. */
    fun isControlQuiescent(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        return try {
            targetReader.isQuiescent() && shell.isQuiescent() && reapTerminatedCommand()
        } finally { busy.set(false) }
    }

    /** Host must close and await this before releasing the global owner-storage lifetime lease. */
    fun isQuiescent(): Boolean {
        if (!closed.get() || busy.get() || !targetReader.isQuiescent() || !shell.isQuiescent()) return false
        return unfinished?.let {
            it.process != null && runCatching { it.process.terminated() }.getOrDefault(false)
        } ?: true
    }
    override fun close() {
        closed.set(true)
        targetReader.close()
        shell.close()
        runCatching { unfinished?.process?.close() }
    }
    companion object { const val POLL_MILLIS = 30_000L }
}
