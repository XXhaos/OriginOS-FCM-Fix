package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autoprotection.waitingForNextAutomaticCheckState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import io.github.originosfcmguardian.recovery.TargetRecoveryCounts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppsProtectionPresentationTest {
    private val first = SelectedTargetIdentity("example.first", 10123, 1, listOf("a".repeat(64)))
    private val second = first.copy(packageName = "example.second", uid = 10124)
    private val firstApp = VisibleProtectionApp(first.packageName, "Zulu", first, true)
    private val secondApp = VisibleProtectionApp(second.packageName, "Alpha", second, true)
    private val ready = HomeUiState(visibleApps = listOf(firstApp, secondApp),
        visibleAppsLoadState = HomeSourceLoadState.LOADED, targetSelectionLoadState = HomeSourceLoadState.LOADED,
        recoveryFirmwareSupported = true, stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready"))
    private fun authority(vararg identities: SelectedTargetIdentity) = AutomaticProtectionState(masterEnabled = true, targets = identities.map {
        AutomaticProtectionTargetState(it, savedAuthorization = true, enabled = true)
    })

    @Test fun onlyExactCurrentSavedConsentIsProtectedAndRemainingAppsAreSorted() {
        val state = ready.copy(selectedTargets = listOf(SelectedTargetUiState(second, "Alpha")),
            automaticProtection = authority(first))
        val groups = protectionAppGroups(state, "")
        assertEquals(listOf(first), groups.protected.map { it.app.identity })
        assertEquals(listOf(second), groups.all.map { it.app.identity })
        assertFalse(targetAutomaticProtectionState(state.automaticProtection, second).savedAuthorization)
    }

    @Test fun transientWaitKeepsBothSelectionsInProtectedGroupAndShowsBriefWaitingStatus() {
        val waiting = waitingForNextAutomaticCheckState(authority(first, second), "等待重新检查")
        val state = ready.copy(automaticProtection = waiting)
        val groups = protectionAppGroups(state, "")
        assertEquals(setOf(first, second), groups.protected.map { it.app.identity }.toSet())
        assertTrue(groups.all.isEmpty())
        groups.protected.forEach { row ->
            assertTrue(targetAutomaticProtectionState(waiting, row.app.identity!!).savedAuthorization)
            assertEquals("等待恢复检测", protectionAppStatus(state, row))
        }
        val pending = state.copy(automaticProtection = waiting.copy(waitingForReconciliation = true))
        assertEquals("等待只读复检", protectionAppStatus(pending, groups.protected.first()))
    }

    @Test fun compactCountsUseExactAuthorizedInstallationEvenWhileWaitingForPrivilege() {
        val counts = TargetRecoveryCounts(today = 2, week = 7)
        val state = ready.copy(automaticProtection = AutomaticProtectionState(targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = true, waitingForPrivilege = true))),
            recoveryCounts = RecoveryCountsUiState(available = true, byIdentity = mapOf(first to counts)))
        assertEquals(counts, protectionAppRecoveryCounts(state, ProtectionAppRow(firstApp, true)))
        assertNull(protectionAppRecoveryCounts(state, ProtectionAppRow(secondApp, true)))
    }

    @Test fun compactCountsNeverBorrowPriorInstallationOrShowAnOldUnavailableRow() {
        val replacement = first.copy(firstInstallTime = 10)
        val state = ready.copy(automaticProtection = authority(replacement),
            recoveryCounts = RecoveryCountsUiState(available = true,
                byIdentity = mapOf(first to TargetRecoveryCounts(today = 2, week = 7))))
        assertNull(protectionAppRecoveryCounts(state, ProtectionAppRow(firstApp.copy(identity = replacement), true)))
        assertNull(protectionAppRecoveryCounts(state.copy(automaticProtection = authority(first)),
            ProtectionAppRow(firstApp, false)))
    }

    @Test fun compactCountsRequireSavedConsentAndAnAvailableExactSnapshotWithoutInventingZero() {
        val counts = TargetRecoveryCounts(today = 0, week = 0)
        val snapshot = RecoveryCountsUiState(available = true, byIdentity = mapOf(first to counts))
        val row = ProtectionAppRow(firstApp, true)
        assertNull(protectionAppRecoveryCounts(ready.copy(recoveryCounts = snapshot), row))
        assertNull(protectionAppRecoveryCounts(ready.copy(automaticProtection = AutomaticProtectionState(targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = false, enabled = true))), recoveryCounts = snapshot), row))
        val authorized = ready.copy(automaticProtection = authority(first), recoveryCounts = snapshot)
        assertEquals(counts, protectionAppRecoveryCounts(authorized, row))
        assertNull(protectionAppRecoveryCounts(authorized.copy(recoveryCounts = snapshot.copy(available = false)), row))
        assertNull(protectionAppRecoveryCounts(authorized.copy(recoveryCounts = snapshot.copy(byIdentity = emptyMap())), row))
        assertNull(protectionAppRecoveryCounts(authorized, row.copy(app = firstApp.copy(identity = null))))
    }

    @Test fun nameAndPackageSearchIsTrimmedCaseInsensitiveAndNeverDuplicatesGrantedRow() {
        val state = ready.copy(automaticProtection = authority(first))
        assertEquals(1, protectionAppGroups(state, " zULu ").protected.size)
        assertEquals(listOf(second), protectionAppGroups(state, " EXAMPLE.SEC ").all.map { it.app.identity })
        assertTrue(protectionAppGroups(state, "missing").all.isEmpty())
        assertEquals(2, protectionAppGroups(state, "").let { it.protected.size + it.all.size })
    }

    @Test fun replacementDoesNotBorrowOldGrantAndOldRowRemainsRevocable() {
        val replacement = first.copy(firstInstallTime = 10)
        val state = ready.copy(visibleApps = listOf(firstApp.copy(identity = replacement)),
            selectedTargets = listOf(SelectedTargetUiState(first, "Zulu")), automaticProtection = authority(first))
        val groups = protectionAppGroups(state, "")
        assertEquals(1, groups.protected.size)
        assertEquals(1, groups.all.size)
        assertFalse(canPrepareAppProtection(state, state.visibleApps.single()))
        val oldRow = groups.protected.single { !it.currentInstallation }
        assertTrue(targetAutomaticProtectionState(state.automaticProtection, oldRow.app.identity!!).savedAuthorization)
        assertEquals("已保留选择；安装身份变化，请取消后重新确认", protectionAppStatus(state, oldRow))
    }

    @Test fun unavailableSelectedRowsStayVisibleWithoutClaimingAVisibleInstallation() {
        val state = ready.copy(visibleApps = emptyList(), selectedTargets = listOf(SelectedTargetUiState(first, "Zulu")))
        val row = protectionAppGroups(state, "").all.single()
        assertFalse(row.currentInstallation)
        assertFalse(canPrepareAppProtection(state, row.app))
    }

    @Test fun historicalStoppedFailureNeverPromotesToProtectedOrAuthorized() {
        val state = ready.copy(stoppedRecovery = StoppedRecoveryUiState(loaded = true, recordIdentity = first,
            receiptOutcome = StoppedRecoveryOutcome.STILL_STOPPED))
        assertTrue(protectionAppGroups(state, "").protected.isEmpty())
        assertEquals("上次核对仍有停止限制；保护未开启，请查看详情",
            protectionAppStatus(state, ProtectionAppRow(firstApp, true)))
        assertFalse(targetAutomaticProtectionState(state.automaticProtection, first).savedAuthorization)
    }

    @Test fun directOnDoesNotRequireSelectionButPreservesSupportAndReadinessGates() {
        assertTrue(canPrepareAppProtection(ready, firstApp))
        assertTrue(ready.selectedTargets.isEmpty())
        assertFalse(canPrepareAppProtection(ready, firstApp.copy(supported = false)))
        assertFalse(canPrepareAppProtection(ready.copy(visibleAppsLoadState = HomeSourceLoadState.FAILED), firstApp))
        assertFalse(canPrepareAppProtection(ready.copy(automaticPreparationPackage = first.packageName), secondApp))
        assertFalse(canPrepareAppProtection(ready.copy(automaticConfirmationIdentity = first), secondApp))
        assertFalse(canPrepareAppProtection(ready.copy(stoppedRecovery = StoppedRecoveryUiState(loaded = true, needsReconcile = true)), firstApp))
        assertFalse(canPrepareAppProtection(ready.copy(targetSelectionStoreCorrupt = true), firstApp))
    }

    @Test fun requestProbeAndCancelLeaveSelectionAndConsentUntouched() = runBlocking {
        val gate = AppProtectionRequestGate()
        val token = gate.begin()!!
        assertNull(gate.begin()) // A rapid second row tap cannot replace this identity.
        val confirmation = probeAppProtectionConfirmation(first, { first }, { true }, { gate.current(token) })
        assertEquals(first, confirmation)
        gate.cancel()
        assertFalse(gate.current(token))
        assertNotEquals(token, gate.begin())
    }

    @Test fun cancelledOrReplacedRequestCannotOfferConfirmation() = runBlocking {
        val gate = AppProtectionRequestGate()
        val token = gate.begin()!!
        assertNull(probeAppProtectionConfirmation(first, { gate.cancel(); first }, { error("Late qualification") }, { gate.current(token) }))
        assertNull(probeAppProtectionConfirmation(first, { first.copy(firstInstallTime = 2) }, { true }, { true }))
        assertNull(probeAppProtectionConfirmation(first, { first }, { false }, { true }))
    }

    @Test fun confirmedEnrollmentReturnsOnlyCapturedFullIdentity() = runBlocking {
        var selections = emptyList<SelectedTargetIdentity>()
        var writes = 0
        val captured = prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { selections },
            { emptyList() }, { writes++; selections = listOf(it); true }, { true })
        assertEquals(first, captured)
        assertEquals(1, writes)
        assertEquals(listOf(first), selections)
    }

    @Test fun existingSelectionDoesNotNeedEnrollmentAndDoesNotBecomeConsent() = runBlocking {
        val captured = prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { listOf(first) },
            { emptyList() }, { error("No selection write needed") }, { true })
        assertEquals(first, captured)
        assertTrue(protectionAppGroups(ready.copy(selectedTargets = listOf(SelectedTargetUiState(first, "Zulu"))), "").protected.isEmpty())
    }

    @Test fun replacementOrCancelDuringEnrollmentDiscardsCapturedRequest() = runBlocking {
        var installed = first
        var selected = emptyList<SelectedTargetIdentity>()
        assertNull(prepareAppProtectionIdentity(first.packageName, first, { installed }, { true }, { selected },
            { emptyList() }, { selected = listOf(it); installed = first.copy(firstInstallTime = 2); true }, { true }))
        val gate = AppProtectionRequestGate()
        val token = gate.begin()!!
        selected = emptyList()
        assertNull(prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { selected },
            { emptyList() }, { selected = listOf(it); gate.cancel(); true }, { gate.current(token) }))
    }

    @Test fun unavailableSelectionNeverClaimsEmptyConfigurationOrSearchResult() {
        for (query in listOf("", "   ", "Telegram")) {
            assertEquals("应用选择记录暂不可用", protectedAppsEmptyLabel(false, query))
        }
    }

    @Test fun availableEmptySelectionDistinguishesNoConfigurationFromNoSearchMatch() {
        assertEquals("尚未选择保护应用", protectedAppsEmptyLabel(true, ""))
        assertEquals("尚未选择保护应用", protectedAppsEmptyLabel(true, "   "))
        assertEquals("没有匹配的已选择应用", protectedAppsEmptyLabel(true, "Telegram"))
    }

    @Test fun failedEnrollmentRemovedSelectionOrOldGrantCannotProceed() = runBlocking {
        assertNull(prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { emptyList() },
            { emptyList() }, { false }, { true }))
        assertNull(prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { emptyList() },
            { emptyList() }, { true }, { true }))
        assertNull(prepareAppProtectionIdentity(first.packageName, first, { first }, { true }, { emptyList() },
            { listOf(first.copy(firstInstallTime = 2)) }, { error("Must not replace old authorized installation") }, { true }))
    }
}
