package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonDiagnosticsStorageTest {
    private val instance = "96fbde55-15e2-45b6-8bea-0de37312dd94"
    private val directory get() = "${SecureDaemonDiagnosticsStorage.PARENT}/$instance"
    @Test fun pathsPermissionsAndOpenedIdentityAreCheckedWithoutAuthorityFiles() {
        val invalid = Files()
        fails { SecureDaemonDiagnosticsStorage("../x", invalid) }
        assertTrue(invalid.opened.isEmpty())
        for (stat in listOf(metadata(type = 0xa000), metadata(uid = 0), metadata(permissions = 0x1ff))) {
            val files = Files(); files.nodes[SecureDaemonDiagnosticsStorage.PARENT] = Node(stat)
            fails { SecureDaemonDiagnosticsStorage(instance, files) }
        }
        val files = Files()
        SecureDaemonDiagnosticsStorage(instance, files).use { store ->
            store.write(DaemonDiagnosticsSnapshot())
            assertEquals(DaemonDiagnosticsSnapshot(), store.read())
            assertTrue(files.opened.all { it.startsWith(SecureDaemonDiagnosticsStorage.PARENT + "/") })
            assertFalse(files.nodes.keys.any { it.contains(".uncertain") || it.endsWith("control.bin") })
            files.mismatch = true
            fails { store.read() }
        }
    }
    @Test fun symlinksHardlinksAndUnsafePermissionsAreRejectedBeforeReading() {
        for (stat in listOf(metadata(type = 0xa000), metadata(links = 2), metadata(uid = 10000), metadata(permissions = 0x1a4))) {
            val files = Files()
            SecureDaemonDiagnosticsStorage(instance, files).use { store ->
                files.nodes["$directory/history.bin"] = Node(stat, byteArrayOf(1))
                val before = files.opened.size
                fails { store.read() }; assertEquals(before, files.opened.size)
            }
        }
    }
    @Test fun oldWriterCannotOverlapReplacementOrReleaseItsLockThroughSecondOpen() {
        val files = Files(); val first = SecureDaemonDiagnosticsStorage(instance, files)
        val before = files.opened.size
        fails { SecureDaemonDiagnosticsStorage(instance, files) }
        assertEquals(before, files.opened.size); assertTrue(files.locked)
        first.write(DaemonDiagnosticsSnapshot()); first.close()
        SecureDaemonDiagnosticsStorage(instance, files).use { assertNotNull(it.read()) }
        fails { first.write(DaemonDiagnosticsSnapshot()) }
    }
    @Test fun interruptedWriteAndCorruptHistoryArePreservedWithoutAnyFenceOrCleanup() {
        val files = Files()
        SecureDaemonDiagnosticsStorage(instance, files).use { store ->
            store.write(DaemonDiagnosticsSnapshot())
            files.failRename = true
            fails { store.write(DaemonDiagnosticsSnapshot(generation = 1)) }
            assertTrue(files.nodes.containsKey("$directory/pending.bin"))
        }
        val before = files.nodes.mapValues { it.value.bytes.toList() }
        fails { SecureDaemonDiagnosticsStorage(instance, files) }
        assertEquals(before, files.nodes.mapValues { it.value.bytes.toList() })
        assertFalse(files.nodes.keys.any { it.contains(".uncertain") })
        val corrupt = Files()
        SecureDaemonDiagnosticsStorage(instance, corrupt).use { store ->
            store.write(DaemonDiagnosticsSnapshot())
            val node = corrupt.nodes.getValue("$directory/history.bin")
            node.bytes = node.bytes.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() }
            val bytes = node.bytes.copyOf()
            fails { store.read() }; assertArrayEquals(bytes, node.bytes)
        }
    }
    private fun fails(action: () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected rejection") }
    private data class Node(val stat: DaemonFileStat, var bytes: ByteArray = byteArrayOf()) {
        fun metadata() = stat.copy(size = bytes.size.toLong())
    }
    private class Files : DaemonShellFilePort {
        val nodes = mutableMapOf<String, Node>()
        val opened = mutableListOf<String>()
        var locked = false
        var mismatch = false
        var failRename = false
        private var inode = 10L
        init { listOf("/data", "/data/local", "/data/local/tmp").forEach { nodes[it] = Node(metadata(type = 0x4000, permissions = 0x1c0)) } }
        override fun uid() = 2000
        override fun lstat(path: String) = nodes[path]?.metadata()
        override fun mkdir(path: String) { check(path !in nodes); nodes[path] = Node(metadata(type = 0x4000, permissions = 0x1c0).copy(inode = ++inode)) }
        override fun open(path: String, mode: DaemonFileOpen): DaemonFileHandle {
            opened += path
            if (mode == DaemonFileOpen.CREATE_EXCLUSIVE) check(path !in nodes)
            val node = nodes[path] ?: run { check(mode != DaemonFileOpen.READ); Node(metadata().copy(inode = ++inode)).also { nodes[path] = it } }
            return object : DaemonFileHandle {
                override fun stat() = node.metadata().let { if (mismatch) it.copy(inode = -1) else it }
                override fun read(maximum: Int) = node.bytes.copyOf().also { check(it.size <= maximum) }
                override fun write(bytes: ByteArray) { node.bytes = bytes.copyOf() }
                override fun sync() = Unit
                override fun tryLock(): AutoCloseable? {
                    if (locked) return null
                    locked = true; return AutoCloseable { locked = false }
                }
                override fun close() = Unit
            }
        }
        override fun rename(from: String, to: String) { check(!failRename); nodes[to] = requireNotNull(nodes.remove(from)) }
        override fun unlink(path: String) { error("Diagnostics must preserve artifacts") }
        override fun syncDirectory(path: String) = Unit
    }
    companion object {
        private fun metadata(uid: Int = 2000, type: Int = 0x8000, permissions: Int = 0x180, links: Long = 1) =
            DaemonFileStat(uid, type or permissions, 1, 1, 0, links)
    }
}
