package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

internal class MemoryDaemonRootStorage : DaemonProtectionRootStorage {
    var saved: ByteArray? = null
    var writes = 0
    var failWrite = false
    var loseReadback = false
    var corruptAfterWrite = false
    private var nextReadFailure = false
    private var nextReadCorrupt = false
    override fun read(): ByteArray? {
        if (nextReadFailure) { nextReadFailure = false; error("readback lost") }
        return saved?.copyOf()?.also { if (nextReadCorrupt) { nextReadCorrupt = false; it[0] = (it[0].toInt() xor 1).toByte() } }
    }
    override fun inspect() = saved?.copyOf()
    override fun write(bytes: ByteArray) {
        if (failWrite) error("write failed")
        writes++; saved = bytes.copyOf(); nextReadFailure = loseReadback; loseReadback = false
        nextReadCorrupt = corruptAfterWrite; corruptAfterWrite = false
    }
    @Synchronized override fun <T> withOwnerLease(action: () -> T) = action()
}

internal object RootFixtures {
    val owner = DaemonOwnerKey("96fbde55-15e2-45b6-8bea-0de37312dd94", 1)
    fun snapshot(authority: ByteArray = byteArrayOf(1, 2, 3), extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()): DaemonProtectionRootSnapshot {
        val domains = DaemonDomainRole.entries.associateWith<DaemonDomainRole, ByteArray?> { null }.toMutableMap()
        domains[DaemonDomainRole.AUTHORITY] = authority
        domains[DaemonDomainRole.AUTHORITY_INITIALIZED] = byteArrayOf(1)
        domains.putAll(extras)
        val control = DaemonControlRecord(owner, 1, DaemonProtectionRootSnapshot.digest(authority), DaemonControlMode.OFF, "migration")
        return DaemonProtectionRootSnapshot(control, domains)
    }
    fun root(snapshot: DaemonProtectionRootSnapshot = snapshot(), storage: MemoryDaemonRootStorage = MemoryDaemonRootStorage()):
        Pair<DaemonProtectionRoot, MemoryDaemonRootStorage> = DaemonProtectionRoot(storage).also { it.initializeImported(snapshot) } to storage
    fun fails(action: () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected failure") }
}

class DaemonProtectionRootTest {
    @Test fun originalBytesAndExplicitAbsenceSurviveCanonicalRoundtripWithoutMutableAliases() {
        val source = byteArrayOf(4, 5, 6)
        val snapshot = RootFixtures.snapshot(source, mapOf(DaemonDomainRole.HEARTBEAT_PENDING to byteArrayOf()))
        source[0] = 99
        val encoded = DaemonProtectionRootCodec.encode(snapshot)
        val restored = DaemonProtectionRootCodec.decode(encoded)
        assertArrayEquals(byteArrayOf(4, 5, 6), restored.bytes(DaemonDomainRole.AUTHORITY))
        assertNull(restored.bytes(DaemonDomainRole.HEARTBEAT))
        assertArrayEquals(byteArrayOf(), restored.bytes(DaemonDomainRole.HEARTBEAT_PENDING))
        val escaped = restored.bytes(DaemonDomainRole.AUTHORITY)!!; escaped[0] = 88
        assertArrayEquals(encoded, DaemonProtectionRootCodec.encode(restored))
        RootFixtures.fails { DaemonProtectionRootSnapshot(snapshot.control, emptyMap()) }
    }

    @Test fun multiRolePatchHasOneCommitAndRollbackLeavesWholeOldSnapshot() {
        val (root, storage) = RootFixtures.root()
        root.transaction {
            root.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(7))
            root.transaction { change -> change.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(8)) }
        }
        assertEquals(2, storage.writes)
        assertEquals(2L, root.snapshot().dataRevision)
        RootFixtures.fails { root.transaction {
            it.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(99)); error("abort")
        } }
        assertEquals(2, storage.writes)
        assertArrayEquals(byteArrayOf(7), root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
    }

    @Test fun staleDataRevisionCannotOverwriteUnrelatedNewJournalChange() {
        val (first, storage) = RootFixtures.root()
        val observed = first.snapshot().dataRevision
        val second = DaemonProtectionRoot(storage)
        second.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(7))
        RootFixtures.fails { first.transaction(observed) { it.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(8)) } }
        assertArrayEquals(byteArrayOf(7), first.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertNull(first.bytes(DaemonDomainRole.RECOVERY_COUNTS))
        first.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(8))
        assertArrayEquals(byteArrayOf(7), first.bytes(DaemonDomainRole.STOPPED_JOURNAL))
    }

    @Test fun ordinaryDomainPatchCannotChangeAuthorityControlOrReceipts() {
        val (root, storage) = RootFixtures.root()
        val before = root.snapshot()
        RootFixtures.fails { root.put(DaemonDomainRole.AUTHORITY, byteArrayOf(99)) }
        RootFixtures.fails { root.transaction { it.control = before.control.copy(revision = 2) } }
        RootFixtures.fails { root.transaction { it.put(DaemonDomainRole.AUTHORITY_INITIALIZED, null) } }
        RootFixtures.fails { root.transaction { it.put(DaemonDomainRole.SELECTION, byteArrayOf(8)) } }
        assertEquals(1, storage.writes)
        assertEquals(before.control, root.snapshot().control)
    }

    @Test fun authorityControlAndReceiptCommitAtomicallyAndLedgerIsBoundedMetadata() {
        val (root, _) = RootFixtures.root()
        for (revision in 2L..70L) {
            val before = root.snapshot()
            val bytes = byteArrayOf(revision.toByte())
            val next = before.control.copy(revision = revision, authorityDigest = DaemonProtectionRootSnapshot.digest(bytes))
            val request = DaemonControlRequest("request-$revision", next.owner, revision - 1,
                DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("pointer-$revision", next)))
            root.controlTransaction(before.dataRevision) {
                it.control = next; it.put(DaemonDomainRole.AUTHORITY, bytes)
                it.remember(DaemonControlReceipt(request, next))
            }
        }
        val after = root.snapshot()
        assertEquals(64, after.receipts.size)
        assertEquals("request-7", after.receipts.first().request.requestId)
        assertArrayEquals(byteArrayOf(70), after.bytes(DaemonDomainRole.AUTHORITY))
        assertEquals(after.authorityDigest(), after.control.authorityDigest)
        assertTrue(DaemonProtectionRootCodec.encode(after).size < 100_000)
        RootFixtures.fails { root.controlTransaction(after.dataRevision) { it.control = it.control.copy(revision = 999) } }
    }

    @Test fun lostOrCorruptReadbackPoisonsOwnerAndNeverFallsBackToOldActiveBytes() {
        for (corrupt in listOf(false, true)) {
            val (root, storage) = RootFixtures.root()
            if (corrupt) storage.corruptAfterWrite = true else storage.loseReadback = true
            RootFixtures.fails { root.transaction { it.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(9)) } }
            RootFixtures.fails { root.snapshot() }
            RootFixtures.fails { root.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(8)) }
            assertArrayEquals(byteArrayOf(9), root.inspectForReconciliation().bytes(DaemonDomainRole.RECOVERY_COUNTS))
        }
    }

    @Test fun invalidChecksumTruncationAndOversizeNeverResetAnInitializedRoot() {
        val encoded = DaemonProtectionRootCodec.encode(RootFixtures.snapshot())
        for (bad in listOf(encoded.copyOf(encoded.size - 1), encoded.copyOf().apply { this[10] = 99 },
                ByteArray(DaemonProtectionRootCodec.MAX_BYTES + 1))) {
            val storage = MemoryDaemonRootStorage().apply { saved = bad }
            val root = DaemonProtectionRoot(storage)
            RootFixtures.fails { root.snapshot() }; RootFixtures.fails { root.initializeImported(RootFixtures.snapshot()) }
            assertEquals(0, storage.writes)
        }
        val tooMuch = mapOf(DaemonDomainRole.RECOVERY_COUNTS to ByteArray(1024 * 1024),
            DaemonDomainRole.FCM_EPISODES to ByteArray(1024 * 1024))
        RootFixtures.fails { RootFixtures.snapshot(extras = tooMuch) }
    }
}
