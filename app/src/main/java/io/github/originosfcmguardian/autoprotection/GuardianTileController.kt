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

internal fun guardianTileMayRestore(result: StartupProbeResult): Boolean {
    if (result.leases.any { it.consent.restartConsented() } && result.status.mayCreateWaitingSession()) return true
    if (result.status != StartupEligibilityStatus.BLOCKED_IDENTITY) return false
    val invalid = result.invalidTargets
    // Request only the existing fresh-probe restore path. Tile evidence never retires consent
    // or authorizes a write; a confirmed invalid target must not strand a valid survivor.
    return invalid.isNotEmpty() && invalid.distinct().size == invalid.size &&
        result.leases.containsAll(invalid) && result.leases.any { it !in invalid && it.consent.restartConsented() }
}

internal data class GuardianTilePresentation(val active: Boolean, val subtitle: String)

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

/** One probe per eligible listening event; callbacks are invalidated by stop, remove and destroy. */
internal class GuardianTileController(
    private val throttle: GuardianTileThrottle,
    private val clock: () -> Long,
    private val hasSession: () -> Boolean,
    private val probe: ((StartupProbeResult) -> Unit) -> Unit,
    private val restore: (mayLaunch: () -> Boolean, finished: () -> Unit) -> Unit,
    private val publish: (StartupEligibilityStatus?) -> Unit,
) {
    private var generation = 0L
    private var listening = false
    private var startupStatus: StartupEligibilityStatus? = null

    fun startListening() {
        val current = synchronized(this) { listening = true; startupStatus = null; ++generation }
        refresh()
        if (hasSession() || !throttle.acquire(clock())) return
        probe { result ->
            if (!isCurrent(current)) return@probe
            synchronized(this) { startupStatus = result.status }
            refresh()
            if (guardianTileMayRestore(result) && !hasSession()) {
                restore({ isCurrent(current) }) { if (isCurrent(current)) refresh() }
            }
        }
    }

    @Synchronized fun stopListening() { listening = false; generation++; startupStatus = null }
    @Synchronized private fun isCurrent(expected: Long): Boolean = listening && generation == expected
    @Synchronized fun refresh() { if (listening) publish(startupStatus) }
}
