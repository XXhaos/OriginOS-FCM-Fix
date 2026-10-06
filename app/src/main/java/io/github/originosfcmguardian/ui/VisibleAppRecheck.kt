package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.RECIPIENT_READ_PARALLELISM
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext

/** Visibility and exact installation identity admit a normal PM read, never a recovery operation. */
internal fun canRecheckVisibleApp(state: HomeUiState, identity: SelectedTargetIdentity): Boolean =
    state.visibleAppsLoadState == HomeSourceLoadState.LOADED &&
        state.visibleApps.singleOrNull { it.packageName == identity.packageName }?.identity == identity &&
        identity !in state.targetReadInFlight && !state.detecting &&
        !targetAutomaticProtectionState(state.automaticProtection, identity).busy &&
        !(state.stoppedRecovery.busy && state.stoppedRecovery.activeIdentity == identity) &&
        state.automaticPreparationPackage != identity.packageName && state.automaticConfirmationIdentity != identity

internal fun canRecheckProtectionApp(state: HomeUiState, row: ProtectionAppRow): Boolean =
    !state.selectedAppsRecheckRunning && row.currentInstallation && row.app.identity?.let { canRecheckVisibleApp(state, it) } == true

/** Snapshot saved choices, not the search-filtered rows or historical attention list. */
internal fun selectedAppsRecheckTargets(state: HomeUiState): List<SelectedTargetIdentity> =
    if (!state.automaticProtection.configurationAvailable || state.visibleAppsLoadState != HomeSourceLoadState.LOADED)
        emptyList() else state.automaticProtection.configuredIdentities.filter { identity ->
            state.visibleApps.singleOrNull { it.packageName == identity.packageName }?.identity == identity
        }

internal fun canRecheckSelectedApps(state: HomeUiState): Boolean =
    !state.selectedAppsRecheckRunning && !state.detecting && state.targetReadInFlight.isEmpty() &&
        selectedAppsRecheckTargets(state).any { canRecheckVisibleApp(state, it) }

/** A bounded number of child jobs; finish this chunk before scheduling another. */
internal suspend fun <T> recheckSelectedAppsInChunks(targets: List<T>, read: suspend (T) -> Unit) {
    for (chunk in targets.chunked(RECIPIENT_READ_PARALLELISM)) {
        coroutineContext.ensureActive()
        coroutineScope { chunk.map { target -> async { read(target) } }.awaitAll() }
    }
}

/** A saved old installation must never present its old clear flag as the current app's result. */
internal fun protectionAppRecheckTarget(state: HomeUiState, row: ProtectionAppRow): SelectedTargetUiState? {
    val identity = row.app.identity ?: return null
    if (!row.currentInstallation) {
        return SelectedTargetUiState(identity, row.app.label, status = TargetAutostartStatus.IDENTITY_CHANGED)
    }
    val current = state.visibleApps.singleOrNull { it.packageName == identity.packageName }
    if (state.visibleAppsLoadState != HomeSourceLoadState.LOADED || current == null || current.identity != identity) {
        return SelectedTargetUiState(identity, row.app.label,
            stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN))
    }
    return state.selectedTargets.singleOrNull { it.identity == identity }
        ?: SelectedTargetUiState(identity, current.label, stoppedSnapshot = current.stoppedSnapshot)
}

/** Refresh invalidates displayed transient evidence but retains each running read's ownership. */
internal fun beginVisibleAppsRefresh(state: HomeUiState): HomeUiState = state.copy(
    visibleAppsLoadState = HomeSourceLoadState.LOADING,
    appsMessage = null,
    visibleApps = state.visibleApps.map { it.copy(stoppedSnapshot = TargetStoppedSnapshot()) },
)

/** A normal resume refreshes installed apps, not the user's in-flight/read results. */
internal fun retainRechecksForUnchangedInstallations(
    state: HomeUiState,
    loaded: List<VisibleProtectionApp>,
): List<VisibleProtectionApp> = loaded.map { app ->
    val previous = state.visibleApps.singleOrNull { it.packageName == app.packageName }
    if (app.identity != null && app.identity == previous?.identity)
        app.copy(stoppedSnapshot = previous.stoppedSnapshot) else app
}

internal fun applyVisibleAppStoppedSnapshot(
    state: HomeUiState,
    identity: SelectedTargetIdentity,
    capturedGeneration: Long,
    currentGeneration: Long,
    snapshot: TargetStoppedSnapshot,
): HomeUiState {
    if (capturedGeneration != currentGeneration || state.visibleAppsLoadState != HomeSourceLoadState.LOADED ||
        state.visibleApps.singleOrNull { it.packageName == identity.packageName }?.identity != identity) return state
    return state.copy(selectedTargets = state.selectedTargets.map { target ->
        if (target.identity == identity) target.copy(stoppedSnapshot = snapshot) else target
    }, visibleApps = state.visibleApps.map { app ->
        if (app.identity == identity) app.copy(stoppedSnapshot = snapshot) else app
    })
}

/** Cancellation discards the result, and the initiating read alone releases its in-flight marker. */
internal suspend fun readAndPublishVisibleAppRecheck(
    read: suspend () -> TargetStoppedSnapshot,
    publish: (TargetStoppedSnapshot) -> Unit,
    finish: () -> Unit,
) {
    try {
        val snapshot = read()
        coroutineContext.ensureActive()
        publish(snapshot)
    } finally {
        finish()
    }
}
