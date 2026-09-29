package io.github.originosfcmguardian.notification

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class NotificationFactsStore private constructor(
    private val access: NotificationFactsFileAccess,
) {
    constructor(context: Context) : this(
        AndroidNotificationFactsFileAccess(
            File(context.applicationContext.noBackupFilesDir, FILE_NAME),
        ),
    )

    internal constructor(baseFile: File, access: NotificationFactsFileAccess) : this(access) {
        require(baseFile.canonicalPath == access.canonicalPath)
    }

    private val lock = lockFor(access.canonicalPath)

    fun load(): NotificationFactsSnapshot = synchronized(lock) { loadLocked() }

    fun record(event: NotificationFact): RecordNotificationFactResult = synchronized(lock) {
        val existing = when (val snapshot = loadLocked()) {
            NotificationFactsSnapshot.Empty -> emptyList()
            is NotificationFactsSnapshot.Available -> snapshot.events
            NotificationFactsSnapshot.Corrupt -> return@synchronized RecordNotificationFactResult.CORRUPT
        }
        val updated = (listOf(event) + existing).take(MAX_EVENTS)
        if (writeLocked(updated)) {
            RecordNotificationFactResult.STORED
        } else {
            RecordNotificationFactResult.FAILED
        }
    }

    /** Explicit user action. This is the only path that removes corrupt artifacts. */
    fun clear(): Boolean = synchronized(lock) {
        runCatching {
            access.clearArtifacts()
            !access.hasAnyArtifact()
        }.getOrDefault(false)
    }

    private fun loadLocked(): NotificationFactsSnapshot {
        if (!access.hasAnyArtifact()) return NotificationFactsSnapshot.Empty
        val bytes = runCatching { access.readBounded(MAX_FILE_BYTES) }.getOrNull()
            ?: return NotificationFactsSnapshot.Corrupt
        val events = NotificationFactsCodec.decode(bytes) ?: return NotificationFactsSnapshot.Corrupt
        return NotificationFactsSnapshot.Available(events)
    }

    private fun writeLocked(events: List<NotificationFact>): Boolean {
        val encoded = runCatching { NotificationFactsCodec.encode(events) }.getOrNull() ?: return false
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

    internal companion object {
        const val MAX_EVENTS = 200
        const val MAX_FILE_BYTES = 64 * 1024
        const val FILE_NAME = "notification-facts-v1.txt"

        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(path: String): Any = locks.computeIfAbsent(path) { Any() }
    }
}

internal interface NotificationFactsFileAccess {
    val canonicalPath: String
    fun hasAnyArtifact(): Boolean
    fun startWrite(): FileOutputStream
    fun finishWrite(output: FileOutputStream)
    fun failWrite(output: FileOutputStream?)
    fun readBounded(maxBytes: Int): ByteArray
    fun clearArtifacts()
}

private class AndroidNotificationFactsFileAccess(
    private val baseFile: File,
) : NotificationFactsFileAccess {
    private val atomicFile = AtomicFile(baseFile)
    private val backupFile get() = File(baseFile.path + ".bak")
    private val newFile get() = File(baseFile.path + ".new")

    override val canonicalPath: String = baseFile.canonicalPath

    override fun hasAnyArtifact(): Boolean = baseFile.exists() || backupFile.exists() || newFile.exists()
    override fun startWrite(): FileOutputStream = atomicFile.startWrite()
    override fun finishWrite(output: FileOutputStream) = atomicFile.finishWrite(output)
    override fun failWrite(output: FileOutputStream?) {
        if (output != null) atomicFile.failWrite(output)
    }

    override fun readBounded(maxBytes: Int): ByteArray = when {
        newFile.exists() -> throw IOException("Interrupted notification facts write")
        backupFile.exists() -> throw IOException("Notification facts backup requires review")
        !baseFile.exists() -> throw IOException("Notification facts base is missing")
        else -> atomicFile.openRead().use { it.readStrictlyBounded(maxBytes) }
    }

    override fun clearArtifacts() {
        atomicFile.delete()
        if (newFile.exists() && !newFile.delete()) throw IOException("Cannot clear pending history")
    }
}

private fun FileInputStream.readStrictlyBounded(maxBytes: Int): ByteArray {
    val size = channel.size()
    if (size < 0 || size > maxBytes) throw IOException("Notification facts exceed size limit")
    val bytes = ByteArray(size.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) throw IOException("Notification facts ended early")
        offset += read
    }
    if (read() != -1) throw IOException("Notification facts grew while reading")
    return bytes
}
