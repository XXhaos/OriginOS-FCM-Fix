package io.github.originosfcmguardian.doze

import android.content.Context
import android.system.Os
import android.system.OsConstants
import io.github.originosfcmguardian.core.doze.DozePhase
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Codec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2DurablePort
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Proof
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2State
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeCodec
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeDocumentPort
import io.github.originosfcmguardian.core.doze.GmsManualLockedDocuments
import io.github.originosfcmguardian.core.doze.GmsManualSessionControl
import io.github.originosfcmguardian.core.doze.GmsManualSessionPort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Finite production session, rooted only at the application's canonical noBackup directory.
 * One shared permanent fence precedes ANY global or broker mutation. Both documents are separately
 * atomic, never a two-document atomic transaction. A restart, old authority, uncertain write or
 * missing established document requires explicit recovery; nothing is deleted or migrated here.
 * withStorage is for short bounded storage work only: no RPC, probe or child wait may run inside it.
 */
internal class GmsManualSessionDocumentStore private constructor(
    private val access: GmsManualSessionFileAccess,
    directoryPath: String,
    registry: GmsManualSessionProcessRegistry,
) : GmsManualSessionPort {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    private constructor(directory: File) : this(
        AndroidGmsManualSessionFileAccess(directory.canonicalFile), directory.canonicalPath, processRegistry,
    )

    /** Real host files and an independent process identity; no production IPC path injection. */
    internal constructor(access: GmsManualSessionFileAccess, directory: File,
        registry: GmsManualSessionProcessRegistry) : this(access, directory.canonicalPath, registry)

    private val directory = File(directoryPath).canonicalFile
    private val global = File(directory, GLOBAL_FILE_NAME)
    private val broker = File(directory, BROKER_FILE_NAME)
    private val fence = File(directory, SESSION_FENCE_NAME)
    private val storageLock = File(directory, STORAGE_LOCK_NAME)
    private val session = registry.sessions.computeIfAbsent(directory.path) { GmsManualStorageSession() }
    override val control: GmsManualSessionControl get() = session.control

    init {
        require(directory.isDirectory && access.canonicalDirectory == directory.path) {
            "Manual session must use its fixed private directory"
        }
    }

    override fun <T> withStorage(action: (GmsManualLockedDocuments) -> T): T = synchronized(session) {
        requireOpen()
        try {
            requireFixedPaths()
            RandomAccessFile(storageLock, "rw").use { handle ->
                val lock = try {
                    handle.channel.tryLock() ?: throw IOException("Manual session storage busy")
                } catch (failure: OverlappingFileLockException) {
                    throw IOException("Manual session storage busy", failure)
                }
                lock.use {
                    validateStorage()
                    val documents = LockedDocuments()
                    try { action(documents) } finally { documents.expire() }
                }
            }
        } catch (failure: Throwable) {
            closeSession()
            throw failure
        }
    }

    override fun fenceUncertainSession() {
        // Close ADD immediately, even if another callback currently holds the short storage lock.
        control.closeAdds()
        synchronized(session) { closeSession() }
    }

    private inner class LockedDocuments : GmsManualLockedDocuments {
        private var active = true
        private val owner = Thread.currentThread()
        fun expire() { active = false }

        private fun <T> call(action: () -> T): T {
            check(active && Thread.currentThread() === owner) { "Manual storage callback expired or wrong thread" }
            requireOpen()
            try {
                validateStorage()
                return action()
            } catch (failure: Throwable) {
                closeSession()
                throw failure
            }
        }

        override val global = object : GmsGlobalDozeDocumentPort {
            override fun load(): ByteArray? = call { readGlobal()?.clone() }

            override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
                val expectedCopy = expected?.clone()
                val next = replacement.clone()
                return call {
                    val before = readGlobal()
                    if (!sameBytes(before, expectedCopy)) return@call false
                    GmsGlobalDozeCodec.validateTransition(before, next)
                    val old = before?.let(GmsGlobalDozeCodec::decode)
                    val after = GmsGlobalDozeCodec.decode(next)
                    val newConsent = after.consent.enabled &&
                        (old == null || old.consent.epoch != after.consent.epoch)
                    val newAddIntent = old?.transaction == null && after.transaction?.phase == DozePhase.ADD_INTENT
                    if (!control.addsLocallyAllowed() && (newConsent || newAddIntent)) return@call false
                    writeDocument(GmsManualDocument.GLOBAL, next)
                    true
                }
            }
        }

        override val broker = object : GmsDozeBrokerV2DurablePort {
            override fun readExact(): GmsDozeBrokerV2Proof = call { GmsDozeBrokerV2Codec.decodeProof(readBroker()) }

            override fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerV2State): ByteArray? {
                val expected = expectedBytes.clone()
                return call {
                    val before = readBroker()
                    if (!before.contentEquals(expected)) return@call null
                    val next = GmsDozeBrokerV2Codec.encode(replacement)
                    GmsDozeBrokerV2Codec.validateTransition(before, next)
                    val old = GmsDozeBrokerV2Codec.decode(before)
                    val oldAdd = old.add
                    val newAdd = replacement.add
                    val advancesAdd = newAdd != null && (oldAdd == null ||
                        (oldAdd.pin == null && newAdd.pin != null) ||
                        (oldAdd.preparation == null && newAdd.preparation != null))
                    if (!control.addsLocallyAllowed() && advancesAdd) return@call null
                    writeDocument(GmsManualDocument.BROKER, next)
                    next.clone()
                }
            }

            override fun fenceUncertainCommit() = call { closeSession() }
        }
    }

    private fun requireOpen() {
        if (session.terminal) throw IOException("Manual storage session permanently closed")
    }

    private fun closeSession() {
        session.terminal = true
        session.ownsFence = false
        control.closeAdds()
    }

    private fun validateStorage() {
        requireOpen()
        requireFixedPaths()
        for (name in RETIRED_AUTHORITY_NAMES) {
            if (File(directory, name).exists()) throw IOException("Retained old authority requires explicit recovery: $name")
        }
        for (base in listOf(global, broker)) {
            if (File(base.path + ".new").exists() || File(base.path + ".bak").exists())
                throw IOException("Interrupted manual document retained")
        }
        if (fence.exists()) {
            if (!session.ownsFence) throw IOException("Manual session fence requires explicit recovery")
            access.validateFence(requireNotNull(session.fenceBytes).clone())
        } else if (session.ownsFence || session.globalEstablished || session.brokerEstablished) {
            throw IOException("Manual session fence missing")
        }
        if (global.exists() != session.globalEstablished || broker.exists() != session.brokerEstablished)
            throw IOException("Unexpected or missing established manual document")
        if (session.globalEstablished) readGlobal()
        if (session.brokerEstablished) readBroker()
    }

    private fun readGlobal(): ByteArray? {
        if (!session.globalEstablished) return null
        return access.readStrictlyBounded(GmsManualDocument.GLOBAL, GmsGlobalDozeCodec.MAX_BYTES).clone().also {
            GmsGlobalDozeCodec.validate(it)
        }
    }

    private fun readBroker(): ByteArray {
        if (!session.brokerEstablished) return GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())
        return access.readStrictlyBounded(GmsManualDocument.BROKER, GmsDozeBrokerV2Codec.MAX_BYTES).clone().also {
            GmsDozeBrokerV2Codec.validate(it)
        }
    }

    private fun writeDocument(document: GmsManualDocument, bytes: ByteArray) {
        if (!session.ownsFence) {
            val token = ByteArray(FENCE_BYTES).also { SecureRandom().nextBytes(it) }
            access.createAndSyncFence(token.clone())
            access.syncDirectory()
            access.validateFence(token.clone())
            session.fenceBytes = token
            session.ownsFence = true
        }
        // Recheck both documents and old materials after the pre-authority barrier.
        validateStorage()
        when (document) {
            GmsManualDocument.GLOBAL -> session.globalEstablished = true
            GmsManualDocument.BROKER -> session.brokerEstablished = true
        }
        var output: FileOutputStream? = null
        try {
            output = access.startWrite(document)
            output.write(bytes)
            output.flush()
            access.sync(output)
            access.finishWrite(document, output)
            output = null
            access.syncDirectory()
            validateStorage()
            val actual = when (document) {
                GmsManualDocument.GLOBAL -> requireNotNull(readGlobal())
                GmsManualDocument.BROKER -> readBroker()
            }
            if (!actual.contentEquals(bytes)) throw IOException("Manual document exact commit readback differs")
        } finally {
            // Retain every .new/.bak; no AtomicFile recovery or cleanup can erase failure evidence.
            output?.close()
        }
    }

    private fun requireFixedPaths() {
        if (directory.canonicalFile != directory || !directory.isDirectory)
            throw IOException("Manual session directory changed")
        val files = listOf(global, broker, fence, storageLock) +
            listOf(global, broker).flatMap { listOf(File(it.path + ".new"), File(it.path + ".bak")) } +
            RETIRED_AUTHORITY_NAMES.map { File(directory, it) } +
            listOf(File(directory, GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME),
                File(directory, GmsDozeBrokerRecordStore.STORAGE_LOCK_NAME))
        if (files.any { it.canonicalPath != it.path }) throw IOException("Manual session artifact path changed")
    }

    companion object {
        const val GLOBAL_FILE_NAME = "gms-global-doze-v1.bin"
        const val BROKER_FILE_NAME = "gms-doze-broker-v2.bin"
        const val SESSION_FENCE_NAME = "gms-global-broker-v2.session-fence"
        const val STORAGE_LOCK_NAME = "gms-global-broker-v2.storage.lock"
        const val FENCE_BYTES = 32
        internal val RETIRED_AUTHORITY_NAMES = listOf(
            "gms-global-doze-cycle.commit-fence", "gms-global-doze-cycle.commit-fence.new",
            "gms-global-doze-cycle.commit-fence.bak",
            "gms-global-doze-v1.initialized", "gms-global-doze-v1.commit-pending",
            "gms-doze-broker-v1.bin", "gms-doze-broker-v1.bin.new", "gms-doze-broker-v1.bin.bak",
            "gms-doze-broker-v1.session-fence",
        )
        private val processRegistry = GmsManualSessionProcessRegistry()
        private fun sameBytes(left: ByteArray?, right: ByteArray?) = when {
            left == null || right == null -> left == null && right == null
            else -> left.contentEquals(right)
        }
    }
}

internal class GmsManualSessionProcessRegistry {
    internal val sessions = ConcurrentHashMap<String, GmsManualStorageSession>()
}

internal class GmsManualStorageSession {
    val control = GmsManualSessionControl()
    var terminal = false
    var ownsFence = false
    var fenceBytes: ByteArray? = null
    var globalEstablished = false
    var brokerEstablished = false
}

internal enum class GmsManualDocument { GLOBAL, BROKER }

/** Fixed two-document access only; host tests inject real files and individual durability faults. */
internal interface GmsManualSessionFileAccess {
    val canonicalDirectory: String
    fun createAndSyncFence(expected: ByteArray)
    fun validateFence(expected: ByteArray)
    fun startWrite(document: GmsManualDocument): FileOutputStream
    fun sync(output: FileOutputStream)
    fun finishWrite(document: GmsManualDocument, output: FileOutputStream)
    fun syncDirectory()
    fun readStrictlyBounded(document: GmsManualDocument, maxBytes: Int): ByteArray
}

private class AndroidGmsManualSessionFileAccess(private val directory: File) : GmsManualSessionFileAccess {
    override val canonicalDirectory = directory.canonicalPath
    private val fence = File(directory, GmsManualSessionDocumentStore.SESSION_FENCE_NAME)
    private fun base(document: GmsManualDocument) = File(directory, when (document) {
        GmsManualDocument.GLOBAL -> GmsManualSessionDocumentStore.GLOBAL_FILE_NAME
        GmsManualDocument.BROKER -> GmsManualSessionDocumentStore.BROKER_FILE_NAME
    })

    override fun createAndSyncFence(expected: ByteArray) {
        if (expected.size != GmsManualSessionDocumentStore.FENCE_BYTES || !fence.createNewFile())
            throw IOException("Manual session fence already exists or invalid")
        FileOutputStream(fence).use { it.write(expected); it.fd.sync() }
    }

    override fun validateFence(expected: ByteArray) {
        if (!fence.isFile || expected.size != GmsManualSessionDocumentStore.FENCE_BYTES ||
            !FileInputStream(fence).use { it.readGlobalGmsDocumentBounded(expected.size) }.contentEquals(expected))
            throw IOException("Manual session fence differs")
    }

    override fun startWrite(document: GmsManualDocument): FileOutputStream {
        val pending = File(base(document).path + ".new")
        if (!pending.createNewFile()) throw IOException("Manual document pending artifact already exists")
        return FileOutputStream(pending)
    }
    override fun sync(output: FileOutputStream) = output.fd.sync()
    override fun finishWrite(document: GmsManualDocument, output: FileOutputStream) {
        output.close()
        val base = base(document)
        Os.rename(base.path + ".new", base.path)
    }
    override fun syncDirectory() {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    override fun readStrictlyBounded(document: GmsManualDocument, maxBytes: Int): ByteArray {
        val base = base(document)
        if (!base.isFile || File(base.path + ".new").exists() || File(base.path + ".bak").exists())
            throw IOException("Manual committed document unavailable")
        return FileInputStream(base).use { it.readGlobalGmsDocumentBounded(maxBytes) }
    }
}
