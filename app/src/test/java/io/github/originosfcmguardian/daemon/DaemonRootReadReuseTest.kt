package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonRootReadReuseTest {
    /** Intentionally exposes the backing read array to test the memo's private byte key. */
    private class Storage(snapshot: DaemonProtectionRootSnapshot) : DaemonProtectionRootStorage {
        var saved: ByteArray? = DaemonProtectionRootCodec.encode(snapshot)
        var reads = 0
        var writes = 0
        var failRead = false
        var failWrite = false
        var loseReadback = false
        override fun read(): ByteArray? {
            reads++
            check(!failRead)
            return saved
        }
        override fun inspect() = saved?.copyOf()
        override fun write(bytes: ByteArray) {
            check(!failWrite)
            writes++
            saved = bytes.copyOf()
            if (loseReadback) failRead = true
        }
        @Synchronized override fun <T> withOwnerLease(action: () -> T): T = action()
    }

    private fun changed(base: DaemonProtectionRootSnapshot, role: DaemonDomainRole, bytes: ByteArray?) =
        DaemonProtectionRootSnapshot(base.control, base.domains().toMutableMap().apply { put(role, bytes) },
            base.receipts, base.halt, base.dataRevision)

    @Test fun unchangedSnapshotsReuseValidatedValueButAlwaysReadStorageAndReturnDefensiveBytes() {
        val storage = Storage(RootFixtures.snapshot())
        val root = DaemonProtectionRoot(storage)
        val first = root.snapshot()
        repeat(4) { assertSame(first, root.snapshot()) }
        assertEquals(5, storage.reads)
        val escaped = first.bytes(DaemonDomainRole.AUTHORITY)!!
        escaped[0] = 99
        first.domains()[DaemonDomainRole.AUTHORITY]!![0] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3), root.bytes(DaemonDomainRole.AUTHORITY))
        assertEquals(6, storage.reads)
        assertEquals(first.control.authorityDigest, first.authorityDigest())
        assertEquals(0, storage.writes)
    }

    @Test fun sameRevisionExternalOffAndMarkerChangesCannotHitAnOldMemo() {
        val initial = RootFixtures.snapshot()
        val active = DaemonProtectionRootSnapshot(initial.control.copy(mode = DaemonControlMode.ACTIVE), initial.domains())
        val storage = Storage(active)
        val root = DaemonProtectionRoot(storage)
        val warm = root.snapshot()
        storage.saved = DaemonProtectionRootCodec.encode(initial)
        val off = root.snapshot()
        assertNotSame(warm, off)
        assertEquals(warm.dataRevision, off.dataRevision)
        assertEquals(warm.control.revision, off.control.revision)
        assertEquals(DaemonControlMode.OFF, off.control.mode)
        for (role in listOf(DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.INTERRUPTED_ARTIFACTS,
            DaemonDomainRole.STOPPED_BLOCKED, DaemonDomainRole.HEARTBEAT_HALT)) {
            storage.saved = DaemonProtectionRootCodec.encode(changed(initial, role, byteArrayOf()))
            assertArrayEquals(byteArrayOf(), root.bytes(role))
        }
        assertEquals(6, storage.reads)
    }

    @Test fun mutatedStorageReadAliasCannotMutateTheMemoKeyAndHideCorruption() {
        val storage = Storage(RootFixtures.snapshot())
        val root = DaemonProtectionRoot(storage)
        root.snapshot()
        storage.saved!![10] = (storage.saved!![10].toInt() xor 1).toByte()
        RootFixtures.fails { root.snapshot() }
        storage.saved = DaemonProtectionRootCodec.encode(RootFixtures.snapshot())
        RootFixtures.fails { root.snapshot() }
        assertEquals(2, storage.reads)
    }

    @Test fun warmCacheNeverMasksMissingStorageReadFailureOrOversizeAndFailureRemainsClosed() {
        for (failure in 0..2) {
            val storage = Storage(RootFixtures.snapshot())
            val root = DaemonProtectionRoot(storage)
            root.snapshot()
            when (failure) {
                0 -> storage.saved = null
                1 -> storage.failRead = true
                else -> storage.saved = ByteArray(DaemonProtectionRootCodec.MAX_BYTES + 1)
            }
            RootFixtures.fails { root.snapshot() }
            storage.failRead = false
            storage.saved = DaemonProtectionRootCodec.encode(RootFixtures.snapshot())
            RootFixtures.fails { root.snapshot() }
            assertEquals(0, storage.writes)
            assertEquals(2, storage.reads)
            assertNotNull(root.inspectForReconciliation())
            RootFixtures.fails { root.snapshot() }
        }
    }

    @Test fun noOpAndNestedRollbackKeepRevisionBytesAndWritesWhileStagedReadsSeeNullDirectly() {
        val initial = RootFixtures.snapshot(extras = mapOf(DaemonDomainRole.STOPPED_JOURNAL to byteArrayOf(7)))
        val storage = Storage(initial)
        val root = DaemonProtectionRoot(storage)
        val before = storage.saved!!.copyOf()
        root.transaction { mutation ->
            mutation.put(DaemonDomainRole.STOPPED_JOURNAL, null)
            assertNull(root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
            RootFixtures.fails { root.transaction {
                it.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(9))
                assertArrayEquals(byteArrayOf(9), root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
                error("nested rollback")
            } }
            assertNull(root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
            mutation.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(7))
        }
        assertEquals(0, storage.writes)
        assertArrayEquals(before, storage.saved)
        assertEquals(initial.dataRevision, root.snapshot().dataRevision)
        root.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf())
        assertEquals(1, storage.writes)
        assertEquals(initial.dataRevision + 1, root.snapshot().dataRevision)
        assertArrayEquals(byteArrayOf(), root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
    }

    @Test fun structuralEqualityChecksEveryRoleAndEveryControlFieldIncludingNullVersusEmpty() {
        val base = RootFixtures.snapshot()
        assertTrue(base.sameContent(DaemonProtectionRootCodec.decode(DaemonProtectionRootCodec.encode(base))))
        for (role in DaemonDomainRole.entries) {
            val absent = changed(base, role, null)
            val empty = changed(base, role, byteArrayOf())
            assertFalse("role=$role", absent.sameContent(empty))
            assertFalse("role=$role", empty.sameContent(absent))
            assertFalse("role=$role", absent.sameRole(empty, role))
            assertFalse("role=$role", empty.sameRole(absent, role))
        }
        assertFalse(base.sameContent(DaemonProtectionRootSnapshot(base.control, base.domains(), dataRevision = 2)))
        assertFalse(base.sameContent(DaemonProtectionRootSnapshot(base.control.copy(revision = 2), base.domains())))
        val request = DaemonControlRequest("same-content", base.control.owner, 1,
            DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("proof", base.control.copy(revision = 2))))
        assertFalse(base.sameContent(DaemonProtectionRootSnapshot(base.control, base.domains(),
            halt = DaemonHaltFence(request, 2))))
        assertFalse(base.sameContent(DaemonProtectionRootSnapshot(base.control, base.domains(),
            receipts = listOf(DaemonControlReceipt(request, base.control.copy(revision = 2))))))
    }

    @Test fun warmCacheCannotSurviveFailedCommitOrReadback() {
        for (readback in listOf(false, true)) {
            val storage = Storage(RootFixtures.snapshot())
            val root = DaemonProtectionRoot(storage)
            root.snapshot()
            if (readback) storage.loseReadback = true else storage.failWrite = true
            RootFixtures.fails { root.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(9)) }
            storage.failRead = false
            storage.failWrite = false
            RootFixtures.fails { root.snapshot() }
            RootFixtures.fails { root.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(8)) }
        }
    }
}
