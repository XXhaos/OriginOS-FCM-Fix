package io.github.originosfcmguardian.fcm

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmEvidenceStoreTest {
    @Test
    fun duplicateKeepsFirstReceiptAndNotificationUpdateUsesElapsedDuration() {
        val access = FakeEvidenceFileAccess()
        val store = store(access)
        val first = receipt("same", receivedWall = 900, sent = 1_000, processStarted = 125)
        val duplicate = receipt("same", receivedWall = 2_000, sent = 1_000)

        assertTrue(store.recordFirst(first) is RecordReceiptResult.Stored)
        assertEquals(RecordReceiptResult.Duplicate, store.recordFirst(duplicate))
        assertTrue(store.updateNotification("same", NotificationOutcome.POST_REQUESTED, 18))

        val stored = (store.load() as FcmEvidenceSnapshot.Available).receipts.single()
        assertEquals(900L, stored.receivedAtEpochMillis)
        assertEquals(-100L, stored.apparentWallClockDeltaMillis)
        assertEquals(18L, stored.handlerDurationMillis)
        assertEquals(NotificationOutcome.POST_REQUESTED, stored.notificationOutcome)
        assertEquals(125L, stored.processStartedAtElapsedMillis)
        assertEquals(375L, stored.processAgeAtCallbackMillis)
    }

    @Test
    fun negativeHandlerDurationIsStoredAsZero() {
        val store = store(FakeEvidenceFileAccess())
        assertTrue(store.recordFirst(receipt("duration")) is RecordReceiptResult.Stored)

        assertTrue(store.updateNotification("duration", NotificationOutcome.POST_REQUESTED, -7L))

        val stored = (store.load() as FcmEvidenceSnapshot.Available).receipts.single()
        assertEquals(0L, stored.handlerDurationMillis)
    }

    @Test
    fun historyIsBoundedToNewestFifty() {
        val store = store(FakeEvidenceFileAccess())
        repeat(51) { index ->
            assertTrue(store.recordFirst(receipt("id_$index")) is RecordReceiptResult.Stored)
        }

        val receipts = (store.load() as FcmEvidenceSnapshot.Available).receipts
        assertEquals(50, receipts.size)
        assertEquals("id_50", receipts.first().testId)
        assertFalse(receipts.any { it.testId == "id_0" })
    }

    @Test
    fun corruptExistingBytesArePreservedAndNeverOverwritten() {
        val access = FakeEvidenceFileAccess().apply {
            exists = true
            bytes = "not-valid-evidence\n".toByteArray()
        }
        val store = store(access)

        assertEquals(FcmEvidenceSnapshot.Corrupt, store.load())
        assertEquals(RecordReceiptResult.Corrupt, store.recordFirst(receipt("new_id")))
        assertEquals(0, access.startCount)
        assertEquals("not-valid-evidence\n", access.bytes.decodeToString())
    }

    @Test
    fun malformedOrDuplicateEncodedEvidenceFailsClosed() {
        val receipt = receipt("dup")
        val encoded = FcmEvidenceCodec.encode(listOf(receipt, receipt.copy(receivedAtEpochMillis = 2_000)))
        assertEquals(null, FcmEvidenceCodec.decode(encoded))
        assertEquals(null, FcmEvidenceCodec.decode(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun v1EvidenceDecodesWithUnknownProcessStart() {
        val encoded = """
            GUARDIAN_FCM_EVIDENCE_V1
            legacy|1000|1500|500|NORMAL|UNKNOWN|UNKNOWN|UNKNOWN|RECEIPT_STORED|-
        """.trimIndent().plus('\n').toByteArray()

        val decoded = FcmEvidenceCodec.decode(encoded)!!.single()

        assertEquals("legacy", decoded.testId)
        assertEquals(null, decoded.processStartedAtElapsedMillis)
        assertEquals(null, decoded.processAgeAtCallbackMillis)
    }

    @Test
    fun v2RoundTripRetainsMixedUnknownAndCapturedProcessStarts() {
        val receipts = listOf(
            receipt("new", processStarted = 100),
            receipt("legacy_unknown", processStarted = null),
        )

        val encoded = FcmEvidenceCodec.encode(receipts)
        val decoded = FcmEvidenceCodec.decode(encoded)

        assertTrue(encoded.decodeToString().startsWith("GUARDIAN_FCM_EVIDENCE_V2\n"))
        assertEquals(receipts, decoded)
    }

    @Test
    fun recordingAfterV1ReadRewritesV2WithoutDroppingLegacyReceipt() {
        val legacy = """
            GUARDIAN_FCM_EVIDENCE_V1
            legacy|1000|1500|500|NORMAL|UNKNOWN|UNKNOWN|UNKNOWN|RECEIPT_STORED|-
        """.trimIndent().plus('\n')
        val access = FakeEvidenceFileAccess().apply {
            exists = true
            bytes = legacy.toByteArray()
        }
        val store = store(access)

        assertTrue(store.recordFirst(receipt("new", processStarted = 100)) is RecordReceiptResult.Stored)

        val stored = (store.load() as FcmEvidenceSnapshot.Available).receipts
        assertTrue(access.bytes.decodeToString().startsWith("GUARDIAN_FCM_EVIDENCE_V2\n"))
        assertEquals(listOf("new", "legacy"), stored.map(FcmReceipt::testId))
        assertEquals(100L, stored[0].processStartedAtElapsedMillis)
        assertEquals(null, stored[1].processStartedAtElapsedMillis)
    }

    @Test
    fun unsupportedOrFutureProcessStartEvidenceIsPreservedAndFailsClosed() {
        val invalidEvidence = listOf(
            "GUARDIAN_FCM_EVIDENCE_V3\n",
            "GUARDIAN_FCM_EVIDENCE_V2\nfuture|1000|1500|500|NORMAL|UNKNOWN|UNKNOWN|UNKNOWN|RECEIPT_STORED|-|501\n",
            "GUARDIAN_FCM_EVIDENCE_V2\nnegative|1000|1500|500|NORMAL|UNKNOWN|UNKNOWN|UNKNOWN|RECEIPT_STORED|-|-1\n",
        )

        invalidEvidence.forEach { raw ->
            val access = FakeEvidenceFileAccess().apply {
                exists = true
                bytes = raw.toByteArray()
            }
            val store = store(access)

            assertEquals(FcmEvidenceSnapshot.Corrupt, store.load())
            assertEquals(RecordReceiptResult.Corrupt, store.recordFirst(receipt("replacement")))
            assertEquals(0, access.startCount)
            assertEquals(raw, access.bytes.decodeToString())
        }
    }

    private fun receipt(
        id: String,
        receivedWall: Long = 1_500,
        sent: Long = 1_000,
        processStarted: Long? = null,
    ) = FcmReceipt(
        testId = id,
        sentAtEpochMillis = sent,
        receivedAtEpochMillis = receivedWall,
        receivedAtElapsedMillis = 500,
        messagePriority = MessagePriority.NORMAL,
        deviceInteractive = ObservedBoolean.UNKNOWN,
        deviceIdleMode = ObservedBoolean.UNKNOWN,
        networkAvailability = NetworkAvailability.UNKNOWN,
        processStartedAtElapsedMillis = processStarted,
    )

    private fun store(access: FakeEvidenceFileAccess): FcmEvidenceStore =
        FcmEvidenceStore(File(access.canonicalPath), access)

    private class FakeEvidenceFileAccess : EvidenceFileAccess {
        private val directory = Files.createTempDirectory("guardian-fcm-evidence").toFile()
        private val pending = File(directory, "pending")
        override val canonicalPath: String = File(directory, "evidence").canonicalPath
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
    }
}
