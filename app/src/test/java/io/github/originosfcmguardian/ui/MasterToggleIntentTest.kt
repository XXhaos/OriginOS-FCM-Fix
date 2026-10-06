package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class MasterToggleIntentTest {
    private fun actions(state: AutomaticProtectionState, canEnable: Boolean, checked: Boolean): List<String> =
        mutableListOf<String>().also { result ->
            dispatchAutomaticProtectionToggle(state, canEnable, checked,
                onEnable = { result += "on" }, onDisable = { result += "off" })
        }

    @Test fun secondOnDuringPendingStartNeverBecomesOff() {
        assertEquals(emptyList<String>(), actions(AutomaticProtectionState(busy = true), false, true))
    }

    @Test fun onDuringFailedStopPersistenceNeverBecomesOff() {
        assertEquals(emptyList<String>(), actions(AutomaticProtectionState(revocationSaveFailed = true), false, true))
    }

    @Test fun disallowedOnDoesNotDispatchEitherAction() {
        assertEquals(emptyList<String>(), actions(AutomaticProtectionState(), false, true))
    }

    @Test fun eligibleOnDispatchesOnce() {
        assertEquals(listOf("on"), actions(AutomaticProtectionState(), true, true))
    }

    @Test fun duplicateOnForAlreadyEnabledMasterDoesNothing() {
        assertEquals(emptyList<String>(), actions(AutomaticProtectionState(masterEnabled = true), true, true))
    }

    @Test fun switchAvailabilitySeparatesOnAdmissionFromExplicitOff() {
        for (master in listOf(false, true)) for (busy in listOf(false, true))
            for (failed in listOf(false, true)) for (eligible in listOf(false, true)) {
                assertEquals(master || (!busy && !failed && eligible), automaticProtectionToggleEnabled(
                    AutomaticProtectionState(masterEnabled = master, busy = busy, revocationSaveFailed = failed), eligible))
            }
    }

    @Test fun explicitOffRemainsAvailableRegardlessOfBusyOrPersistenceFailure() {
        for (busy in listOf(false, true)) for (failed in listOf(false, true)) {
            assertEquals(listOf("off"), actions(AutomaticProtectionState(masterEnabled = true,
                busy = busy, revocationSaveFailed = failed), false, false))
        }
    }
}
