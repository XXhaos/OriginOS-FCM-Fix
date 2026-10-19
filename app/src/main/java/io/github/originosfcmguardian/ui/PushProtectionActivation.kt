package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PushProtectionEnableAction { INITIAL_FCM_CONFIRMATION, ENABLE_MASTER, NONE }

/** Existing FCM consent is resumed by its owner; an unavailable branch is never a new opt-in. */
internal fun pushProtectionEnableAction(state: HomeUiState): PushProtectionEnableAction = when {
    !state.fcmProtection.configured -> PushProtectionEnableAction.INITIAL_FCM_CONFIRMATION
    !state.automaticProtection.masterEnabled -> PushProtectionEnableAction.ENABLE_MASTER
    else -> PushProtectionEnableAction.NONE
}

/** Readiness for composing existing Home commands; this grants no execution authority. */
internal fun pushProtectionHostReady(state: HomeUiState): Boolean =
    state.automaticProtection.masterEnabled && state.automaticProtection.enabled &&
        !state.automaticProtection.busy && !state.automaticProtection.waitingForPrivilege &&
        !state.automaticProtection.waitingForService && !state.automaticProtection.waitingForReconciliation &&
        !state.automaticProtection.revocationSaveFailed && state.automaticProtection.configurationAvailable &&
        state.shizuku.status == ShizukuStatus.READY_SHELL && !state.fcmProtection.busy &&
        !state.fcmProtection.storageBlocked && state.fcmProtection.maintenance != FcmMaintenanceState.STOP_PENDING

/** The initial pre-command OFF snapshot is not a failed enable acknowledgement. */
internal suspend fun awaitPushProtectionHost(states: StateFlow<HomeUiState>, timeoutMillis: Long = 30_000): HomeUiState? =
    withTimeoutOrNull(timeoutMillis) {
        states.first { state -> pushProtectionHostReady(state) || !state.automaticProtection.busy &&
            (state.automaticProtection.revocationSaveFailed || state.automaticProtection.waitingForReconciliation) }
    }
