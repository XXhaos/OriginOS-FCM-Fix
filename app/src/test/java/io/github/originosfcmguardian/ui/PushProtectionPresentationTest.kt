package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionConfirmationFailure
import org.junit.Assert.*
import org.junit.Test

class PushProtectionPresentationTest {
    private val ready = HomeUiState(recoveryFirmwareSupported = true,
        targetSelectionLoadState = HomeSourceLoadState.LOADED,
        stoppedRecovery = StoppedRecoveryUiState(loaded = true))

    @Test fun offRemainsReachableDuringWaitFailureAndAnInFlightCombinedCommand() {
        val on = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        listOf(on.copy(pushProtectionBusy = true),
            on.copy(automaticProtection = on.automaticProtection.copy(busy = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForPrivilege = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForReconciliation = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(waitingForService = true)),
            on.copy(automaticProtection = on.automaticProtection.copy(revocationSaveFailed = true)),
            on.copy(fcmProtection = FcmProtectionUiState(storageBlocked = true))).forEach { state ->
            assertTrue(pushProtectionToggleEnabled(state))
            var disabled = 0
            dispatchPushProtectionToggle(state, false, { fail("OFF must not request ON") }, { disabled++ })
            assertEquals(1, disabled)
        }
    }

    @Test fun rejectedOrDuplicateOnNeverDispatchesOffOrAnotherOn() {
        val blocked = listOf(ready.copy(pushProtectionBusy = true),
            ready.copy(automaticProtection = AutomaticProtectionState(busy = true)),
            ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true),
                fcmProtection = FcmProtectionUiState(configured = true)),
            ready.copy(fcmProtection = FcmProtectionUiState(busy = true)),
            ready.copy(fcmProtection = FcmProtectionUiState(maintenance = FcmMaintenanceState.STOP_PENDING)))
        blocked.forEach { state ->
            dispatchPushProtectionToggle(state, true, { fail("Blocked ON") }, { fail("Rejected ON is not OFF") })
        }
        var enabled = 0
        dispatchPushProtectionToggle(ready, true, { enabled++ }, { fail("ON must not request OFF") })
        assertEquals(1, enabled)
        assertTrue(ready.automaticProtection.configuredIdentities.isEmpty())
    }

    @Test fun unifiedSwitchRetainsPendingIntentAndAllowsOldIncompleteSetupToBeEnabled() {
        val on = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        assertFalse(pushProtectionRequested(on))
        var enabled = 0
        dispatchPushProtectionToggle(on, true, { enabled++ }, { fail("ON must not dispatch OFF") })
        assertEquals(1, enabled)
        val pending = on.copy(pushActivation = io.github.originosfcmguardian.pushactivation.PushActivationUiState(pending = true))
        assertTrue(pushProtectionRequested(pending))
        dispatchPushProtectionToggle(pending, true, { fail("Pending intent is not another opt-in") }, { fail("ON must not dispatch OFF") })
        var disabled = 0
        dispatchPushProtectionToggle(pending, false, { fail("OFF must not dispatch ON") }, { disabled++ })
        assertEquals(1, disabled)
        assertTrue(pushProtectionRequested(on.copy(fcmProtection = FcmProtectionUiState(
            configured = true, maintenance = FcmMaintenanceState.WAITING_HOST))))
        assertEquals("等待 Shizuku", pushProtectionStatus(on.copy(
            automaticProtection = on.automaticProtection.copy(waitingForPrivilege = true))))
    }

    @Test fun configuredUnavailableParentNeverOffersAnotherConfirmationOrClaimsRecovery() {
        val on = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true, enabled = true))
        val configured = FcmProtectionUiState(configured = true)
        listOf(configured.copy(requiresConfirmation = true, maintenance = FcmMaintenanceState.ACTIVE),
            configured.copy(storageBlocked = true), configured.copy(maintenance = FcmMaintenanceState.RECONFIRM),
            configured.copy(confirmationFailure = FcmProtectionConfirmationFailure.READ_UNAVAILABLE)).forEach { fcm ->
            val state = on.copy(fcmProtection = fcm)
            assertTrue(pushProtectionRequested(state))
            assertEquals(PushProtectionEnableAction.NONE, pushProtectionEnableAction(state))
            assertEquals("FCM 保护暂不可用", pushProtectionStatus(state))
            assertFalse(fcmProtectionGuidance(fcm).contains("重新确认"))
            assertFalse(fcmProtectionGuidance(fcm).contains("关闭后"))
        }
    }

    @Test fun resumingMasterWithAnExistingBlockedParentKeepsItsOriginalConsent() {
        val configured = ready.copy(fcmProtection = FcmProtectionUiState(configured = true,
            requiresConfirmation = true, storageBlocked = true, maintenance = FcmMaintenanceState.RECONFIRM))
        assertTrue(canStartPushProtection(configured))
        assertEquals(PushProtectionEnableAction.ENABLE_MASTER, pushProtectionEnableAction(configured))
        var enabled = 0
        dispatchPushProtectionToggle(configured, true, { enabled++ }, { fail("ON cannot dispatch OFF") })
        assertEquals(1, enabled)
        assertTrue(configured.fcmProtection.configured)
        assertTrue(configured.fcmProtection.requiresConfirmation)
        assertEquals(PushProtectionEnableAction.INITIAL_FCM_CONFIRMATION, pushProtectionEnableAction(ready))
        assertFalse(canStartPushProtection(configured.copy(fcmProtection = configured.fcmProtection.copy(
            maintenance = FcmMaintenanceState.STOP_PENDING))))
    }
}
