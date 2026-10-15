package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthorityAllocationRegressionTest {
    private val legacy = TargetConsent.Legacy(DurableConsentToken(
        "b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 7, 90,
        SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64))),
        "vivo/fingerprint:17/user/release-keys", policyVersion = 2))
    private val recipient = TargetConsent.Recipient(RecipientConsentToken(
        "a1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 6, 91,
        SelectedTargetIdentity("example.recipient", 10435, 101, listOf("b".repeat(64))), "f".repeat(256)))
    private val budget = BudgetSnapshot(BudgetClock(14, 600000, 1600000), listOf(0L, 300000L, 600000L), null)
    private fun snapshot(schema: Int) = ProtectionAuthoritySnapshot(7, ProtectionConsentMode.ACTIVE,
        if (schema == 1) legacy.value else null, null, budget,
        if (schema == 1) emptyList() else listOf(recipient, legacy), schema,
        if (schema == 3) 90 else null, if (schema == 3) listOf(recipient.id) else emptyList())

    /** Independent original wire layout, including checksum, guards against a paired codec drift. */
    private fun golden(value: ProtectionAuthoritySnapshot): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            fun string(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
            fun consent(value: TargetConsent) {
                out.writeInt(1); out.writeInt(value.policyVersion); out.writeInt(0)
                string(value.id); out.writeLong(value.generation); out.writeLong(value.consentedAtMillis)
                string(value.identity.packageName); out.writeInt(value.identity.uid); out.writeLong(value.identity.firstInstallTime)
                out.writeInt(value.identity.signerSha256.size); value.identity.signerSha256.forEach(::string)
                string(value.fingerprint)
            }
            out.writeInt(0x46434D41); out.writeInt(value.schemaVersion); out.writeLong(7)
            out.writeByte(ProtectionConsentMode.ACTIVE.ordinal); out.writeInt(-1)
            if (value.schemaVersion == 1) { out.writeBoolean(true); consent(legacy) }
            else { out.writeInt(2); out.writeByte(1); consent(recipient); out.writeByte(0); consent(legacy) }
            if (value.schemaVersion == 3) { out.writeLong(90); out.writeInt(1); string(recipient.id) }
            val budgetBytes = BudgetSnapshotCodec.encode(budget); out.writeInt(budgetBytes.size); out.write(budgetBytes)
        } }.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    @Test fun `all historical schema bytes and long strings retain the exact original layout`() {
        val hashes = listOf("09aac60f150bcb37ca73e41ffec0e062bbc530998b56d29659e2f9c7af10f090",
            "d88a0d76605d0d77d82a42fb3edc4f65b3374f4eb2bfe520feab9249d71f7308",
            "31263e3c1666a6020c8865f0cf95632592a5de73b271280e220e672fac8819a7")
        for (schema in 1..3) {
            val expected = golden(snapshot(schema))
            assertEquals(hashes[schema - 1], MessageDigest.getInstance("SHA-256").digest(expected)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
            assertArrayEquals(expected, ProtectionAuthorityCodec.encode(snapshot(schema)))
            assertEquals(snapshot(schema), ProtectionAuthorityCodec.decode(expected))
            assertArrayEquals(expected, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(expected)))
            for (cut in expected.indices) assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(expected.copyOf(cut)) }
            assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(expected + 0) }
            val changed = expected.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(changed) }
        }
    }

    private fun stringOffsets(payload: ByteArray): List<Int> {
        val input = DataInputStream(ByteArrayInputStream(payload)); val offsets = mutableListOf<Int>()
        fun string() { val size = input.readInt(); offsets += payload.size - input.available(); input.skipBytes(size) }
        input.skipBytes(21); val count = input.readInt()
        repeat(count) {
            input.skipBytes(13); string(); input.skipBytes(16); string(); input.skipBytes(12)
            repeat(input.readInt()) { string() }; string()
        }
        input.skipBytes(8); repeat(input.readInt()) { string() }
        return offsets
    }

    @Test fun `each string still rejects malformed utf8 after earlier decoder use`() {
        val good = golden(snapshot(3)); val payload = good.copyOf(good.size - 32)
        for (offset in stringOffsets(payload)) {
            for (bad in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
                val changed = payload.clone(); bad.copyInto(changed, offset)
                val signed = changed + MessageDigest.getInstance("SHA-256").digest(changed)
                assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(signed) }
                assertEquals(snapshot(3), ProtectionAuthorityCodec.decode(good))
            }
        }
    }

    @Test fun `large authority keeps all targets and still enforces its byte ceiling`() {
        val signers = (0..15).map { it.toString(16).padStart(64, '0') }
        fun large(count: Int): ProtectionAuthoritySnapshot {
            val targets = (0 until count).map { index -> TargetConsent.Recipient(RecipientConsentToken(
                index.toString(16).padStart(8, '0') + "-544b-499a-9dc3-16eb5b4c97a2", 1, 1,
                SelectedTargetIdentity("app.example.p$index", 11000 + index, 100, signers), "f".repeat(256))) }
                .sortedBy { it.identity.packageName }
            return ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null, budget,
                targets, 3, 1)
        }
        val expected = large(150)
        val bytes = ProtectionAuthorityCodec.encode(expected)
        assertTrue(bytes.size > 200000 && bytes.size <= ProtectionAuthorityCodec.MAX_BYTES)
        val read = readBoundedAuthorityBytes(ByteArrayInputStream(bytes), ProtectionAuthorityCodec.MAX_BYTES)
        assertEquals(expected, ProtectionAuthorityCodec.decode(read))
        assertArrayEquals(bytes, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(read)))
        assertThrows(IllegalArgumentException::class.java) { ProtectionAuthorityCodec.encode(large(200)) }
        assertThrows(IllegalArgumentException::class.java) { ProtectionAuthorityCodec.decode(ByteArray(ProtectionAuthorityCodec.MAX_BYTES + 1)) }
    }

    @Test fun `decoders remain isolated across concurrent schema reads`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map { executor.submit {
                repeat(30) { for (schema in 1..3) assertEquals(snapshot(schema), ProtectionAuthorityCodec.decode(golden(snapshot(schema)))) }
            } }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `uuid validation remains lowercase exact and independent between tokens`() {
        for (id in listOf(legacy.id.uppercase(), legacy.id + "\n", legacy.id.dropLast(1), " " + legacy.id)) {
            assertThrows(IllegalArgumentException::class.java) { legacy.value.copy(id = id) }
            assertThrows(IllegalArgumentException::class.java) { recipient.value.copy(id = id) }
            assertEquals(legacy.value, legacy.value.copy())
            assertEquals(recipient.value, recipient.value.copy())
        }
    }

    @Test fun `small chunk reader permits every exact boundary and fresh short reads`() {
        for (length in listOf(0, 1, 8191, 8192, 8193, ProtectionAuthorityCodec.MAX_BYTES)) {
            val bytes = ByteArray(length) { (it % 251).toByte() }
            assertArrayEquals(bytes, readBoundedAuthorityBytes(ByteArrayInputStream(bytes), ProtectionAuthorityCodec.MAX_BYTES))
        }
        val bytes = golden(snapshot(3))
        val short = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(3, len))
        }
        assertEquals(snapshot(3), ProtectionAuthorityCodec.decode(readBoundedAuthorityBytes(short, bytes.size)))
        bytes[0] = 0
        assertThrows(Exception::class.java) {
            ProtectionAuthorityCodec.decode(readBoundedAuthorityBytes(ByteArrayInputStream(bytes), bytes.size))
        }
    }

    @Test fun `small chunk reader rejects overflow zero progress and io without partial return`() {
        assertThrows(IllegalStateException::class.java) {
            readBoundedAuthorityBytes(ByteArrayInputStream(ByteArray(ProtectionAuthorityCodec.MAX_BYTES + 1)), ProtectionAuthorityCodec.MAX_BYTES)
        }
        var consumed = 0
        val bounded = object : InputStream() {
            override fun read(): Int = error("Must use bounded bulk read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertTrue(len in 1..8192); consumed += len; return len
            }
        }
        assertThrows(IllegalStateException::class.java) { readBoundedAuthorityBytes(bounded, 8192) }
        assertEquals(8193, consumed)
        val noProgress = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(IllegalStateException::class.java) { readBoundedAuthorityBytes(noProgress, 8192) }
        val broken = object : InputStream() {
            private var called = false
            override fun read(): Int = error("Must use bounded bulk read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (called) throw IOException("read failure")
                called = true; b[off] = 1; return 1
            }
        }
        assertThrows(IOException::class.java) { readBoundedAuthorityBytes(broken, 8192) }
    }
}
