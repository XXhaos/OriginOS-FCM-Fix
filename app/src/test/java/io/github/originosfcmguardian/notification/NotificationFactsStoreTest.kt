package io.github.originosfcmguardian.notification

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactsStoreTest {
    @Test
    fun historyKeepsNewestTwoHundredEvents() {
        val access = FakeNotificationFactsFileAccess()
        val store = store(access)

        repeat(NotificationFactsStore.MAX_EVENTS + 1) { index ->
            assertEquals(RecordNotificationFactResult.STORED, store.record(fact(index)))
        }

        val events = (store.load() as NotificationFactsSnapshot.Available).events
        assertEquals(NotificationFactsStore.MAX_EVENTS, events.size)
        assertEquals(201_000L, events.first().observedAtEpochMillis)
        assertFalse(events.any { it.observedAtEpochMillis == 1_000L })
        assertTrue(access.bytes.size <= NotificationFactsStore.MAX_FILE_BYTES)
    }

    @Test
    fun corruptBytesArePreservedUntilExplicitClear() {
        val access = FakeNotificationFactsFileAccess().apply {
            exists = true
            bytes = "not-notification-facts\n".toByteArray()
        }
        val store = store(access)

        assertEquals(NotificationFactsSnapshot.Corrupt, store.load())
        assertEquals(RecordNotificationFactResult.CORRUPT, store.record(fact(1)))
        assertEquals(0, access.startCount)
        assertEquals("not-notification-facts\n", access.bytes.decodeToString())

        assertTrue(store.clear())
        assertEquals(NotificationFactsSnapshot.Empty, store.load())
    }

    @Test
    fun codecRejectsTruncationOversizeAndTooManyEvents() {
        val encoded = NotificationFactsCodec.encode(listOf(fact(1)))
        assertEquals(null, NotificationFactsCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertEquals(
            null,
            NotificationFactsCodec.decode(ByteArray(NotificationFactsStore.MAX_FILE_BYTES + 1)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NotificationFactsCodec.encode(
                List(NotificationFactsStore.MAX_EVENTS + 1) { fact(it) },
            )
        }
        val unconfirmed = "GUARDIAN_NOTIFICATION_FACTS_V1\n" +
            "org.telegram.messenger|1700000000000|POSTED_OR_UPDATED|HIGH_CONFIDENCE_INFERENCE\n"
        assertEquals(null, NotificationFactsCodec.decode(unconfirmed.toByteArray()))
    }

    @Test
    fun serializedHistoryContainsNoNotificationContentOrIdentifiers() {
        val encoded = NotificationFactsCodec.encode(
            listOf(NotificationFact("org.telegram.messenger", 1_700_000_000_000, NotificationFactKind.REMOVED)),
        ).decodeToString()

        assertEquals(
            "GUARDIAN_NOTIFICATION_FACTS_V1\n" +
                "org.telegram.messenger|1700000000000|REMOVED|CONFIRMED\n",
            encoded,
        )
        listOf("secret title", "message body", "channel", "notification-key", "tag").forEach {
            assertFalse(encoded.contains(it))
        }
    }

    private fun fact(index: Int) = NotificationFact(
        packageName = "org.telegram.messenger",
        observedAtEpochMillis = (index + 1) * 1_000L,
        kind = if (index % 2 == 0) NotificationFactKind.POSTED_OR_UPDATED else NotificationFactKind.REMOVED,
    )

    private fun store(access: FakeNotificationFactsFileAccess): NotificationFactsStore =
        NotificationFactsStore(File(access.canonicalPath), access)

    private class FakeNotificationFactsFileAccess : NotificationFactsFileAccess {
        private val directory = Files.createTempDirectory("guardian-notification-facts").toFile()
        private val pending = File(directory, "pending")
        override val canonicalPath: String = File(directory, "history").canonicalPath
        var exists = false
        var bytes = ByteArray(0)
        var startCount = 0

        override fun hasAnyArtifact(): Boolean = exists

        override fun startWrite(): FileOutputStream {
            startCount += 1
            return FileOutputStream(pending)
        }

        override fun finishWrite(output: FileOutputStream) {
            output.close()
            bytes = pending.readBytes()
            exists = true
        }

        override fun failWrite(output: FileOutputStream?) {
            runCatching { output?.close() }
        }

        override fun readBounded(maxBytes: Int): ByteArray = bytes.copyOf()

        override fun clearArtifacts() {
            exists = false
            bytes = ByteArray(0)
            pending.delete()
        }
    }
}
