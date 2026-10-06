package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.privileged.StoppedObservationUnknown
import org.junit.Assert.*
import org.junit.Test

class StoppedRecoveryTransactionTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private fun known(stopped: Boolean = true) = StoppedTargetObservation.Known(1, identity, stopped, true, 100)
    private class Memory : RecoveryJournalStorage {
        var bytes: ByteArray? = null
        var fail = false
        var uncertain = false
        override fun read(): ByteArray? { check(!uncertain); return bytes?.clone() }
        override fun write(bytes: ByteArray) { check(!fail); this.bytes = bytes.clone() }
        override fun markUncertain() { uncertain = true }
    }

    @Test fun claimSurvivesRestartAndCannotReplayAfterReconcile() {
        val memory = Memory()
        var journal = StoppedRecoveryJournal(memory)
        val first = journal.prepare(identity, known(), true)
        assertTrue(journal.claim(first.requestId))
        journal = StoppedRecoveryJournal(memory)
        assertFalse(journal.claim(first.requestId))
        assertThrows(IllegalStateException::class.java) { journal.prepare(identity, known(), true) }
        journal.finish(first.requestId, StoppedRecoveryOutcome.STILL_STOPPED, "readonly", reconciled = true)
        val second = journal.prepare(identity, known(), true)
        assertTrue(second.requestId > first.requestId)
        assertFalse(journal.claim(first.requestId))
        assertTrue(journal.claim(second.requestId))
    }

    @Test fun failedCommitDoesNotAuthorizeCommand() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        val record = journal.prepare(identity, known(), true)
        memory.fail = true
        var writes = 0
        val result = StoppedRecoveryEngine({ known() }, { journal.claim(it) }, { true }, { writes++; 0 })
            .recover(record.requestId, identity)
        assertEquals(0, writes)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertThrows(IllegalStateException::class.java) { journal.load() }
        assertThrows(IllegalStateException::class.java) { StoppedRecoveryJournal(memory).load() }
    }

    @Test fun failedPrepareAndCorruptJournalFailClosed() {
        val memory = Memory().apply { fail = true }
        val journal = StoppedRecoveryJournal(memory)
        assertThrows(IllegalStateException::class.java) { journal.prepare(identity, known(), true) }
        val corrupt = StoppedRecoveryJournal(Memory().apply { bytes = byteArrayOf(1, 2, 3) })
        assertThrows(Exception::class.java) { corrupt.load() }
        assertThrows(Exception::class.java) { corrupt.prepare(identity, known(), true) }
    }

    @Test fun resultPersistenceFailureLeavesClaimUnresolved() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        val r = journal.prepare(identity, known(), true)
        assertTrue(journal.claim(r.requestId))
        memory.fail = true
        assertThrows(IllegalStateException::class.java) { journal.finish(r.requestId, StoppedRecoveryOutcome.FLAG_CLEARED, "done") }
        assertThrows(IllegalStateException::class.java) { StoppedRecoveryJournal(memory).load() }
        assertThrows(IllegalStateException::class.java) { journal.claim(r.requestId) }
    }

    @Test fun falseBaselineNeverClaimsOrWrites() {
        val result = StoppedRecoveryEngine({ known(false) }, { error("claim") }, { true }, { error("write") }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.NO_WRITE_NEEDED, result.outcome)
    }

    @Test fun changedIdentityAndSharedUidNeverClaim() {
        listOf(known().copy(identity = identity.copy(firstInstallTime = 200)), known().copy(exclusiveUidOwner = false)).forEach { sample ->
            val result = StoppedRecoveryEngine({ sample }, { error("claim") }, { true }, { error("write") }).recover(1, identity)
            assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        }
    }

    @Test fun cancellationDuringClaimPreventsWriteAndKeepsUnconfirmed() {
        var active = true
        var writes = 0
        val result = StoppedRecoveryEngine({ known() }, { active = false; true }, { active }, { writes++; 0 }).recover(1, identity)
        assertEquals(0, writes)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
    }

    @Test fun cancellationDuringFinalIdentityReadPreventsWrite() {
        var reads = 0
        var active = true
        var writes = 0
        val result = StoppedRecoveryEngine({ if (++reads == 2) active = false; known() }, { true }, { active }, { writes++; 0 }).recover(1, identity)
        assertEquals(0, writes)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
    }

    @Test fun readbackUnknownDoesNotRepeatCommand() {
        var reads = 0
        var writes = 0
        val result = StoppedRecoveryEngine({ if (++reads < 3) known() else StoppedTargetObservation.Unknown(1, StoppedObservationUnknown.TIMED_OUT) },
            { true }, { true }, { writes++; 0 }).recover(1, identity)
        assertEquals(1, writes)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
    }

    @Test fun verifiedReadbackClassifiesFlagNotDelivery() {
        for (stopped in listOf(false, true)) {
            var reads = 0
            var writes = 0
            val result = StoppedRecoveryEngine({ known(if (++reads < 3) true else stopped) }, { true }, { true }, { writes++; 0 }).recover(1, identity)
            assertEquals(1, writes)
            assertEquals(if (stopped) StoppedRecoveryOutcome.STILL_STOPPED else StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
            assertTrue(result.detail.contains("未验证"))
        }
    }

    @Test fun duplicateClaimDoesNotWrite() {
        val result = StoppedRecoveryEngine({ known() }, { false }, { true }, { error("write") }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
    }

    @Test fun malformedResultsAreUnconfirmedAndCannotFinishIntent() {
        listOf(null, "FLAG_CLEARED", "UNKNOWN|x", "x".repeat(300)).forEach {
            assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, RecoveryResultCodec.decode(it).outcome)
        }
        val journal = StoppedRecoveryJournal(Memory())
        val r = journal.prepare(identity, known(), true)
        assertThrows(IllegalStateException::class.java) { journal.finish(r.requestId, StoppedRecoveryOutcome.UNCONFIRMED, "unknown") }
        assertTrue(journal.load().latest!!.requiresReconcile)
    }
}
