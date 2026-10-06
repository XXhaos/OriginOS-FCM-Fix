package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCoordinator
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerOperation
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

class GmsDozeBrokerRecordStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val initial = GmsDozeBrokerCodec.initialState()
    private val initialBytes = GmsDozeBrokerCodec.encode(initial)
    private val enabled = initial.copy(revision = 1, preparationAllowed = true)
    private val enabledBytes = GmsDozeBrokerCodec.encode(enabled)

    /** Host files, fd.sync, rename and FileLock are real; Android directory fsync is fault-injected. */
    private class Access(val directory: File) : GmsDozeBrokerRecordFileAccess {
        val base = File(directory, GmsDozeBrokerRecordStore.FILE_NAME)
        val fence = File(directory, GmsDozeBrokerRecordStore.SESSION_FENCE_NAME)
        val pending = File(base.path + ".new")
        val events = mutableListOf<String>()
        var failAt: String? = null
        var directorySyncs = 0
        var beforeStart: (() -> Unit)? = null
        var readbackOverride: ByteArray? = null
        override val canonicalPath = base.canonicalPath

        private fun event(name: String) {
            events += name
            if (failAt == name) throw IOException("Injected $name failure")
        }

        override fun createAndSyncFence(expected: ByteArray) {
            event("fence-create")
            check(fence.createNewFile())
            FileOutputStream(fence).use { output ->
                event("fence-write")
                output.write(expected)
                event("fence-sync")
                output.fd.sync()
            }
        }

        override fun validateFence(expected: ByteArray) {
            event("fence-read")
            if (!fence.isFile || !fence.readBytes().contentEquals(expected)) throw IOException("Fence differs")
        }

        override fun startWrite(): FileOutputStream {
            assertTrue(fence.isFile && directorySyncs >= 1)
            event("record-start")
            beforeStart?.invoke()
            check(pending.createNewFile())
            return FileOutputStream(pending)
        }

        override fun sync(output: FileOutputStream) {
            event("record-sync")
            output.fd.sync()
        }

        override fun finishWrite(output: FileOutputStream) {
            output.close()
            event("record-finish")
            Files.move(pending.toPath(), base.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        }

        override fun syncDirectory() { event("directory-${++directorySyncs}") }

        override fun readStrictlyBounded(maxBytes: Int): ByteArray {
            event("record-read")
            if (pending.exists() || File(base.path + ".bak").exists()) throw IOException("Orphan record")
            val actual = FileInputStream(base).use { it.readGlobalGmsDocumentBounded(maxBytes) }
            return readbackOverride ?: actual
        }

        fun store(registry: GmsDozeBrokerProcessRegistry) = GmsDozeBrokerRecordStore(this, directory, registry)
    }

    @Test fun freshOffSameProcessExactCasAndDefensiveProofs() {
        val access = Access(folder.newFolder("fresh"))
        val registry = GmsDozeBrokerProcessRegistry()
        val store = access.store(registry)
        assertEquals(initial, store.readExact().state)
        assertFalse(access.fence.exists() || access.base.exists())
        val wrong = initialBytes.clone().also { it[0] = 0 }
        assertNull(store.compareAndSet(wrong, enabled))
        assertFalse(access.fence.exists())
        assertArrayEquals(enabledBytes, store.compareAndSet(initialBytes, enabled))
        assertEquals(listOf("fence-create", "fence-write", "fence-sync", "directory-1", "fence-read",
            "record-start", "record-sync", "record-finish", "directory-2", "record-read"), access.events)
        val token = access.fence.readBytes()
        assertEquals(GmsDozeBrokerRecordStore.FENCE_BYTES, token.size)
        val second = access.store(registry)
        assertEquals(enabled, second.readExact().state)
        val escaped = second.readExact().exactBytes()
        escaped.fill(0)
        assertArrayEquals(enabledBytes, second.readExact().exactBytes())
        assertNull(second.compareAndSet(initialBytes, enabled))
        val off = enabled.copy(revision = 2, preparationAllowed = false, revokeVersion = 1)
        assertArrayEquals(GmsDozeBrokerCodec.encode(off), second.compareAndSet(enabledBytes, off))
        assertArrayEquals(token, access.fence.readBytes())
        assertEquals(off, store.readExact().state)
        val legacy = File(access.directory, "doze-transaction-v2.bin").apply { writeBytes(byteArrayOf(4, 3, 2)) }
        assertArrayEquals(byteArrayOf(4, 3, 2), legacy.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            GmsDozeBrokerRecordStore(access, folder.newFolder("wrong-path"), registry)
        }
    }

    @Test fun distinctProcessCannotRestorePermitEvenAfterSuccessfulCommit() {
        val access = Access(folder.newFolder("restart"))
        val registry = GmsDozeBrokerProcessRegistry()
        val store = access.store(registry)
        val contender = access.store(GmsDozeBrokerProcessRegistry())
        assertEquals(initial, contender.readExact().state)
        assertArrayEquals(enabledBytes, store.compareAndSet(initialBytes, enabled))
        assertThrows(IOException::class.java) { contender.readExact() }
        assertThrows(IOException::class.java) { contender.compareAndSet(enabledBytes, enabled.copy(revision = 2)) }
        assertEquals(enabled, access.store(registry).readExact().state)
        assertThrows(IOException::class.java) { access.store(GmsDozeBrokerProcessRegistry()).readExact() }
        assertArrayEquals(enabledBytes, access.base.readBytes())
        assertTrue(access.fence.isFile)
    }

    @Test fun everyFenceOrRecordBarrierFailureRetainsSafeRestartBoundary() {
        for (stage in listOf("fence-create", "fence-write", "fence-sync", "directory-1", "fence-read",
            "record-start", "record-sync", "record-finish", "directory-2", "record-read")) {
            val access = Access(folder.newFolder(stage))
            val registry = GmsDozeBrokerProcessRegistry()
            val store = access.store(registry)
            access.failAt = stage
            assertThrows(IOException::class.java) { store.compareAndSet(initialBytes, enabled) }
            access.failAt = null
            assertThrows(IOException::class.java) { access.store(registry).readExact() }
            val restarted = access.store(GmsDozeBrokerProcessRegistry())
            if (stage == "fence-create") {
                assertEquals(initial, restarted.readExact().state)
                assertFalse(access.fence.exists() || access.base.exists() || access.pending.exists())
            } else {
                assertTrue(access.fence.exists())
                assertThrows(IOException::class.java) { restarted.readExact() }
            }
            if (stage in listOf("fence-create", "fence-write", "fence-sync", "directory-1", "fence-read")) {
                assertFalse(access.base.exists() || access.pending.exists())
                assertFalse("record-start" in access.events)
            }
        }
        val access = Access(folder.newFolder("nonexact-readback"))
        val registry = GmsDozeBrokerProcessRegistry()
        access.readbackOverride = initialBytes
        assertThrows(IOException::class.java) { access.store(registry).compareAndSet(initialBytes, enabled) }
        assertArrayEquals(enabledBytes, access.base.readBytes())
        access.readbackOverride = null
        assertThrows(IOException::class.java) { access.store(registry).readExact() }
        assertThrows(IOException::class.java) { access.store(GmsDozeBrokerProcessRegistry()).readExact() }
    }

    @Test fun uncertainOuterReadbackRetainedIntentOffAndArtifactsNeverReopenAuthority() {
        val access = Access(folder.newFolder("intent"))
        val registry = GmsDozeBrokerProcessRegistry()
        val store = access.store(registry)
        assertArrayEquals(enabledBytes, store.compareAndSet(initialBytes, enabled))
        val coordinator = GmsDozeBrokerCoordinator(store)
        val pin = requireNotNull(coordinator.pin("broker_nonce")).state.pin!!
        val prepared = requireNotNull(coordinator.prepare(pin, "transaction_1", GmsDozeBrokerOperation.GLOBAL_ADD))
        val off = requireNotNull(coordinator.disable())
        assertFalse(off.state.preparationAllowed)
        assertEquals(prepared.state.intent, off.state.intent)
        assertArrayEquals(off.exactBytes(), access.base.readBytes())
        assertThrows(IOException::class.java) { access.store(GmsDozeBrokerProcessRegistry()).readExact() }
        val beforeFence = access.events.toList()
        store.fenceUncertainCommit()
        store.fenceUncertainCommit()
        assertEquals(beforeFence, access.events)
        assertThrows(IOException::class.java) { access.store(registry).readExact() }
        assertArrayEquals(off.exactBytes(), access.base.readBytes())

        for (artifact in listOf("record", "fence", "pending", "backup")) {
            val orphan = Access(folder.newFolder("orphan-$artifact"))
            val file = when (artifact) {
                "record" -> orphan.base
                "fence" -> orphan.fence
                "pending" -> orphan.pending
                else -> File(orphan.base.path + ".bak")
            }
            file.writeBytes(byteArrayOf(8, 9))
            assertThrows(IOException::class.java) { orphan.store(GmsDozeBrokerProcessRegistry()).readExact() }
            assertArrayEquals(byteArrayOf(8, 9), file.readBytes())
        }
        for (damage in listOf("missing-record", "bad-record", "missing-fence", "replaced-fence")) {
            val damaged = Access(folder.newFolder(damage))
            val process = GmsDozeBrokerProcessRegistry()
            assertArrayEquals(enabledBytes, damaged.store(process).compareAndSet(initialBytes, enabled))
            when (damage) {
                "missing-record" -> assertTrue(damaged.base.delete())
                "bad-record" -> damaged.base.writeBytes(byteArrayOf(1))
                "missing-fence" -> assertTrue(damaged.fence.delete())
                else -> damaged.fence.writeBytes(ByteArray(GmsDozeBrokerRecordStore.FENCE_BYTES))
            }
            assertThrows(Exception::class.java) { damaged.store(process).readExact() }
        }
        val outer = Access(folder.newFolder("independent-readback"))
        val process = GmsDozeBrokerProcessRegistry()
        val outerStore = outer.store(process)
        assertArrayEquals(enabledBytes, outerStore.compareAndSet(initialBytes, enabled))
        outer.failAt = "record-read"
        assertThrows(IOException::class.java) { outerStore.readExact() }
        outer.failAt = null
        assertThrows(IOException::class.java) { outer.store(process).readExact() }
        assertThrows(IOException::class.java) { outer.store(GmsDozeBrokerProcessRegistry()).readExact() }
        val lostPin = Access(folder.newFolder("pin-proof-not-delivered"))
        val pinProcess = GmsDozeBrokerProcessRegistry()
        val pinStore = lostPin.store(pinProcess)
        assertArrayEquals(enabledBytes, pinStore.compareAndSet(initialBytes, enabled))
        lostPin.readbackOverride = enabledBytes
        assertNull(GmsDozeBrokerCoordinator(pinStore).pin("unacknowledged_nonce"))
        assertEquals("unacknowledged_nonce", GmsDozeBrokerCodec.decode(lostPin.base.readBytes()).pin?.nonce)
        lostPin.readbackOverride = null
        assertThrows(IOException::class.java) { lostPin.store(pinProcess).readExact() }
        assertThrows(IOException::class.java) { lostPin.store(GmsDozeBrokerProcessRegistry()).readExact() }
    }

    @Test fun samePathMonitorAndActualFileLockSerializeExactCas() {
        val access = Access(folder.newFolder("parallel"))
        val registry = GmsDozeBrokerProcessRegistry()
        val first = access.store(registry)
        val second = Access(File(access.directory, ".")).store(registry)
        val entered = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        access.beforeStart = { entered.countDown(); check(continueWrite.await(5, TimeUnit.SECONDS)) }
        var firstBytes: ByteArray? = null
        var secondBytes: ByteArray? = enabledBytes
        val firstWorker = thread { firstBytes = first.compareAndSet(initialBytes, enabled) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val secondWorker = thread { secondBytes = second.compareAndSet(initialBytes, enabled); secondFinished.countDown() }
        try { assertFalse(secondFinished.await(50, TimeUnit.MILLISECONDS)) }
        finally { continueWrite.countDown(); firstWorker.join(5000); secondWorker.join(5000) }
        assertFalse(firstWorker.isAlive || secondWorker.isAlive)
        assertArrayEquals(enabledBytes, firstBytes)
        assertNull(secondBytes)
        assertArrayEquals(enabledBytes, second.readExact().exactBytes())
        val locked = Access(folder.newFolder("os-lock"))
        RandomAccessFile(File(locked.directory, GmsDozeBrokerRecordStore.STORAGE_LOCK_NAME), "rw").use { handle ->
            handle.channel.lock().use {
                assertThrows(IOException::class.java) { locked.store(GmsDozeBrokerProcessRegistry()).readExact() }
                assertThrows(IOException::class.java) {
                    locked.store(GmsDozeBrokerProcessRegistry()).compareAndSet(initialBytes, enabled)
                }
                assertFalse(locked.fence.exists() || locked.base.exists())
            }
        }
        assertArrayEquals(enabledBytes, locked.store(GmsDozeBrokerProcessRegistry()).compareAndSet(initialBytes, enabled))
    }
}
