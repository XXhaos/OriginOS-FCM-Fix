package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.TargetRecoveryCounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisibleAppRecheckTest {
    private val first = SelectedTargetIdentity("example.recheck.first", 10123, 1, listOf("a".repeat(64)))
    private val second = first.copy(packageName = "example.recheck.second", uid = 10124)
    private val snapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20)
    private val app = VisibleProtectionApp(first.packageName, "First", first, supported = false)
    private val ready = HomeUiState(visibleApps = listOf(app, app.copy(packageName = second.packageName, label = "Second", identity = second)),
        visibleAppsLoadState = HomeSourceLoadState.LOADED,
        shizuku = ShizukuStatusSnapshot(ShizukuStatus.ERROR, "No Shizuku"))

    @Test fun unselectedUnsupportedVisibleAppCanReadWithoutPrivilegeConsentOrSelection() {
        assertTrue(ready.selectedTargets.isEmpty())
        assertFalse(ready.recoveryFirmwareSupported)
        assertFalse(app.supported)
        assertTrue(canRecheckVisibleApp(ready, first))
        assertTrue(canRecheckProtectionApp(ready, ProtectionAppRow(app, true)))
        val target = protectionAppRecheckTarget(ready, ProtectionAppRow(app, true))!!
        assertEquals(first, target.identity)
        assertEquals(TargetStoppedStatus.NOT_CHECKED, target.stoppedSnapshot.status)
        assertFalse(targetAutomaticProtectionState(ready.automaticProtection, first).savedAuthorization)
    }

    @Test fun admissionRequiresLoadedUniqueExactCurrentInstallation() {
        listOf(HomeSourceLoadState.LOADING, HomeSourceLoadState.FAILED).forEach {
            assertFalse(canRecheckVisibleApp(ready.copy(visibleAppsLoadState = it), first))
        }
        assertFalse(canRecheckVisibleApp(ready.copy(visibleApps = emptyList()), first))
        assertFalse(canRecheckVisibleApp(ready.copy(visibleApps = listOf(app.copy(identity = null))), first))
        assertFalse(canRecheckVisibleApp(ready.copy(visibleApps = listOf(app, app)), first))
        val variants = listOf(first.copy(uid = 10125), first.copy(firstInstallTime = 2),
            first.copy(signerSha256 = listOf("b".repeat(64))))
        variants.forEach { changed -> assertFalse(canRecheckVisibleApp(ready, changed)) }
        assertFalse(canRecheckProtectionApp(ready, ProtectionAppRow(app, false)))
    }

    @Test fun duplicateOrConflictingTargetOperationIsBlockedWithoutBlockingOtherTargets() {
        assertFalse(canRecheckVisibleApp(ready.copy(targetReadInFlight = setOf(first)), first))
        assertTrue(canRecheckVisibleApp(ready.copy(targetReadInFlight = setOf(second)), first))
        assertFalse(canRecheckVisibleApp(ready.copy(detecting = true), first))
        fun recovering(identity: SelectedTargetIdentity) = ready.copy(stoppedRecovery = StoppedRecoveryUiState(
            busy = true, activeIdentity = identity))
        assertFalse(canRecheckVisibleApp(recovering(first), first))
        assertTrue(canRecheckVisibleApp(recovering(second), first))
        val busy = ready.copy(automaticProtection = AutomaticProtectionState(targets = listOf(
            AutomaticProtectionTargetState(first, busy = true))))
        assertFalse(canRecheckVisibleApp(busy, first))
        assertTrue(canRecheckVisibleApp(busy, second))
        assertFalse(canRecheckVisibleApp(ready.copy(automaticPreparationPackage = first.packageName), first))
        assertFalse(canRecheckVisibleApp(ready.copy(automaticConfirmationIdentity = first), first))
    }

    @Test fun normalReadPublishesTransientOnlyAndNeverAddsSelectionOrAuthorization() = runBlocking {
        val selected = SelectedTargetUiState(second, "Second")
        var state = ready.copy(selectedTargets = listOf(selected), targetReadInFlight = setOf(first, second),
            automaticProtection = AutomaticProtectionState(targets = listOf(
                AutomaticProtectionTargetState(second, savedAuthorization = true, enabled = true))),
            stoppedRecovery = StoppedRecoveryUiState(loaded = true, recordIdentity = second,
                receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED),
            recoveryCounts = RecoveryCountsUiState(available = true, byIdentity = mapOf(second to TargetRecoveryCounts(2, 4))))
        val original = state
        var reads = 0
        readAndPublishVisibleAppRecheck(
            read = { reads++; snapshot },
            publish = { state = applyVisibleAppStoppedSnapshot(state, first, 4, 4, it) },
            finish = { state = state.copy(targetReadInFlight = state.targetReadInFlight - first) },
        )
        assertEquals(1, reads)
        assertEquals(snapshot, state.visibleApps.first().stoppedSnapshot)
        assertEquals(original.selectedTargets, state.selectedTargets)
        assertEquals(original.automaticProtection, state.automaticProtection)
        assertEquals(original.stoppedRecovery, state.stoppedRecovery)
        assertEquals(original.recoveryCounts, state.recoveryCounts)
        assertEquals(original.targetSelection, state.targetSelection)
        assertEquals(original.targetSelectionLoadState, state.targetSelectionLoadState)
        assertEquals(original.targetReadAttemptCount, state.targetReadAttemptCount)
        assertEquals(setOf(second), state.targetReadInFlight)
        assertTrue(protectionAppGroups(state, "First").protected.isEmpty())
        assertFalse(targetAutomaticProtectionState(state.automaticProtection, first).savedAuthorization)
    }

    @Test fun exactSelectedResultTakesPrecedenceAndReplacementCannotBorrowIt() {
        val selected = SelectedTargetUiState(first, "Selected", stoppedSnapshot = snapshot)
        val state = ready.copy(selectedTargets = listOf(selected),
            visibleApps = listOf(app.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 10))))
        assertSame(selected, protectionAppRecheckTarget(state, ProtectionAppRow(app, true)))
        val replacement = first.copy(firstInstallTime = 2)
        val replacementApp = app.copy(identity = replacement)
        val replacedState = state.copy(visibleApps = listOf(replacementApp))
        val target = protectionAppRecheckTarget(replacedState, ProtectionAppRow(replacementApp, true))!!
        assertEquals(replacement, target.identity)
        assertEquals(TargetStoppedStatus.NOT_CHECKED, target.stoppedSnapshot.status)
    }

    @Test fun selectedAppRecheckUpdatesOnlyItsSnapshotAndPreservesSettingsAndOemEvidence() {
        val selected = SelectedTargetUiState(first, "Selected", status = TargetAutostartStatus.ALLOWED)
        val state = ready.copy(selectedTargets = listOf(selected))
        val updated = applyVisibleAppStoppedSnapshot(state, first, 3, 3, snapshot)
        assertEquals(selected.copy(stoppedSnapshot = snapshot), updated.selectedTargets.single())
        assertEquals(snapshot, protectionAppRecheckTarget(updated, ProtectionAppRow(app, true))!!.stoppedSnapshot)
        assertEquals(state.automaticProtection, updated.automaticProtection)
        assertEquals(state.targetSelection, updated.targetSelection)
        assertEquals(state.stoppedRecovery, updated.stoppedRecovery)
        val cleared = applyVisibleAppStoppedSnapshot(updated, first, 3, 3, TargetStoppedSnapshot())
        assertEquals(TargetStoppedStatus.NOT_CHECKED, cleared.selectedTargets.single().stoppedSnapshot.status)
    }

    @Test fun oldInstallationNeverDisplaysSavedClearFlagOrChecksReplacement() {
        val state = ready.copy(selectedTargets = listOf(SelectedTargetUiState(first, "Old", stoppedSnapshot = snapshot)))
        val row = ProtectionAppRow(app.copy(stoppedSnapshot = snapshot), false)
        val target = protectionAppRecheckTarget(state, row)!!
        assertEquals(TargetAutostartStatus.IDENTITY_CHANGED, target.status)
        assertEquals(TargetStoppedStatus.NOT_CHECKED, target.stoppedSnapshot.status)
        assertEquals(TargetStatusTone.UNCERTAIN, appRecheckResult(target, false).tone)
        assertFalse(canRecheckProtectionApp(state, row))
    }

    @Test fun missingIdentityAndUnloadedListNeverDisplayHealthyEvidence() {
        val missing = ProtectionAppRow(app.copy(identity = null, stoppedSnapshot = snapshot), true)
        assertNull(protectionAppRecheckTarget(ready, missing))
        assertFalse(canRecheckProtectionApp(ready, missing))
        assertEquals("无法读取应用，请刷新", protectionAppStatus(ready, missing))
        val healthy = ready.copy(selectedTargets = listOf(SelectedTargetUiState(first, "First", stoppedSnapshot = snapshot)),
            visibleApps = listOf(app.copy(stoppedSnapshot = snapshot)))
        listOf(HomeSourceLoadState.LOADING, HomeSourceLoadState.FAILED).forEach { load ->
            val target = protectionAppRecheckTarget(healthy.copy(visibleAppsLoadState = load), ProtectionAppRow(app, true))!!
            assertEquals(TargetStoppedStatus.UNKNOWN, target.stoppedSnapshot.status)
            assertEquals(TargetStatusTone.UNCERTAIN, appRecheckResult(target, false).tone)
        }
    }

    @Test fun allReadOutcomesStayTypedAndDoNotImplyDelivery() {
        TargetStoppedStatus.entries.forEach { status ->
            val stopped = TargetStoppedSnapshot(status, 50)
            val applied = applyVisibleAppStoppedSnapshot(ready, first, 7, 7, stopped)
            val target = protectionAppRecheckTarget(applied, ProtectionAppRow(app, true))!!
            assertEquals(stopped, target.stoppedSnapshot)
            assertFalse(appRecheckResult(target, false).text.contains("通知已送达"))
        }
    }

    @Test fun refreshClearsTransientEvidenceButKeepsOldReadMarkerUntilCompletion() {
        val started = ready.copy(visibleApps = listOf(app.copy(stoppedSnapshot = snapshot)), targetReadInFlight = setOf(first))
        val loading = beginVisibleAppsRefresh(started)
        assertEquals(HomeSourceLoadState.LOADING, loading.visibleAppsLoadState)
        assertEquals(TargetStoppedSnapshot(), loading.visibleApps.single().stoppedSnapshot)
        assertEquals(setOf(first), loading.targetReadInFlight)
        val failed = loading.copy(visibleAppsLoadState = HomeSourceLoadState.FAILED)
        assertEquals(TargetStoppedSnapshot(), failed.visibleApps.single().stoppedSnapshot)
        val reloaded = loading.copy(visibleAppsLoadState = HomeSourceLoadState.LOADED)
        assertFalse(canRecheckVisibleApp(reloaded, first))
        assertSame(reloaded, applyVisibleAppStoppedSnapshot(reloaded, first, 1, 2, snapshot))
    }

    @Test fun resumeRefreshPreservesCompletedAndInFlightResultsButNotReplacementEvidence() {
        val previous = ready.copy(visibleApps = listOf(app.copy(stoppedSnapshot = snapshot)), targetReadInFlight = setOf(first))
        val refreshed = retainRechecksForUnchangedInstallations(previous, listOf(app.copy(label = "New label")))
        assertEquals(snapshot, refreshed.single().stoppedSnapshot)
        assertEquals("New label", refreshed.single().label)
        assertEquals(setOf(first), previous.targetReadInFlight)
        val replacement = app.copy(identity = first.copy(firstInstallTime = 99))
        assertEquals(TargetStoppedSnapshot(), retainRechecksForUnchangedInstallations(previous, listOf(replacement)).single().stoppedSnapshot)
        assertEquals(TargetStoppedSnapshot(), retainRechecksForUnchangedInstallations(previous, listOf(app.copy(identity = null))).single().stoppedSnapshot)
    }

    @Test fun lateResultsCannotPublishAcrossGenerationLoadFailureRemovalOrReplacement() {
        assertSame(ready, applyVisibleAppStoppedSnapshot(ready, first, 1, 2, snapshot))
        listOf(
            ready.copy(visibleAppsLoadState = HomeSourceLoadState.LOADING),
            ready.copy(visibleAppsLoadState = HomeSourceLoadState.FAILED),
            ready.copy(visibleApps = emptyList()),
            ready.copy(visibleApps = listOf(app.copy(identity = first.copy(firstInstallTime = 2)))),
            ready.copy(visibleApps = listOf(app.copy(identity = null))),
        ).forEach { state -> assertSame(state, applyVisibleAppStoppedSnapshot(state, first, 2, 2, snapshot)) }
    }

    @Test fun cancelledReadNeverPublishesAndReleasesOnlyItsOwnMarker() = runBlocking {
        var state = ready.copy(targetReadInFlight = setOf(first, second))
        var published = false
        try {
            readAndPublishVisibleAppRecheck(
                read = { throw CancellationException("cancel fixture") },
                publish = { published = true },
                finish = { state = state.copy(targetReadInFlight = state.targetReadInFlight - first) },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(published)
        assertEquals(setOf(second), state.targetReadInFlight)
        assertEquals(TargetStoppedSnapshot(), state.visibleApps.first().stoppedSnapshot)
        assertTrue(state.selectedTargets.isEmpty())
        assertTrue(state.automaticProtection.authorizedIdentities.isEmpty())
    }

    @Test fun cancellationAfterReadBeforePublicationDiscardsSampleAndStillFinishes() = runBlocking {
        var published = false
        var finished = false
        val job = launch {
            readAndPublishVisibleAppRecheck(
                read = { currentCoroutineContext().cancel(); snapshot },
                publish = { published = true },
                finish = { finished = true },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertTrue(finished)
    }
}
