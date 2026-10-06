package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import org.junit.Assert.*
import org.junit.Test

class PendingReconciliationPolicyTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val pending = StoppedRecoveryRecord(18, identity, 2000, 1900, VERIFIED_AUTOSTART_FINGERPRINT, 0,
        true, false, true, StoppedRecoveryStage.CLAIMED, source = StoppedRecoverySource.AUTOMATIC,
        consentId = "12345678-1234-1234-1234-123456789abc", consentedAtMillis = 1000, consentVersion = 4)

    @Test fun `readiness is initially closed and old RPC tickets never revive after a readonly round`() {
        val gate = AutomaticWriteGate()
        assertNull(gate.ticket())
        assertFalse(gate.permits(null))
        gate.open()
        val old = gate.ticket()
        assertTrue(gate.permits(old))
        gate.block()
        assertFalse(gate.permits(old))
        assertNull(gate.ticket())
        gate.open()
        assertFalse(gate.permits(old))
        assertTrue(gate.permits(gate.ticket()))
    }

    @Test fun `readonly retry round has exactly three attempts and polling or sticky wakes cannot extend it`() {
        val budget = PendingReconciliationBudget()
        assertEquals(0L, budget.nextDelay(18, 1, 0))
        assertEquals(3_000L, budget.nextDelay(18, 1, 0))
        assertEquals(15_000L, budget.nextDelay(18, 1, 0))
        repeat(100) { assertNull(budget.nextDelay(18, 1, 0)) }
    }

    @Test fun `only a new connection explicit refresh or genuinely new pending request starts a fresh bounded round`() {
        val budget = PendingReconciliationBudget()
        for (round in listOf(Triple(18L, 1L, 0L), Triple(18L, 2L, 0L), Triple(18L, 2L, 1L), Triple(19L, 2L, 1L))) {
            assertEquals(0L, budget.nextDelay(round.first, round.second, round.third))
            assertEquals(3_000L, budget.nextDelay(round.first, round.second, round.third))
            assertEquals(15_000L, budget.nextDelay(round.first, round.second, round.third))
            assertNull(budget.nextDelay(round.first, round.second, round.third))
        }
    }

    @Test fun `same exact readonly conclusion may clear barrier without proving command execution`() {
        for (outcome in listOf(StoppedRecoveryOutcome.FLAG_CLEARED, StoppedRecoveryOutcome.STILL_STOPPED)) {
            assertTrue(resolvedPendingRecovery(pending, pending.copy(stage = StoppedRecoveryStage.RECONCILED,
                outcome = outcome, detail = "readonly conclusion")))
        }
        assertFalse(resolvedPendingRecovery(pending, pending))
        assertTrue(sameRecoveryRequest(pending, pending.copy(stage = StoppedRecoveryStage.PREPARED)))
    }

    @Test fun `missing replaced unsafe or original command terminal receipts cannot clear readonly barrier`() {
        val cleared = pending.copy(stage = StoppedRecoveryStage.RECONCILED, outcome = StoppedRecoveryOutcome.FLAG_CLEARED)
        val unsafe = listOf(null, cleared.copy(requestId = 19), cleared.copy(stage = StoppedRecoveryStage.FINISHED),
            cleared.copy(outcome = StoppedRecoveryOutcome.BLOCKED), cleared.copy(outcome = StoppedRecoveryOutcome.UNCONFIRMED),
            cleared.copy(identity = identity.copy(firstInstallTime = 101)), cleared.copy(consentId = "different"),
            cleared.copy(consentedAtMillis = 1001), cleared.copy(consentVersion = 2),
            cleared.copy(source = StoppedRecoverySource.MANUAL), cleared.copy(fingerprint = "different"),
            cleared.copy(currentUser = 10))
        unsafe.forEach { assertFalse(resolvedPendingRecovery(pending, it)) }
    }
}
