package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Models independently durable barriers; AtomicFile may roll back only the snapshot. */
internal class ColdHeartbeatStorage : HeartbeatStorage {
    val memory = HeartbeatMemoryStorage()
    val events = mutableListOf<String>()
    var haltPresent = false
    var pendingValue: HeartbeatPending? = null
    var damagedPending = false
    var interruptedAtomic = false
    var failAt: String? = null
    private fun boundary(name: String) { events += name; check(failAt != name) }
    override fun <T> exclusive(action: () -> T): T = action()
    override fun read() = memory.read()
    override fun write(value: HeartbeatSnapshot) { boundary("snapshot"); memory.write(value) }
    override fun coldBarrier(): HeartbeatResumeBlock? = when {
        haltPresent -> HeartbeatResumeBlock.COLD_RECONSTRUCTION
        damagedPending || pendingValue != null -> HeartbeatResumeBlock.LEFTOVER_PENDING
        interruptedAtomic -> HeartbeatResumeBlock.STORAGE_FAILURE
        else -> null
    }
    override fun unsettledPending() = pendingValue != null || damagedPending
    override fun halt() { haltPresent = true; boundary("halt-fsync"); boundary("halt-dirsync") }
    override fun pending(value: HeartbeatPending) {
        check(!unsettledPending()); pendingValue = value
        boundary("pending-fsync"); boundary("pending-dirsync")
    }
    override fun clearPending(value: HeartbeatPending) {
        check(pendingValue == value); boundary("delete-pending")
        pendingValue = null; boundary("delete-dirsync")
    }
    override fun releaseForExplicitConsent() {
        check(!unsettledPending()); boundary("release-halt")
        haltPresent = false; boundary("release-dirsync")
    }
}

class GmsHeartbeatColdStoreTest {
    private val digest = "b".repeat(64)
    private val oldOwner = GmsHeartbeatCallerIdentity(10123, 4001, 123L, "12345678-1234-1234-1234-123456789abc")
    private val newOwner = oldOwner.copy(pid = 4002, startTicks = 124L)
    private fun id() = UUID.randomUUID().toString()
    private fun consent(storage: ColdHeartbeatStorage): Pair<GmsAutoHeartbeatStore, HeartbeatSnapshot> {
        val store = GmsAutoHeartbeatStore(storage)
        return store to requireNotNull(store.prepareConfirmation(null, digest, true, oldOwner))
    }
    private fun armed(storage: ColdHeartbeatStorage): Pair<GmsAutoHeartbeatStore, HeartbeatSnapshot> {
        val (store, initial) = consent(storage)
        val op = id(); val intent = store.beginArm(initial.generation, op)!!
        val active = store.armed(intent.generation, id())!!; store.settleArm(active, op)
        store.releaseExplicitConsent(active)
        return store to active
    }
    @Test fun schema3RequiresExplicitQualifiedConsentAndStrictOwnerRoundTrip() {
        val storage = ColdHeartbeatStorage(); val (_, value) = consent(storage)
        assertEquals(3, value.schemaVersion); assertEquals(3, value.durableConsent!!.version)
        assertEquals(oldOwner, value.callerIdentity)
        val bytes = HeartbeatSnapshotCodec.encode(value)
        assertEquals(value, HeartbeatSnapshotCodec.decode(bytes))
        for (n in bytes.indices) assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(bytes.copyOf(n)) }
        assertThrows(Exception::class.java) { value.copy(schemaVersion = 2) }
        assertThrows(Exception::class.java) { value.copy(callerIdentity = null) }
        assertThrows(Exception::class.java) { value.copy(durableConsent = value.durableConsent.copy(version = 4)) }
        assertThrows(Exception::class.java) { value.copy(durableConsent = value.durableConsent.copy(version = 0)) }
        val legacy = value.copy(durableConsent = value.durableConsent.copy(version = 2))
        assertEquals(legacy, HeartbeatSnapshotCodec.decode(HeartbeatSnapshotCodec.encode(legacy)))
        assertThrows(Exception::class.java) { legacy.copy(callerIdentity = null) }
    }
    @Test fun coldCandidatePreservesOldOwnerAndEpochThenPersistsNewOwnerBeforeArm() {
        val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage)
        val cold = store.reconstruct()!!
        assertEquals(HeartbeatPause.WAITING_HOST, cold.pause); assertNull(cold.resumeBlock)
        assertEquals(oldOwner, cold.callerIdentity); assertNull(cold.remoteSessionId)
        assertEquals(active.durableConsent, cold.durableConsent)
        val resumed = store.prepareResume(cold, cold.durableConsent!!.epoch, newOwner)!!
        assertEquals(newOwner, HeartbeatSnapshotCodec.decode(storage.memory.bytes!!).callerIdentity)
        assertTrue(resumed.generation > cold.generation)
        assertNull(store.prepareResume(cold, cold.durableConsent.epoch, newOwner))
    }
    @Test fun legacyVersionTwoRemainsUnchangedUntilNewExplicitConfirmationCreatesNewEpoch() {
        val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage)
        val legacy = active.copy(durableConsent = active.durableConsent!!.copy(version = 2))
        storage.write(legacy)
        val cold = store.reconstruct()!!
        assertEquals(legacy.durableConsent, cold.durableConsent)
        assertEquals(2, cold.durableConsent!!.version)
        val off = store.off(false)
        val fresh = store.prepareConfirmation(off, digest, true, newOwner)!!
        assertEquals(3, fresh.durableConsent!!.version)
        assertNotEquals(legacy.durableConsent!!.epoch, fresh.durableConsent.epoch)
        assertEquals(3, fresh.schemaVersion)
    }
    @Test fun checksumValidUnknownConsentVersionIsRejectedByCodec() {
        val (_, value) = consent(ColdHeartbeatStorage())
        val bytes = HeartbeatSnapshotCodec.encode(value)
        val payload = bytes.copyOf(bytes.size - 32)
        val input = DataInputStream(ByteArrayInputStream(payload))
        input.readInt(); input.readInt(); input.readLong(); input.readBoolean(); input.readInt()
        repeat(3) { input.readUTF() }
        input.readInt(); input.readInt(); assertTrue(input.readBoolean())
        val versionOffset = payload.size - input.available()
        input.close()
        for (version in listOf(0, 4)) {
            val invalid = payload.clone()
            ByteBuffer.wrap(invalid).putInt(versionOffset, version)
            val encoded = invalid + MessageDigest.getInstance("SHA-256").digest(invalid)
            assertThrows(Exception::class.java) { HeartbeatSnapshotCodec.decode(encoded) }
        }
    }
    @Test fun legacyConsentDoesNotBecomeColdCandidate() {
        for (durable in listOf(false, true)) {
            val storage = ColdHeartbeatStorage(); val store = GmsAutoHeartbeatStore(storage)
            val old = store.prepareConfirmation(null, digest, durable)!!
            store.armed(old.generation, id())
            val cold = store.reconstruct()!!
            assertEquals(HeartbeatPause.RECONFIRM, cold.pause)
            assertTrue(cold.schemaVersion < 3)
            if (durable) assertEquals(HeartbeatResumeBlock.COLD_RECONSTRUCTION, cold.resumeBlock)
        }
    }
    @Test fun haltPendingCorruptionAndAtomicBackupInterruptEachBlockCleanOldOnRollback() {
        for (barrier in listOf("halt", "pending", "damaged", "atomic")) {
            val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage)
            val oldOn = storage.memory.bytes!!.clone()
            when (barrier) {
                "halt" -> storage.haltPresent = true
                "pending" -> storage.pendingValue = HeartbeatPending(HeartbeatPendingKind.ARM, active.durableConsent!!.epoch, active.generation, id())
                "damaged" -> storage.damagedPending = true
                "atomic" -> storage.interruptedAtomic = true
            }
            storage.memory.bytes = oldOn // Atomic rollback cannot roll back independent barriers.
            val cold = store.reconstruct()!!
            assertEquals(HeartbeatPause.RECONFIRM, cold.pause); assertNotNull(cold.resumeBlock)
            assertNull(store.prepareResume(cold, cold.durableConsent!!.epoch, newOwner))
        }
    }
    @Test fun offHaltCommitsBeforeSnapshotAndEveryFailureKeepsColdBlocked() {
        for (failure in listOf("halt-fsync", "halt-dirsync", "snapshot")) {
            val storage = ColdHeartbeatStorage(); val (store, _) = armed(storage)
            storage.events.clear(); storage.failAt = failure
            assertThrows(Exception::class.java) { store.off(true) }
            assertEquals("halt-fsync", storage.events.first()); assertTrue(storage.haltPresent)
            storage.failAt = null
            assertNotNull(store.reconstruct()!!.resumeBlock)
        }
    }
    @Test fun stickyBlockSnapshotFailureCannotRollbackToColdAuthority() {
        val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage)
        val oldOn = storage.memory.bytes!!.clone(); storage.events.clear(); storage.failAt = "snapshot"
        assertThrows(Exception::class.java) { store.block(active.generation, HeartbeatResumeBlock.SECURITY_FAILURE) }
        assertEquals(listOf("halt-fsync", "halt-dirsync", "snapshot"), storage.events)
        storage.memory.bytes = oldOn; storage.failAt = null
        val cold = store.reconstruct()!!
        assertNotNull(cold.resumeBlock); assertEquals(HeartbeatPause.RECONFIRM, cold.pause)
    }
    @Test fun eachArmIntentBoundaryLeavesMarkerAndNoColdAuthority() {
        for (failure in listOf("pending-fsync", "pending-dirsync", "snapshot")) {
            val storage = ColdHeartbeatStorage(); val (store, initial) = consent(storage)
            storage.events.clear(); storage.failAt = failure
            assertThrows(Exception::class.java) { store.beginArm(initial.generation, id()) }
            assertEquals("pending-fsync", storage.events.first()); assertNotNull(storage.pendingValue)
            storage.failAt = null
            assertNotNull(store.reconstruct()!!.resumeBlock)
            assertNull(store.prepareConfirmation(store.read(), digest, true, newOwner))
        }
    }
    @Test fun knownArmCommitsBeforeMarkerDeletionAndFailuresRemainConservative() {
        for (failure in listOf("snapshot", "delete-pending", "delete-dirsync")) {
            val storage = ColdHeartbeatStorage(); val (store, initial) = consent(storage)
            val op = id(); store.beginArm(initial.generation, op); storage.events.clear()
            storage.failAt = failure
            assertThrows(Exception::class.java) {
                val active = store.armed(initial.generation, id())!!; store.settleArm(active, op)
            }
            assertEquals("snapshot", storage.events.first())
            storage.failAt = null
            if (failure != "delete-dirsync") assertNotNull(store.reconstruct()!!.resumeBlock)
            // On directory-sync failure, production local storage block prevents subsequent dispatch.
        }
    }
    @Test fun unknownHeartbeatMarkerSurvivesOffRevokeAckAndReconfirmation() {
        val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage); val op = id()
        store.beginAttempt(active.generation, op)
        val unknown = store.complete(active.generation, op, GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT))!!
        store.settleAttempt(unknown, op, GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT))
        assertNotNull(storage.pendingValue); assertEquals(op, unknown.pendingAttempt)
        val off = store.off(true); val stopped = store.acknowledgeStop(off.generation)!!
        assertEquals(op, stopped.pendingAttempt); assertTrue(store.unsettledOperation())
        assertNull(store.prepareConfirmation(stopped, digest, true, newOwner))
        assertFalse(stopped.configured); assertNull(stopped.durableConsent)
    }
    @Test fun everyHeartbeatIntentAndKnownResultBoundaryKeepsSnapshotBeforeDelete() {
        for (failure in listOf("pending-fsync", "pending-dirsync", "snapshot")) {
            val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage)
            storage.events.clear(); storage.failAt = failure
            assertThrows(Exception::class.java) { store.beginAttempt(active.generation, id()) }
            assertEquals("pending-fsync", storage.events.first()); assertNotNull(storage.pendingValue)
            storage.failAt = null; assertNotNull(store.reconstruct()!!.resumeBlock)
        }
        for (failure in listOf("snapshot", "delete-pending", "delete-dirsync")) {
            val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage); val op = id()
            store.beginAttempt(active.generation, op); storage.events.clear(); storage.failAt = failure
            val result = GmsHeartbeatResult.notSent(1, GmsHeartbeatReason.CANCELLED)
            assertThrows(Exception::class.java) {
                val done = store.complete(active.generation, op, result)!!; store.settleAttempt(done, op, result)
            }
            assertEquals("snapshot", storage.events.first())
            if (failure != "delete-dirsync") assertNotNull(storage.pendingValue)
        }
    }
    @Test fun knownHeartbeatResultSnapshotAlwaysPrecedesDeleteAndStaleEpochCannotSettle() {
        val storage = ColdHeartbeatStorage(); val (store, active) = armed(storage); val op = id()
        store.beginAttempt(active.generation, op); storage.events.clear()
        val result = GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)
        val done = store.complete(active.generation, op, result)!!; store.settleAttempt(done, op, result)
        assertEquals(listOf("snapshot", "delete-pending", "delete-dirsync"), storage.events)
        assertNull(storage.pendingValue)
        val off = store.off(false)
        assertNull(store.complete(active.generation, op, result)); assertEquals(off, store.read())
    }
    @Test fun haltOnlyReleasesAfterQualifiedNewEpochArmedSnapshotAndNoUnsettledOperation() {
        val storage = ColdHeartbeatStorage(); val (store, _) = armed(storage)
        val off = store.off(false); storage.events.clear()
        val next = store.prepareConfirmation(off, digest, true, newOwner)!!
        assertEquals(listOf("halt-fsync", "halt-dirsync", "snapshot"), storage.events)
        assertTrue(storage.haltPresent); assertEquals(newOwner, next.callerIdentity)
        assertEquals(3, next.schemaVersion)
        assertThrows(Exception::class.java) { store.releaseExplicitConsent(next) }
        val op = id(); store.beginArm(next.generation, op)
        val active = store.armed(next.generation, id())!!; store.settleArm(active, op)
        store.releaseExplicitConsent(active)
        assertFalse(storage.haltPresent)
    }
    @Test fun pendingCodecRejectsTruncationCorruptionTrailingBytesAndWrongOperation() {
        val value = HeartbeatPending(HeartbeatPendingKind.ARM, id(), 7L, id())
        val bytes = HeartbeatPendingCodec.encode(value)
        assertEquals(value, HeartbeatPendingCodec.decode(bytes))
        for (size in bytes.indices) assertThrows(Exception::class.java) { HeartbeatPendingCodec.decode(bytes.copyOf(size)) }
        for (index in bytes.indices) {
            val changed = bytes.clone(); changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { HeartbeatPendingCodec.decode(changed) }
        }
        assertThrows(Exception::class.java) { HeartbeatPendingCodec.decode(bytes + 0) }
    }
}
