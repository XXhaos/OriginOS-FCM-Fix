package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class GuardedReconciliationTest {
    private class Memory : RecoveryJournalStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { this.bytes = bytes.clone() }
        override fun markUncertain() = Unit
    }
    private val pending = StoppedRecoveryRecord(18,
        SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64))),
        2000, 1900, VERIFIED_AUTOSTART_FINGERPRINT, 0, true, false, true, StoppedRecoveryStage.CLAIMED,
        source = StoppedRecoverySource.AUTOMATIC,
        consentId = "12345678-1234-1234-1234-123456789abc", consentedAtMillis = 1000, consentVersion = 4)

    @Test fun `OFF after a successful read prevents conclusion commit and old readonly guard never revives`() {
        val memory = Memory().apply { bytes = RecoveryJournalCodec.encode(RecoveryJournalState(18, pending)) }
        val journal = StoppedRecoveryJournal(memory)
        var consentActive = true
        val authorization = AutomaticRecoveryAuthorization { consentActive }
        assertTrue(authorization.active()) // Read completed while the consent was active.
        consentActive = false
        assertThrows(IllegalStateException::class.java) {
            guardedReconciliationFinish(authorization::active) {
                journal.finish(18, StoppedRecoveryOutcome.FLAG_CLEARED, "readonly", reconciled = true)
            }
        }
        assertEquals(pending, journal.load().latest)
        consentActive = true // Replacement consent cannot revive the captured request guard.
        assertFalse(authorization.active())
        assertEquals(18L, journal.load().claimedHighWater)
    }

    @Test fun `guarded readonly completion retains request watermark and never becomes command success`() {
        val memory = Memory().apply { bytes = RecoveryJournalCodec.encode(RecoveryJournalState(18, pending)) }
        val journal = StoppedRecoveryJournal(memory)
        val finished = guardedReconciliationFinish({ true }) {
            journal.finish(18, StoppedRecoveryOutcome.STILL_STOPPED, "readonly", reconciled = true)
        }
        assertEquals(StoppedRecoveryStage.RECONCILED, finished.stage)
        assertEquals(18L, journal.load().claimedHighWater)
        assertFalse(StoppedRecoveryResult(StoppedRecoveryOutcome.STILL_STOPPED, "readonly", finished).commandSucceeded)
    }
}
