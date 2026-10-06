package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.DozePhase
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeCodec
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeConsent
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeDocument
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeTransaction
import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsGlobalInstallation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GmsGlobalAtomicDocumentStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val installation = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val consent = GmsGlobalDozeConsent(true, 1, 1, 1000, installation,
        GmsGlobalGuardianBinding(0, installation.copy(appId = 10493)), "fingerprint")
    private val initial = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(consent))
    private val baseline = "system,android,1000\nuser,com.other.example,11111\n"
    private val intent = GmsGlobalDozeTransaction("00000000-0000-0000-0000-000000000001", 1,
        installation, 1, baseline, null, DozePhase.ADD_INTENT)
    private val next = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(consent.copy(generation = 2), intent))

    /** Real files, fsync and store-owned OS locks; AtomicFile rename behavior is injected for JVM. */
    private class Access(val directory: File) : GmsGlobalDocumentFileAccess {
        val base = File(directory, GmsGlobalAtomicDocumentStore.FILE_NAME)
        val backup = File(base.path + ".bak")
        val pending = File(base.path + ".new")
        val initialized = File(directory, GmsGlobalAtomicDocumentStore.INITIALIZED_NAME)
        val pendingCommit = File(directory, GmsGlobalAtomicDocumentStore.COMMIT_PENDING_NAME)
        val events = mutableListOf<String>()
        var failSync = false
        var failFinish = false
        var directoryFailureAt = 0
        var directorySyncs = 0
        var removePendingOnFailure = false
        var failMarkerRecovery = false
        var markerRecoveries = 0
        var afterFinish: (() -> Unit)? = null
        var beforeStart: (() -> Unit)? = null
        var sharedReadBytes: ByteArray? = null
        override val canonicalPath: String = base.canonicalPath
        override fun hasAnyArtifact(): Boolean = base.exists() || backup.exists() || pending.exists()
        override fun readStrictlyBounded(maxBytes: Int): ByteArray {
            events += "read"
            if (backup.exists() || pending.exists()) throw IOException("Orphan AtomicFile artifact")
            val read = FileInputStream(base).use { it.readGlobalGmsDocumentBounded(maxBytes) }
            return sharedReadBytes ?: read
        }
        override fun startWrite(): FileOutputStream {
            assertTrue(initialized.exists() && pendingCommit.exists())
            assertTrue(directorySyncs >= 2)
            events += "start"
            beforeStart?.invoke()
            return FileOutputStream(pending)
        }
        override fun sync(output: FileOutputStream) {
            events += "sync"
            if (failSync) throw IOException("Injected fsync failure")
            output.fd.sync()
        }
        override fun finishWrite(output: FileOutputStream) {
            events += "finish"
            output.close()
            if (failFinish) throw IOException("Injected finish failure")
            Files.move(pending.toPath(), base.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            afterFinish?.invoke()
        }
        override fun failWrite(output: FileOutputStream?) {
            events += "fail"
            output?.close()
            if (removePendingOnFailure) pending.delete()
        }
        override fun syncDirectory() {
            // Android directory fsync is not executable under the Windows JVM; verify call order
            // and faults here. File bytes/rename/CAS and OS FileLock are real host operations.
            events += "directory"
            directorySyncs++
            if (directorySyncs == directoryFailureAt) throw IOException("Injected directory fsync failure")
        }
        override fun recoverCommitPendingMarker() {
            markerRecoveries++
            if (failMarkerRecovery) throw IOException("Injected commit marker recovery failure")
            check(pendingCommit.createNewFile())
            FileOutputStream(pendingCommit).use { it.write(1); it.fd.sync() }
        }
        fun store() = GmsGlobalAtomicDocumentStore(this, directory)
    }

    @Test fun absentOnlyBeforeAnyInitializationAndCommittedLoadIsDefensive() {
        val access = Access(folder.newFolder("absent"))
        val store = access.store()
        assertNull(store.load())
        assertTrue(File(access.directory, GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME).isFile)
        assertTrue(store.compareAndSet(null, initial))
        assertTrue(access.initialized.isFile)
        assertFalse(access.pendingCommit.exists())
        assertArrayEquals(initial, store.load())
        val returned = requireNotNull(store.load())
        returned[0] = 0
        assertArrayEquals(initial, access.store().load())
        access.sharedReadBytes = initial.clone()
        requireNotNull(store.load())[0] = 0
        assertArrayEquals(initial, access.sharedReadBytes)
        assertEquals(listOf("directory", "directory", "start", "sync", "finish", "directory", "read", "directory", "read"),
            access.events.take(9))
    }

    @Test fun exactCasAndTransitionsPreserveRawBytesAcrossReconstruction() {
        val access = Access(folder.newFolder("cas"))
        assertTrue(access.store().compareAndSet(null, initial))
        assertFalse(access.store().compareAndSet(null, next))
        assertThrows(IllegalArgumentException::class.java) { access.store().compareAndSet(initial, initial) }
        assertTrue(access.store().compareAndSet(initial, next))
        assertArrayEquals(next, access.store().load())
        assertEquals(baseline, GmsGlobalDozeCodec.decode(requireNotNull(access.store().load())).transaction?.baseline)
        assertFalse(access.store().compareAndSet(initial, next))
        assertFalse(access.pendingCommit.exists())
    }

    @Test fun pathIsFixedAndStorageDoesNotTouchSchema2() {
        val directory = folder.newFolder("fixed")
        val access = Access(directory)
        val legacy = File(directory, "doze-transaction-v2.bin")
        val legacyBytes = byteArrayOf(1, 2, 3, 4)
        legacy.writeBytes(legacyBytes)
        assertTrue(access.store().compareAndSet(null, initial))
        assertArrayEquals(legacyBytes, legacy.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            GmsGlobalAtomicDocumentStore(access, folder.newFolder("wrong"))
        }
    }

    @Test fun invalidFirstReplacementNeverInitializesOrCreatesAuthority() {
        val access = Access(folder.newFolder("invalid"))
        assertThrows(Exception::class.java) { access.store().compareAndSet(null, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { access.store().compareAndSet(null, next) }
        assertNull(access.store().load())
        assertFalse(access.initialized.exists() || access.pendingCommit.exists() || access.base.exists())
    }

    @Test fun corruptionAndAtomicFileArtifactsAreRetainedAndBlockCas() {
        for (artifact in listOf("corrupt", "backup", "pending")) {
            val access = Access(folder.newFolder(artifact))
            assertTrue(access.store().compareAndSet(null, initial))
            val evidence = when (artifact) {
                "backup" -> access.backup
                "pending" -> access.pending
                else -> access.base
            }
            evidence.writeBytes(byteArrayOf(9, 8))
            assertThrows(Exception::class.java) { access.store().load() }
            assertThrows(Exception::class.java) { access.store().compareAndSet(null, initial) }
            assertArrayEquals(byteArrayOf(9, 8), evidence.readBytes())
            if (evidence != access.base) assertArrayEquals(initial, access.base.readBytes())
        }
    }

    @Test fun orphanArtifactsMissingInitializedDocumentAndInvalidMarkersNeverLookAbsent() {
        for (artifact in listOf("base", "backup", "pending", "initialized", "empty-marker", "invalid-marker", "commit")) {
            val access = Access(folder.newFolder("orphan-$artifact"))
            when (artifact) {
                "base" -> access.base.writeBytes(initial)
                "backup" -> access.backup.writeBytes(initial)
                "pending" -> access.pending.writeBytes(initial)
                "initialized" -> access.initialized.writeBytes(byteArrayOf(1))
                "empty-marker" -> access.initialized.writeBytes(byteArrayOf())
                "invalid-marker" -> access.initialized.writeBytes(byteArrayOf(2))
                "commit" -> access.pendingCommit.writeBytes(byteArrayOf(1))
            }
            assertThrows(IOException::class.java) { access.store().load() }
            assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
        }
        val access = Access(folder.newFolder("lost-base"))
        assertTrue(access.store().compareAndSet(null, initial))
        assertTrue(access.base.delete())
        assertThrows(IOException::class.java) { access.store().load() }
        assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
        assertTrue(access.initialized.exists())
    }

    @Test fun interruptedInitializationRetainsMarkersAndNeverRetriesAsFirstUse() {
        val access = Access(folder.newFolder("init-failure"))
        access.directoryFailureAt = 1
        assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
        assertTrue(access.initialized.exists())
        assertTrue(access.pendingCommit.exists())
        assertFalse(access.base.exists())
        access.directoryFailureAt = 0
        assertThrows(IOException::class.java) { access.store().load() }
        assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
    }

    @Test fun failedSyncOrFinishCannotRecoverEvenWhenAtomicFileRemovesNewArtifact() {
        for (failFinish in listOf(false, true)) {
            val access = Access(folder.newFolder("failure-$failFinish"))
            assertTrue(access.store().compareAndSet(null, initial))
            access.failSync = !failFinish
            access.failFinish = failFinish
            access.removePendingOnFailure = true
            assertThrows(IOException::class.java) { access.store().compareAndSet(initial, next) }
            assertFalse(access.pending.exists())
            assertArrayEquals(initial, access.base.readBytes())
            assertTrue(access.pendingCommit.exists())
            assertThrows(IOException::class.java) { access.store().load() }
            assertThrows(IOException::class.java) { access.store().compareAndSet(initial, next) }
        }
    }

    @Test fun directorySyncFailureAfterRenameLeavesCommittedBytesBlocked() {
        val access = Access(folder.newFolder("directory-failure"))
        assertTrue(access.store().compareAndSet(null, initial))
        access.directoryFailureAt = access.directorySyncs + 2 // pending barrier passes; post-rename barrier fails.
        assertThrows(IOException::class.java) { access.store().compareAndSet(initial, next) }
        assertArrayEquals(next, access.base.readBytes())
        assertTrue(access.pendingCommit.exists())
        assertThrows(IOException::class.java) { access.store().load() }
    }

    @Test fun validButNonExactReadbackNeverAcknowledgesCommitOrRestoresAuthority() {
        val access = Access(folder.newFolder("readback"))
        assertTrue(access.store().compareAndSet(null, initial))
        access.afterFinish = { access.base.writeBytes(initial) }
        assertThrows(IOException::class.java) { access.store().compareAndSet(initial, next) }
        assertTrue(access.pendingCommit.exists())
        assertArrayEquals(initial, access.base.readBytes())
        assertThrows(IOException::class.java) { access.store().load() }
    }

    @Test fun failedCommitMarkerRemovalBarrierRecreatesBlockingEvidence() {
        val access = Access(folder.newFolder("marker-removal"))
        assertTrue(access.store().compareAndSet(null, initial))
        access.directoryFailureAt = access.directorySyncs + 3 // Failure after commit marker deletion.
        assertThrows(IOException::class.java) { access.store().compareAndSet(initial, next) }
        assertArrayEquals(next, access.base.readBytes())
        assertTrue(access.pendingCommit.exists())
        assertThrows(IOException::class.java) { access.store().load() }
    }

    @Test fun postDeleteFailureAndFailedMarkerRecoveryPermanentlyCloseAllLocalInstances() {
        val access = Access(folder.newFolder("terminal-double-failure"))
        val original = access.store()
        assertTrue(original.compareAndSet(null, initial))
        access.directoryFailureAt = access.directorySyncs + 3 // Marker deleted before this barrier.
        access.failMarkerRecovery = true
        assertThrows(IOException::class.java) { original.compareAndSet(initial, next) }
        assertEquals(1, access.markerRecoveries)
        assertFalse(access.pendingCommit.exists())
        assertArrayEquals(next, access.base.readBytes())
        assertTrue(GmsGlobalDozeCodec.decode(access.base.readBytes()).consent.enabled)
        access.directoryFailureAt = 0
        access.failMarkerRecovery = false
        for (store in listOf(original, access.store())) {
            assertThrows(IOException::class.java) { store.load() }
            assertThrows(IOException::class.java) { store.compareAndSet(next, initial) }
        }
        // This proves a same-process sticky fence only, not persistence after process recreation.
        assertFalse(access.pendingCommit.exists())
    }

    @Test fun boundedReadRejectsEmptyOversizedAndTruncatedChecksum() {
        for ((index, raw) in listOf(byteArrayOf(), ByteArray(GmsGlobalDozeCodec.MAX_BYTES + 1),
            initial.copyOf(initial.size - 1)).withIndex()) {
            val access = Access(folder.newFolder("size-$index"))
            access.initialized.writeBytes(byteArrayOf(1))
            access.base.writeBytes(raw)
            assertThrows(Exception::class.java) { access.store().load() }
            assertArrayEquals(raw, access.base.readBytes())
        }
    }

    @Test fun actualOsLockContentionBlocksReadAndCasWithoutCreatingMarkers() {
        val access = Access(folder.newFolder("os-lock"))
        RandomAccessFile(File(access.directory, GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME), "rw").use { handle ->
            handle.channel.lock().use {
                assertThrows(IOException::class.java) { access.store().load() }
                assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
                assertFalse(access.initialized.exists() || access.base.exists())
            }
        }
        assertTrue(access.store().compareAndSet(null, initial))
    }

    @Test fun versionedCyclePendingMarkerAndResidueBlockLegacyInitialization() {
        for ((index, suffix) in listOf("", ".new", ".bak").withIndex()) {
            val access = Access(folder.newFolder("cycle-marker-$index"))
            val marker = File(access.directory, "gms-global-doze-cycle.commit-fence$suffix")
            val retained = byteArrayOf(3, 4, 5)
            marker.writeBytes(retained)
            assertThrows(IOException::class.java) { access.store().load() }
            assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
            assertArrayEquals(retained, marker.readBytes())
            assertFalse(access.base.exists() || access.initialized.exists() || access.pendingCommit.exists())
        }
    }

    @Test fun legacyAndManualSchemesUseTheSameActualOsStorageLock() {
        assertEquals(GmsManualSessionDocumentStore.STORAGE_LOCK_NAME,
            GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME)
        val access = Access(folder.newFolder("shared-cycle-lock"))
        RandomAccessFile(File(access.directory, GmsManualSessionDocumentStore.STORAGE_LOCK_NAME), "rw").use { handle ->
            handle.channel.lock().use {
                assertThrows(IOException::class.java) { access.store().compareAndSet(null, initial) }
                assertFalse(access.base.exists() || access.initialized.exists() || access.pendingCommit.exists())
            }
        }
        assertTrue(access.store().compareAndSet(null, initial))
    }

    @Test fun canonicalPathMonitorSerializesTwoInstancesAndOnlyOneCasWins() {
        val directory = folder.newFolder("concurrent")
        val firstAccess = Access(directory)
        val secondAccess = Access(File(directory, "."))
        val firstStore = firstAccess.store()
        val secondStore = secondAccess.store()
        val entered = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        firstAccess.beforeStart = { entered.countDown(); check(continueWrite.await(5, TimeUnit.SECONDS)) }
        var firstResult = false
        var secondResult = true
        val firstWorker = thread { firstResult = firstStore.compareAndSet(null, initial) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val secondWorker = thread { secondResult = secondStore.compareAndSet(null, initial); secondFinished.countDown() }
        try { assertFalse(secondFinished.await(50, TimeUnit.MILLISECONDS)) }
        finally { continueWrite.countDown(); firstWorker.join(5000); secondWorker.join(5000) }
        assertFalse(firstWorker.isAlive || secondWorker.isAlive)
        assertTrue(firstResult)
        assertFalse(secondResult)
        assertArrayEquals(initial, secondStore.load())
    }
}
