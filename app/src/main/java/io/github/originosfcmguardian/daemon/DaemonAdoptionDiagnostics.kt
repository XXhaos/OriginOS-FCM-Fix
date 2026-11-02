package io.github.originosfcmguardian.daemon

/** Fixed diagnostic categories only: no exception messages, caller identities, paths or payloads. */
enum class DaemonAdoptionStatus { NOT_ATTEMPTED, ATTEMPTING, FAILED, ADOPTED }
enum class DaemonAdoptionStage {
    NONE, READINESS, BIND, SERVICE_IDENTITY, PREFLIGHT, AUTHENTICATION, EXIT_HISTORY,
    POSTFLIGHT, MIGRATION_BARRIER, HANDOFF,
    HANDOFF_PREPARE, DRAIN_LOCAL_SERVICE, DRAIN_STOPPED, DRAIN_FCM, DRAIN_HEARTBEAT, DRAIN_ENDPOINTS,
    EXPORT_READ, EXPORT_VALIDATE, EXPORT_WRITE, BOOTSTRAP, IMPORT, ACTIVATE, POINTER_COMMIT, REFRESH,
}
enum class DaemonAdoptionReason {
    NONE, NOT_READY, PERMISSION_DENIED, PERMISSION_UNAVAILABLE, BIND_TIMEOUT, BIND_DISCONNECTED, BIND_FAILED,
    WRONG_SERVICE_UID, DEAD_BINDER, BUSY, TIMED_OUT, SECURITY_ERROR, INVALID_REQUEST,
    INVALID_RESPONSE, REMOTE_UNAVAILABLE, ENVIRONMENT_UNAVAILABLE, PRINCIPAL_UNAVAILABLE, PRINCIPAL_CHANGED,
    EXIT_HISTORY_UNAVAILABLE, CONFIGURATION_CHANGED, READINESS_CHANGED, STORAGE_UNAVAILABLE,
    CANCELLED, HANDOFF_UNAVAILABLE, DRAIN_UNCONFIRMED, EXPORT_UNAVAILABLE, BOOTSTRAP_UNAVAILABLE,
    IMPORT_UNAVAILABLE, ACTIVATION_UNAVAILABLE, POINTER_UNAVAILABLE, REFRESH_UNAVAILABLE, OWNER_CONFLICT,
}

data class DaemonAdoptionDiagnostics(
    val status: DaemonAdoptionStatus = DaemonAdoptionStatus.NOT_ATTEMPTED,
    val stage: DaemonAdoptionStage = DaemonAdoptionStage.NONE,
    val reason: DaemonAdoptionReason = DaemonAdoptionReason.NONE,
    val attemptCount: Long = 0,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastOutcomeAtEpochMillis: Long? = null,
)

internal class DaemonAdoptionException(val stage: DaemonAdoptionStage, val reason: DaemonAdoptionReason) :
    IllegalStateException("${stage.name}:${reason.name}")

internal fun daemonAdoptionStageFailureReason(stage: DaemonAdoptionStage,
    fallback: DaemonAdoptionReason = DaemonAdoptionReason.HANDOFF_UNAVAILABLE): DaemonAdoptionReason = when (stage) {
    DaemonAdoptionStage.DRAIN_LOCAL_SERVICE, DaemonAdoptionStage.DRAIN_STOPPED, DaemonAdoptionStage.DRAIN_FCM,
    DaemonAdoptionStage.DRAIN_HEARTBEAT, DaemonAdoptionStage.DRAIN_ENDPOINTS -> DaemonAdoptionReason.DRAIN_UNCONFIRMED
    DaemonAdoptionStage.EXPORT_READ, DaemonAdoptionStage.EXPORT_VALIDATE, DaemonAdoptionStage.EXPORT_WRITE -> DaemonAdoptionReason.EXPORT_UNAVAILABLE
    DaemonAdoptionStage.BOOTSTRAP -> DaemonAdoptionReason.BOOTSTRAP_UNAVAILABLE
    DaemonAdoptionStage.IMPORT -> DaemonAdoptionReason.IMPORT_UNAVAILABLE
    DaemonAdoptionStage.ACTIVATE -> DaemonAdoptionReason.ACTIVATION_UNAVAILABLE
    DaemonAdoptionStage.POINTER_COMMIT -> DaemonAdoptionReason.POINTER_UNAVAILABLE
    DaemonAdoptionStage.REFRESH -> DaemonAdoptionReason.REFRESH_UNAVAILABLE
    else -> fallback
}

/** The snapshot is observational RAM only; reading it cannot bind a service or alter retry admission. */
internal class DaemonAdoptionJournal {
    private var value = DaemonAdoptionDiagnostics()
    @Synchronized fun snapshot(): DaemonAdoptionDiagnostics = value
    @Synchronized fun begin(atMillis: Long) {
        require(atMillis > 0)
        value = value.copy(status = DaemonAdoptionStatus.ATTEMPTING, stage = DaemonAdoptionStage.PREFLIGHT,
            reason = DaemonAdoptionReason.NONE,
            attemptCount = if (value.attemptCount == Long.MAX_VALUE) Long.MAX_VALUE else value.attemptCount + 1,
            lastAttemptAtEpochMillis = atMillis, lastOutcomeAtEpochMillis = null)
    }
    @Synchronized fun failed(stage: DaemonAdoptionStage, reason: DaemonAdoptionReason, atMillis: Long) {
        require(stage != DaemonAdoptionStage.NONE && reason != DaemonAdoptionReason.NONE && atMillis > 0)
        value = value.copy(status = DaemonAdoptionStatus.FAILED, stage = stage, reason = reason,
            lastOutcomeAtEpochMillis = atMillis)
    }
    @Synchronized fun progress(stage: DaemonAdoptionStage) {
        require(stage != DaemonAdoptionStage.NONE)
        value = value.copy(status = DaemonAdoptionStatus.ATTEMPTING, stage = stage,
            reason = DaemonAdoptionReason.NONE, lastOutcomeAtEpochMillis = null)
    }
    @Synchronized fun adopted(atMillis: Long) {
        require(atMillis > 0)
        value = value.copy(status = DaemonAdoptionStatus.ADOPTED, stage = DaemonAdoptionStage.HANDOFF,
            reason = DaemonAdoptionReason.NONE, lastOutcomeAtEpochMillis = atMillis)
    }
}

/** Only these service-owned preflight stages may cross the diagnostic wire. */
internal fun daemonPreflightFailure(stage: String?, reason: String?): DaemonAdoptionException? {
    val parsedStage = runCatching { DaemonAdoptionStage.valueOf(requireNotNull(stage)) }.getOrNull() ?: return null
    val parsedReason = runCatching { DaemonAdoptionReason.valueOf(requireNotNull(reason)) }.getOrNull() ?: return null
    val valid = when (parsedStage) {
        DaemonAdoptionStage.SERVICE_IDENTITY -> parsedReason == DaemonAdoptionReason.WRONG_SERVICE_UID
        DaemonAdoptionStage.AUTHENTICATION -> parsedReason in setOf(DaemonAdoptionReason.ENVIRONMENT_UNAVAILABLE,
            DaemonAdoptionReason.PRINCIPAL_UNAVAILABLE, DaemonAdoptionReason.PRINCIPAL_CHANGED,
            DaemonAdoptionReason.PERMISSION_DENIED, DaemonAdoptionReason.PERMISSION_UNAVAILABLE)
        DaemonAdoptionStage.EXIT_HISTORY -> parsedReason == DaemonAdoptionReason.EXIT_HISTORY_UNAVAILABLE
        else -> false
    }
    return if (valid) DaemonAdoptionException(parsedStage, parsedReason) else null
}
