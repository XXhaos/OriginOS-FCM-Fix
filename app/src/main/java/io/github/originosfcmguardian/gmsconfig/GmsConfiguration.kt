package io.github.originosfcmguardian.gmsconfig

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.*
import kotlinx.coroutines.*

const val GMS_CONFIG_INTERVAL_MILLIS = 15 * 60_000L
internal const val GMS_CONFIG_BUDGET_MILLIS = 45_000L

data class GmsConfigIdentity(
    val packageIdentity: SelectedTargetIdentity,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val enabled: Boolean,
) {
    init {
        require(packageIdentity.packageName == GMS_PACKAGE_NAME)
        require(packageIdentity.signerSha256.size <= 8)
        require(versionCode >= 0 && lastUpdateTime > 0)
    }
}

data class GmsConfigContext(val fingerprint: String, val userId: Int, val bootCount: Int?, val identity: GmsConfigIdentity?) {
    init { require(fingerprint.length in 1..512 && !fingerprint.contains('\n')); require(bootCount == null || bootCount >= 0) }
}

enum class GmsConfigFailure { NONE, IDENTITY_OR_ENVIRONMENT, UNAVAILABLE, TIMED_OUT, READ_FAILED }

data class GmsConfigSnapshot(
    val context: GmsConfigContext,
    val checkedAtMillis: Long,
    val checkedAtElapsed: Long,
    val gms: EvidenceStatus,
    val runInBackground: EvidenceStatus,
    val runAnyInBackground: EvidenceStatus,
    val wakeLock: EvidenceStatus,
    val doze: EvidenceStatus,
    val failure: GmsConfigFailure,
) {
    init { require(checkedAtMillis > 0 && checkedAtElapsed >= 0) }
    val settings: List<EvidenceStatus> get() = listOf(gms, runInBackground, runAnyInBackground, wakeLock, doze)

    /** Even fresh values remain last-observed read-only evidence, never protection authority. */
    fun isFresh(current: GmsConfigContext?, wall: Long, elapsed: Long): Boolean {
        if (context.bootCount == null || context.identity == null || context.userId != 0 || context != current) return false
        val age = elapsed - checkedAtElapsed
        val wallAge = wall - checkedAtMillis
        return age in 0 until GMS_CONFIG_INTERVAL_MILLIS && wallAge >= 0 &&
            kotlin.math.abs(wallAge - age) <= 120_000
    }
}

internal fun gmsConfigSnapshot(before: GmsConfigContext, after: GmsConfigContext,
    report: DetectionReport, wall: Long, elapsed: Long): GmsConfigSnapshot {
    val valid = before == after && before.bootCount != null && before.identity != null && before.userId == 0 &&
        report.currentUser == 0 && report.buildFingerprint == before.fingerprint &&
        report.gms.appId == before.identity.packageIdentity.uid &&
        (report.gms.enabled == EnabledState.ENABLED) == before.identity.enabled
    val failure = when {
        report.privilegedAccess != PrivilegedAccessState.READY_SHELL -> GmsConfigFailure.UNAVAILABLE
        !valid -> GmsConfigFailure.IDENTITY_OR_ENVIRONMENT
        else -> GmsConfigFailure.NONE
    }
    fun status(value: EvidenceStatus?) = if (failure == GmsConfigFailure.NONE) value ?: EvidenceStatus.UNKNOWN else EvidenceStatus.UNKNOWN
    return GmsConfigSnapshot(before, wall, elapsed, status(report.gms.status),
        status(report.appOps[AppOp.RUN_IN_BACKGROUND]?.status), status(report.appOps[AppOp.RUN_ANY_IN_BACKGROUND]?.status),
        status(report.appOps[AppOp.WAKE_LOCK]?.status), status(report.doze.status), failure)
}

internal fun unknownGmsConfig(context: GmsConfigContext, wall: Long, elapsed: Long, failure: GmsConfigFailure) =
    GmsConfigSnapshot(context, wall, elapsed, EvidenceStatus.UNKNOWN, EvidenceStatus.UNKNOWN,
        EvidenceStatus.UNKNOWN, EvidenceStatus.UNKNOWN, EvidenceStatus.UNKNOWN, failure)

/** One child, no catch-up. Admission is charged before launch, including failures/cancellation. */
internal class GmsConfigCadence(private val scope: CoroutineScope, private val clock: () -> Long,
    initialAttempt: Long? = null) {
    private var lastAttempt = initialAttempt
    private var job: Job? = null
    fun launchIfEligible(eligible: Boolean, read: suspend () -> Unit): Boolean {
        val now = clock()
        if (!eligible || job?.isCompleted == false || lastAttempt?.let { now < it || now - it < GMS_CONFIG_INTERVAL_MILLIS } == true) return false
        lastAttempt = now
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Config errors cannot revoke recipient grants. */ }
        }.also { it.start() }
        return true
    }
    fun cancel() { job?.cancel() }
}
