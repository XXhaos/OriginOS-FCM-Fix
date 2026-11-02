package io.github.originosfcmguardian.pushactivation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Presentation only. A pending ticket is never a privileged execution capability. */
enum class PushActivationOrigin { NONE, EXPLICIT_THIS_PROCESS, RESTORED }

/** Fixed enum names only; no command ids, raw errors or installation identifiers. */
data class PushActivationAttemptDiagnostic(val remoteOutcome: String, val remoteReason: String,
    val preparationReason: String? = null, val readUnknown: String? = null) {
    init {
        require(listOfNotNull(remoteOutcome, remoteReason, preparationReason, readUnknown)
            .all { it.matches(Regex("[A-Z_]{1,64}")) })
    }
}

data class PushActivationUiState(val pending: Boolean = false, val busy: Boolean = false,
    val blocked: Boolean = false, val storageFailed: Boolean = false,
    val reason: PushActivationReason = PushActivationReason.NONE,
    val origin: PushActivationOrigin = PushActivationOrigin.NONE,
    val confirmedAtEpochMillis: Long? = null,
    val confirmedVersionCode: Long? = null,
    val dispatchesThisProcess: Int = 0,
    val recordedDispatches: Int? = null,
    val lastDispatch: PushActivationDispatch? = null,
    val lastAttempt: PushActivationAttemptDiagnostic? = null)

internal data class PushActivationReadiness(
    val epoch: Any,
    val masterOn: Boolean = false,
    val ready: Boolean = false,
    val configured: Boolean = false,
    val canStartMaster: Boolean = false,
    val block: PushActivationReason? = null,
    val masterKnown: Boolean = true,
)

enum class PushActivationDispatch { CONFIGURED, SAFE_READ_FAILURE, UNKNOWN, STORAGE_FAILURE, SAFE_RETRY }

/** Only the remote's explicit pre-stage proof permits a bounded continuation. */
internal fun remoteActivationResult(outcome: io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome,
    configured: Boolean): PushActivationDispatch = when (outcome) {
    io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome.APPLIED ->
        if (configured) PushActivationDispatch.CONFIGURED else PushActivationDispatch.UNKNOWN
    io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome.SAFE_RETRY -> PushActivationDispatch.SAFE_RETRY
    else -> PushActivationDispatch.UNKNOWN
}

/** A false return or changing owner alone never proves that no positive authority was written. */
internal fun localActivationResult(configured: Boolean, authorityWriteStarted: Boolean,
    storageBlocked: Boolean, noAuthority: Boolean): PushActivationDispatch = when {
    storageBlocked -> PushActivationDispatch.STORAGE_FAILURE
    configured -> PushActivationDispatch.CONFIGURED
    !authorityWriteStarted && noAuthority -> PushActivationDispatch.SAFE_READ_FAILURE
    else -> PushActivationDispatch.UNKNOWN
}

internal interface PushActivationPort {
    fun installation(): PushActivationInstallation
    fun confirmed() {}
    fun readiness(): PushActivationReadiness
    /** Existing master API only; this does not create recipient choices or FCM consent. */
    fun startMaster()
    suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch
    fun attemptDiagnostic(): PushActivationAttemptDiagnostic? = null
    fun nowMillis(): Long = System.currentTimeMillis()
}

/** One process-owned, event-driven continuation. There is no timer or screen-owned job. */
internal class PushActivationController(private val store: PushActivationStore, private val port: PushActivationPort,
    private val scope: CoroutineScope, private val safeRetryDelayMillis: Long = 1_000) {
    private val fence = Any()
    private val io = Mutex()
    private val mutable = MutableStateFlow(PushActivationUiState())
    val state = mutable.asStateFlow()
    private var revision = 0L
    private var snapshot: PushActivationSnapshot? = null
    private var loaded = false
    private var storageFailed = false
    private var saving = false
    private var revoked = false
    private var worker: Job? = null
    private var event = false
    private var trigger = 0L
    private var attempted: Pair<Long, Long>? = null
    private var masterAttempted: Pair<Long, Long>? = null
    private var observedEpoch: Any? = null
    private var readinessEpoch = 0L
    private var sawMasterOn = false
    private var mayStartMaster = false
    private var retirement: Job? = null
    private var origin = PushActivationOrigin.NONE
    private var dispatches = 0
    private var safeRetryUsed: Pair<Long, Long>? = null

    init { require(safeRetryDelayMillis in 0..5_000) }

    fun initialize() = changed()
    fun onForeground() {
        synchronized(fence) { trigger++ }
        changed()
    }

    /** Only a newly accepted explicit unified ON may call this method. */
    suspend fun confirmExplicit(): Boolean = confirmExplicitAsync().await()

    /** Capture ON before scheduling IO, so an immediate later OFF also cancels a not-yet-run save. */
    fun confirmExplicitAsync(): Deferred<Boolean> {
        val captured: Long
        val drain: Job?
        synchronized(fence) {
            captured = ++revision
            cancelWorkerLocked()
            snapshot = null
            loaded = true
            storageFailed = false
            saving = true; revoked = false
            sawMasterOn = false
            mayStartMaster = true
            attempted = null; masterAttempted = null; trigger++
            origin = PushActivationOrigin.EXPLICIT_THIS_PROCESS
            dispatches = 0; safeRetryUsed = null
            drain = retirement
            publishLocked()
        }
        return scope.async {
            // A previous OFF must be durable before a new explicit ON can replace it.
            drain?.join()
            val accepted = try {
                io.withLock {
                    if (!current(captured)) return@withLock null
                    val installation = port.installation()
                    if (!current(captured)) return@withLock null
                    port.confirmed()
                    store.begin(installation, UUID.randomUUID().toString(), port.nowMillis().coerceAtLeast(1))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage(captured); return@async false }
            val retained = synchronized(fence) {
                if (revision != captured || accepted == null) false else {
                    snapshot = accepted; saving = false; publishLocked(); true
                }
            }
            if (retained) changed()
            retained
        }
    }

    /** Synchronous negative fence first; durable OFF follows on the process scope. */
    fun cancellationToken(): Long = synchronized(fence) { revision }

    /** A historical host callback may cancel only the exact intent observed at its admission. */
    fun cancelExplicit(expectedRevision: Long? = null, admit: () -> Boolean = { true }): Boolean {
        val captured: Long
        val before: Job?
        synchronized(fence) {
            if (expectedRevision != null && revision != expectedRevision) return false
            // CPU-only session/command admission shares this fence with a replacement ON.
            // Persistence and actual command execution must stay outside this callback.
            if (!admit()) return false
            captured = ++revision
            cancelWorkerLocked()
            snapshot = null; loaded = true; sawMasterOn = false
            mayStartMaster = false
            saving = false; revoked = false
            attempted = null; masterAttempted = null
            origin = PushActivationOrigin.NONE
            dispatches = 0; safeRetryUsed = null
            before = retirement
            val task = scope.launch(start = CoroutineStart.LAZY) {
                before?.join()
                try {
                    val retired = io.withLock { store.retire() }
                    synchronized(fence) {
                        if (revision == captured) { snapshot = retired; storageFailed = false; publishLocked() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failStorage(captured) }
            }
            retirement = task
            publishLocked()
            task.start()
        }
        return true
    }

    /** Explicit permission denial is stronger than a temporary missing Shizuku process. */
    fun privilegeRevoked() {
        val captured: Long
        synchronized(fence) {
            if (!saving && loaded && snapshot?.mode !in unfinished) return
            captured = ++revision
            cancelWorkerLocked()
            saving = false; revoked = true
            mayStartMaster = false
            snapshot?.takeIf { it.mode in setOf(PushActivationMode.PENDING, PushActivationMode.DISPATCHING) }?.let {
                snapshot = it.copy(mode = PushActivationMode.BLOCKED, reason = PushActivationReason.PRIVILEGE_REVOKED)
            }
            publishLocked()
        }
        scope.launch {
            try {
                val blocked = io.withLock {
                    if (!current(captured)) return@withLock null
                    // Read after any already-started save. Revocation also fences a pending save
                    // whose file was committed just before its in-memory publication.
                    val previous = store.read()
                    if (previous?.mode in setOf(PushActivationMode.PENDING, PushActivationMode.DISPATCHING))
                        store.transition(checkNotNull(previous), PushActivationMode.BLOCKED, PushActivationReason.PRIVILEGE_REVOKED)
                    else previous
                }
                synchronized(fence) { if (revision == captured) { snapshot = blocked; loaded = true; publishLocked() } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage(captured) }
        }
    }

    /** State collectors coalesce, including changes emitted by the dispatch itself. */
    fun changed() {
        val task = synchronized(fence) {
            event = true
            if (worker != null) return
            scope.launch(start = CoroutineStart.LAZY) { drainEvents() }.also { worker = it }
        }
        task.start()
    }

    private suspend fun drainEvents() {
        val own = currentCoroutineContext()[Job]
        try {
            while (true) {
                val captured = synchronized(fence) {
                    if (!event) return
                    event = false
                    revision
                }
                process(captured)
            }
        } finally {
            val again = synchronized(fence) {
                if (worker === own) worker = null
                event
            }
            if (again) changed()
        }
    }

    private suspend fun process(captured: Long) {
        try {
            if (!synchronized(fence) { loaded }) {
                val saved = io.withLock { store.read() }
                synchronized(fence) {
                    if (revision != captured) return
                    snapshot = saved; loaded = true
                    origin = if (saved?.mode in unfinished || saved?.mode == PushActivationMode.COMPLETE)
                        PushActivationOrigin.RESTORED else PushActivationOrigin.NONE
                    publishLocked()
                }
            }
            var pending = synchronized(fence) { snapshot?.takeIf { revision == captured && !storageFailed && !revoked } } ?: return
            if (pending.mode !in unfinished) return
            if (!sameInstallation(checkNotNull(pending.installation), port.installation())) {
                settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.IDENTITY_CHANGED); return
            }
            val ready = port.readiness()
            if (!current(captured)) return
            synchronized(fence) {
                // Busy/unavailable states emitted by our own command are not a new retry budget.
                if ((ready.ready || ready.canStartMaster) && observedEpoch != ready.epoch) {
                    observedEpoch = ready.epoch; readinessEpoch++
                }
            }
            ready.block?.let { settle(captured, pending, PushActivationMode.BLOCKED, it); return }
            // An existing configured result may settle a cold DISPATCHING/BLOCKED record, never replay it.
            if (ready.configured) { settle(captured, pending, PushActivationMode.COMPLETE); return }
            if (pending.mode == PushActivationMode.DISPATCHING && pending.lastDispatch in
                setOf(PushActivationDispatch.SAFE_RETRY, PushActivationDispatch.SAFE_READ_FAILURE)) {
                // A completed pre-write proof was saved before a crash between the two commits.
                // Legacy or unknown DISPATCHING has no such evidence and remains blocked below.
                pending = transition(captured, pending, PushActivationMode.PENDING) ?: return
            }
            if (pending.mode == PushActivationMode.DISPATCHING) {
                settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT); return
            }
            if (pending.mode == PushActivationMode.BLOCKED) return
            if (!ready.masterKnown) return
            val masterWasOn = synchronized(fence) {
                if (revision != captured) return
                if (ready.masterOn) sawMasterOn = true
                sawMasterOn
            }
            if (!ready.masterOn) {
                if (masterWasOn) { cancelExplicit(); return }
                // A cold PENDING file cannot prove it is newer than a separately committed OFF.
                // Only this process's new explicit ON may initiate master startup.
                if (!synchronized(fence) { mayStartMaster }) {
                    settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.MASTER_OFF); return
                }
                if (ready.canStartMaster) synchronized(fence) {
                    val key = readinessEpoch to trigger
                    if (revision == captured && masterAttempted != key) {
                        masterAttempted = key
                        // OFF takes this same short fence before invoking the existing stop APIs.
                        port.startMaster()
                    }
                }
                return
            }
            if (!ready.ready) return
            val admittedKey = synchronized(fence) {
                val key = readinessEpoch to trigger
                if (revision != captured || attempted == key) return
                attempted = key
                key
            }
            pending = transition(captured, pending, PushActivationMode.DISPATCHING) ?: return
            synchronized(fence) { if (revision == captured) {
                dispatches = (dispatches.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                publishLocked(true)
            } }
            currentCoroutineContext().ensureActive()
            if (!current(captured)) return
            val result = port.dispatch({ current(captured) }) { action ->
                synchronized(fence) {
                    if (revision != captured) false else { action(); true }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!current(captured)) return
            pending = io.withLock {
                if (!current(captured)) return
                val saved = store.recordAttempt(pending, result, port.attemptDiagnostic())
                synchronized(fence) {
                    if (revision != captured) return
                    if (saved == null) { storageFailed = true; publishLocked(); return }
                    snapshot = saved; publishLocked()
                }
                checkNotNull(saved)
            }
            when (result) {
                PushActivationDispatch.CONFIGURED -> if (port.readiness().configured)
                    settle(captured, pending, PushActivationMode.COMPLETE)
                else settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT)
                PushActivationDispatch.SAFE_READ_FAILURE -> settle(captured, pending, PushActivationMode.PENDING)
                PushActivationDispatch.SAFE_RETRY -> {
                    // Only a positive proof that this attempt did not stage activation permits
                    // this one continuation. Persist PENDING before giving it another admission.
                    transition(captured, pending, PushActivationMode.PENDING) ?: return
                    val retry = synchronized(fence) {
                        if (revision != captured || safeRetryUsed == admittedKey) false
                        else { safeRetryUsed = admittedKey; true }
                    }
                    if (retry) {
                        delay(safeRetryDelayMillis)
                        synchronized(fence) {
                            if (revision == captured && attempted == admittedKey) {
                                attempted = null; event = true
                            }
                        }
                    }
                }
                PushActivationDispatch.STORAGE_FAILURE -> settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.STORAGE_UNAVAILABLE)
                PushActivationDispatch.UNKNOWN -> settle(captured, pending, PushActivationMode.BLOCKED, PushActivationReason.UNKNOWN_RESULT)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failStorage(captured) }
    }

    private suspend fun transition(captured: Long, expected: PushActivationSnapshot, mode: PushActivationMode,
        reason: PushActivationReason = PushActivationReason.NONE): PushActivationSnapshot? = io.withLock {
        if (!current(captured)) return@withLock null
        val next = store.transition(expected, mode, reason)
        synchronized(fence) {
            if (revision != captured) return@withLock null
            if (next == null) { storageFailed = true; publishLocked(); return@withLock null }
            snapshot = next; publishLocked()
        }
        next
    }

    private suspend fun settle(captured: Long, expected: PushActivationSnapshot, mode: PushActivationMode,
        reason: PushActivationReason = PushActivationReason.NONE) {
        if (expected.mode == mode) return
        transition(captured, expected, mode, reason)
    }

    private fun current(captured: Long): Boolean = synchronized(fence) { revision == captured }

    /** A LAZY worker cancelled before its first instruction has no finally block to clear itself. */
    private fun cancelWorkerLocked() {
        val old = worker
        worker = null; event = false
        old?.cancel()
    }

    private suspend fun failStorage(captured: Long) {
        // An ambiguous write is never permission to dispatch again, even if readback later recovers.
        synchronized(fence) {
            if (revision != captured) return
            storageFailed = true; publishLocked()
        }
        runCatching {
            io.withLock {
                if (!current(captured)) return@withLock
                val saved = store.read()
                if (saved?.mode == PushActivationMode.PENDING || saved?.mode == PushActivationMode.DISPATCHING)
                    store.transition(saved, PushActivationMode.BLOCKED, PushActivationReason.STORAGE_UNAVAILABLE)
            }
        }
    }

    private fun publishLocked(busy: Boolean = false) {
        val value = snapshot
        mutable.value = PushActivationUiState(
            pending = !storageFailed && value?.mode in setOf(PushActivationMode.PENDING, PushActivationMode.DISPATCHING),
            busy = busy && !storageFailed,
            blocked = value?.mode == PushActivationMode.BLOCKED || storageFailed || revoked,
            storageFailed = storageFailed || value?.reason == PushActivationReason.STORAGE_UNAVAILABLE,
            reason = when {
                storageFailed -> PushActivationReason.STORAGE_UNAVAILABLE
                revoked -> PushActivationReason.PRIVILEGE_REVOKED
                else -> value?.reason ?: PushActivationReason.NONE
            },
            origin = origin,
            confirmedAtEpochMillis = value?.confirmedAtEpochMillis?.takeIf { it > 0 },
            confirmedVersionCode = value?.installation?.versionCode,
            dispatchesThisProcess = dispatches, recordedDispatches = value?.recordedDispatches,
            lastDispatch = value?.lastDispatch, lastAttempt = value?.lastAttempt)
    }

    private companion object {
        val unfinished = setOf(PushActivationMode.PENDING, PushActivationMode.DISPATCHING, PushActivationMode.BLOCKED)
    }
}

/** Version/update timestamps are observations, not the durable identity of an installation. */
internal fun sameInstallation(before: PushActivationInstallation, now: PushActivationInstallation): Boolean =
    before.uid == now.uid && before.firstInstallTime == now.firstInstallTime && before.signerSha256 == now.signerSha256
