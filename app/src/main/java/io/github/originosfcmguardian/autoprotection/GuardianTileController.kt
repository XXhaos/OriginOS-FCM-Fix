package io.github.originosfcmguardian.autoprotection

internal const val GUARDIAN_TILE_LABEL = "推送保护"

/** Process-wide elapsed-time throttle. Rebinding a tile never resets the three-second window. */
internal class GuardianTileThrottle(private val minimumIntervalMillis: Long = 3_000) {
    init { require(minimumIntervalMillis >= 3_000) }
    private var lastProbeAt: Long? = null

    @Synchronized fun acquire(now: Long): Boolean {
        if (now < 0) return false
        val previous = lastProbeAt
        if (previous != null && (now < previous || now - previous < minimumIntervalMillis)) return false
        lastProbeAt = now
        return true
    }
}

/** One process-wide, bounded recovery opportunity; tile visibility owns no recovery authority. */
internal class GuardianTileRecoveryOpportunity(private val throttle: GuardianTileThrottle = GuardianTileThrottle()) {
    internal class Ticket(val startedAt: Long, val runtimeRevision: Long) {
        internal var resultClaimed = false
    }

    private var current: Ticket? = null

    @Synchronized fun acquire(now: Long, runtimeRevision: Long): Ticket? {
        // Even an expired, stuck read retains its slot until completion: never queue another read.
        if (current != null || !throttle.acquire(now)) return null
        return Ticket(now, runtimeRevision).also { current = it }
    }

    @Synchronized fun mayLaunch(ticket: Ticket, now: Long): Boolean =
        current === ticket && now >= ticket.startedAt && now - ticket.startedAt < 10_000

    @Synchronized fun claimResult(ticket: Ticket): Boolean {
        if (current !== ticket || ticket.resultClaimed) return false
        ticket.resultClaimed = true
        return true
    }

    @Synchronized fun finish(ticket: Ticket) {
        if (current === ticket) current = null
    }
}

internal fun guardianTileMayRestore(result: StartupProbeResult): Boolean {
    if (result.leases.any { it.consent.restartConsented() } && result.status.mayCreateWaitingSession()) return true
    if (result.status != StartupEligibilityStatus.BLOCKED_IDENTITY) return false
    val invalid = result.invalidTargets
    // Request only the existing fresh-probe restore path. Tile evidence never retires consent
    // or authorizes a write; a confirmed invalid target must not strand a valid survivor.
    return invalid.isNotEmpty() && invalid.distinct().size == invalid.size &&
        result.leases.containsAll(invalid) && result.leases.any { it !in invalid && it.consent.restartConsented() }
}

internal data class GuardianTilePresentation(
    val active: Boolean,
    val subtitle: String,
    val available: Boolean = true,
)

internal fun guardianTilePresentation(
    runtime: AutomaticProtectionState,
    actuallyProtecting: Boolean,
    startupStatus: StartupEligibilityStatus? = null,
): GuardianTilePresentation = GuardianTilePresentation(
    active = actuallyProtecting && runtime.enabled && !runtime.waitingForPrivilege && !runtime.waitingForReconciliation && !runtime.revocationSaveFailed,
    subtitle = when {
        actuallyProtecting && runtime.enabled && !runtime.waitingForPrivilege && !runtime.waitingForReconciliation && !runtime.revocationSaveFailed -> "检测已开启"
        runtime.waitingForReconciliation -> "等待只读复检，当前未执行保护"
        runtime.waitingForPrivilege -> "等待连接，当前未执行保护"
        runtime.busy -> "正在核对，当前未执行保护"
        startupStatus != null && !runtime.savedAuthorization -> startupStatus.description()
        else -> runtime.detail
    },
)

/** Observation follows tile visibility; a bounded recovery follows the captured runtime revision. */
internal class GuardianTileController(
    private val opportunity: GuardianTileRecoveryOpportunity,
    private val clock: () -> Long,
    private val hasSession: () -> Boolean,
    private val captureRecoveryRevision: () -> Long,
    private val probe: ((StartupProbeResult) -> Unit) -> Unit,
    private val restore: (expectedRevision: Long, mayLaunch: () -> Boolean, finished: () -> Unit) -> Unit,
    private val publish: (StartupEligibilityStatus?) -> Unit,
    private val record: (ProtectionLifecycleEventKind) -> Unit = {},
) {
    private var generation = 0L
    private var listening = false
    private var startupStatus: StartupEligibilityStatus? = null

    fun startListening() {
        val current = synchronized(this) { listening = true; startupStatus = null; ++generation }
        record(ProtectionLifecycleEventKind.TILE_LISTENING)
        refresh()
        if (hasSession()) return
        val revision = captureRecoveryRevision()
        val ticket = opportunity.acquire(clock(), revision) ?: return
        fun mayLaunch() = captureRecoveryRevision() == ticket.runtimeRevision &&
            opportunity.mayLaunch(ticket, clock())
        probe { result ->
            if (!opportunity.claimResult(ticket)) return@probe
            if (!mayLaunch()) {
                opportunity.finish(ticket)
                return@probe
            }
            publishProbeIfCurrent(current, result.status)
            if (!guardianTileMayRestore(result) || hasSession()) {
                opportunity.finish(ticket)
                return@probe
            }
            record(ProtectionLifecycleEventKind.TILE_RESTORE_REQUESTED)
            restore(ticket.runtimeRevision, ::mayLaunch) {
                opportunity.finish(ticket)
                record(ProtectionLifecycleEventKind.TILE_RESTORE_RESULT)
                if (isCurrent(current)) refresh()
            }
        }
    }

    @Synchronized fun stopListening() { listening = false; generation++; startupStatus = null }
    @Synchronized private fun isCurrent(expected: Long): Boolean = listening && generation == expected
    @Synchronized private fun publishProbeIfCurrent(expected: Long, status: StartupEligibilityStatus) {
        if (listening && generation == expected) {
            startupStatus = status
            publish(startupStatus)
        }
    }
    @Synchronized fun refresh() { if (listening) publish(startupStatus) }
}
