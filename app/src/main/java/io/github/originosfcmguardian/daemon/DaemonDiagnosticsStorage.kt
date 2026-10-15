package io.github.originosfcmguardian.daemon

import java.util.UUID

internal class DaemonDiagnosticsLocked : IllegalStateException("Diagnostic writer already owned")
internal class DaemonDiagnosticsCorrupt(cause: Exception) : IllegalStateException("Diagnostic history unavailable", cause)
internal interface DaemonDiagnosticsStorage : AutoCloseable {
    fun read(): DaemonDiagnosticsSnapshot?
    fun write(value: DaemonDiagnosticsSnapshot)
}

/** Separate lease and files: no authority storage method, uncertainty marker or CONTROL access. */
internal class SecureDaemonDiagnosticsStorage(
    instance: String, private val fs: DaemonShellFilePort = AndroidDaemonShellFilePort(),
) : DaemonDiagnosticsStorage {
    private val directory: String
    private val reservation = Any()
    private var reserved = false
    private var lockHandle: DaemonFileHandle? = null
    private var lock: AutoCloseable? = null
    private var parent: DaemonFileStat? = null
    private var instanceDirectory: DaemonFileStat? = null
    private var closed = false
    init {
        val id = UUID.fromString(instance)
        require(id.toString() == instance && id.version() == 4 && id.variant() == 2)
        directory = "$PARENT/$instance"
        try {
            check(fs.uid() == 2000)
            synchronized(MONITOR) {
                if (processReservation != null) throw DaemonDiagnosticsLocked()
                processReservation = reservation; reserved = true
            }
            listOf("/data", "/data/local", "/data/local/tmp").forEach {
                check(requireNotNull(fs.lstat(it)).mode and 0xf000 == 0x4000)
            }
            parent = directory(PARENT, "/data/local/tmp")
            lockHandle = checkedOpen("$PARENT/writer.lock", DaemonFileOpen.OWNER_LOCK)
            lock = lockHandle!!.tryLock() ?: throw DaemonDiagnosticsLocked()
            lockHandle!!.sync(); verify(PARENT, requireNotNull(parent)); fs.syncDirectory(PARENT)
            instanceDirectory = directory(directory, PARENT)
            // A previous incomplete write is evidence, never silently removed or overwritten.
            if (fs.lstat("$directory/pending.bin") != null) throw DaemonDiagnosticsCorrupt(IllegalStateException("Unsettled diagnostic write"))
        } catch (failure: Exception) { close(); throw failure }
    }
    private fun directory(path: String, parentPath: String): DaemonFileStat {
        if (fs.lstat(path) == null) { fs.mkdir(path); fs.syncDirectory(parentPath) }
        return requireNotNull(fs.lstat(path)).also(::checkDirectory)
    }
    private fun checkDirectory(s: DaemonFileStat) { check(s.uid == 2000 && s.mode and 0xf000 == 0x4000 && s.mode and 0xfff == 0x1c0) }
    private fun checkFile(s: DaemonFileStat) { check(s.uid == 2000 && s.mode and 0xf000 == 0x8000 && s.mode and 0xfff == 0x180 && s.links == 1L) }
    private fun verify(path: String, expected: DaemonFileStat) {
        val actual = requireNotNull(fs.lstat(path))
        if (expected.mode and 0xf000 == 0x4000) checkDirectory(actual) else checkFile(actual)
        check(actual.device == expected.device && actual.inode == expected.inode)
    }
    private fun verifyDirectories() {
        check(!closed && fs.uid() == 2000 && lock != null)
        verify(PARENT, requireNotNull(parent)); verify(directory, requireNotNull(instanceDirectory))
    }
    private fun checkedOpen(path: String, mode: DaemonFileOpen): DaemonFileHandle {
        fs.lstat(path)?.let { checkFile(it); check(mode != DaemonFileOpen.CREATE_EXCLUSIVE) }
        val handle = fs.open(path, mode)
        try { checkFile(handle.stat()); verify(path, handle.stat()); return handle }
        catch (failure: Exception) { runCatching { handle.close() }; throw failure }
    }
    override fun read(): DaemonDiagnosticsSnapshot? {
        verifyDirectories()
        val path = "$directory/history.bin"
        if (fs.lstat(path) == null) return null
        return checkedOpen(path, DaemonFileOpen.READ).use { handle ->
            check(handle.stat().size in 40..DaemonDiagnosticsCodec.MAX_BYTES.toLong())
            val bytes = handle.read(DaemonDiagnosticsCodec.MAX_BYTES)
            check(handle.stat().size == bytes.size.toLong()); verifyDirectories(); verify(path, handle.stat())
            try { DaemonDiagnosticsCodec.decode(bytes) } catch (failure: Exception) { throw DaemonDiagnosticsCorrupt(failure) }
        }
    }
    override fun write(value: DaemonDiagnosticsSnapshot) {
        val bytes = DaemonDiagnosticsCodec.encode(value)
        verifyDirectories()
        val target = "$directory/history.bin"; val pending = "$directory/pending.bin"
        fs.lstat(target)?.let(::checkFile)
        checkedOpen(pending, DaemonFileOpen.CREATE_EXCLUSIVE).use { handle ->
            handle.write(bytes); handle.sync(); check(handle.stat().size == bytes.size.toLong())
            verifyDirectories(); verify(pending, handle.stat()); fs.lstat(target)?.let(::checkFile)
            fs.rename(pending, target); fs.syncDirectory(directory)
            verify(target, handle.stat())
        }
        // Readback belongs solely to this diagnostic owner; failure disables only diagnostics.
        check(read() == value)
    }
    override fun close() {
        if (closed) return
        closed = true
        try { try { lock?.close() } finally { lockHandle?.close() } }
        finally { synchronized(MONITOR) { if (reserved && processReservation === reservation) processReservation = null; reserved = false } }
    }
    companion object {
        const val PARENT = "/data/local/tmp/originos-fcm-guardian-diagnostics"
        private val MONITOR = Any()
        private var processReservation: Any? = null
    }
}
