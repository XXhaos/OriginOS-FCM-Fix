package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.ProtectionNotificationVisibility
import io.github.originosfcmguardian.autoprotection.guardianTilePresentation
import io.github.originosfcmguardian.autoprotection.waitingForNextAutomaticCheckState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.*
import org.junit.Test

class AutomaticProtectionPresentationTest {
    private val target = SelectedTargetUiState(SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64))), "Telegram")
    private val ready = HomeUiState(selectedTargets = listOf(target), recoveryFirmwareSupported = true,
        targetSelectionLoadState = HomeSourceLoadState.LOADED, stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready"))

    @Test fun notificationLabelsDescribeSettingsWithoutClaimingDrawerPresence() {
        assertEquals("保护通知：允许显示", ProtectionNotificationVisibility.ALLOWED.label())
        assertEquals("保护通知：系统已关闭", ProtectionNotificationVisibility.BLOCKED.label())
        assertEquals("保护通知：状态未知", ProtectionNotificationVisibility.UNKNOWN.label())
    }

    @Test fun notificationVisibilityDoesNotChangeConsentEligibility() {
        ProtectionNotificationVisibility.entries.forEach { visibility ->
            val state = ready.copy(protectionNotificationVisibility = visibility)
            assertTrue(canEnableAutomaticProtection(state))
            assertTrue(canEnableTargetAutomaticProtection(state, target.identity))
            assertFalse(canEnableTargetAutomaticProtection(state.copy(recoveryFirmwareSupported = false), target.identity))
            val authorized = AutomaticProtectionState(savedAuthorization = true, targets = listOf(
                AutomaticProtectionTargetState(target.identity, savedAuthorization = true)))
            assertFalse(canEnableTargetAutomaticProtection(state.copy(automaticProtection = authorized), target.identity))
            assertTrue(targetAutomaticProtectionState(state.copy(automaticProtection = authorized).automaticProtection,
                target.identity).savedAuthorization)
        }
    }

    @Test fun consentEntryRequiresCurrentTargetAndShell() {
        assertTrue(canEnableAutomaticProtection(ready))
        assertTrue(canEnableAutomaticProtection(ready.copy(selectedTargets = emptyList())))
        assertTrue(canEnableAutomaticProtection(ready.copy(shizuku = ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "off"))))
        listOf(ready.copy(recoveryFirmwareSupported = false),
            ready.copy(targetSelectionLoadState = HomeSourceLoadState.LOADING),
            ready.copy(targetSelectionStoreCorrupt = true)
        ).forEach { assertFalse(canEnableAutomaticProtection(it)) }
    }

    @Test fun unresolvedBusyOrBlockedRecordNeverOffersEnable() {
        listOf(StoppedRecoveryUiState(),
            StoppedRecoveryUiState(loaded = true, storageBlocked = true),
            StoppedRecoveryUiState(loaded = true, busy = true)).forEach {
            assertFalse(canEnableAutomaticProtection(ready.copy(stoppedRecovery = it)))
        }
        assertTrue(canEnableAutomaticProtection(ready.copy(targetReadInFlight = setOf(target.identity))))
        assertFalse(canEnableAutomaticProtection(ready.copy(automaticProtection = ready.automaticProtection.copy(masterEnabled = true))))
        assertFalse(canEnableAutomaticProtection(ready.copy(automaticProtection = ready.automaticProtection.copy(busy = true))))
        assertFalse(canEnableAutomaticProtection(ready.copy(automaticProtection = ready.automaticProtection.copy(revocationSaveFailed = true))))
    }

    @Test fun anotherWatchedAppIsNotGrantedByExistingTelegramConsent() {
        val other = target.copy(identity = target.identity.copy(packageName = "example.other", uid = 10435), label = "Other")
        val active = AutomaticProtectionState(enabled = true, savedAuthorization = true,
            targets = listOf(AutomaticProtectionTargetState(identity = target.identity, savedAuthorization = true, enabled = true)))
        val state = ready.copy(selectedTargets = listOf(target, other), automaticProtection = active)
        assertFalse(canEnableTargetAutomaticProtection(state, target.identity))
        assertTrue(canEnableTargetAutomaticProtection(state, other.identity))
        assertTrue(targetAutomaticProtectionState(active, target.identity).enabled)
        assertFalse(targetAutomaticProtectionState(active, other.identity).enabled)
        assertFalse(targetAutomaticProtectionState(active, other.identity).savedAuthorization)
        assertFalse(canEnableTargetAutomaticProtection(state.copy(automaticProtection = active.copy(busy = true)), other.identity))
    }

    @Test fun changedIdentityDoesNotDisplaySavedProtectionFromPriorInstallation() {
        val active = AutomaticProtectionState(savedAuthorization = true,
            targets = listOf(AutomaticProtectionTargetState(identity = target.identity, savedAuthorization = true)))
        val replaced = target.identity.copy(firstInstallTime = 200)
        assertFalse(targetAutomaticProtectionState(active, replaced).savedAuthorization)
        assertTrue(targetAutomaticProtectionState(active, target.identity).savedAuthorization)
    }

    @Test fun readonlyWaitingRetainsToggleConsentAndBlocksEnrollmentWithoutClaimingDetection() {
        val waiting = AutomaticProtectionState(masterEnabled = true, savedAuthorization = true, waitingForReconciliation = true,
            detail = "全部自动恢复写入已阻止", targets = listOf(
                AutomaticProtectionTargetState(target.identity, savedAuthorization = true, detail = "等待只读复检")))
        val shown = targetAutomaticProtectionState(waiting, target.identity)
        assertTrue(shown.savedAuthorization)
        assertTrue(shown.waitingForReconciliation)
        assertFalse(shown.enabled)
        assertEquals("等待只读复检", automaticProtectionStatusLabel(shown))
        assertFalse(canEnableAutomaticProtection(ready.copy(automaticProtection = waiting)))
        val normal = waiting.copy(waitingForReconciliation = false, enabled = true)
        assertEquals("检测已开启", automaticProtectionStatusLabel(normal))
    }

    @Test fun transientWaitKeepsApplicationsProtectedButDoesNotClaimActiveDetection() {
        val other = target.identity.copy(packageName = "example.recipient", uid = 10435)
        val previous = AutomaticProtectionState(enabled = true, savedAuthorization = true, targets = listOf(
            AutomaticProtectionTargetState(target.identity, savedAuthorization = true, enabled = true),
            AutomaticProtectionTargetState(other, savedAuthorization = true, enabled = true)))
        val waiting = waitingForNextAutomaticCheckState(previous, "等待重新检查，保护选择已保留")
        assertEquals(previous.authorizedIdentities, waiting.authorizedIdentities)
        waiting.targets.forEach { row ->
            val shown = targetAutomaticProtectionState(waiting, row.identity)
            assertTrue(shown.savedAuthorization)
            assertFalse(shown.enabled)
            assertEquals("等待恢复检测", automaticProtectionStatusLabel(shown))
        }
        assertFalse(guardianTilePresentation(waiting, actuallyProtecting = true).active)
        assertEquals(waiting.detail, guardianTilePresentation(waiting, actuallyProtecting = false).subtitle)
        assertEquals("检测未开启", automaticProtectionStatusLabel(AutomaticProtectionState()))
        assertEquals("检测已开启", automaticProtectionStatusLabel(previous))
    }
}
