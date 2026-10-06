package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.privileged.StoppedObservationUnknown
import org.junit.Assert.*
import org.junit.Test

class StoppedRecoveryReadbackWaitTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val initial = StoppedRecoveryResult(StoppedRecoveryOutcome.STILL_STOPPED, "still")
    private fun known(stopped: Boolean) = StoppedTargetObservation.Known(1, identity, stopped, true, 100)
    @Test fun delayedFalseCompletesWithoutAnotherWrite() {
        var reads = 0
        val result = awaitStoppedRecoveryReadback(identity, initial, { known(++reads < 5) }, { true }, {}, { 0 })
        assertEquals(5, reads)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
    }
    @Test fun delayedClearPreservesSuccessfulCommandProvenanceOnly() {
        listOf(false, true).forEach { succeeded ->
            val result = awaitStoppedRecoveryReadback(identity, initial.copy(commandSucceeded = succeeded),
                { known(false) }, { true }, {}, { 0 })
            assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
            assertEquals(succeeded, result.commandSucceeded)
        }
    }
    @Test fun stillStoppedIsFinite() {
        var reads = 0
        val result = awaitStoppedRecoveryReadback(identity, initial, { reads++; known(true) }, { true }, {}, { 0 })
        assertEquals(8, reads)
        assertEquals(StoppedRecoveryOutcome.STILL_STOPPED, result.outcome)
    }
    @Test fun unknownAndChangedIdentityNeverGreen() {
        listOf(StoppedTargetObservation.Unknown(1, StoppedObservationUnknown.CAPABILITY_UNAVAILABLE),
            known(false).copy(identity = identity.copy(firstInstallTime = 200)),
            known(false).copy(exclusiveUidOwner = false)).forEach { sample ->
            assertEquals(StoppedRecoveryOutcome.UNCONFIRMED,
                awaitStoppedRecoveryReadback(identity, initial, { sample }, { true }, {}, { 0 }).outcome)
        }
    }
    @Test fun cancelledDuringPauseDoesNotRead() {
        var live = true
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED,
            awaitStoppedRecoveryReadback(identity, initial, { error("late read") }, { live }, { live = false }, { 0 }).outcome)
    }
    @Test fun lateResultDiscarded() {
        var clock = 0L
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED,
            awaitStoppedRecoveryReadback(identity, initial, { clock = 9_000_000_000; known(false) }, { true }, {}, { clock }).outcome)
    }
    @Test fun otherOutcomesDoNotPollOrRetry() {
        StoppedRecoveryOutcome.entries.filter { it != StoppedRecoveryOutcome.STILL_STOPPED }.forEach { outcome ->
            val result = initial.copy(outcome = outcome)
            assertEquals(result, awaitStoppedRecoveryReadback(identity, result, { error("read") }, { true }, { error("wait") }))
        }
    }
}
