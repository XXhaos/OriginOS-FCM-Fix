package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutomaticProtectionPolicyTest {
    @Test fun healthyFlagSkipsRecovery() {
        assertEquals(AutomaticCheckDecision.Wait, decision(TargetStoppedStatus.NOT_STOPPED))
        assertEquals(AutomaticCheckDecision.Recover, decision(TargetStoppedStatus.STOPPED))
    }

    @Test fun unknownFlagRequestsFreshCheckButChangedIdentityStillPauses() {
        assertTrue(decision(TargetStoppedStatus.UNKNOWN) is AutomaticCheckDecision.Recheck)
        listOf(TargetStoppedStatus.IDENTITY_CHANGED, TargetStoppedStatus.NOT_CHECKED)
            .forEach { assertTrue(decision(it) is AutomaticCheckDecision.Pause) }
        assertTrue(decision(TargetStoppedStatus.UNKNOWN, authorized = false) is AutomaticCheckDecision.Pause)
        assertTrue(decision(TargetStoppedStatus.UNKNOWN, prerequisites = false) is AutomaticCheckDecision.Pause)
        assertEquals(AutomaticCheckDecision.Reconcile, decision(TargetStoppedStatus.UNKNOWN, pending = true))
        assertEquals(AutomaticCheckDecision.Wait, decision(TargetStoppedStatus.UNKNOWN, busy = true))
    }

    @Test fun lossRevocationPendingReceiptAndManualCollisionHaveDistinctSafetyDecisions() {
        assertTrue(decision(TargetStoppedStatus.NOT_STOPPED, authorized = false) is AutomaticCheckDecision.Pause)
        assertTrue(decision(TargetStoppedStatus.NOT_STOPPED, prerequisites = false) is AutomaticCheckDecision.Pause)
        assertEquals(AutomaticCheckDecision.Reconcile, decision(TargetStoppedStatus.NOT_STOPPED, pending = true))
        assertEquals(AutomaticCheckDecision.Wait, decision(TargetStoppedStatus.STOPPED, busy = true))
        assertEquals(AutomaticCheckDecision.Reconcile, decision(TargetStoppedStatus.STOPPED, pending = true, busy = true))
        assertTrue(decision(TargetStoppedStatus.STOPPED, authorized = false, busy = true) is AutomaticCheckDecision.Pause)
        assertTrue(decision(TargetStoppedStatus.STOPPED, prerequisites = false, busy = true) is AutomaticCheckDecision.Pause)
    }

    @Test fun repeatedTrueDuringCooldownWaitsAndDoesNotConsumeAttempt() {
        val budget = AutomaticAttemptBudget()
        assertEquals(AutomaticCheckDecision.Recover, budget.take(0))
        assertEquals(AutomaticCheckDecision.Cooldown(1), budget.take(299_999))
        assertEquals(AutomaticCheckDecision.Recover, budget.take(300_000))
        assertEquals(AutomaticCheckDecision.Recover, budget.take(600_000))
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000), budget.take(900_000))
        assertEquals(AutomaticCheckDecision.Cooldown(1), budget.take(3_599_999))
        assertEquals(AutomaticCheckDecision.Recover, budget.take(3_600_000))
    }

    @Test fun offOnDoesNotResetProcessBudget() {
        val budget = AutomaticAttemptBudget()
        val gate = AutomaticSessionGate()
        gate.reserve("first"); gate.claim("first")
        assertEquals(AutomaticCheckDecision.Recover, budget.take(1_000))
        gate.revoke("first"); gate.reserve("replacement"); gate.claim("replacement")
        assertEquals(AutomaticCheckDecision.Cooldown(299_000), budget.take(2_000))
        assertEquals(AutomaticCheckDecision.Recover, budget.take(301_000))
    }

    @Test fun staleStopsClaimsAndFinishesCannotAffectReplacement() {
        val gate = AutomaticSessionGate()
        assertTrue(gate.reserve("first")); assertTrue(gate.claim("first"))
        assertFalse(gate.claim("first")); assertTrue(gate.revoke("first"))
        assertFalse(gate.active("first")); assertFalse(gate.claim("first"))
        assertTrue(gate.reserve("replacement")); assertFalse(gate.revoke("first"))
        assertTrue(gate.active("replacement")); assertTrue(gate.claim("replacement"))
        assertFalse(gate.reserve("another"))
    }

    @Test fun cooldownPollingDoesNotExtendDelayAndFreshHealthyReadSkipsRecovery() {
        val budget = AutomaticAttemptBudget()
        assertEquals(AutomaticCheckDecision.Recover, budget.take(1_000))
        for (time in 31_000L..271_000L step 30_000L) {
            assertEquals(AutomaticCheckDecision.Cooldown(301_000 - time), budget.take(time))
        }
        assertEquals(AutomaticCheckDecision.Wait, decision(TargetStoppedStatus.NOT_STOPPED))
        assertTrue(decision(TargetStoppedStatus.STOPPED, authorized = false) is AutomaticCheckDecision.Pause)
        assertEquals(AutomaticCheckDecision.Reconcile, decision(TargetStoppedStatus.STOPPED, pending = true))
        assertEquals(AutomaticCheckDecision.Recover, budget.take(301_000))
    }

    @Test fun missingProcessGrantCannotBeClaimedOrRestarted() {
        val afterProcessDeath = AutomaticSessionGate()
        assertFalse(afterProcessDeath.claim("persisted-old-intent"))
        assertFalse(afterProcessDeath.active("persisted-old-intent"))
        assertFalse(afterProcessDeath.revoke("persisted-old-intent"))
    }

    @Test fun monotonicClockRegressionFailsClosed() {
        val budget = AutomaticAttemptBudget()
        budget.take(1_000)
        assertTrue(budget.take(999) is AutomaticCheckDecision.Pause)
    }

    @Test fun stopQueuesForegroundRetirementWithoutWaitingForPollingCompletion() = runBlocking {
        val actions = mutableListOf<String>()
        var retirementQueued = false
        val enteredIo = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val polling = launch(Dispatchers.IO) {
            enteredIo.countDown()
            check(releaseIo.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(enteredIo.await(2, TimeUnit.SECONDS))
            cancelAutomaticSession(
                cancelRecovery = { actions += "revoke recovery" },
                cancelPolling = { polling.cancel(); actions += "cancel polling" },
                enqueueRetirement = { retirementQueued = true; actions += "queue foreground retirement" },
            )
            assertFalse(polling.isCompleted)
            assertTrue(retirementQueued)
            assertEquals(listOf("revoke recovery", "cancel polling", "queue foreground retirement"), actions)
        } finally {
            releaseIo.countDown()
            polling.join()
        }
    }

    @Test fun foregroundRetirementStillQueuesIfCancellationCleanupThrows() {
        var pollingCancelled = false
        var retirementQueued = false
        val result = runCatching {
            cancelAutomaticSession(
                cancelRecovery = { throw IllegalStateException("cleanup failed") },
                cancelPolling = { pollingCancelled = true },
                enqueueRetirement = { retirementQueued = true },
            )
        }
        assertTrue(result.isFailure)
        assertTrue(pollingCancelled)
        assertTrue(retirementQueued)
    }

    private fun decision(status: TargetStoppedStatus, authorized: Boolean = true,
        prerequisites: Boolean = true, pending: Boolean = false, busy: Boolean = false) =
        automaticCheckDecision(authorized, prerequisites, pending, busy, status)
}
