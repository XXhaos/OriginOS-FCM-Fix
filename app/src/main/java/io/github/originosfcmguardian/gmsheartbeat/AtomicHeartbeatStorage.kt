package io.github.originosfcmguardian.gmsheartbeat

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption

/** Independent no-backup snapshot; never reads/writes target authority or the recovery journal. */
internal class AtomicHeartbeatStorage(context: Context) : HeartbeatStorage {
    private val directory = context.noBackupFilesDir
    private val file = AtomicFile(File(directory, "gms-auto-heartbeat-v1.bin"))
    private val initialized = File(directory, "gms-auto-heartbeat-v1.initialized")
    private val lockFile = File(directory, "gms-auto-heartbeat-v1.lock")
    private val haltFile = File(directory, "gms-auto-heartbeat-v1.halt")
    private val pendingFile = File(directory, "gms-auto-heartbeat-v1.pending")
    override fun unsettledPending(): Boolean = present(pendingFile)
    override fun coldBarrier(): HeartbeatResumeBlock? {
        // Probe before openRead(): AtomicFile can delete .new or restore .bak itself.
        return try {
            when {
                present(haltFile) -> HeartbeatResumeBlock.COLD_RECONSTRUCTION
                present(pendingFile) -> HeartbeatResumeBlock.LEFTOVER_PENDING
                present(File(file.baseFile.path + ".new")) || present(File(file.baseFile.path + ".bak")) -> HeartbeatResumeBlock.STORAGE_FAILURE
                else -> null
            }
        } catch (_: Exception) { HeartbeatResumeBlock.STORAGE_FAILURE }
    }
    private fun present(target: File): Boolean {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        check(Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        return false
    }
    override fun halt() { durableMarker(haltFile, byteArrayOf(1)) }
    override fun pending(value: HeartbeatPending) {
        check(!present(pendingFile))
        durableMarker(pendingFile, HeartbeatPendingCodec.encode(value))
    }
    override fun clearPending(value: HeartbeatPending) {
        check(pendingFile.isFile && pendingFile.length() <= HeartbeatPendingCodec.MAX_BYTES)
        check(HeartbeatPendingCodec.decode(pendingFile.readBytes()) == value)
        check(pendingFile.delete()); syncDirectory()
    }
    override fun releaseForExplicitConsent() {
        // Caller already committed the fresh policy3 epoch and exact qualified owner.
        check(!present(pendingFile))
        for (target in listOf(haltFile)) if (present(target)) {
            check(target.isFile && target.delete()); syncDirectory()
        }
        check(coldBarrier() == null)
    }
    private fun durableMarker(target: File, bytes: ByteArray) {
        // A partial/truncated marker is deliberately still a barrier at reconstruction.
        FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
        syncDirectory()
    }
    override fun <T> exclusive(action: () -> T): T = RandomAccessFile(lockFile, "rw").use { handle ->
        (handle.channel.tryLock() ?: error("Heartbeat storage busy")).use { action() }
    }
    override fun read(): HeartbeatSnapshot? {
        val bytes = try { file.openRead().use { input ->
            val buffer = ByteArray(HeartbeatSnapshotCodec.MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                check(count > 0); size += count
            }
            check(size <= HeartbeatSnapshotCodec.MAX_BYTES)
            buffer.copyOf(size)
        } } catch (_: FileNotFoundException) {
            check(!initialized.exists() && !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists() && !File(file.baseFile.path + ".new").exists())
            return null
        }
        check(initialized.isFile && initialized.length() == 1L && initialized.inputStream().use { it.read() } == 1)
        return HeartbeatSnapshotCodec.decode(bytes)
    }
    override fun write(value: HeartbeatSnapshot) {
        val bytes = HeartbeatSnapshotCodec.encode(value)
        read() // Never overwrite corruption or initialized-but-missing data.
        if (!initialized.exists()) {
            FileOutputStream(initialized).use { it.write(1); it.fd.sync() }; syncDirectory()
        }
        val output = file.startWrite()
        try {
            output.write(bytes); output.fd.sync(); file.finishWrite(output); syncDirectory()
        } catch (failed: Exception) { file.failWrite(output); throw failed }
        check(!File(file.baseFile.path + ".new").exists() && read() == value)
    }
    private fun syncDirectory() {
        check(directory.isDirectory)
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
