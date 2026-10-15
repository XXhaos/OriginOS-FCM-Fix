package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonShellStorageTest {
    private val instance = "96fbde55-15e2-45b6-8bea-0de37312dd94"
    private val other = "470130f6-6518-44fb-8ab6-c76daee78ffb"
    private val directory get() = "${DaemonShellStorage.PARENT}/$instance"

    @Test fun fixedPathsRejectTraversalAndNonRandomInstanceIdsBeforeAnyIo() {
        for (id in listOf("../escape", "$instance/extra", instance.uppercase(),
                "00000000-0000-0000-0000-000000000000")) {
            val fs = MemoryFiles()
            fails { DaemonShellStorage.provisionAuthenticated(id, fs) }
            assertTrue(fs.events.isEmpty())
        }
    }

    @Test fun requiresShellAndRejectsWrongUidModeSymlinkAndNonDirectory() {
        val wrongCaller = MemoryFiles().apply { currentUid = 10000 }
        fails { DaemonShellStorage.provisionAuthenticated(instance, wrongCaller) }
        assertTrue(wrongCaller.events.isEmpty())
        for (metadata in listOf(
                stat(uid = 0, directory = true), stat(directory = true, permissions = 0x1ff),
                stat(type = 0xa000), stat())) {
            val fs = MemoryFiles().apply { nodes[DaemonShellStorage.PARENT] = Node(metadata) }
            fails { DaemonShellStorage.provisionAuthenticated(instance, fs) }
        }
        val ancestor = MemoryFiles().apply { nodes["/data/local"] = Node(stat(type = 0xa000)) }
        fails { DaemonShellStorage.provisionAuthenticated(instance, ancestor) }
    }

    @Test fun lockIsExclusiveAcrossInstancesAndEpochsAndCloseDoesNotDeleteCommittedData() {
        val fs = MemoryFiles()
        val first = DaemonShellStorage.provisionAuthenticated(instance, fs)
        first.write(DaemonRecordRole.AUTHORITY, byteArrayOf(9, 8, 7))
        val before = fs.events.count { it == "open:OWNER_LOCK:${DaemonShellStorage.PARENT}/owner.lock" }
        fails { DaemonShellStorage.provisionAuthenticated(other, fs) }
        fails { DaemonShellStorage.openExisting(instance, fs) }
        assertEquals(before, fs.events.count { it == "open:OWNER_LOCK:${DaemonShellStorage.PARENT}/owner.lock" })
        assertTrue(fs.ownerLocked())
        assertFalse(fs.nodes.containsKey("${DaemonShellStorage.PARENT}/$other"))
        first.close()
        fails { first.read(DaemonRecordRole.AUTHORITY) }
        DaemonShellStorage.openExisting(instance, fs).use {
            assertArrayEquals(byteArrayOf(9, 8, 7), it.read(DaemonRecordRole.AUTHORITY))
        }
        DaemonShellStorage.provisionAuthenticated(other, fs).close()
    }

    @Test fun externalLockDenialAndInitializationFailureReleaseOnlyTheirOwnReservation() {
        val fs = MemoryFiles().apply { externallyLocked = true }
        fails { DaemonShellStorage.provisionAuthenticated(instance, fs) }
        fs.externallyLocked = false
        // The rejected constructor's local reservation is gone; a real owner can now acquire it.
        DaemonShellStorage.provisionAuthenticated(instance, fs).use { assertFalse(it.isUncertain()) }
        val invalid = MemoryFiles().apply { nodes[DaemonShellStorage.PARENT] = Node(stat(uid = 0, directory = true)) }
        fails { DaemonShellStorage.provisionAuthenticated(instance, invalid) }
        DaemonShellStorage.openExisting(instance, fs).close()
    }

    @Test fun recordPayloadsAreExactAndRoleBoundAndMarkersCanBeRemoved() {
        val fs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, fs).use {
            val bytes = ByteArray(8193) { index -> (index % 127).toByte() }
            it.write(DaemonRecordRole.STOPPED_JOURNAL, bytes)
            assertArrayEquals(bytes, it.read(DaemonRecordRole.STOPPED_JOURNAL))
            it.write(DaemonRecordRole.HEARTBEAT_PENDING, byteArrayOf(1))
            it.delete(DaemonRecordRole.HEARTBEAT_PENDING)
            assertNull(it.read(DaemonRecordRole.HEARTBEAT_PENDING))
            // A valid envelope for another role is not valid authority.
            fs.nodes["$directory/authority.bin"] = requireNotNull(fs.nodes["$directory/stopped-journal.bin"]).copyNode()
            fails { it.read(DaemonRecordRole.AUTHORITY) }
            assertTrue(it.isUncertain())
        }
    }

    @Test fun existingRecordSymlinkWrongUidAndHardlinkAreDeniedBeforeOpen() {
        for (metadata in listOf(stat(type = 0xa000), stat(uid = 10000), stat(links = 2), stat(permissions = 0x1a4))) {
            val fs = MemoryFiles()
            DaemonShellStorage.provisionAuthenticated(instance, fs).use {
                fs.nodes["$directory/authority.bin"] = Node(metadata, byteArrayOf(1))
                fs.events.clear()
                fails { it.read(DaemonRecordRole.AUTHORITY) }
                assertFalse(fs.events.any { event -> event == "open:READ:$directory/authority.bin" })
                assertTrue(it.isUncertain())
            }
        }
    }

    @Test fun mismatchedOpenedDescriptorAndReplacedDirectoryAreDenied() {
        val fs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, fs).use {
            it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(1))
            fs.mismatchDescriptor = true
            fails { it.read(DaemonRecordRole.AUTHORITY) }
            assertTrue(it.isUncertain())
        }
        val otherFs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, otherFs).use {
            val old = requireNotNull(otherFs.nodes[directory])
            old.metadata = old.metadata.copy(inode = old.metadata.inode + 1)
            fails { it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(1)) }
            assertTrue(it.isUncertain())
        }
    }

    @Test fun truncatedOversizeAndChecksumFailuresBlockNormalReadsAcrossRestart() {
        for (corrupt in listOf<(ByteArray) -> ByteArray>(
                { it.copyOf(it.size - 1) }, { ByteArray(DaemonRecordRole.AUTHORITY.maximumBytes + 45) },
                { it.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() } })) {
            val fs = MemoryFiles()
            DaemonShellStorage.provisionAuthenticated(instance, fs).use {
                it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(1, 2))
                val node = requireNotNull(fs.nodes["$directory/authority.bin"])
                node.bytes = corrupt(node.bytes)
                fails { it.read(DaemonRecordRole.AUTHORITY) }
                assertTrue(fs.nodes.containsKey("$directory/.uncertain"))
            }
            DaemonShellStorage.openExisting(instance, fs).use {
                assertTrue(it.isUncertain())
                fails { it.read(DaemonRecordRole.AUTHORITY) }
            }
        }
    }

    @Test fun oversizeWriteIsRejectedWithoutChangingOrFencingExistingData() {
        val fs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, fs).use {
            it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(1))
            fails { it.write(DaemonRecordRole.AUTHORITY, ByteArray(DaemonRecordRole.AUTHORITY.maximumBytes + 1)) }
            assertFalse(it.isUncertain())
            assertArrayEquals(byteArrayOf(1), it.read(DaemonRecordRole.AUTHORITY))
        }
    }

    @Test fun everyAtomicWriteFailureIsStickyAndNeverDeletesCommittedFilesOrForeignTemps() {
        val phases = listOf("fence-write", "file-sync", "rename", "directory-sync-after-rename", "readback", "fence-clear-sync")
        for (phase in phases) {
            val fs = MemoryFiles()
            DaemonShellStorage.provisionAuthenticated(instance, fs).use {
                it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(1))
                val foreign = "$directory/.write-not-owned"
                fs.nodes[foreign] = Node(stat(), byteArrayOf(77))
                fs.failure = phase
                fails { it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(2)) }
                assertTrue("phase=$phase", it.isUncertain())
                assertTrue("phase=$phase", fs.nodes.containsKey("$directory/authority.bin"))
                assertTrue("phase=$phase", fs.nodes.containsKey("$directory/.uncertain"))
                assertArrayEquals(byteArrayOf(77), fs.nodes[foreign]?.bytes)
                assertEquals(listOf(foreign), fs.nodes.keys.filter { path -> path.startsWith("$directory/.write-") })
                fails { it.read(DaemonRecordRole.AUTHORITY) }
            }
            DaemonShellStorage.openExisting(instance, fs).use { assertTrue(it.isUncertain()) }
        }
    }

    @Test fun explicitReconciliationRequiresExactCompleteSnapshot() {
        val fs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, fs).use {
            it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(4))
            fs.failure = "rename"
            fails { it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(5)) }
            fails { it.acknowledgeReconciliation(emptyMap()) }
            val expected = DaemonRecordRole.entries.associateWith { role -> it.inspectForReconciliation(role) }
            val wrong = expected.toMutableMap().apply { put(DaemonRecordRole.AUTHORITY, byteArrayOf(99)) }
            fails { it.acknowledgeReconciliation(wrong) }
            assertTrue(it.isUncertain())
            it.acknowledgeReconciliation(expected)
            assertFalse(it.isUncertain())
            assertArrayEquals(byteArrayOf(4), it.read(DaemonRecordRole.AUTHORITY))
        }
    }

    @Test fun interruptedFenceCreationCanOnlyBeClearedByExplicitReconciliation() {
        val fs = MemoryFiles()
        DaemonShellStorage.provisionAuthenticated(instance, fs).use {
            it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(4))
            fs.failure = "fence-write"
            fails { it.write(DaemonRecordRole.AUTHORITY, byteArrayOf(5)) }
            assertTrue(it.isUncertain())
            assertEquals(0, fs.nodes["$directory/.uncertain"]?.bytes?.size)
        }
        DaemonShellStorage.openExisting(instance, fs).use {
            assertTrue(it.isUncertain())
            val expected = DaemonRecordRole.entries.associateWith { role -> it.inspectForReconciliation(role) }
            it.acknowledgeReconciliation(expected)
            assertArrayEquals(byteArrayOf(4), it.read(DaemonRecordRole.AUTHORITY))
        }
    }

    private fun fails(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("Expected failure")
    }

    private data class Node(var metadata: DaemonFileStat, var bytes: ByteArray = byteArrayOf()) {
        fun currentStat() = metadata.copy(size = bytes.size.toLong())
        fun copyNode() = Node(metadata.copy(inode = metadata.inode + 10000), bytes.copyOf())
    }
    private class MemoryFiles : DaemonShellFilePort {
        val nodes = mutableMapOf<String, Node>()
        val events = mutableListOf<String>()
        var currentUid = 2000
        var externallyLocked = false
        var mismatchDescriptor = false
        var failure: String? = null
        private var inode = 100L
        private var locked = false
        private var renamed = false
        private var cleared = false
        fun ownerLocked() = locked
        init { listOf("/data", "/data/local", "/data/local/tmp").forEach { nodes[it] = Node(stat(directory = true)) } }
        override fun uid() = currentUid
        override fun lstat(path: String): DaemonFileStat? { events += "lstat:$path"; return nodes[path]?.currentStat() }
        override fun mkdir(path: String) { check(nodes[path] == null); nodes[path] = Node(stat(directory = true).copy(inode = ++inode)) }
        override fun open(path: String, mode: DaemonFileOpen): DaemonFileHandle {
            events += "open:$mode:$path"
            if (mode == DaemonFileOpen.CREATE_EXCLUSIVE) check(nodes[path] == null)
            if (mode == DaemonFileOpen.CREATE_EXCLUSIVE && path.endsWith(".uncertain")) { renamed = false; cleared = false }
            val node = nodes[path] ?: run {
                check(mode != DaemonFileOpen.READ)
                Node(stat().copy(inode = ++inode)).also { nodes[path] = it }
            }
            check(node.metadata.mode and 0xf000 == 0x8000)
            if (mode == DaemonFileOpen.READ && renamed && path.endsWith("authority.bin")) fault("readback")
            return object : DaemonFileHandle {
                override fun stat() = node.currentStat().let { if (mismatchDescriptor) it.copy(inode = -1) else it }
                override fun read(maximum: Int): ByteArray { check(node.bytes.size <= maximum); return node.bytes.copyOf() }
                override fun write(bytes: ByteArray) {
                    if (path.endsWith(".uncertain")) fault("fence-write")
                    node.bytes = bytes.copyOf()
                }
                override fun sync() { if (path.contains("/.write-")) fault("file-sync") }
                override fun tryLock(): AutoCloseable? {
                    if (locked || externallyLocked) return null
                    locked = true
                    return AutoCloseable { locked = false }
                }
                override fun close() = Unit
            }
        }
        override fun rename(from: String, to: String) {
            fault("rename"); nodes[to] = requireNotNull(nodes.remove(from)); renamed = true; cleared = false
        }
        override fun unlink(path: String) { check(nodes.remove(path) != null); if (path.endsWith(".uncertain")) cleared = true }
        override fun syncDirectory(path: String) {
            if (cleared) fault("fence-clear-sync")
            if (renamed) fault("directory-sync-after-rename")
        }
        private fun fault(phase: String) { if (failure == phase) { failure = null; throw IllegalStateException(phase) } }
    }
    companion object {
        private fun stat(uid: Int = 2000, directory: Boolean = false, type: Int = if (directory) 0x4000 else 0x8000,
            permissions: Int = if (directory) 0x1c0 else 0x180, links: Long = 1) =
            DaemonFileStat(uid, type or permissions, 1, 1, 0, links)
    }
}
