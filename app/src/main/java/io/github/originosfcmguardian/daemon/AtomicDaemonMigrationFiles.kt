package io.github.originosfcmguardian.daemon

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructStat
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/** App-private files only. Never reads/changes shell directory permissions or copies authority. */
internal class AtomicDaemonMigrationFiles(context: Context) : DaemonMigrationFilePort {
    private val directory = context.noBackupFilesDir
    private val lockFile = File(directory, "protection-daemon-migration-v1.lock")
    private fun path(role: DaemonMigrationFile) = File(directory, when (role) {
        DaemonMigrationFile.INTENT -> "protection-daemon-migration-v1.intent"
        DaemonMigrationFile.POINTER -> "protection-daemon-pointer-v1.bin"
        DaemonMigrationFile.PENDING -> "protection-daemon-pointer-v1.pending"
        DaemonMigrationFile.STOP -> "protection-daemon-stop-v1.bin"
    })
    override fun <T> exclusive(action: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { handle ->
            (handle.channel.tryLock() ?: error("Migration pointer busy")).use { action() }
        }
    }
    override fun read(file: DaemonMigrationFile): ByteArray? {
        val base = path(file)
        // AtomicFile.openRead may restore an old backup. Never normalize an interrupted pointer.
        check(statOrMissing(File(base.path + ".bak")) == null && statOrMissing(File(base.path + ".new")) == null)
        val before = statOrMissing(base) ?: return null
        check(before.st_uid == Os.getuid() && before.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
            before.st_nlink == 1L && before.st_size in 1..DaemonMigrationPointerCodec.MAX_BYTES.toLong())
        return FileInputStream(base).use { input ->
            val opened = Os.fstat(input.fd)
            check(opened.st_dev == before.st_dev && opened.st_ino == before.st_ino && opened.st_size == before.st_size)
            val buffer = ByteArray(DaemonMigrationPointerCodec.MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                check(read > 0); count += read
            }
            val bytes = buffer.copyOf(count)
            val after = Os.fstat(input.fd)
            check(bytes.size.toLong() == before.st_size && after.st_size == before.st_size)
            bytes
        }
    }
    override fun write(file: DaemonMigrationFile, bytes: ByteArray) {
        require(bytes.size in 1..DaemonMigrationPointerCodec.MAX_BYTES)
        read(file) // Reject pre-existing interrupted or non-regular state before AtomicFile changes it.
        val atomic = AtomicFile(path(file))
        val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output); syncDirectory()
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        check(read(file)?.contentEquals(bytes) == true)
    }
    override fun remove(file: DaemonMigrationFile) {
        check(file == DaemonMigrationFile.PENDING || file == DaemonMigrationFile.STOP) { "Migration intent/pointer are never deleted" }
        val before = requireNotNull(read(file))
        if (file == DaemonMigrationFile.STOP) DaemonMigrationPointerCodec.decodeStopIntent(before)
        check(read(file)?.contentEquals(before) == true && path(file).delete())
        syncDirectory()
        check(read(file) == null)
    }
    private fun syncDirectory() {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun statOrMissing(file: File): StructStat? = try { Os.lstat(file.absolutePath) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
    private companion object { val monitor = Any() }
}
