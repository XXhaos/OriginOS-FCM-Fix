package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage

internal fun sameRecoveryRequest(expected: StoppedRecoveryRecord, observed: StoppedRecoveryRecord?): Boolean =
    observed?.copy(stage = expected.stage, outcome = expected.outcome, detail = expected.detail) == expected

/** A read-only conclusion describes current flags, never confirms whether the old command ran. */
internal fun resolvedPendingRecovery(expected: StoppedRecoveryRecord, observed: StoppedRecoveryRecord?): Boolean =
    expected.requiresReconcile && sameRecoveryRequest(expected, observed) &&
        observed?.stage == StoppedRecoveryStage.RECONCILED &&
        observed.outcome in setOf(StoppedRecoveryOutcome.FLAG_CLEARED, StoppedRecoveryOutcome.STILL_STOPPED)

/** Closing invalidates every issued ticket permanently, even when the same owner becomes ready again. */
internal class AutomaticWriteGate {
    private var epoch = 0L
    private var ready = false
    @Synchronized fun block() { ready = false; epoch++ }
    @Synchronized fun open() { ready = true }
    @Synchronized fun ticket(): Long? = epoch.takeIf { ready }
    @Synchronized fun permits(ticket: Long?): Boolean = ready && ticket != null && ticket == epoch
}

/** Timers or queued/sticky events do not replenish a round; only a new connection or explicit refresh does. */
internal class PendingReconciliationBudget {
    private var round: Triple<Long, Long, Long>? = null
    private var attempts = 0
    fun nextDelay(requestId: Long, connection: Long, explicitRefresh: Long): Long? {
        val next = Triple(requestId, connection, explicitRefresh)
        if (round != next) { round = next; attempts = 0 }
        return DELAYS.getOrNull(attempts)?.also { attempts++ }
    }
    private companion object { val DELAYS = listOf(0L, 3_000L, 15_000L) }
}
