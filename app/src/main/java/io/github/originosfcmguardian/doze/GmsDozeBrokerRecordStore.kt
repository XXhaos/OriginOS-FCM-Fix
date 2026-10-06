package io.github.originosfcmguardian.doze

import android.content.Context
import android.system.Os
import android.system.OsConstants
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerDurablePort
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerProof
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerState
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Finite storage adapter only: no writer, consent source, RPC or automatic recovery is connected.
 * Before the first record mutation, a permanent session fence is fsynced and read back. Only its
 * creating App process earns an in-memory permit; same-path instances in that process share it.
 * EVERY other process/restart is recovery-required, including after a completely successful CAS.
 * This deliberately sacrifices restart availability rather than recreating authority from disk.
 * No fence removal/reset API exists. The short App-UID FileLock is storage serialization only.
 */
internal class GmsDozeBrokerRecordStore private constructor(
    private val access: GmsDozeBrokerRecordFileAccess,
    directoryPath: String,
    registry: GmsDozeBrokerProcessRegistry,
) : GmsDozeBrokerDurablePort {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    /** Internal debug fixture uses its own fixed noBackup subdirectory; never a caller IPC path. */
    internal constructor(directory: File) : this(
        AndroidGmsDozeBrokerRecordFileAccess(directory.canonicalFile), directory.canonicalPath, processRegistry,
    )

    /** A distinct registry models a different process in host tests; production never injects one. */
    internal constructor(access: GmsDozeBrokerRecordFileAccess, directory: File,
        registry: GmsDozeBrokerProcessRegistry) : this(access, directory.canonicalPath, registry)

    private val directory = File(directoryPath).canonicalFile
    private val base = File(this.directory, FILE_NAME)
    private val pending = File(base.path + ".new")
    private val backup = File(base.path + ".bak")
    private val fence = File(this.directory, SESSION_FENCE_NAME)
    private val storageLock = File(this.directory, STORAGE_LOCK_NAME)
    private val session = registry.sessions.computeIfAbsent(base.path) { GmsDozeBrokerStorageSession() }

    init {
        require(this.directory.isDirectory) { "Broker storage directory missing" }
        require(access.canonicalPath == base.path && base.canonicalPath == base.path) {
            "Broker record must use its fixed private path"
        }
    }

    override fun readExact(): GmsDozeBrokerProof = locked {
        GmsDozeBrokerCodec.decodeProof(readLocked())
    }

    override fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerState): ByteArray? {
        val expected = expectedBytes.clone()
        val bytes = GmsDozeBrokerCodec.encode(replacement)
        return locked {
            val before = readLocked()
            if (!before.contentEquals(expected)) return@locked null
            GmsDozeBrokerCodec.validateTransition(before, bytes)
            // No authority bytes are touched before this durable, permanent restart barrier.
            if (!session.ownsFence) {
                val token = ByteArray(FENCE_BYTES).also { SecureRandom().nextBytes(it) }
                access.createAndSyncFence(token.clone())
                access.syncDirectory()
                access.validateFence(token)
                session.fenceBytes = token
                session.ownsFence = true
            }
            var output: FileOutputStream? = null
            try {
                session.recordEstablished = true
                output = access.startWrite()
                output.write(bytes)
                output.flush()
                access.sync(output)
                access.finishWrite(output)
                output = null
                access.syncDirectory()
                val actual = readDocument()
                if (!actual.contentEquals(bytes)) throw IOException("Broker exact commit readback differs")
                actual.clone()
            } finally {
                // Retain .new on every interrupted write. Never recover/delete storage evidence.
                output?.close()
            }
        }
    }

    override fun fenceUncertainCommit() = synchronized(session) {
        // The fence was already durable before any authority mutation. No fallible repair write
        // is needed here. If fence establishment failed, no record mutation was attempted.
        session.terminal = true
        session.ownsFence = false
    }

    private fun <T> locked(action: () -> T): T = synchronized(session) {
        if (session.terminal) throw IOException("Broker storage session permanently closed")
        try {
            requireFixedPaths()
            RandomAccessFile(storageLock, "rw").use { handle ->
                val lock = try {
                    handle.channel.tryLock() ?: throw IOException("Broker storage busy")
                } catch (failure: OverlappingFileLockException) {
                    throw IOException("Broker storage busy", failure)
                }
                lock.use { action() }
            }
        } catch (failure: Throwable) {
            // A failed fence establishment can leave a marker but never authority. Subsequent
            // local reads also stay closed; a fresh process either sees that marker or clean OFF.
            session.terminal = true
            session.ownsFence = false
            throw failure
        }
    }

    private fun readLocked(): ByteArray {
        requireFixedPaths()
        if (fence.exists()) {
            if (!session.ownsFence) throw IOException("Broker session fence requires explicit recovery")
            access.validateFence(requireNotNull(session.fenceBytes).clone())
        } else {
            if (session.ownsFence || session.recordEstablished || base.exists() || pending.exists() || backup.exists())
                throw IOException("Broker record/session fence missing or orphaned")
        }
        if (pending.exists() || backup.exists()) throw IOException("Interrupted broker record retained")
        if (!base.exists()) {
            if (session.recordEstablished) throw IOException("Committed broker record missing")
            val initial = GmsDozeBrokerCodec.encode(GmsDozeBrokerCodec.initialState())
            GmsDozeBrokerCodec.validateTransition(null, initial)
            return initial
        }
        return readDocument()
    }

    private fun readDocument(): ByteArray {
        val bytes = access.readStrictlyBounded(GmsDozeBrokerCodec.MAX_BYTES).clone()
        GmsDozeBrokerCodec.validate(bytes)
        return bytes
    }

    private fun requireFixedPaths() {
        if (directory.canonicalFile != directory || !directory.isDirectory)
            throw IOException("Broker storage directory changed")
        for (file in listOf(base, pending, backup, fence, storageLock)) {
            if (file.canonicalPath != file.path) throw IOException("Broker artifact path changed")
        }
    }

    companion object {
        const val FILE_NAME = "gms-doze-broker-v1.bin"
        const val SESSION_FENCE_NAME = "gms-doze-broker-v1.session-fence"
        const val STORAGE_LOCK_NAME = "gms-doze-broker-v1.storage.lock"
        const val FENCE_BYTES = 32
        private val processRegistry = GmsDozeBrokerProcessRegistry()
    }
}

internal class GmsDozeBrokerProcessRegistry {
    internal val sessions = ConcurrentHashMap<String, GmsDozeBrokerStorageSession>()
}

internal class GmsDozeBrokerStorageSession {
    var ownsFence = false
    var recordEstablished = false
    var terminal = false
    var fenceBytes: ByteArray? = null
}

/** Fixed broker files only; injected so host tests can use real files and fault each barrier. */
internal interface GmsDozeBrokerRecordFileAccess {
    val canonicalPath: String
    fun createAndSyncFence(expected: ByteArray)
    fun validateFence(expected: ByteArray)
    fun startWrite(): FileOutputStream
    fun sync(output: FileOutputStream)
    fun finishWrite(output: FileOutputStream)
    fun syncDirectory()
    fun readStrictlyBounded(maxBytes: Int): ByteArray
}

private class AndroidGmsDozeBrokerRecordFileAccess(directory: File) : GmsDozeBrokerRecordFileAccess {
    private val base = File(directory, GmsDozeBrokerRecordStore.FILE_NAME)
    private val pending = File(base.path + ".new")
    private val fence = File(directory, GmsDozeBrokerRecordStore.SESSION_FENCE_NAME)
    override val canonicalPath = base.canonicalPath

    override fun createAndSyncFence(expected: ByteArray) {
        if (!fence.createNewFile()) throw IOException("Broker session fence already exists")
        FileOutputStream(fence).use { output -> output.write(expected); output.fd.sync() }
    }

    override fun validateFence(expected: ByteArray) {
        if (!fence.isFile || expected.size != GmsDozeBrokerRecordStore.FENCE_BYTES ||
            !FileInputStream(fence).use { it.readGlobalGmsDocumentBounded(expected.size) }.contentEquals(expected))
            throw IOException("Invalid broker session fence")
    }

    override fun startWrite(): FileOutputStream {
        if (!pending.createNewFile()) throw IOException("Broker pending record already exists")
        return FileOutputStream(pending)
    }

    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun finishWrite(output: FileOutputStream) {
        output.close()
        Os.rename(pending.absolutePath, base.absolutePath)
    }

    override fun syncDirectory() {
        val descriptor = Os.open(requireNotNull(base.parentFile).absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    override fun readStrictlyBounded(maxBytes: Int): ByteArray {
        if (pending.exists() || File(base.path + ".bak").exists() || !base.isFile)
            throw IOException("Broker committed record unavailable")
        return FileInputStream(base).use { it.readGlobalGmsDocumentBounded(maxBytes) }
    }
}
