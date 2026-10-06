package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoveryResult
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage

internal sealed interface AutomaticCheckDecision {
    data object Wait : AutomaticCheckDecision
    data object Recover : AutomaticCheckDecision
    data object Reconcile : AutomaticCheckDecision
    data class Recheck(val detail: String) : AutomaticCheckDecision
    data class Cooldown(val remainingMillis: Long) : AutomaticCheckDecision
    data class Pause(val detail: String) : AutomaticCheckDecision
}

/** Historical quota algorithm retained for compatibility tests; production recovery never calls it. */
internal class AutomaticAttemptBudget {
    private val attempts = ArrayDeque<Long>()
    private var lastClock: Long? = null

    @Synchronized
    fun take(nowElapsedMillis: Long): AutomaticCheckDecision {
        if (nowElapsedMillis < 0 || lastClock?.let { nowElapsedMillis < it } == true) {
            return AutomaticCheckDecision.Pause("保护不可用：计时状态异常，请重新确认")
        }
        lastClock = nowElapsedMillis
        while (attempts.firstOrNull()?.let { nowElapsedMillis - it >= HOUR_MILLIS } == true) attempts.removeFirst()
        if (attempts.size >= 3) return AutomaticCheckDecision.Cooldown(HOUR_MILLIS - (nowElapsedMillis - attempts.first()))
        val remaining = attempts.lastOrNull()?.let { MIN_INTERVAL_MILLIS - (nowElapsedMillis - it) } ?: 0
        if (remaining > 0) {
            return AutomaticCheckDecision.Cooldown(remaining)
        }
        attempts.addLast(nowElapsedMillis)
        return AutomaticCheckDecision.Recover
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 5 * 60 * 1000L
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}

/** A confirmed current attempt may wait for a fresh request; unresolved or old receipts never qualify. */
internal fun mayContinueAfterStoppedRecovery(
    result: StoppedRecoveryResult,
    latest: StoppedRecoveryRecord?,
    previousRequestId: Long,
    consent: TargetConsent,
): Boolean {
    val record = result.record ?: return false
    return previousRequestId >= 0 && record.requestId > previousRequestId && record == latest &&
        result.outcome == StoppedRecoveryOutcome.STILL_STOPPED &&
        record.outcome == StoppedRecoveryOutcome.STILL_STOPPED && record.stage == StoppedRecoveryStage.FINISHED &&
        record.source == StoppedRecoverySource.AUTOMATIC && record.identity == consent.identity &&
        record.consentId == consent.id && record.consentedAtMillis == consent.consentedAtMillis &&
        record.consentVersion == consent.policyVersion && record.fingerprint == consent.fingerprint &&
        record.currentUser == 0 && record.baselineStopped && !record.confirmedManualAction &&
        record.acknowledgedRuntimeRisk
}

/** Local single-flight rejection is not a failed recovery. Caller still checks live authority/connection. */
internal fun mayWaitAfterRecoveryAdmission(
    result: StoppedRecoveryResult,
    latest: Result<StoppedRecoveryRecord?>,
): Boolean = result.admissionBusy && result.outcome == StoppedRecoveryOutcome.BLOCKED &&
    !result.commandSucceeded && result.record == null &&
    latest.isSuccess && latest.getOrNull()?.requiresReconcile != true

/** Only a local bind failure before the action, with unchanged clear storage, may retain consent. */
internal fun mayWaitAfterRecoveryBinding(
    result: StoppedRecoveryResult,
    before: Result<StoppedRecoveryRecord?>,
    latest: Result<StoppedRecoveryRecord?>,
): Boolean = result.bindingFailedBeforeAction && !result.admissionBusy &&
    result.outcome == StoppedRecoveryOutcome.BLOCKED && !result.commandSucceeded &&
    before.isSuccess && latest.isSuccess && before.getOrNull() == latest.getOrNull() &&
    result.record == latest.getOrNull() && latest.getOrNull()?.requiresReconcile != true

/** Full fresh startup evidence is mandatory before converting uncertainty into a retained wait. */
internal fun mayRetainAfterFreshAutomaticCheck(probe: StartupProbeResult, leases: List<TargetConsentLease>): Boolean =
    leases.isNotEmpty() && probe.leases == leases && probe.invalidTargets.isEmpty() &&
        probe.status in setOf(StartupEligibilityStatus.READY_TO_START, StartupEligibilityStatus.WAITING_BUDGET)

internal fun automaticCheckDecision(
    authorized: Boolean,
    prerequisitesAvailable: Boolean,
    pendingReceipt: Boolean,
    recoveryBusy: Boolean,
    stoppedStatus: TargetStoppedStatus,
): AutomaticCheckDecision = when {
    !authorized -> AutomaticCheckDecision.Pause("自动保护已停止")
    !prerequisitesAvailable -> AutomaticCheckDecision.Pause("保护不可用：安装身份或 Shizuku 不可用，请重新确认")
    pendingReceipt -> AutomaticCheckDecision.Reconcile
    recoveryBusy -> AutomaticCheckDecision.Wait
    stoppedStatus == TargetStoppedStatus.NOT_STOPPED -> AutomaticCheckDecision.Wait
    stoppedStatus == TargetStoppedStatus.STOPPED -> AutomaticCheckDecision.Recover
    stoppedStatus == TargetStoppedStatus.UNKNOWN -> AutomaticCheckDecision.Recheck("停止标记暂时无法读取，等待下次完整检测；授权已保留，当前未执行保护")
    else -> AutomaticCheckDecision.Pause("保护不可用：停止标记或安装身份无法确认，请重新选择并确认")
}

/** Retire the foreground lifetime immediately; a cancelled IO job may not finish promptly. */
internal fun cancelAutomaticSession(
    cancelRecovery: () -> Unit,
    cancelPolling: () -> Unit,
    enqueueRetirement: () -> Unit,
) {
    try {
        cancelRecovery()
    } finally {
        try {
            cancelPolling()
        } finally {
            enqueueRetirement()
        }
    }
}

/** Revocation is latched; neither stale callbacks nor a replacement id revive an old grant. */
internal class AutomaticSessionGate {
    private var id: String? = null
    private var claimed = false

    @Synchronized fun reserve(value: String): Boolean {
        if (id != null) return false
        id = value
        claimed = false
        return true
    }

    @Synchronized fun claim(value: String): Boolean {
        if (id != value || claimed) return false
        claimed = true
        return true
    }

    @Synchronized fun active(value: String): Boolean = id == value

    @Synchronized fun revoke(value: String): Boolean {
        if (id != value) return false
        id = null
        claimed = false
        return true
    }
}
