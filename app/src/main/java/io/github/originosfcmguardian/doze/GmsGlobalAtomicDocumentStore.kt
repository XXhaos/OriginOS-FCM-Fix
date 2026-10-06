package io.github.originosfcmguardian.doze

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeCodec
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeDocumentPort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap

/**
 * App-private, independent global GMS document. This short OS storage lock is NOT operation,
 * revocation or dispatch admission. No command, service, UI or schema2 journal is connected here.
 * AtomicFile artifacts and interrupted initialization/commit markers block ordinary reads/writes;
 * they are preserved rather than recovered into authority. No deletion/reset API is provided.
 * A failed commit also permanently closes all same-path instances in this process. That memory
 * fence is not a cross-restart guarantee if durable marker recovery itself fails.
 */
internal class GmsGlobalAtomicDocumentStore private constructor(
    private val access: GmsGlobalDocumentFileAccess,
    directoryPath: String,
) : GmsGlobalDozeDocumentPort {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    private constructor(directory: File) : this(
        AndroidGmsGlobalDocumentFileAccess(File(directory, FILE_NAME)), directory.canonicalPath,
    )

    internal constructor(access: GmsGlobalDocumentFileAccess, expectedDirectory: File) :
        this(access, expectedDirectory.canonicalPath)

    private val canonicalDirectory = File(directoryPath).canonicalFile
    private val base = File(canonicalDirectory, FILE_NAME)
    private val storageLock = File(canonicalDirectory, STORAGE_LOCK_NAME)
    private val initialized = File(canonicalDirectory, INITIALIZED_NAME)
    private val pendingCommit = File(canonicalDirectory, COMMIT_PENDING_NAME)
    private val localState = localStates.computeIfAbsent(base.path) { LocalStorageState() }

    init {
        require(canonicalDirectory.isDirectory) { "Global GMS storage directory missing" }
        require(access.canonicalPath == base.path && base.canonicalPath == base.path) {
            "Global GMS document must use its fixed private path"
        }
    }

    override fun load(): ByteArray? = locked { loadLocked()?.clone() }

    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
        // Snapshot caller arrays before waiting for storage admission; no hidden normalization.
        val expectedBytes = expected?.clone()
        val replacementBytes = replacement.clone()
        require(replacementBytes.size <= GmsGlobalDozeCodec.MAX_BYTES) { "Global GMS document oversized" }
        return locked {
            val current = loadLocked()
            if (!sameBytes(current, expectedBytes)) return@locked false
            GmsGlobalDozeCodec.validateTransition(current, replacementBytes)

            var output: FileOutputStream? = null
            var finished = false
            try {
                if (!initialized.exists()) {
                    writeMarker(initialized)
                    access.syncDirectory()
                }
                writeMarker(pendingCommit)
                access.syncDirectory()
                output = access.startWrite()
                output.write(replacementBytes)
                output.flush()
                access.sync(output)
                access.finishWrite(output)
                finished = true
                access.syncDirectory()
                requireExactReadback(replacementBytes)
                if (!pendingCommit.delete()) throw IOException("Global GMS commit marker removal failed")
                access.syncDirectory()
                val finalReadback = loadLocked()
                if (!sameBytes(finalReadback, replacementBytes)) throw IOException("Global GMS final read-back differs")
                true
            } catch (failure: Throwable) {
                localState.terminalFailure = true
                if (!finished) runCatching { access.failWrite(output) }
                // A failed CAS never makes an old enabled document or uncertain new consent live.
                // Preserve/create blocking evidence even when AtomicFile.failWrite removes .new.
                if (!pendingCommit.exists()) runCatching {
                    access.recoverCommitPendingMarker()
                    access.syncDirectory()
                }
                throw failure
            }
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(localState) {
        if (localState.terminalFailure) throw IOException("Global GMS storage permanently closed after failed commit")
        requireFixedArtifacts()
        RandomAccessFile(storageLock, "rw").use { handle ->
            val fileLock = try {
                handle.channel.tryLock() ?: throw IOException("Global GMS storage owner busy")
            } catch (overlap: OverlappingFileLockException) {
                throw IOException("Global GMS storage owner busy", overlap)
            }
            fileLock.use { action() }
        }
    }

    private fun loadLocked(): ByteArray? {
        requireFixedArtifacts()
        if (pendingCommit.exists()) throw IOException("Interrupted global GMS commit requires explicit review")
        val hasDocument = access.hasAnyArtifact()
        if (!initialized.exists()) {
            if (hasDocument) throw IOException("Global GMS document exists without initialization marker")
            return null
        }
        validateMarker(initialized)
        if (!hasDocument) throw IOException("Global GMS initialized document is missing")
        return readDocument()
    }

    private fun requireExactReadback(expected: ByteArray) {
        validateMarker(initialized)
        validateMarker(pendingCommit)
        if (!sameBytes(readDocument(), expected)) throw IOException("Global GMS exact commit read-back differs")
    }

    private fun readDocument(): ByteArray {
        val bytes = access.readStrictlyBounded(GmsGlobalDozeCodec.MAX_BYTES).clone()
        GmsGlobalDozeCodec.validate(bytes)
        return bytes
    }

    private fun requireFixedArtifacts() {
        for (file in listOf(base, File(base.path + ".bak"), File(base.path + ".new"),
            storageLock, initialized, pendingCommit) + CYCLE_MARKER_NAMES.map { File(canonicalDirectory, it) }) {
            if (file.canonicalPath != file.path) throw IOException("Global GMS artifact path changed")
        }
        // Also reached from loadLocked while holding the same OS lock as the newer stores.
        // A durable PENDING marker may exist before any new ledger bytes become visible.
        if (CYCLE_MARKER_NAMES.any { File(canonicalDirectory, it).exists() })
            throw IOException("Versioned cycle authority requires its own store")
    }

    private fun writeMarker(marker: File) {
        createGlobalGmsMarker(marker)
    }

    private fun validateMarker(marker: File) {
        if (!marker.isFile || marker.length() != 1L || marker.inputStream().use { it.read() } != 1)
            throw IOException("Invalid global GMS initialization/commit marker")
    }

    companion object {
        const val FILE_NAME = "gms-global-doze-v1.bin"
        const val STORAGE_LOCK_NAME = "gms-global-broker-v2.storage.lock"
        const val INITIALIZED_NAME = "gms-global-doze-v1.initialized"
        const val COMMIT_PENDING_NAME = "gms-global-doze-v1.commit-pending"
        private val CYCLE_MARKER_NAMES = listOf("gms-global-doze-cycle.commit-fence",
            "gms-global-doze-cycle.commit-fence.new", "gms-global-doze-cycle.commit-fence.bak")
        private val localStates = ConcurrentHashMap<String, LocalStorageState>()
        private fun sameBytes(left: ByteArray?, right: ByteArray?): Boolean = when {
            left == null || right == null -> left == null && right == null
            else -> left.contentEquals(right)
        }
    }

    private class LocalStorageState { var terminalFailure = false }
}

/** Fixed document file access only; operation/dispatch locks are not part of this interface. */
internal interface GmsGlobalDocumentFileAccess {
    val canonicalPath: String
    fun hasAnyArtifact(): Boolean
    fun readStrictlyBounded(maxBytes: Int): ByteArray
    fun startWrite(): FileOutputStream
    fun sync(output: FileOutputStream)
    fun finishWrite(output: FileOutputStream)
    fun failWrite(output: FileOutputStream?)
    fun syncDirectory()
    /** Best-effort recovery for this fixed marker only; failure must never reopen a local store. */
    fun recoverCommitPendingMarker()
}

private class AndroidGmsGlobalDocumentFileAccess(private val base: File) : GmsGlobalDocumentFileAccess {
    private val atomic = AtomicFile(base)
    private val backup: File get() = File(base.path + ".bak")
    private val pending: File get() = File(base.path + ".new")
    override val canonicalPath: String = base.canonicalPath
    override fun hasAnyArtifact(): Boolean = base.exists() || backup.exists() || pending.exists()
    override fun readStrictlyBounded(maxBytes: Int): ByteArray {
        if (backup.exists() || pending.exists()) throw IOException("Global GMS AtomicFile artifact requires explicit review")
        if (!base.isFile) throw IOException("Global GMS committed document missing")
        // Avoid AtomicFile.openRead(): that method can restore/delete retained artifacts.
        return FileInputStream(base).use { it.readGlobalGmsDocumentBounded(maxBytes) }
    }
    override fun startWrite(): FileOutputStream = atomic.startWrite()
    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun finishWrite(output: FileOutputStream) = atomic.finishWrite(output)
    override fun failWrite(output: FileOutputStream?) { if (output != null) atomic.failWrite(output) }
    override fun recoverCommitPendingMarker() {
        createGlobalGmsMarker(File(base.parentFile, GmsGlobalAtomicDocumentStore.COMMIT_PENDING_NAME))
    }
    override fun syncDirectory() {
        val directory = requireNotNull(base.parentFile)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}

private fun createGlobalGmsMarker(marker: File) {
    if (!marker.createNewFile()) throw IOException("Global GMS marker already exists")
    FileOutputStream(marker).use { output -> output.write(1); output.fd.sync() }
}

internal fun FileInputStream.readGlobalGmsDocumentBounded(maxBytes: Int): ByteArray {
    val length = channel.size()
    if (length <= 0 || length > maxBytes) throw IOException("Global GMS document size invalid")
    val bytes = ByteArray(length.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val count = read(bytes, offset, bytes.size - offset)
        if (count <= 0) throw IOException("Global GMS document ended before declared size")
        offset += count
    }
    if (read() != -1) throw IOException("Global GMS document grew while being read")
    return bytes
}
