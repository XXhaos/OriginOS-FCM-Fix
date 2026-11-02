package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.ui.HomeUiState

enum class DiagnosticFcmObservationKind { NONE, UNKNOWN, SAMPLE }

/** Fixed scalar evidence only. Installation, caller, socket and network identities are absent. */
data class DiagnosticFcmObservation(
    val kind: DiagnosticFcmObservationKind = DiagnosticFcmObservationKind.NONE,
    val unknownReason: GmsUnknown? = null,
    val gmsVersionCode: Long? = null,
    val sampleObservedAtEpochMillis: Long? = null,
    val sampleElapsedRealtimeMillis: Long? = null,
    val serviceState: GcmConnectionState? = null,
    val serviceCompleteness: SourceCompleteness? = null,
    val serviceObservedAtEpochMillis: Long? = null,
    val serviceElapsedRealtimeMillis: Long? = null,
    val connectedDurationMillis: Long? = null,
)

/** Existing Home memory, not a fresh read, selected display source or proof of FCM maintenance. */
data class DiagnosticFcmConnectionRead(
    val attempted: Boolean = false,
    val busy: Boolean = false,
    val readCompletedAtElapsedMillis: Long? = null,
    val networkChanged: Boolean = false,
    val networkTrackingAvailable: Boolean = false,
    val networkKnown: Boolean = false,
    val foreground: DiagnosticFcmObservation = DiagnosticFcmObservation(),
    val automatic: DiagnosticFcmObservation = DiagnosticFcmObservation(),
)

internal fun diagnosticFcmConnectionRead(state: HomeUiState): DiagnosticFcmConnectionRead {
    val foreground = state.gmsConnection
    return DiagnosticFcmConnectionRead(
        attempted = foreground.readAttempted || foreground.observation != null,
        busy = foreground.busy,
        readCompletedAtElapsedMillis = foreground.readCompletedAtElapsedMillis,
        networkChanged = foreground.networkChanged,
        networkTrackingAvailable = foreground.networkTrackingAvailable,
        networkKnown = !foreground.networkKey.isNullOrEmpty() && foreground.networkKey != "unknown",
        foreground = diagnosticFcmObservation(foreground.observation),
        automatic = diagnosticFcmObservation(state.fcmProtection.latestObservation),
    )
}

private fun diagnosticFcmObservation(observation: GmsConnectionObservation?): DiagnosticFcmObservation = when (observation) {
    null -> DiagnosticFcmObservation()
    is GmsConnectionObservation.Unknown -> DiagnosticFcmObservation(
        kind = DiagnosticFcmObservationKind.UNKNOWN, unknownReason = observation.reason)
    is GmsConnectionObservation.Sample -> with(observation.gcmService) {
        DiagnosticFcmObservation(
            kind = DiagnosticFcmObservationKind.SAMPLE,
            gmsVersionCode = observation.versionCode,
            sampleObservedAtEpochMillis = observation.observedAtMillis,
            sampleElapsedRealtimeMillis = observation.elapsedRealtimeMillis,
            serviceState = state,
            serviceCompleteness = completeness,
            serviceObservedAtEpochMillis = observedAtMillis,
            serviceElapsedRealtimeMillis = elapsedRealtimeMillis,
            connectedDurationMillis = connectedDurationMillis,
        )
    }
}
