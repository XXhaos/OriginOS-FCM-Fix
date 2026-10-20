package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonRootMutationCopyTest {
    private fun request(control: DaemonControlRecord, expected: Long = control.revision) =
        DaemonControlRequest("copy-test", control.owner, expected,
            DaemonControlIntent.SetAuthority(DaemonPointerCommitProof("proof", control)))

    @Test fun untouchedMutationReusesBaseAndMetadataChangesNeverDisappear() {
        val initial = RootFixtures.snapshot()
        val base = DaemonProtectionRootSnapshot(initial.control.copy(revision = 2), initial.domains())
        val mutation = DaemonRootMutation(base)
        assertSame(base, mutation.freeze())
        mutation.control = base.control.copy(revision = 3)
        assertEquals(3L, mutation.freeze().control.revision)
        assertNotSame(base, mutation.freeze())
        mutation.control = base.control
        assertSame(base, mutation.freeze())
        val halt = DaemonHaltFence(request(base.control), 3)
        mutation.halt = halt
        assertEquals(halt, mutation.freeze().halt)
        assertNotSame(base, mutation.freeze())
        mutation.halt = null
        mutation.dataRevision = 2
        assertEquals(2L, mutation.freeze().dataRevision)
        assertNotSame(base, mutation.freeze())
        mutation.dataRevision = base.dataRevision
        val receipt = DaemonControlReceipt(request(base.control, 1), base.control)
        mutation.remember(receipt)
        assertEquals(listOf(receipt), mutation.freeze().receipts)
        assertNotSame(base, mutation.freeze())
        mutation.restore(base)
        assertSame(base, mutation.freeze())
    }

    @Test fun copyOnFirstPutAndFrozenSnapshotsPreserveAllArrayBoundaries() {
        val original = byteArrayOf(1, 2, 3)
        val base = RootFixtures.snapshot(extras = mapOf(DaemonDomainRole.STOPPED_JOURNAL to original))
        val mutation = DaemonRootMutation(base)
        mutation.bytes(DaemonDomainRole.STOPPED_JOURNAL)!![0] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3), base.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        val written = byteArrayOf(4, 5, 6)
        mutation.put(DaemonDomainRole.STOPPED_JOURNAL, written)
        written[0] = 99
        val frozen = mutation.freeze()
        mutation.bytes(DaemonDomainRole.STOPPED_JOURNAL)!![0] = 77
        mutation.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(7))
        assertArrayEquals(byteArrayOf(4, 5, 6), frozen.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertArrayEquals(byteArrayOf(1, 2, 3), base.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertFalse(base.sameRole(frozen, DaemonDomainRole.STOPPED_JOURNAL))
        assertTrue(base.sameRole(frozen, DaemonDomainRole.AUTHORITY))
        mutation.restore(frozen)
        assertSame(frozen, mutation.freeze())
        mutation.put(DaemonDomainRole.STOPPED_JOURNAL, null)
        assertNull(mutation.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertArrayEquals(byteArrayOf(4, 5, 6), frozen.bytes(DaemonDomainRole.STOPPED_JOURNAL))
    }

    @Test fun repeatedNullPutsAndRestoredMetadataRemainNoOpsWithoutWriting() {
        val (root, storage) = RootFixtures.root()
        val before = storage.saved!!.copyOf()
        root.transaction { mutation ->
            mutation.put(DaemonDomainRole.STOPPED_JOURNAL, null)
            mutation.put(DaemonDomainRole.STOPPED_JOURNAL, null)
            assertNull(root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
            val originalControl = mutation.control
            mutation.control = originalControl.copy(revision = originalControl.revision + 1)
            mutation.control = originalControl
            mutation.halt = DaemonHaltFence(request(originalControl), originalControl.revision + 1)
            mutation.halt = null
        }
        assertArrayEquals(before, storage.saved)
        assertEquals(1, storage.writes)
        assertEquals(1L, root.snapshot().dataRevision)
    }

    @Test fun nestedRollbackAfterOuterPutRestoresOuterBytesAndMetadataBeforeContinuing() {
        val (root, storage) = RootFixtures.root()
        val before = root.snapshot()
        root.transaction { outer ->
            outer.put(DaemonDomainRole.STOPPED_JOURNAL, byteArrayOf(7))
            RootFixtures.fails { root.transaction { nested ->
                nested.put(DaemonDomainRole.STOPPED_JOURNAL, null)
                nested.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(99))
                nested.control = before.control.copy(revision = 2)
                nested.halt = DaemonHaltFence(request(before.control), 2)
                nested.remember(DaemonControlReceipt(request(before.control), nested.control))
                error("rollback after outer put")
            } }
            assertArrayEquals(byteArrayOf(7), root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
            assertNull(root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
            assertEquals(before.control, outer.control)
            assertNull(outer.halt)
            assertTrue(outer.freeze().receipts.isEmpty())
            outer.put(DaemonDomainRole.RECOVERY_COUNTS, byteArrayOf(8))
        }
        assertEquals(2, storage.writes)
        assertEquals(2L, root.snapshot().dataRevision)
        assertArrayEquals(byteArrayOf(7), root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertArrayEquals(byteArrayOf(8), root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
    }

    @Test fun metadataOnlyControlAndHaltCommitButSameRevisionReceiptStillFailsGuard() {
        val (root, storage) = RootFixtures.root()
        root.controlTransaction(1) { it.control = it.control.copy(revision = 2) }
        val afterControl = root.snapshot()
        assertEquals(2L, afterControl.control.revision)
        assertEquals(2L, afterControl.dataRevision)
        val halt = DaemonHaltFence(request(afterControl.control), 3)
        root.controlTransaction(afterControl.dataRevision) { it.halt = halt }
        val afterHalt = root.snapshot()
        assertEquals(halt, afterHalt.halt)
        assertEquals(3L, afterHalt.dataRevision)
        val receipt = DaemonControlReceipt(request(afterHalt.control, 1), afterHalt.control)
        RootFixtures.fails { root.controlTransaction(afterHalt.dataRevision) { it.remember(receipt) } }
        assertEquals(3, storage.writes)
        assertTrue(root.snapshot().receipts.isEmpty())
        assertEquals(halt, root.snapshot().halt)
    }
}
