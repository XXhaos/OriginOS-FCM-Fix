package io.github.originosfcmguardian.fcm

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class FcmEvidenceStore private constructor(
    private val access: EvidenceFileAccess,
) {
    constructor(context: Context) : this(
        AndroidEvidenceFileAccess(File(context.applicationContext.noBackupFilesDir, FILE_NAME)),
    )

    internal constructor(baseFile: File, access: EvidenceFileAccess) : this(access) {
        require(baseFile.canonicalPath == access.canonicalPath)
    }

    private val lock = lockFor(access.canonicalPath)

    fun load(): FcmEvidenceSnapshot = synchronized(lock) {
        loadLocked()
    }

    fun recordFirst(receipt: FcmReceipt): RecordReceiptResult = synchronized(lock) {
        val existing = when (val snapshot = loadLocked()) {
            FcmEvidenceSnapshot.Empty -> emptyList()
            is FcmEvidenceSnapshot.Available -> snapshot.receipts
            FcmEvidenceSnapshot.Corrupt -> return@synchronized RecordReceiptResult.Corrupt
        }
        if (existing.any { it.testId == receipt.testId }) return@synchronized RecordReceiptResult.Duplicate
        val updated = (listOf(receipt) + existing).take(MAX_RECEIPTS)
        if (!writeLocked(updated)) return@synchronized RecordReceiptResult.Failed
        notifyListeners()
        RecordReceiptResult.Stored(receipt)
    }

    fun updateNotification(
        testId: String,
        outcome: NotificationOutcome,
        handlerDurationMillis: Long,
    ): Boolean = synchronized(lock) {
        val existing = (loadLocked() as? FcmEvidenceSnapshot.Available)?.receipts
            ?: return@synchronized false
        val index = existing.indexOfFirst { it.testId == testId }
        if (index < 0) return@synchronized false
        val updated = existing.toMutableList().apply {
            this[index] = this[index].copy(
                notificationOutcome = outcome,
                handlerDurationMillis = handlerDurationMillis.coerceAtLeast(0),
            )
        }
        if (!writeLocked(updated)) return@synchronized false
        notifyListeners()
        true
    }

    fun registerListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun loadLocked(): FcmEvidenceSnapshot {
        if (!access.hasAnyArtifact()) return FcmEvidenceSnapshot.Empty
        val bytes = runCatching { access.readBounded(MAX_FILE_BYTES) }.getOrNull()
            ?: return FcmEvidenceSnapshot.Corrupt
        val receipts = FcmEvidenceCodec.decode(bytes) ?: return FcmEvidenceSnapshot.Corrupt
        return FcmEvidenceSnapshot.Available(receipts)
    }

    private fun writeLocked(receipts: List<FcmReceipt>): Boolean {
        val encoded = runCatching { FcmEvidenceCodec.encode(receipts) }.getOrNull() ?: return false
        var output: FileOutputStream? = null
        var finished = false
        return try {
            output = access.startWrite()
            output.write(encoded)
            output.flush()
            output.fd.sync()
            access.finishWrite(output)
            finished = true
            access.readBounded(MAX_FILE_BYTES).contentEquals(encoded)
        } catch (_: Throwable) {
            false
        } finally {
            if (!finished) runCatching { access.failWrite(output) }
        }
    }

    private fun notifyListeners() = listeners.forEach { listener -> runCatching(listener) }

    internal companion object {
        const val MAX_RECEIPTS = 50
        const val MAX_FILE_BYTES = 64 * 1024
        const val FILE_NAME = "fcm-self-test-evidence-v1.txt"

        private val locks = ConcurrentHashMap<String, Any>()
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private fun lockFor(path: String): Any = locks.computeIfAbsent(path) { Any() }
    }
}

internal interface EvidenceFileAccess {
    val canonicalPath: String
    fun hasAnyArtifact(): Boolean
    fun startWrite(): FileOutputStream
    fun finishWrite(output: FileOutputStream)
    fun failWrite(output: FileOutputStream?)
    fun readBounded(maxBytes: Int): ByteArray
}

private class AndroidEvidenceFileAccess(private val baseFile: File) : EvidenceFileAccess {
    private val atomicFile = AtomicFile(baseFile)
    override val canonicalPath: String = baseFile.canonicalPath
    private val backupFile get() = File(baseFile.path + ".bak")
    private val newFile get() = File(baseFile.path + ".new")

    override fun hasAnyArtifact(): Boolean = baseFile.exists() || backupFile.exists() || newFile.exists()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun finishWrite(output: FileOutputStream) = atomicFile.finishWrite(output)
    override fun failWrite(output: FileOutputStream?) {
        if (output != null) atomicFile.failWrite(output)
    }

    override fun readBounded(maxBytes: Int): ByteArray = when {
        newFile.exists() -> throw IOException("Interrupted FCM evidence write")
        backupFile.exists() -> throw IOException("FCM evidence backup requires review")
        !baseFile.exists() -> throw IOException("FCM evidence base is missing")
        else -> atomicFile.openRead().use { it.readStrictlyBounded(maxBytes) }
    }
}

private fun FileInputStream.readStrictlyBounded(maxBytes: Int): ByteArray {
    val size = channel.size()
    if (size < 0 || size > maxBytes) throw IOException("FCM evidence exceeds size limit")
    val bytes = ByteArray(size.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) throw IOException("FCM evidence ended early")
        offset += read
    }
    if (read() != -1) throw IOException("FCM evidence grew while reading")
    return bytes
}
