package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState

/** Fixed Home memory flags only; no live authority check, request count or result timestamp. */
data class DiagnosticHeartbeatState(
    val configured: Boolean = false,
    val active: Boolean = false,
    val paused: Boolean = false,
    val stopPending: Boolean = false,
    val durableConsent: Boolean = false,
    val resumeAuthorized: Boolean = false,
    val waitingForHost: Boolean = false,
    val resumeBlocked: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val lastStatus: GmsHeartbeatStatus? = null,
)

internal fun diagnosticHeartbeatState(state: GmsAutoHeartbeatUiState) = DiagnosticHeartbeatState(
    configured = state.configured,
    active = state.active,
    paused = state.paused,
    stopPending = state.stopPending,
    durableConsent = state.durableConsent,
    resumeAuthorized = state.resumeAuthorized,
    waitingForHost = state.waitingForHost,
    resumeBlocked = state.resumeBlocked,
    requiresConfirmation = state.requiresConfirmation,
    lastStatus = state.lastStatus,
)

internal const val DIAGNOSTIC_HEARTBEAT_SCOPE = "CURRENT_HOME_MEMORY_PROJECTION"
internal const val DIAGNOSTIC_HEARTBEAT_LIMITATIONS =
    "Current Home memory only; no new read, timestamp or request count. " +
        "lastStatus may describe a past request and does not prove delivery or continuous maintenance."
