package io.github.originosfcmguardian.doze

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.core.doze.DozeJournalCodec
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The app is the only journal writer. This path is separate from the immutable first read record.
 * An orphaned AtomicFile artifact is evidence, never an empty slot to overwrite or auto-recover.
 */
internal class DozeAtomicJournalStore private constructor(
    private val access: DozeJournalFileAccess,
) : DozeJournalPort {
    constructor(context: Context) : this(
        AndroidDozeJournalFileAccess(
            File(context.applicationContext.noBackupFilesDir, FILE_NAME),
        ),
    )

    internal constructor(access: DozeJournalFileAccess, expectedCanonicalPath: String) : this(access) {
        require(access.canonicalPath == File(expectedCanonicalPath).canonicalPath)
    }

    private val lock = lockFor(access.canonicalPath)

    override fun load(): ByteArray? = synchronized(lock) { loadLocked() }

    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean = synchronized(lock) {
        require(replacement.size <= DozeJournalCodec.MAX_BYTES) { "Doze journal exceeds size limit" }
        val current = loadLocked()
        if (!sameBytes(current, expected)) return@synchronized false
        DozeJournalCodec.validateTransition(current, replacement)

        var output: FileOutputStream? = null
        var finished = false
        try {
            output = access.startWrite()
            output.write(replacement)
            output.flush()
            access.sync(output)
            access.finishWrite(output)
            finished = true
        } catch (failure: Throwable) {
            if (!finished) runCatching { access.failWrite(output) }
            throw failure
        }

        val readBack = loadLocked()
        if (!sameBytes(readBack, replacement)) {
            throw IOException("Doze journal read-back differs from committed replacement")
        }
        true
    }

    private fun loadLocked(): ByteArray? {
        if (!access.hasAnyArtifact()) return null
        val bytes = access.readStrictlyBounded(DozeJournalCodec.MAX_BYTES)
        DozeJournalCodec.validate(bytes)
        return bytes
    }

    companion object {
        const val FILE_NAME = "doze-transaction-v2.bin"
        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(path: String): Any = locks.computeIfAbsent(path) { Any() }
        private fun sameBytes(left: ByteArray?, right: ByteArray?): Boolean = when {
            left == null || right == null -> left == null && right == null
            else -> left.contentEquals(right)
        }
    }
}

internal interface DozeJournalFileAccess {
    val canonicalPath: String
    fun hasAnyArtifact(): Boolean
    fun readStrictlyBounded(maxBytes: Int): ByteArray
    fun startWrite(): FileOutputStream
    fun sync(output: FileOutputStream)
    fun finishWrite(output: FileOutputStream)
    fun failWrite(output: FileOutputStream?)
}

private class AndroidDozeJournalFileAccess(private val base: File) : DozeJournalFileAccess {
    private val atomic = AtomicFile(base)
    private val backup: File get() = File(base.path + ".bak")
    private val pending: File get() = File(base.path + ".new")

    override val canonicalPath: String = base.canonicalPath

    override fun hasAnyArtifact(): Boolean = base.exists() || backup.exists() || pending.exists()

    override fun readStrictlyBounded(maxBytes: Int): ByteArray {
        if (backup.exists() || pending.exists()) {
            throw IOException("Doze journal AtomicFile artifact requires explicit review")
        }
        if (!base.exists()) throw IOException("Doze journal artifact exists without readable base")
        return atomic.openRead().use { input -> input.readDozeJournalBounded(maxBytes) }
    }

    override fun startWrite(): FileOutputStream = atomic.startWrite()
    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun finishWrite(output: FileOutputStream) = atomic.finishWrite(output)
    override fun failWrite(output: FileOutputStream?) {
        if (output != null) atomic.failWrite(output)
    }
}

private fun FileInputStream.readDozeJournalBounded(maxBytes: Int): ByteArray {
    val length = channel.size()
    if (length <= 0 || length > maxBytes) throw IOException("Doze journal has invalid size")
    val bytes = ByteArray(length.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val count = read(bytes, offset, bytes.size - offset)
        if (count < 0) throw IOException("Doze journal ended before declared size")
        offset += count
    }
    if (read() != -1) throw IOException("Doze journal grew while being read")
    return bytes
}
