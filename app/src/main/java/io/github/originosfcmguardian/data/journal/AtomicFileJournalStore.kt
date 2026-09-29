package io.github.originosfcmguardian.data.journal

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.core.journal.JournalStore
import io.github.originosfcmguardian.core.journal.JournalCodec
import io.github.originosfcmguardian.core.journal.StoreWriteResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * App-private, no-backup storage for the first read-only baseline.
 *
 * Any AtomicFile artifact counts as an existing record. A damaged base, backup, or
 * interrupted `.new` file must be inspected and surfaced as a failure; it must never
 * be mistaken for an empty slot and overwritten by a later detection.
 * The canonical-path lock coordinates app-process instances. The privileged service
 * never accesses this store, so no cross-process writer is supported or required.
 */
class AtomicFileJournalStore private constructor(
    private val access: JournalFileAccess,
) : JournalStore {
    constructor(context: Context) : this(
        AndroidAtomicFileAccess(
            File(context.applicationContext.noBackupFilesDir, BASELINE_FILE_NAME),
        ),
    )

    internal constructor(baseFile: File) : this(AndroidAtomicFileAccess(baseFile))

    internal constructor(baseFile: File, access: JournalFileAccess) : this(access) {
        require(baseFile.canonicalPath == access.canonicalPath)
    }

    private val lock: Any = lockFor(access.canonicalPath)

    override fun read(): ByteArray? = synchronized(lock) {
        if (!access.hasAnyArtifact()) return@synchronized null
        access.readBounded(MAX_FILE_BYTES)
    }

    override fun writeIfAbsent(encoded: ByteArray): StoreWriteResult = synchronized(lock) {
        require(encoded.size <= MAX_FILE_BYTES) { "Encoded baseline exceeds storage limit" }
        if (access.hasAnyArtifact()) return@synchronized StoreWriteResult.ALREADY_EXISTS

        var output: FileOutputStream? = null
        var finished = false
        try {
            output = access.startWrite()
            output.write(encoded)
            output.flush()
            output.fd.sync()
            access.finishWrite(output)
            finished = true
        } catch (failure: Throwable) {
            if (!finished) runCatching { access.failWrite(output) }
            throw failure
        }

        val persisted = access.readBounded(MAX_FILE_BYTES)
        if (!persisted.contentEquals(encoded)) {
            throw IOException("Atomic baseline read-back did not match the bytes written")
        }
        StoreWriteResult.STORED
    }

    internal companion object {
        const val BASELINE_FILE_NAME = "first-read-only-baseline.bin"
        const val MAX_FILE_BYTES = JournalCodec.MAX_ENCODED_BYTES

        private val locks = ConcurrentHashMap<String, Any>()

        private fun lockFor(canonicalPath: String): Any =
            locks.computeIfAbsent(canonicalPath) { Any() }
    }
}

internal interface JournalFileAccess {
    val canonicalPath: String

    fun hasAnyArtifact(): Boolean

    fun startWrite(): FileOutputStream

    fun finishWrite(output: FileOutputStream)

    fun failWrite(output: FileOutputStream?)

    fun readBounded(maxBytes: Int): ByteArray
}

private class AndroidAtomicFileAccess(
    private val baseFile: File,
) : JournalFileAccess {
    private val atomicFile = AtomicFile(baseFile)

    override val canonicalPath: String = baseFile.canonicalPath

    override fun hasAnyArtifact(): Boolean =
        baseFile.exists() || backupFile.exists() || newFile.exists()

    override fun startWrite(): FileOutputStream = atomicFile.startWrite()

    override fun finishWrite(output: FileOutputStream) = atomicFile.finishWrite(output)

    override fun failWrite(output: FileOutputStream?) {
        if (output != null) atomicFile.failWrite(output)
    }

    override fun readBounded(maxBytes: Int): ByteArray =
        when {
            newFile.exists() -> throw IOException("Interrupted baseline write artifact is present")
            backupFile.exists() -> throw IOException("Baseline backup artifact requires explicit review")
            !baseFile.exists() -> throw IOException("Baseline artifact exists without a readable base file")
            else -> atomicFile.openRead().use { input -> input.readStrictlyBounded(maxBytes) }
        }

    private val backupFile: File
        get() = File(baseFile.path + ".bak")

    private val newFile: File
        get() = File(baseFile.path + ".new")
}

private fun FileInputStream.readStrictlyBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val declaredLength = channel.size()
    if (declaredLength < 0 || declaredLength > maxBytes) {
        throw IOException("Stored baseline exceeds size limit")
    }

    val output = ByteArray(declaredLength.toInt())
    var offset = 0
    while (offset < output.size) {
        val count = read(output, offset, output.size - offset)
        if (count < 0) throw IOException("Stored baseline ended before its declared length")
        offset += count
    }
    if (read() != -1) throw IOException("Stored baseline grew beyond size limit while reading")
    return output
}
