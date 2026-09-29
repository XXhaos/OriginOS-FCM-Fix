package io.github.originosfcmguardian.data.journal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.journal.StoreWriteResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AtomicFileJournalStoreInstrumentedTest {
    private lateinit var baseFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        baseFile = File(
            context.noBackupFilesDir,
            "atomic-store-test-${UUID.randomUUID()}.bin",
        )
    }

    @After
    fun tearDown() {
        listOf(baseFile, File(baseFile.path + ".new"), File(baseFile.path + ".bak"))
            .forEach(File::delete)
    }

    @Test
    fun atomicStorePersistsFirstBytesAndPreservesThem() {
        val store = AtomicFileJournalStore(baseFile)

        assertEquals(StoreWriteResult.STORED, store.writeIfAbsent(byteArrayOf(1, 2, 3)))
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read())
    }

    @Test
    fun orphanNewFileIsNotRecoveredOrOverwritten() {
        File(baseFile.path + ".new").writeBytes(byteArrayOf(7))
        val store = AtomicFileJournalStore(baseFile)

        assertThrows(IOException::class.java) { store.read() }
        assertEquals(StoreWriteResult.ALREADY_EXISTS, store.writeIfAbsent(byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(7), File(baseFile.path + ".new").readBytes())
    }
}
