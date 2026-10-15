package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

class DaemonMigrationPointerTest {
    private val intent = DaemonMigrationIntent(DaemonOwnerKey("0e12ad2f-6d90-41b7-8f34-41bac1d128f8", 1),
        "7f231d03-499d-4dc7-863a-07599b9b38c4")
    private val digest = "a".repeat(64)
    private val stopId = "fb030f1c-9fd0-4535-86c5-09a44bb9c7e6"
    private val target = SelectedTargetIdentity("example.recipient", 10435, 100, listOf("a".repeat(64)))
    private fun stop(kind: Int = DaemonWire.MASTER_OFF, id: String = stopId) =
        DaemonAppStopIntent(id, intent, DaemonAppCommand(kind, target.takeIf { kind == DaemonWire.TARGET_OFF }))
    private fun challenge(revision: Long = 1, mode: DaemonControlMode = DaemonControlMode.ACTIVE,
        nonce: String = "nonce-$revision") = DaemonPointerChallenge(nonce, DaemonPointerPurpose.TRANSITION,
        DaemonPointerCommitProof(nonce, DaemonControlRecord(intent.owner, revision, digest, mode, intent.migrationId)), digest)
    private class Files : DaemonMigrationFilePort {
        val values = mutableMapOf<DaemonMigrationFile, ByteArray>()
        val operations = mutableListOf<String>()
        var writeCount = 0; var failWrite = Int.MAX_VALUE; var failRemove = false; var failRead = false
        var throwAfterWrite = false; var corruptAfterWrite = false
        override fun read(file: DaemonMigrationFile): ByteArray? {
            check(!failRead); return values[file]?.copyOf()
        }
        override fun write(file: DaemonMigrationFile, bytes: ByteArray) {
            operations += "write:$file"
            check(++writeCount != failWrite); values[file] = bytes.copyOf()
            if (corruptAfterWrite) { corruptAfterWrite = false; values[file]!![0] = (values[file]!![0].toInt() xor 1).toByte() }
            if (throwAfterWrite) { throwAfterWrite = false; error("Ambiguous write result") }
        }
        override fun remove(file: DaemonMigrationFile) {
            operations += "remove:$file"
            check(!failRemove); values.remove(file)
        }
        override fun <T> exclusive(action: () -> T) = action()
    }
    @Test fun onlyEntirelyEmptyStorageAllowsOldExecutor() {
        val files = Files(); val store = DaemonMigrationPointerStore(files)
        assertTrue(store.localExecutionAllowed())
        assertTrue(store.begin(intent)); assertFalse(store.localExecutionAllowed())
        assertEquals(DaemonMigrationRead.Migrating(intent), store.read())
        assertTrue(store.begin(intent)); assertFalse(store.localExecutionAllowed())
    }
    @Test fun privateCommitReadbackPrecedesExactAttestation() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        val challenge = challenge()
        assertEquals(DaemonAppPointerAttestation(challenge, challenge.pointer), store.commit(intent, digest, challenge))
        assertEquals(DaemonMigrationRead.Remote(DaemonRemotePointer(intent, challenge.pointer, digest)), store.read())
        assertFalse(files.values.containsKey(DaemonMigrationFile.PENDING))
        assertFalse(store.localExecutionAllowed())
    }
    @Test fun failedReplacementCannotRestoreOlderActivePointer() {
        listOf(2, 3).forEach { offset ->
            val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
            assertNotNull(store.commit(intent, digest, challenge()))
            // Pending first, pointer second. Test pointer write failure and pending removal failure.
            if (offset == 2) files.failWrite = files.writeCount + 2 else files.failRemove = true
            assertNull(store.commit(intent, digest, challenge(2, DaemonControlMode.OFF)))
            assertEquals(DaemonMigrationRead.Blocked, store.read()); assertFalse(store.localExecutionAllowed())
            assertFalse(DaemonMigrationPointerStore(files).localExecutionAllowed())
        }
    }
    @Test fun malformedOrOrphanedFilesBlockRatherThanTreatAsFirstUse() {
        DaemonMigrationFile.entries.forEach { role ->
            val files = Files(); files.values[role] = byteArrayOf(1)
            assertEquals(DaemonMigrationRead.Blocked, DaemonMigrationPointerStore(files).read())
        }
        val files = Files(); files.failRead = true
        assertFalse(DaemonMigrationPointerStore(files).localExecutionAllowed())
    }
    @Test fun cannotSkipRevisionOrReplaceImportAndInstallation() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        assertNull(store.commit(intent, digest, challenge(3)))
        assertNull(store.commit(intent, "b".repeat(64), challenge(2)))
        assertFalse(store.begin(intent.copy(owner = intent.owner.copy(ownerEpoch = 2))))
        assertNotNull(store.commit(intent, digest, challenge(2, DaemonControlMode.OFF)))
    }
    @Test fun reconciliationCanReplaceNonceWithoutChangingCurrentRecord() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        val reconcile = challenge(nonce = "new-reconcile").copy(purpose = DaemonPointerPurpose.RECONCILE)
        assertEquals(DaemonAppPointerAttestation(reconcile, reconcile.pointer), store.commit(intent, digest, reconcile))
    }
    @Test fun codecsAreExactBoundedAndRejectCorruption() {
        assertEquals(intent, DaemonMigrationPointerCodec.decodeIntent(DaemonMigrationPointerCodec.encodeIntent(intent)))
        val value = DaemonRemotePointer(intent, challenge().pointer, digest)
        val bytes = DaemonMigrationPointerCodec.encodePointer(value)
        assertEquals(value, DaemonMigrationPointerCodec.decodePointer(bytes))
        bytes[8] = (bytes[8].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationPointerCodec.decodePointer(bytes) }
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationPointerCodec.decodePointer(ByteArray(2049)) }
    }

    @Test fun coldPendingOffInspectionDoesNotAuthorizeUntilExactFreshTransitionIsCommitted() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        files.failWrite = files.writeCount + 2
        val interrupted = challenge(2, DaemonControlMode.OFF)
        assertNull(store.commit(intent, digest, interrupted))
        val restarted = DaemonMigrationPointerStore(files)
        val view = restarted.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        assertEquals(intent, view.intent)
        assertEquals(DaemonControlMode.ACTIVE, view.pointer!!.committed.record.mode)
        assertEquals(interrupted.pointer, view.candidate.committed)
        assertEquals(DaemonMigrationRead.Blocked, restarted.read())
        assertFalse(restarted.localExecutionAllowed())
        files.operations.clear()
        val fresh = challenge(2, DaemonControlMode.OFF, "fresh-host-transition")
        assertEquals(DaemonAppPointerAttestation(fresh, fresh.pointer), restarted.recoverPending(view, fresh))
        assertEquals(listOf("write:PENDING", "write:POINTER", "remove:PENDING"), files.operations)
        assertEquals(DaemonMigrationRead.Remote(DaemonRemotePointer(intent, fresh.pointer, digest)), restarted.read())
        assertFalse(restarted.localExecutionAllowed())
    }

    @Test fun pointerAlreadyReplacedCanOnlyRecoverCurrentCandidateNotOlderActiveFallback() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        files.failRemove = true
        assertNull(store.commit(intent, digest, challenge(2, DaemonControlMode.OFF)))
        val restarted = DaemonMigrationPointerStore(files)
        val view = restarted.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        assertEquals(view.candidate, view.pointer)
        files.failRemove = false
        val oldActive = challenge(nonce = "host-current-old-active").copy(purpose = DaemonPointerPurpose.RECONCILE)
        assertNull(restarted.recoverPending(view, oldActive))
        assertEquals(DaemonMigrationRead.Blocked, restarted.read())
        val current = challenge(2, DaemonControlMode.OFF, "host-current-off").copy(purpose = DaemonPointerPurpose.RECONCILE)
        assertEquals(DaemonAppPointerAttestation(current, current.pointer), restarted.recoverPending(view, current))
    }

    @Test fun initialPendingCandidateAndStaleArtifactsRequireExactFreshChallenge() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        files.failWrite = files.writeCount + 2
        val initial = challenge(2)
        assertNull(store.commit(intent, digest, initial))
        val view = store.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        assertNull(view.pointer)
        assertNull(store.recoverPending(view, initial)) // Original interrupted nonce is not fresh.
        val changed = view.candidate.copy(committed = initial.pointer.copy(commitId = "changed-pending"))
        files.values[DaemonMigrationFile.PENDING] = DaemonMigrationPointerCodec.encodePointer(changed)
        val fresh = challenge(2, nonce = "fresh-resume")
        assertNull(store.recoverPending(view, fresh)) // Even a nonce-only artifact change requires a new inspection.
        val current = store.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        assertEquals(DaemonAppPointerAttestation(fresh, fresh.pointer), store.recoverPending(current, fresh))
    }

    @Test fun inconsistentPendingArtifactsStayBlockedAndRecoveryFailureKeepsBarrier() {
        val old = DaemonRemotePointer(intent, challenge().pointer, digest)
        val otherIntent = intent.copy(owner = intent.owner.copy(ownerEpoch = 2))
        val badCandidates = listOf(
            DaemonRemotePointer(intent, challenge(2).pointer, "b".repeat(64)),
            DaemonRemotePointer(intent, challenge(3).pointer, digest),
            DaemonRemotePointer(intent, challenge(1, DaemonControlMode.OFF).pointer, digest),
            DaemonRemotePointer(otherIntent, challenge(2).pointer.let {
                it.copy(record = it.record.copy(owner = otherIntent.owner))
            }, digest),
        )
        badCandidates.forEach { candidate ->
            val files = Files()
            files.values[DaemonMigrationFile.INTENT] = DaemonMigrationPointerCodec.encodeIntent(intent)
            files.values[DaemonMigrationFile.POINTER] = DaemonMigrationPointerCodec.encodePointer(old)
            files.values[DaemonMigrationFile.PENDING] = DaemonMigrationPointerCodec.encodePointer(candidate)
            val store = DaemonMigrationPointerStore(files)
            assertEquals(DaemonMigrationRecoveryRead.Blocked, store.inspectForRecovery())
            assertFalse(store.localExecutionAllowed())
        }
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        files.failWrite = files.writeCount + 2
        assertNull(store.commit(intent, digest, challenge(2)))
        val view = store.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        files.failRemove = true
        assertNull(store.recoverPending(view, challenge(2, nonce = "fresh-but-remove-failed")))
        assertEquals(DaemonMigrationRead.Blocked, store.read())
        assertFalse(store.localExecutionAllowed())
        files.failRead = true // Models unknown/unreadable AtomicFile side artifacts as well.
        assertEquals(DaemonMigrationRecoveryRead.Blocked, store.inspectForRecovery())
    }

    @Test fun stopCodecBindsOnlyNegativeCommandsAndExactTargetWithStrictBounds() {
        listOf(DaemonWire.MASTER_OFF, DaemonWire.FCM_OFF, DaemonWire.TARGET_OFF).forEach { kind ->
            val value = stop(kind)
            assertEquals(value, DaemonMigrationPointerCodec.decodeStopIntent(DaemonMigrationPointerCodec.encodeStopIntent(value)))
        }
        listOf(DaemonWire.MASTER_ON, DaemonWire.FCM_ON, DaemonWire.TARGET_ON, DaemonWire.ACTIVATE).forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) { DaemonAppStopIntent(stopId, intent, DaemonAppCommand(kind, target)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DaemonAppStopIntent("not-a-uuid", intent, DaemonAppCommand(DaemonWire.MASTER_OFF)) }
        assertThrows(IllegalArgumentException::class.java) { DaemonAppStopIntent(stopId, intent, DaemonAppCommand(DaemonWire.TARGET_OFF)) }
        assertThrows(IllegalArgumentException::class.java) { DaemonAppStopIntent(stopId, intent, DaemonAppCommand(DaemonWire.MASTER_OFF, target)) }
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationPointerCodec.decodeStopIntent(ByteArray(2049)) }
        val encoded = DaemonMigrationPointerCodec.encodeStopIntent(stop())
        val body = encoded.copyOf(encoded.size - 32)
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            input.readInt(); input.readInt(); input.readUTF(); input.readUTF(); input.readLong(); input.readUTF()
            ByteBuffer.wrap(body).putInt(body.size - input.available(), DaemonWire.MASTER_ON)
        }
        val forgedOn = body + MessageDigest.getInstance("SHA-256").digest(body)
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationPointerCodec.decodeStopIntent(forgedOn) }
    }

    @Test fun durableStopAcceptsIntentBeforePointerAndUsesExactIdempotentSaveAndClearCas() {
        val files = Files(); val store = DaemonMigrationPointerStore(files)
        val value = stop(DaemonWire.TARGET_OFF)
        assertFalse(store.saveStopIntent(value)); assertTrue(store.localExecutionAllowed())
        store.begin(intent)
        assertTrue(store.saveStopIntent(value)); assertFalse(store.localExecutionAllowed())
        assertEquals(value, DaemonMigrationPointerStore(files).readStopIntent())
        assertEquals(DaemonMigrationRead.Migrating(intent), store.read())
        assertFalse(store.clearStopIntent(value)) // No remote OFF acknowledgement yet.
        assertNotNull(store.commit(intent, digest, challenge()))
        val remote = store.read()
        assertTrue(store.saveStopIntent(value))
        val writes = files.writeCount
        assertTrue(store.saveStopIntent(value)); assertEquals(writes, files.writeCount)
        assertEquals(value, store.readStopIntent()); assertEquals(remote, store.read())
        assertFalse(store.saveStopIntent(stop(DaemonWire.FCM_OFF)))
        assertFalse(store.clearStopIntent(value.copy(id = "72004302-552d-460c-a271-dcc408a5528a")))
        assertFalse(store.clearStopIntent(value.copy(command = value.command.copy(target = target.copy(firstInstallTime = 200)))))
        assertEquals(value, store.readStopIntent())
        files.failRemove = true
        assertFalse(store.clearStopIntent(value)); assertEquals(value, store.readStopIntent())
        files.failRemove = false
        assertTrue(store.clearStopIntent(value)); assertNull(store.readStopIntent())
        assertFalse(store.clearStopIntent(value)); assertEquals(remote, store.read())
    }

    @Test fun stopSurvivesInterruptedPointerCommitAndFreshPointerRecovery() {
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        val value = stop()
        assertTrue(store.saveStopIntent(value))
        files.failWrite = files.writeCount + 2
        assertNull(store.commit(intent, digest, challenge(2, DaemonControlMode.OFF)))
        assertEquals(DaemonMigrationRead.Blocked, store.read())
        assertEquals(value, store.readStopIntent())
        assertFalse(store.clearStopIntent(value))
        val recovery = store.inspectForRecovery() as DaemonMigrationRecoveryRead.Pending
        val fresh = challenge(2, DaemonControlMode.OFF, "fresh-stop-resume")
        assertNotNull(store.recoverPending(recovery, fresh))
        assertEquals(value, store.readStopIntent())
        assertTrue(store.clearStopIntent(value))
    }

    @Test fun orphanMismatchedCorruptAndAmbiguousStopNeverFallbackToLocalOrDisappearAsAbsent() {
        val value = stop()
        val orphan = Files().also { it.values[DaemonMigrationFile.STOP] = DaemonMigrationPointerCodec.encodeStopIntent(value) }
        assertEquals(DaemonMigrationRead.Blocked, DaemonMigrationPointerStore(orphan).read())
        assertEquals(DaemonMigrationRecoveryRead.Blocked, DaemonMigrationPointerStore(orphan).inspectForRecovery())
        assertThrows(IllegalStateException::class.java) { DaemonMigrationPointerStore(orphan).readStopIntent() }
        val files = Files(); val store = DaemonMigrationPointerStore(files); store.begin(intent)
        assertNotNull(store.commit(intent, digest, challenge()))
        files.throwAfterWrite = true
        assertFalse(store.saveStopIntent(value))
        assertEquals(value, DaemonMigrationPointerStore(files).readStopIntent())
        assertTrue(store.read() is DaemonMigrationRead.Remote)
        assertFalse(store.localExecutionAllowed())
        assertTrue(store.clearStopIntent(value))
        files.corruptAfterWrite = true
        assertFalse(store.saveStopIntent(value))
        assertEquals(DaemonMigrationRead.Blocked, store.read())
        assertThrows(IllegalArgumentException::class.java) { store.readStopIntent() }
        val otherIntent = intent.copy(owner = intent.owner.copy(ownerEpoch = 2))
        files.values[DaemonMigrationFile.STOP] = DaemonMigrationPointerCodec.encodeStopIntent(value.copy(intent = otherIntent))
        assertEquals(DaemonMigrationRead.Blocked, store.read())
        assertEquals(DaemonMigrationRecoveryRead.Blocked, store.inspectForRecovery())
        assertThrows(IllegalStateException::class.java) { store.readStopIntent() }
    }
}
