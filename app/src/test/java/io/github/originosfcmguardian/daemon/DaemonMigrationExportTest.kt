package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonMigrationExportTest {
    private val intent = DaemonMigrationIntent(RootFixtures.owner, "7f231d03-499d-4dc7-863a-07599b9b38c4")
    private fun raw(authority: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
        val source = RootFixtures.snapshot(authority, mapOf(DaemonDomainRole.LEGACY_BUDGET to byteArrayOf(9, 0, 8)))
        return DaemonProtectionRootCodec.encode(DaemonProtectionRootSnapshot(source.control.copy(
            mode = DaemonControlMode.PREPARED, migrationId = intent.migrationId), source.domains()))
    }
    private class Files : DaemonMigrationExportFilePort {
        var saved: ByteArray? = null
        var unknown = false
        var ambiguousWrite = false
        var corruptWrite = false
        var writes = 0
        override fun read(): ByteArray? { check(!unknown); return saved?.copyOf() }
        override fun write(bytes: ByteArray) {
            writes++; saved = bytes.copyOf()
            if (corruptWrite) saved!![0] = (saved!![0].toInt() xor 1).toByte()
            if (ambiguousWrite) error("Write outcome unavailable")
        }
        override fun <T> exclusive(action: () -> T) = action()
    }

    @Test fun frozenPayloadPreservesExactRawBytesAndReturnsOnlyCopies() {
        val files = Files(); val store = DaemonMigrationExportStore(files)
        assertFalse(store.hasArtifact()); assertNull(store.read(intent))
        val source = raw(); val original = source.copyOf()
        assertTrue(store.saveOnce(intent, source))
        source.fill(0)
        val frozen = requireNotNull(store.read(intent))
        assertEquals(intent, frozen.intent)
        assertEquals(DaemonProtectionRootSnapshot.digest(original), frozen.sha256)
        assertArrayEquals(original, frozen.bytes())
        frozen.bytes().fill(0)
        assertArrayEquals(original, frozen.bytes())
        assertArrayEquals(byteArrayOf(9, 0, 8), frozen.snapshot().bytes(DaemonDomainRole.LEGACY_BUDGET))
        assertTrue(store.hasArtifact())
    }

    @Test fun retryIsIdempotentButDifferentPayloadOrIntentCanNeverReplaceTheFrozenExport() {
        val files = Files(); val store = DaemonMigrationExportStore(files); val original = raw()
        assertTrue(store.saveOnce(intent, original)); assertTrue(store.saveOnce(intent, original))
        assertEquals(1, files.writes)
        assertFalse(store.saveOnce(intent, raw(byteArrayOf(4, 5, 6))))
        val replacement = intent.copy(owner = intent.owner.copy(ownerEpoch = 2))
        assertFalse(store.saveOnce(replacement, original))
        assertThrows(IllegalStateException::class.java) { store.read(replacement) }
        assertArrayEquals(original, requireNotNull(store.read(intent)).bytes())
        assertEquals(1, files.writes)
    }

    @Test fun codecRejectsChangedChecksumOversizeOrNoninitialControlRatherThanNormalizing() {
        val encoded = DaemonMigrationExportCodec.encode(intent, raw())
        val decoded = DaemonMigrationExportCodec.decode(encoded)
        assertArrayEquals(raw(), decoded.bytes())
        encoded[20] = (encoded[20].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationExportCodec.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) {
            DaemonMigrationExportCodec.decode(ByteArray(DaemonMigrationExportCodec.MAX_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DaemonMigrationExportCodec.encode(intent, ByteArray(DaemonProtectionRootCodec.MAX_BYTES + 1))
        }
        val prepared = DaemonProtectionRootCodec.decode(raw())
        val active = DaemonProtectionRootCodec.encode(DaemonProtectionRootSnapshot(
            prepared.control.copy(mode = DaemonControlMode.ACTIVE), prepared.domains()))
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationExportCodec.encode(intent, active) }
        val later = DaemonProtectionRootCodec.encode(DaemonProtectionRootSnapshot(prepared.control, prepared.domains(), dataRevision = 2))
        assertThrows(IllegalArgumentException::class.java) { DaemonMigrationExportCodec.encode(intent, later) }
    }

    @Test fun unknownOrAmbiguousWriteRemainsObservableAndNeverBecomesKnownAbsence() {
        val files = Files(); val store = DaemonMigrationExportStore(files)
        files.ambiguousWrite = true
        assertFalse(store.saveOnce(intent, raw()))
        assertTrue(store.hasArtifact())
        assertArrayEquals(raw(), requireNotNull(store.read(intent)).bytes())
        files.unknown = true
        assertThrows(IllegalStateException::class.java) { store.hasArtifact() }
        assertThrows(IllegalStateException::class.java) { store.read(intent) }
        assertFalse(store.saveOnce(intent, raw()))
        files.unknown = false; files.saved = byteArrayOf(1)
        assertTrue(store.hasArtifact()) // Parent must guard an orphan independently of INTENT.
        assertThrows(IllegalArgumentException::class.java) { store.read(intent) }
        assertFalse(store.saveOnce(intent, raw()))
    }
}
