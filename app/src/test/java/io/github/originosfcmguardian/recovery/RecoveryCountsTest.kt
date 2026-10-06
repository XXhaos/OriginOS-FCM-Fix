package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RecoveryCountsTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val utc = ZoneId.of("UTC")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private val monday = at("2026-09-28T00:00:00Z")
    private val now = at("2026-10-02T12:00:00Z")

    private class Memory : RecoveryCountsStorage {
        var bytes: ByteArray? = null
        var failWrite = false
        var failed = false
        override fun read(): ByteArray? { check(!failed); return bytes?.clone() }
        override fun write(bytes: ByteArray) { check(!failWrite); this.bytes = bytes.clone() }
        override fun markUnavailable() { failed = true }
    }
    private fun receipt(id: Long, timestamp: Long = now, target: SelectedTargetIdentity = identity) =
        RecoveryCountReceipt(id, target, timestamp)
    private fun completed() = StoppedRecoveryRecord(1, identity, now - 1000, now - 1000,
        VERIFIED_AUTOSTART_FINGERPRINT, 0, true, true, true, StoppedRecoveryStage.FINISHED,
        StoppedRecoveryOutcome.FLAG_CLEARED)

    @Test fun onlyNewSuccessfulCommandAndFinishedClearReceiptQualify() {
        val accepted = StoppedRecoveryResult(StoppedRecoveryOutcome.FLAG_CLEARED, "done", completed(), true)
        assertNotNull(confirmedRecoveryReceipt(accepted, now))
        StoppedRecoveryOutcome.entries.filter { it != StoppedRecoveryOutcome.FLAG_CLEARED }.forEach {
            assertNull(confirmedRecoveryReceipt(accepted.copy(outcome = it), now))
        }
        StoppedRecoveryStage.entries.filter { it != StoppedRecoveryStage.FINISHED }.forEach {
            assertNull(confirmedRecoveryReceipt(accepted.copy(record = completed().copy(stage = it)), now))
        }
        assertNull(confirmedRecoveryReceipt(accepted.copy(commandSucceeded = false), now))
        assertNull(confirmedRecoveryReceipt(accepted.copy(record = null), now))
        assertNull(confirmedRecoveryReceipt(accepted.copy(record = completed().copy(outcome = StoppedRecoveryOutcome.NO_WRITE_NEEDED)), now))
        assertNull(confirmedRecoveryReceipt(accepted, 0))
        assertNull(confirmedRecoveryReceipt(accepted, now - 2000))
    }

    @Test fun repeatedCompletionAndRestartCountOnce() {
        val memory = Memory()
        var store = RecoveryCountsStore(memory)
        assertTrue(store.record(receipt(1)))
        assertTrue(store.record(receipt(1)))
        store = RecoveryCountsStore(memory)
        assertTrue(store.record(receipt(1, now + 10)))
        val result = store.snapshot(listOf(identity), now + 100, utc)
        assertTrue(result.available)
        assertEquals(now + 100, result.calculatedAtMillis)
        assertEquals("UTC", result.zoneId)
        assertEquals(1, result.byIdentity[identity]!!.today)
        assertFalse(result.byIdentity[identity]!!.todayComplete)
        assertFalse(result.byIdentity[identity]!!.weekComplete)
    }

    @Test fun fullInstallationIdentitySeparatesUidTimeAndSigners() {
        val targets = listOf(identity, identity.copy(uid = 10435), identity.copy(firstInstallTime = 200),
            identity.copy(signerSha256 = listOf("b".repeat(64))))
        val store = RecoveryCountsStore(Memory())
        targets.forEachIndexed { index, target -> assertTrue(store.record(receipt(index + 1L, target = target))) }
        val counts = store.snapshot(targets, now, utc).byIdentity
        assertEquals(4, counts.size)
        targets.forEach { assertEquals(1, counts[it]!!.today) }
        assertEquals(0, store.snapshot(listOf(identity.copy(firstInstallTime = 300)), now, utc).byIdentity.values.single().week)
    }

    @Test fun localDayAndMondayWeekUseRequestedZoneIncludingDst() {
        val store = RecoveryCountsStore(Memory())
        listOf(monday - 1, monday, at("2026-10-01T23:30:00Z"), now).forEachIndexed { index, timestamp ->
            store.record(receipt(index + 1L, timestamp))
        }
        val counts = store.snapshot(listOf(identity), now, utc).byIdentity[identity]!!
        assertEquals(1, counts.today)
        assertEquals(3, counts.week)
        assertEquals(2, store.snapshot(listOf(identity), now, ZoneId.of("Asia/Tokyo")).byIdentity[identity]!!.today)
        val dst = RecoveryCountsStore(Memory())
        dst.record(receipt(1, at("2026-03-08T04:59:59Z")))
        dst.record(receipt(2, at("2026-03-08T05:00:00Z")))
        dst.record(receipt(3, at("2026-03-08T07:30:00Z")))
        val local = dst.snapshot(listOf(identity), at("2026-03-08T20:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(2, local.byIdentity[identity]!!.today)
        assertEquals(3, local.byIdentity[identity]!!.week)
    }

    @Test fun futureAndBackwardsClockNeverInflateCurrentCountsAndPersistCaveat() {
        val memory = Memory()
        val store = RecoveryCountsStore(memory)
        store.record(receipt(1, now))
        store.record(receipt(2, now + 1000))
        val result = store.snapshot(listOf(identity), now, utc)
        assertEquals(1, result.byIdentity[identity]!!.today)
        assertTrue(result.detail.contains("时间"))
        assertTrue(RecoveryCountsStore(memory).snapshot(listOf(identity), now + 2000, utc).detail.contains("时间"))
    }

    @Test fun normalAsyncReceiptAfterUiSnapshotIsNotClockChange() {
        val store = RecoveryCountsStore(Memory())
        store.snapshot(listOf(identity), now + 10, utc)
        store.record(receipt(1, now))
        assertFalse(store.snapshot(listOf(identity), now + 20, utc).detail.contains("时间"))
    }

    @Test fun capacityIsBoundedAndWatermarkDedupsPrunedOrOutOfOrderIds() {
        val memory = Memory()
        val store = RecoveryCountsStore(memory)
        for (id in 1L..513) store.record(receipt(id, now + id))
        assertEquals(512, RecoveryCountsCodec.decode(memory.bytes!!).receipts.size)
        assertTrue(RecoveryCountsStore(memory).record(receipt(1, now + 1000)))
        assertTrue(RecoveryCountsStore(memory).record(receipt(500, now + 1000)))
        assertEquals(512, store.snapshot(listOf(identity), now + 1000, utc).byIdentity[identity]!!.today)
        assertTrue(memory.bytes!!.size <= RecoveryCountsCodec.MAX_BYTES)
    }

    @Test fun absentLedgerDoesNotBackfillLegacyLatestJournal() {
        // No journal input exists in the statistics API, even if a legacy FINISHED receipt exists.
        assertNull(confirmedRecoveryReceipt(StoppedRecoveryResult(StoppedRecoveryOutcome.FLAG_CLEARED, "legacy", completed()), now))
        val result = RecoveryCountsStore(Memory()).snapshot(listOf(identity), now, utc)
        assertTrue(result.available)
        assertEquals(now, result.sinceMillis)
        assertEquals(0, result.byIdentity[identity]!!.week)
        assertFalse(result.byIdentity[identity]!!.weekComplete)
    }

    @Test fun invalidStorageAndPersistenceFailureAreUnavailableWithoutJournalDamage() {
        val memory = Memory().apply { bytes = byteArrayOf(1, 2, 3) }
        val before = memory.bytes!!.clone()
        assertFalse(RecoveryCountsStore(memory).snapshot(listOf(identity), now, utc).available)
        assertArrayEquals(before, memory.bytes)
        val failed = Memory().apply { failWrite = true }
        assertFalse(RecoveryCountsStore(failed).record(receipt(1)))
        assertTrue(failed.failed)
        assertFalse(RecoveryCountsStore(failed).snapshot(listOf(identity), now, utc).available)
        assertThrows(IllegalArgumentException::class.java) { RecoveryCountsCodec.decode(ByteArray(RecoveryCountsCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryCountsCodec.encode(RecoveryCountsLedger(-1)) }
        assertThrows(IllegalArgumentException::class.java) { RecoveryCountsCodec.encode(RecoveryCountsLedger(now,
            1, receipts = listOf(receipt(1), receipt(1)))) }
    }

    @Test fun resultProtocolLegacyMalformedProvenanceCannotCount() {
        val success = StoppedRecoveryResult(StoppedRecoveryOutcome.FLAG_CLEARED, "done", commandSucceeded = true)
        assertTrue(RecoveryResultCodec.decode(RecoveryResultCodec.encode(success)).commandSucceeded)
        listOf("FLAG_CLEARED|legacy", "FLAG_CLEARED|legacy|true", "FLAG_CLEARED|legacy|1|junk").forEach {
            assertFalse(RecoveryResultCodec.decode(it).commandSucceeded)
        }
        listOf("FLAG_CLEARED|legacy|true", "FLAG_CLEARED|legacy|1|junk").forEach {
            assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, RecoveryResultCodec.decode(it).outcome)
        }
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, RecoveryResultCodec.decode("bad|x|1").outcome)
    }

    @Test fun nonzeroCommandExitClearedIsExcludedEvenThoughOriginalOutcomeStaysCleared() {
        var reads = 0
        val result = StoppedRecoveryEngine({ StoppedTargetObservation.Known(1, identity, ++reads < 3, true, now) },
            { true }, { true }, { 1 }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
        assertFalse(result.commandSucceeded)
        assertNull(confirmedRecoveryReceipt(result.copy(record = completed()), now))
    }
}
