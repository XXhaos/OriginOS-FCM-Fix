package io.github.originosfcmguardian.core.gms

enum class GmsHeartbeatStatus { NOT_SENT, REQUESTED, UNKNOWN }
enum class GmsHeartbeatReason {
    INVALID_REQUEST, SHELL_UNAVAILABLE, WRONG_USER, IDENTITY_UNKNOWN, IDENTITY_CHANGED, SHARED_UID,
    OBSERVATION_UNKNOWN, REPORTED_DISCONNECTED, STALE_OBSERVATION, BUSY, TIMED_OUT, CANCELLED,
    BRIDGE_UNAVAILABLE, COMMAND_ACCEPTED, COMMAND_FAILED, OUTPUT_INCOMPLETE, MALFORMED_RESPONSE,
    SESSION_REVOKED, INTERVAL_NOT_ELAPSED, DUPLICATE_ATTEMPT, CALLER_UNAVAILABLE,
    /** The external execution fence declined before dispatch; fresh checks may retry later. */
    EXECUTION_NOT_READY,
}

/** Request outcome only: REQUESTED never asserts receiver delivery, protocol ACK or repair. */
data class GmsHeartbeatResult(val requestId: Long, val status: GmsHeartbeatStatus, val reason: GmsHeartbeatReason) {
    init {
        require((status == GmsHeartbeatStatus.REQUESTED) == (reason == GmsHeartbeatReason.COMMAND_ACCEPTED))
    }
    companion object {
        fun notSent(requestId: Long, reason: GmsHeartbeatReason) = GmsHeartbeatResult(requestId, GmsHeartbeatStatus.NOT_SENT, reason)
        fun unknown(requestId: Long, reason: GmsHeartbeatReason) = GmsHeartbeatResult(requestId, GmsHeartbeatStatus.UNKNOWN, reason)
    }
}

/** This first manual package only admits the connected case qualified on the current device. */
object GmsHeartbeatPolicy {
    fun rejection(observation: GmsConnectionObservation, nowElapsed: Long): GmsHeartbeatReason? {
        if (observation is GmsConnectionObservation.Unknown) return when (observation.reason) {
            GmsUnknown.WRONG_USER -> GmsHeartbeatReason.WRONG_USER
            GmsUnknown.SHELL_UNAVAILABLE -> GmsHeartbeatReason.SHELL_UNAVAILABLE
            GmsUnknown.IDENTITY_CHANGED -> GmsHeartbeatReason.IDENTITY_CHANGED
            GmsUnknown.IDENTITY_UNKNOWN -> GmsHeartbeatReason.IDENTITY_UNKNOWN
            GmsUnknown.SHARED_UID -> GmsHeartbeatReason.SHARED_UID
            GmsUnknown.TIMED_OUT -> GmsHeartbeatReason.TIMED_OUT
            GmsUnknown.CANCELLED -> GmsHeartbeatReason.CANCELLED
            else -> GmsHeartbeatReason.OBSERVATION_UNKNOWN
        }
        val sample = observation as GmsConnectionObservation.Sample
        val source = sample.gcmService
        if (source.userId != 0 || source.completeness != SourceCompleteness.COMPLETE ||
            source.state == GcmConnectionState.UNKNOWN || source.state == GcmConnectionState.SERVICE_ABSENT) return GmsHeartbeatReason.OBSERVATION_UNKNOWN
        val elapsed = source.elapsedRealtimeMillis ?: return GmsHeartbeatReason.OBSERVATION_UNKNOWN
        if (!sample.isFresh(nowElapsed) || nowElapsed < elapsed || nowElapsed - elapsed > 30_000) return GmsHeartbeatReason.STALE_OBSERVATION
        return if (source.state == GcmConnectionState.REPORTED_CONNECTED) null else GmsHeartbeatReason.REPORTED_DISCONNECTED
    }
}

object GmsHeartbeatCodec {
    const val BUNDLE_KEY = "gms_manual_heartbeat_v1"
    fun encode(result: GmsHeartbeatResult) = "1|${result.requestId}|${result.status}|${result.reason}"
    fun decode(raw: String?, expectedRequestId: Long): GmsHeartbeatResult = runCatching {
        require(expectedRequestId > 0 && raw != null && raw.length <= 160)
        val parts = raw.split('|')
        require(parts.size == 4 && parts[0] == "1" && parts[1] == expectedRequestId.toString())
        GmsHeartbeatResult(expectedRequestId, GmsHeartbeatStatus.valueOf(parts[2]), GmsHeartbeatReason.valueOf(parts[3]))
    }.getOrElse { GmsHeartbeatResult.unknown(expectedRequestId, GmsHeartbeatReason.MALFORMED_RESPONSE) }
}
