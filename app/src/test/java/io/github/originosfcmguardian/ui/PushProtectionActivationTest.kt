package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PushProtectionActivationTest {
    private fun ready() = HomeUiState(
        shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready"),
        automaticProtection = AutomaticProtectionState(masterEnabled = true, enabled = true))

    @Test fun initialOffIsNotMistakenForARejectedAsynchronousEnable() = runBlocking {
        val states = MutableStateFlow(HomeUiState())
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitPushProtectionHost(states, 1_000) }
        assertFalse(waiting.isCompleted)
        states.value = states.value.copy(automaticProtection = AutomaticProtectionState(busy = true))
        yield()
        assertFalse(waiting.isCompleted)
        states.value = ready().copy(automaticProtection = ready().automaticProtection.copy(busy = true))
        yield()
        assertFalse(waiting.isCompleted)
        states.value = ready()
        assertSame(states.value, waiting.await())
    }

    @Test fun temporaryPrivilegeLossOrMissingHostDoesNotAcknowledgeReadiness() {
        val good = ready()
        listOf(good.copy(shizuku = ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "waiting")),
            good.copy(automaticProtection = good.automaticProtection.copy(waitingForPrivilege = true)),
            good.copy(automaticProtection = good.automaticProtection.copy(waitingForService = true)),
            good.copy(automaticProtection = good.automaticProtection.copy(configurationAvailable = false)),
            good.copy(fcmProtection = FcmProtectionUiState(busy = true)),
            good.copy(fcmProtection = FcmProtectionUiState(storageBlocked = true)),
            good.copy(fcmProtection = FcmProtectionUiState(maintenance = FcmMaintenanceState.STOP_PENDING)))
            .forEach { assertFalse(pushProtectionHostReady(it)) }
        assertTrue(pushProtectionHostReady(good))
    }

    @Test fun timeoutDoesNotRevokeSavedMasterOrInventFcmConsent() = runBlocking {
        val initial = ready().copy(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        val states = MutableStateFlow(initial)
        assertNull(awaitPushProtectionHost(states, 20))
        assertSame(initial, states.value)
        assertTrue(states.value.automaticProtection.masterEnabled)
        assertFalse(states.value.fcmProtection.configured)
    }

    @Test fun cancellationStopsWaitingWithoutCreatingAnyLateAction() = runBlocking {
        val states = MutableStateFlow(HomeUiState())
        var advanced = false
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPushProtectionHost(states, 1_000)
            advanced = true
        }
        waiting.cancelAndJoin()
        states.value = ready()
        yield()
        assertFalse(advanced)
    }
}
