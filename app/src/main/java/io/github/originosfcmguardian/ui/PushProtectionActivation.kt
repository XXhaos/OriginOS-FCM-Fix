package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
