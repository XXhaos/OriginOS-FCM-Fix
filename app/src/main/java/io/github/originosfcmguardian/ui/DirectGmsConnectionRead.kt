package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** UI-only read: no observation is forwarded to a heartbeat or recovery controller. */
internal suspend fun readDirectGmsConnection(
    attemptId: Long,
    timeoutMillis: Long = 12_000,
    read: suspend () -> GmsConnectionObservation,
): GmsConnectionObservation {
    require(attemptId > 0 && timeoutMillis > 0)
    return try {
        withTimeoutOrNull(timeoutMillis) { read() }
            ?: GmsConnectionObservation.Unknown(attemptId, GmsUnknown.TIMED_OUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GmsConnectionObservation.Unknown(attemptId, GmsUnknown.BRIDGE_UNAVAILABLE)
    }
}

/** Ignore unrelated old-network loss callbacks and identical initial capability delivery. */
internal fun gmsReadNetworkInvalidated(
    sampledKey: String?,
    eventNetworkHandle: Long,
    eventKey: String? = null,
    lost: Boolean = false,
): Boolean {
    if (sampledKey == null) return false
    if (sampledKey == "unknown") return true
    val sameNetwork = sampledKey.substringBefore(':') == eventNetworkHandle.toString()
    return if (lost) sameNetwork else !sameNetwork || eventKey != null && sampledKey != eventKey
}
