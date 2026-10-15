package io.github.originosfcmguardian.daemon

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** One fixed App-private frozen payload. Unknown/side artifacts throw; only genuine absence is null. */
internal interface DaemonMigrationExportFilePort {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun <T> exclusive(action: () -> T): T
}

/** Exact frozen bytes, not consent or an execution capability. No mutable byte array escapes. */
internal class DaemonFrozenMigrationExport private constructor(val intent: DaemonMigrationIntent,
    val sha256: String, raw: ByteArray) {
    private val content = raw.copyOf()
    fun bytes(): ByteArray = content.copyOf()
    fun snapshot(): DaemonProtectionRootSnapshot = DaemonProtectionRootCodec.decode(content)

    companion object {
        internal fun capture(intent: DaemonMigrationIntent, raw: ByteArray,
            expectedDigest: String? = null): DaemonFrozenMigrationExport {
            require(raw.size in 40..DaemonProtectionRootCodec.MAX_BYTES)
            val content = raw.copyOf()
            val snapshot = DaemonProtectionRootCodec.decode(content)
            require(snapshot.control.owner == intent.owner && snapshot.control.migrationId == intent.migrationId &&
                snapshot.control.mode == DaemonControlMode.PREPARED && snapshot.control.revision == 1L &&
                snapshot.dataRevision == 1L && snapshot.receipts.isEmpty() && snapshot.halt == null)
            require(DaemonProtectionRootCodec.encode(snapshot).contentEquals(content)) { "Noncanonical frozen export" }
            val digest = DaemonProtectionRootSnapshot.digest(content)
            require(expectedDigest == null || expectedDigest == digest)
            return DaemonFrozenMigrationExport(intent, digest, content)
        }
    }
}

/** Immutable once captured. A failed/unknown capture must never cause an older owner fallback. */
internal class DaemonMigrationExportStore(private val files: DaemonMigrationExportFilePort) {
    /** Presence OR a thrown unknown must make the caller reject LOCAL, including an orphan payload. */
    fun hasArtifact(): Boolean = files.exclusive { files.read() != null }

    fun read(intent: DaemonMigrationIntent): DaemonFrozenMigrationExport? = files.exclusive {
        val bytes = files.read() ?: return@exclusive null
        DaemonMigrationExportCodec.decode(bytes).also { check(it.intent == intent) { "Frozen export belongs to another migration" } }
    }

    /** Capture only after actual old workers/FIFO drain; retries must supply exactly the same bytes. */
    fun saveOnce(intent: DaemonMigrationIntent, raw: ByteArray): Boolean = try {
        val encoded = DaemonMigrationExportCodec.encode(intent, raw)
        files.exclusive {
            val existing = files.read()
            if (existing != null) {
                check(DaemonMigrationExportCodec.decode(existing).intent == intent && existing.contentEquals(encoded)) {
                    "Frozen export cannot be replaced"
                }
            } else files.write(encoded)
            val saved = requireNotNull(files.read())
            check(saved.contentEquals(encoded))
            check(DaemonMigrationExportCodec.decode(saved).intent == intent)
            true
        }
    } catch (_: Exception) { false }
}

internal object DaemonMigrationExportCodec {
    const val MAX_BYTES = 2 * 1024 * 1024 + 512
    private const val MAGIC = 0x474d4531

    fun encode(intent: DaemonMigrationIntent, raw: ByteArray): ByteArray {
        val value = DaemonFrozenMigrationExport.capture(intent, raw)
        val content = value.bytes()
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(1)
            out.writeUTF(intent.owner.installInstance); out.writeLong(intent.owner.ownerEpoch); out.writeUTF(intent.migrationId)
            out.writeUTF(value.sha256); out.writeInt(content.size); out.write(content)
        } }.toByteArray()
        require(body.size + 32 <= MAX_BYTES)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    fun decode(bytes: ByteArray): DaemonFrozenMigrationExport {
        require(bytes.size in 80..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val intent = DaemonMigrationIntent(DaemonOwnerKey(input.text(), input.readLong()), input.text())
            val digest = input.text().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val size = input.readInt().also { require(it in 40..DaemonProtectionRootCodec.MAX_BYTES && it == input.available()) }
            val raw = ByteArray(size).also(input::readFully)
            DaemonFrozenMigrationExport.capture(intent, raw, digest)
        }
    }

    private fun DataInputStream.text(): String = readUTF().also { require(it.isNotBlank() && it.length <= 128) }
}

/** Strict AtomicFile access never restores .bak/.new or overwrites a previous frozen payload. */
internal class AtomicDaemonMigrationExportFile(context: Context) : DaemonMigrationExportFilePort {
    private val directory = context.applicationContext.noBackupFilesDir
    private val base = File(directory, "protection-daemon-export-v1.bin")
    private val lockFile = File(directory, "protection-daemon-export-v1.lock")

    override fun <T> exclusive(action: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lockFile, "rw").use { handle ->
            (handle.channel.tryLock() ?: error("Frozen export busy")).use { action() }
        }
    }

    override fun read(): ByteArray? {
        check(statOrMissing(File(base.path + ".bak")) == null && statOrMissing(File(base.path + ".new")) == null)
        val before = statOrMissing(base) ?: return null
        check(before.st_uid == Os.getuid() && before.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
            before.st_nlink == 1L && before.st_size in 1..DaemonMigrationExportCodec.MAX_BYTES.toLong())
        return FileInputStream(base).use { input ->
            val opened = Os.fstat(input.fd)
            check(same(before, opened))
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0); offset += count
            }
            check(input.read() == -1 && same(opened, Os.fstat(input.fd)) && same(opened, requireNotNull(statOrMissing(base))))
            bytes
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size in 80..DaemonMigrationExportCodec.MAX_BYTES)
        DaemonMigrationExportCodec.decode(bytes)
        read()?.let { check(it.contentEquals(bytes)) { "Frozen export cannot be overwritten" }; return }
        val atomic = AtomicFile(base)
        val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output); syncDirectory()
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        check(read()?.contentEquals(bytes) == true)
    }

    private fun syncDirectory() {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun same(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_mode == b.st_mode && a.st_nlink == b.st_nlink &&
        a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime
    private fun statOrMissing(file: File): StructStat? = try { Os.lstat(file.absolutePath) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
    private companion object { val monitor = Any() }
}
