package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class AutomaticStoppedRecoveryTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val consent = AutomaticRecoveryConsent("b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 90, 1)
    private fun known(stopped: Boolean = true) = StoppedTargetObservation.Known(8, identity, stopped, true, 100)
    private class Memory : RecoveryJournalStorage {
        var bytes: ByteArray? = null
        var afterWrite: () -> Unit = {}
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { this.bytes = bytes.clone(); afterWrite() }
        override fun markUncertain() = Unit
    }
    private fun legacyRecord(stage: StoppedRecoveryStage = StoppedRecoveryStage.FINISHED) = StoppedRecoveryRecord(
        8, identity, 200, 100, known().fingerprint, 0, true, true, true, stage,
        if (stage == StoppedRecoveryStage.FINISHED) StoppedRecoveryOutcome.FLAG_CLEARED else null, "legacy receipt",
    )

    /** Historical fixture bytes, deliberately independent of the new schema encoder. */
    private fun fixture(record: StoppedRecoveryRecord, highWater: Long, schema: Int = 1,
        source: String = record.source.name): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(schema); out.writeLong(highWater); out.writeBoolean(true)
            out.writeLong(record.requestId); out.writeUTF(TargetSelectionCodec.encode(listOf(record.identity)))
            out.writeLong(record.createdAtMillis); out.writeLong(record.baselineAtMillis)
            out.writeUTF(record.fingerprint); out.writeInt(record.currentUser)
            out.writeBoolean(record.baselineStopped); out.writeBoolean(record.confirmedManualAction)
            out.writeBoolean(record.acknowledgedRuntimeRisk); out.writeUTF(record.stage.name)
            out.writeUTF(record.outcome?.name.orEmpty()); out.writeUTF(record.detail)
            if (schema == 2) {
                out.writeUTF(source); out.writeUTF(record.consentId.orEmpty())
                out.writeLong(record.consentedAtMillis ?: 0); out.writeInt(record.consentVersion ?: 0)
            }
        }
    }.toByteArray()

    @Test fun schemaOneManualReceiptMigratesLosslesslyToSchemaTwo() {
        val original = RecoveryJournalState(7, legacyRecord())
        val decoded = RecoveryJournalCodec.decode(fixture(original.latest!!, original.claimedHighWater))
        assertEquals(original, decoded)
        val upgraded = RecoveryJournalCodec.encode(decoded)
        assertEquals(2, DataInputStream(upgraded.inputStream()).readInt())
        assertEquals(original, RecoveryJournalCodec.decode(upgraded))
        assertEquals(StoppedRecoverySource.MANUAL, decoded.latest!!.source)
        assertNull(decoded.latest.consentId)
    }

    @Test fun schemaOnePendingClaimPreservesBaselineAndHighWaterOnFirstWrite() {
        val historical = legacyRecord(StoppedRecoveryStage.PREPARED).copy(detail = "")
        val memory = Memory().apply { bytes = fixture(historical, 7) }
        val journal = StoppedRecoveryJournal(memory)
        assertTrue(journal.claim(8))
        assertEquals(RecoveryJournalState(8, historical.copy(stage = StoppedRecoveryStage.CLAIMED)), journal.load())
        assertEquals(2, DataInputStream(memory.bytes!!.inputStream()).readInt())
        journal.finish(8, StoppedRecoveryOutcome.FLAG_CLEARED, "readonly", reconciled = true)
        val next = journal.prepare(identity, known(), true)
        assertEquals(9L, next.requestId)
        assertEquals(8L, journal.load().claimedHighWater)
        assertTrue(next.confirmedManualAction)
        assertEquals(StoppedRecoverySource.MANUAL, next.source)
        assertNull(next.consentedAtMillis)
    }

    @Test fun automaticRecordCarriesExplicitConsentWithoutFabricatingManualAction() {
        val memory = Memory()
        var journal = StoppedRecoveryJournal(memory)
        val prepared = journal.prepareAutomatically(identity, known(), consent)
        assertEquals(StoppedRecoverySource.AUTOMATIC, prepared.source)
        assertFalse(prepared.confirmedManualAction)
        assertTrue(prepared.acknowledgedRuntimeRisk)
        assertEquals(consent.id, prepared.consentId)
        assertEquals(consent.consentedAtMillis, prepared.consentedAtMillis)
        assertEquals(consent.version, prepared.consentVersion)
        assertEquals(identity, prepared.identity)
        assertTrue(journal.claim(prepared.requestId))
        journal = StoppedRecoveryJournal(memory)
        assertFalse(journal.claim(prepared.requestId))
        assertThrows(IllegalStateException::class.java) { journal.prepareAutomatically(identity, known(), consent) }
        val receipt = journal.finish(prepared.requestId, StoppedRecoveryOutcome.FLAG_CLEARED, "done")
        assertEquals(prepared.copy(stage = StoppedRecoveryStage.FINISHED,
            outcome = StoppedRecoveryOutcome.FLAG_CLEARED, detail = "done"), receipt)
        assertEquals(receipt, StoppedRecoveryJournal(memory).load().latest)
    }

    @Test fun malformedOrContradictoryProvenanceCannotAuthorizeAClaim() {
        val auto = StoppedRecoveryJournal(Memory()).prepareAutomatically(identity, known(), consent)
        val corrupt = listOf(
            fixture(auto, 0, 2, "FUTURE"),
            fixture(auto.copy(confirmedManualAction = true), 0, 2),
            fixture(auto.copy(acknowledgedRuntimeRisk = false), 0, 2),
            fixture(auto.copy(consentId = null), 0, 2),
            fixture(auto.copy(consentId = "invalid"), 0, 2),
            fixture(auto.copy(consentedAtMillis = -1), 0, 2),
            fixture(auto.copy(consentVersion = 3), 0, 2),
            fixture(auto, 0, 2, "MANUAL"),
        )
        corrupt.forEach { bytes ->
            val journal = StoppedRecoveryJournal(Memory().apply { this.bytes = bytes })
            assertThrows(Exception::class.java) { journal.load() }
            assertThrows(Exception::class.java) { journal.claim(auto.requestId) }
        }
    }

    @Test fun explicitRestartConsentRetainsVersionTwoProvenance() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        val restart = consent.copy(version = 2)
        val prepared = journal.prepareAutomatically(identity, known(), restart)
        assertEquals(2, prepared.consentVersion)
        assertFalse(prepared.confirmedManualAction)
        assertEquals(restart.id, prepared.consentId)
        assertTrue(journal.claim(prepared.requestId))
        journal.finish(prepared.requestId, StoppedRecoveryOutcome.FLAG_CLEARED, "done")
        assertEquals(2, StoppedRecoveryJournal(memory).load().latest?.consentVersion)
    }

    @Test fun automaticPreparationRequiresTrueExclusiveSameIdentityBaselineAndValidConsent() {
        listOf(known(false), known().copy(exclusiveUidOwner = false),
            known().copy(identity = identity.copy(firstInstallTime = 200))).forEach { baseline ->
            val memory = Memory()
            assertThrows(IllegalStateException::class.java) {
                StoppedRecoveryJournal(memory).prepareAutomatically(identity, baseline, consent)
            }
            assertNull(memory.bytes)
        }
        listOf(consent.copy(id = ""), consent.copy(consentedAtMillis = 0), consent.copy(version = 0)).forEach { invalid ->
            val memory = Memory()
            assertThrows(IllegalArgumentException::class.java) {
                StoppedRecoveryJournal(memory).prepareAutomatically(identity, known(), invalid)
            }
            assertNull(memory.bytes)
        }
    }

    @Test fun authorizationFailureIsFailClosedAndRevocationIsLatched() {
        var enabled = true
        val authorization = AutomaticRecoveryAuthorization { enabled }
        assertTrue(authorization.active())
        enabled = false
        assertFalse(authorization.active())
        enabled = true
        assertFalse(authorization.active())
        var throws = true
        val failure = AutomaticRecoveryAuthorization { if (throws) error("runtime unavailable"); true }
        assertFalse(failure.active())
        throws = false
        assertFalse(failure.active())
    }

    @Test fun revocationDuringClaimCommitConsumesIdWithoutDispatchOrReplay() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        val prepared = journal.prepareAutomatically(identity, known(), consent)
        var enabled = true
        var writes = 0
        val authorization = AutomaticRecoveryAuthorization { enabled }
        memory.afterWrite = { enabled = false }
        val result = StoppedRecoveryEngine({ id -> known().copy(requestId = id) }, { id ->
            if (!authorization.active()) false else journal.claim(id) && authorization.active()
        }, authorization::active, { writes++; 0 }).recover(prepared.requestId, identity)
        assertEquals(0, writes)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertEquals(StoppedRecoveryStage.CLAIMED, journal.load().latest!!.stage)
        assertTrue(journal.load().latest!!.requiresReconcile)
        enabled = true
        assertFalse(authorization.active())
        assertFalse(journal.claim(prepared.requestId))
    }

    @Test fun revocationDuringPreCommandObservationPreventsWrite() {
        var enabled = true
        var reads = 0
        var writes = 0
        val authorization = AutomaticRecoveryAuthorization { enabled }
        val result = StoppedRecoveryEngine({ if (++reads == 2) enabled = false; known() }, { true },
            authorization::active, { writes++; 0 }).recover(8, identity)
        assertEquals(0, writes)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
    }

    @Test fun revocationDuringReadbackLeavesDispatchedResultUnconfirmed() {
        var enabled = true
        var reads = 0
        var writes = 0
        val authorization = AutomaticRecoveryAuthorization { enabled }
        val result = StoppedRecoveryEngine({ if (++reads == 3) enabled = false; known(reads < 3) }, { true },
            authorization::active, { writes++; 0 }).recover(8, identity)
        assertEquals(1, writes)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
    }
}
