package io.github.originosfcmguardian.pushactivation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** App-private and excluded from backup. Interrupted files are unknown, never silently restored. */
internal class AndroidPushActivationStorage(context: Context) : PushActivationStorage {
    private val directory = context.noBackupFilesDir
    private val file = File(directory, "push-activation-v1.bin")
    private val lock = File(directory, "push-activation-v1.lock")

    override fun <T> exclusive(action: () -> T): T = synchronized(monitor) {
        RandomAccessFile(lock, "rw").use { handle ->
            (handle.channel.tryLock() ?: error("Activation storage busy")).use { action() }
        }
    }

    override fun read(): PushActivationSnapshot? {
        check(stat(File(file.path + ".bak")) == null && stat(File(file.path + ".new")) == null)
        val before = stat(file) ?: return null
        check(before.st_uid == Os.getuid() && before.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
            before.st_nlink == 1L && before.st_size in 1..PushActivationCodec.MAX_BYTES.toLong())
        val bytes = FileInputStream(file).use { input ->
            val opened = Os.fstat(input.fd)
            check(opened.st_dev == before.st_dev && opened.st_ino == before.st_ino && opened.st_size == before.st_size)
            val buffer = ByteArray(PushActivationCodec.MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                check(read > 0); count += read
            }
            val after = Os.fstat(input.fd)
            check(count.toLong() == before.st_size && after.st_size == before.st_size)
            buffer.copyOf(count)
        }
        return PushActivationCodec.decode(bytes)
    }

    override fun write(value: PushActivationSnapshot) {
        read() // Reject unknown previous state before AtomicFile can restore it.
        val bytes = PushActivationCodec.encode(value)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun stat(value: File): StructStat? = try { Os.lstat(value.absolutePath) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }

    private companion object { val monitor = Any() }
}

/** Current Guardian principal only. A normal update with the same UID/install/signers is allowed. */
@Suppress("DEPRECATION")
internal fun readPushActivationInstallation(context: Context): PushActivationInstallation {
    val packageName = context.packageName
    val before = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val app = checkNotNull(before.applicationInfo)
    check(before.packageName == packageName && app.packageName == packageName && app.uid == Process.myUid() && app.enabled)
    val signers = checkNotNull(before.signingInfo?.apkContentsSigners)
    check(signers.size in 1..16)
    val hashes = signers.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }.sorted()
    val value = PushActivationInstallation(app.uid, before.firstInstallTime, before.longVersionCode, before.lastUpdateTime, hashes)
    val after = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    check(after.applicationInfo?.uid == app.uid && after.firstInstallTime == before.firstInstallTime &&
        after.lastUpdateTime == before.lastUpdateTime && after.longVersionCode == before.longVersionCode &&
        after.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.sorted() == signers.map { it.toCharsString() }.sorted())
    return value
}
