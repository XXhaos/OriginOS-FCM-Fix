package io.github.originosfcmguardian.data.journal

import io.github.originosfcmguardian.core.journal.StoreWriteResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AtomicFileJournalStoreTest {
    @Test
    fun firstWritePersistsAndLaterWritesPreserveIt() {
        val backend = FakeFileAccess()
        val store = store(backend)

        assertEquals(StoreWriteResult.STORED, store.writeIfAbsent(byteArrayOf(1, 2, 3)))
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read())
        assertEquals(1, backend.finishCount)
    }

    @Test
    fun anyOrphanArtifactPreventsOverwrite() {
        val backend = FakeFileAccess().apply { artifactExists.set(true) }

        assertEquals(StoreWriteResult.ALREADY_EXISTS, store(backend).writeIfAbsent(byteArrayOf(1)))
        assertEquals(0, backend.startCount)
    }

    @Test
    fun corruptExistingArtifactFailsReadAndRemainsProtected() {
        val backend = FakeFileAccess().apply {
            artifactExists.set(true)
            readFailure = IOException("corrupt")
        }
        val store = store(backend)

        assertThrows(IOException::class.java) { store.read() }
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(4)))
        assertEquals(0, backend.startCount)
    }

    @Test
    fun oversizedExistingArtifactFailsWithoutAllocatingOrOverwriting() {
        val backend = FakeFileAccess().apply {
            artifactExists.set(true)
            enforceOversize = true
        }
        val store = store(backend)

        assertThrows(IOException::class.java) { store.read() }
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(4)))
        assertEquals(0, backend.startCount)
    }

    @Test
    fun failedWriteCallsRollbackAndDoesNotReportStored() {
        val backend = FakeFileAccess().apply { failFinish = true }
        val store = store(backend)

        assertThrows(IOException::class.java) { store.writeIfAbsent(byteArrayOf(1, 2, 3)) }
        assertEquals(1, backend.failCount)
        assertEquals(0, backend.finishCount)

        backend.failFinish = false
        assertEquals(StoreWriteResult.STORED, store.writeIfAbsent(byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(4, 5), store.read())
    }

    @Test
    fun readBackMismatchFailsClosedAndProtectsWrittenArtifact() {
        val backend = FakeFileAccess().apply { mismatchAfterFinish = true }
        val store = store(backend)

        assertThrows(IOException::class.java) { store.writeIfAbsent(byteArrayOf(1, 2, 3)) }
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(8)))
        assertEquals(1, backend.finishCount)
    }

    @Test
    fun storesWithSameCanonicalPathSerializeFirstWrite() {
        val backend = FakeFileAccess()
        val first = store(backend)
        val second = store(backend)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first to byteArrayOf(1), second to byteArrayOf(2)).map { (store, bytes) ->
                pool.submit<StoreWriteResult> {
                    start.await()
                    store.writeIfAbsent(bytes)
                }
            }
            start.countDown()
            val values = results.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, values.count { it == StoreWriteResult.STORED })
            assertEquals(1, values.count { it == StoreWriteResult.ALREADY_EXISTS })
            assertEquals(1, backend.finishCount)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun store(access: FakeFileAccess): AtomicFileJournalStore =
        AtomicFileJournalStore(File(access.canonicalPath), access)

    private class FakeFileAccess : JournalFileAccess {
        private val directory = Files.createTempDirectory("guardian-journal-test").toFile()
        private val pending = File(directory, "pending")
        override val canonicalPath: String = File(directory, "baseline").canonicalPath
        val artifactExists = AtomicBoolean(false)
        var bytes = ByteArray(0)
        var readFailure: IOException? = null
        var enforceOversize = false
        var failFinish = false
        var mismatchAfterFinish = false
        var startCount = 0
        var finishCount = 0
        var failCount = 0

        override fun hasAnyArtifact(): Boolean = artifactExists.get()

        override fun startWrite(): FileOutputStream {
            startCount += 1
            return FileOutputStream(pending)
        }

        override fun finishWrite(output: FileOutputStream) {
            if (failFinish) throw IOException("finish failed")
            output.close()
            bytes = pending.readBytes()
            artifactExists.set(true)
            finishCount += 1
        }

        override fun failWrite(output: FileOutputStream?) {
            failCount += 1
            runCatching { output?.close() }
            pending.delete()
        }

        override fun readBounded(maxBytes: Int): ByteArray {
            readFailure?.let { throw it }
            if (enforceOversize) throw IOException("oversized")
            return if (mismatchAfterFinish) byteArrayOf(99) else bytes.copyOf()
        }
    }
}
