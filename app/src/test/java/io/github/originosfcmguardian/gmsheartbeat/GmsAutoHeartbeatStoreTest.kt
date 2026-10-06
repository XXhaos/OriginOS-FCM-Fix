package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal class HeartbeatMemoryStorage : HeartbeatStorage {
    var bytes: ByteArray? = null
    var failWrite = false
    var failRead = false
    var writes = 0
    var onWrite: (HeartbeatSnapshot) -> Unit = {}
    @Synchronized override fun <T> exclusive(action: () -> T): T = action()
    override fun read(): HeartbeatSnapshot? { check(!failRead); return bytes?.let(HeartbeatSnapshotCodec::decode) }
    override fun write(value: HeartbeatSnapshot) {
        check(!failWrite)
        bytes = HeartbeatSnapshotCodec.encode(value)
        check(read() == value)
        writes++
        onWrite(value)
    }
}

class GmsAutoHeartbeatStoreTest {
    private val digest = "a".repeat(64)
    private val session = UUID.randomUUID().toString()
    private val attempt = UUID.randomUUID().toString()
    private fun armed(store: GmsAutoHeartbeatStore): HeartbeatSnapshot {
        val prepared = store.prepareConfirmation(store.read(), digest)!!
        return store.armed(prepared.generation, session)!!
    }
    @Test fun absentPreferenceIsOffAndReadingDoesNotCreateAuthority() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        assertNull(store.read()); assertNull(store.reconstruct()); assertNull(storage.bytes); assertEquals(0, storage.writes)
    }
    @Test fun pendingMustCommitBeforeRequestAndLateResultCannotOverwriteOff() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val active = armed(store)
        val pending = store.beginAttempt(active.generation, attempt)!!
        assertEquals(attempt, GmsAutoHeartbeatStore(storage).read()!!.pendingAttempt)
        assertNull(store.beginAttempt(active.generation, UUID.randomUUID().toString()))
        val off = store.off(true)
        val before = storage.bytes!!.clone()
        assertTrue(off.generation > pending.generation); assertFalse(off.configured)
        assertNull(store.complete(active.generation, attempt, GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)))
        assertArrayEquals(before, storage.bytes)
        assertEquals(HeartbeatPause.OFF_PENDING, store.read()!!.pause)
        assertNull(store.prepareConfirmation(off, digest))
        assertNotNull(store.acknowledgeStop(off.generation))
        assertNull(store.read()!!.pendingAttempt)
        assertEquals(GmsHeartbeatStatus.UNKNOWN, store.read()!!.lastStatus)
    }
    @Test fun reconstructionAndExplicitNewSessionNeverReplayPendingOrCallItRequested() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val active = armed(store)
        val first = UUID.randomUUID().toString()
        store.beginAttempt(active.generation, first)
        store.complete(active.generation, first, GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED))
        store.beginAttempt(active.generation, attempt)
        val reconstructed = GmsAutoHeartbeatStore(storage).reconstruct()!!
        assertEquals(attempt, reconstructed.pendingAttempt)
        assertEquals(HeartbeatPause.LEFTOVER_PENDING, reconstructed.pause)
        assertEquals(GmsHeartbeatStatus.UNKNOWN, reconstructed.lastStatus)
        val explicit = store.prepareConfirmation(reconstructed, digest)!!
        assertNull(explicit.pendingAttempt); assertNull(explicit.remoteSessionId)
        assertEquals(GmsHeartbeatStatus.UNKNOWN, explicit.lastStatus)
        assertTrue(explicit.generation > reconstructed.generation)
        assertNull(store.complete(active.generation, attempt, GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)))
    }
    @Test fun unknownCompletionPausesAndCannotStartAnotherAttemptWithoutConfirmation() {
        val store = GmsAutoHeartbeatStore(HeartbeatMemoryStorage())
        val active = armed(store); store.beginAttempt(active.generation, attempt)
        val unknown = store.complete(active.generation, attempt, GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT))!!
        assertEquals(HeartbeatPause.UNKNOWN_RESULT, unknown.pause)
        assertNull(store.beginAttempt(active.generation, UUID.randomUUID().toString()))
        assertEquals(GmsHeartbeatStatus.UNKNOWN, unknown.lastStatus)
    }
    @Test fun writeFailuresAndCorruptionNeverIssueNewPendingRecord() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val active = armed(store); val before = storage.bytes!!.clone()
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.beginAttempt(active.generation, attempt) }
        assertArrayEquals(before, storage.bytes)
        storage.failWrite = false; storage.bytes = byteArrayOf(1, 2, 3)
        assertThrows(Exception::class.java) { store.read() }
        assertThrows(Exception::class.java) { store.off(false) }
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.bytes)
    }
    @Test fun codecStrictRoundTripRejectsEveryTruncationCorruptionAndUnknownEnum() {
        val snapshot = HeartbeatSnapshot(7, true, HeartbeatPause.LEFTOVER_PENDING, attempt, digest, session,
            GmsHeartbeatStatus.UNKNOWN, GmsHeartbeatReason.TIMED_OUT)
        val bytes = HeartbeatSnapshotCodec.encode(snapshot)
        assertEquals(snapshot, HeartbeatSnapshotCodec.decode(bytes))
        assertArrayEquals(bytes, HeartbeatSnapshotCodec.encode(HeartbeatSnapshotCodec.decode(bytes)))
        for (size in bytes.indices) assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(bytes.copyOf(size)) }
        for (index in bytes.indices) {
            val changed = bytes.clone(); changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(changed) }
        }
        assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(bytes + 0) }
    }
    @Test fun staleConfirmationCannotOverwriteNewOffAndStopPendingSurvivesReconstruction() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val initial = armed(store)
        val off = store.off(true); val before = storage.bytes!!.clone()
        assertNull(store.prepareConfirmation(initial, digest))
        assertEquals(off, store.reconstruct())
        assertArrayEquals(before, storage.bytes)
        assertNull(store.acknowledgeStop(initial.generation))
    }

    @Test fun schemaOneRoundTripAndSessionConfirmationNeverImplyDurableConsent() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val old = armed(store); val bytes = storage.bytes!!.clone()
        assertEquals(1, old.schemaVersion); assertNull(old.durableConsent)
        assertArrayEquals(bytes, HeartbeatSnapshotCodec.encode(HeartbeatSnapshotCodec.decode(bytes)))
        val cold = store.reconstruct()!!
        assertNull(cold.durableConsent); assertNull(cold.resumeBlock)
        assertNull(store.prepareResume(cold, UUID.randomUUID().toString()))
    }

    @Test fun durableConsentExactBindingAtomicCodecAndColdReconstructionStillBlocks() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val next = store.prepareConfirmation(null, digest, durable = true)!!
        assertEquals(2, next.schemaVersion); assertEquals(digest, next.durableConsent!!.bindingDigest)
        assertEquals(0, next.durableConsent.userId); assertEquals(1, next.durableConsent.version)
        val bytes = storage.bytes!!.clone()
        assertEquals(next, HeartbeatSnapshotCodec.decode(bytes))
        assertArrayEquals(bytes, HeartbeatSnapshotCodec.encode(HeartbeatSnapshotCodec.decode(bytes)))
        for (size in bytes.indices) assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(bytes.copyOf(size)) }
        store.armed(next.generation, session)
        val cold = store.reconstruct()!!
        assertEquals(HeartbeatResumeBlock.COLD_RECONSTRUCTION, cold.resumeBlock)
        assertEquals(next.durableConsent, cold.durableConsent)
        assertNull(store.prepareResume(cold, next.durableConsent.epoch))
    }

    @Test fun cleanAcknowledgementPreservesConsentButOffRevokesItAndStickyFaultSurvivesAck() {
        val store = GmsAutoHeartbeatStore(HeartbeatMemoryStorage())
        val prepared = store.prepareConfirmation(null, digest, true)!!
        store.armed(prepared.generation, session)
        val stopping = store.pause(prepared.generation, HeartbeatPause.OFF_PENDING)!!
        val waiting = store.acknowledgeStop(stopping.generation, clean = true)!!
        assertEquals(HeartbeatPause.WAITING_HOST, waiting.pause)
        val resumed = store.prepareResume(waiting, waiting.durableConsent!!.epoch)!!
        assertEquals(waiting.durableConsent, resumed.durableConsent)
        assertTrue(resumed.generation > waiting.generation)
        assertNull(store.prepareResume(waiting, waiting.durableConsent.epoch))
        store.block(resumed.generation, HeartbeatResumeBlock.IDENTITY_CHANGED)
        store.pause(resumed.generation, HeartbeatPause.OFF_PENDING)
        val blocked = store.acknowledgeStop(resumed.generation, clean = true)!!
        assertEquals(HeartbeatResumeBlock.IDENTITY_CHANGED, blocked.resumeBlock)
        assertEquals(HeartbeatPause.RECONFIRM, blocked.pause)
        val off = store.off(false)
        assertNull(off.durableConsent); assertEquals(HeartbeatResumeBlock.IDENTITY_CHANGED, off.resumeBlock)
    }

    @Test fun pendingAndCurrentEpochUnknownBlockButHistoricalUnknownDoesNotPoisonNewConsent() {
        val store = GmsAutoHeartbeatStore(HeartbeatMemoryStorage())
        val prepared = store.prepareConfirmation(null, digest, true)!!
        store.armed(prepared.generation, session); store.beginAttempt(prepared.generation, attempt)
        val unknown = store.complete(prepared.generation, attempt, GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT))!!
        assertTrue(unknown.currentEpochUnknown()); assertEquals(HeartbeatResumeBlock.UNKNOWN_RESULT, unknown.resumeBlock)
        store.pause(unknown.generation, HeartbeatPause.OFF_PENDING)
        val stopped = store.acknowledgeStop(unknown.generation, clean = true)!!
        assertEquals(HeartbeatResumeBlock.UNKNOWN_RESULT, stopped.resumeBlock)
        val fresh = store.prepareConfirmation(stopped, digest, true)!!
        assertEquals(GmsHeartbeatStatus.UNKNOWN, fresh.lastStatus); assertFalse(fresh.currentEpochUnknown())
        assertNotEquals(unknown.durableConsent!!.epoch, fresh.durableConsent!!.epoch)
        store.armed(fresh.generation, session); store.pause(fresh.generation, HeartbeatPause.OFF_PENDING)
        val clean = store.acknowledgeStop(fresh.generation, clean = true)!!
        assertEquals(HeartbeatPause.WAITING_HOST, clean.pause)
        assertNotNull(store.prepareResume(clean, fresh.durableConsent.epoch))
    }

    @Test fun offWithPendingRetainsStickyEvidenceAndCannotResumeOrOverwriteOnFailedWrite() {
        val storage = HeartbeatMemoryStorage(); val store = GmsAutoHeartbeatStore(storage)
        val prepared = store.prepareConfirmation(null, digest, true)!!
        store.armed(prepared.generation, session); store.beginAttempt(prepared.generation, attempt)
        val off = store.off(true)
        assertEquals(HeartbeatResumeBlock.LEFTOVER_PENDING, off.resumeBlock); assertNull(off.durableConsent)
        val bytes = storage.bytes!!.clone(); storage.failWrite = true
        assertThrows(Exception::class.java) { store.acknowledgeStop(off.generation, true) }
        assertArrayEquals(bytes, storage.bytes)
    }
}
