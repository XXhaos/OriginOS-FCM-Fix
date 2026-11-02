package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.core.gms.GmsUnknown

/** Fixed categories only. NOT_STAGED refers to this positive activation, not old negative cleanup. */
internal enum class DaemonFcmPreparationReason {
    CLIENT_BUSY, NOT_REMOTE, PREPARATION_UNAVAILABLE, CALLER_UNAVAILABLE, CONTROL_BUSY,
    CLOSING, OWNER_NOT_READY, FCM_BUSY, REVISION_CHANGED, ENVIRONMENT_NOT_READY,
    CLOCK_UNAVAILABLE, READ_UNAVAILABLE, READ_UNQUALIFIED, RECOVERY_UNAVAILABLE,
    PLANNER_DECLINED, DERIVATION_UNAVAILABLE,
}

internal data class DaemonFcmPreparationFailure(val reason: DaemonFcmPreparationReason,
    val readUnknown: GmsUnknown? = null) {
    init { require(readUnknown == null || reason == DaemonFcmPreparationReason.READ_UNAVAILABLE) }
}

/** Only created from an authenticated typed response or proof that the RPC was not submitted. */
internal class DaemonFcmNotStagedException(val failure: DaemonFcmPreparationFailure) :
    IllegalStateException("FCM activation not staged: ${failure.reason.name}")

internal data class DaemonFcmStageAttempt(val challenge: DaemonPointerChallenge?,
    val notStaged: DaemonFcmPreparationFailure?) {
    init { require(challenge == null || notStaged == null) }
}

/** Legacy/malformed/generic NOT_READY responses carry no proof of where staging stopped. */
internal fun decodeDaemonFcmNotStaged(error: String?, reason: String?, readUnknown: String?): DaemonFcmPreparationFailure? {
    if (error != "FCM_NOT_STAGED") return null
    val parsed = runCatching { DaemonFcmPreparationReason.valueOf(requireNotNull(reason)) }.getOrNull() ?: return null
    if (parsed in setOf(DaemonFcmPreparationReason.CLIENT_BUSY, DaemonFcmPreparationReason.NOT_REMOTE,
            DaemonFcmPreparationReason.PREPARATION_UNAVAILABLE)) return null
    val unknown = if (readUnknown == null) null else
        runCatching { GmsUnknown.valueOf(readUnknown) }.getOrNull() ?: return null
    if (unknown != null && parsed != DaemonFcmPreparationReason.READ_UNAVAILABLE) return null
    return DaemonFcmPreparationFailure(parsed, unknown)
}
