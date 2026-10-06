package io.github.originosfcmguardian.doze

import android.content.Context
import android.system.Os
import android.system.OsConstants
import io.github.originosfcmguardian.core.doze.GmsDozeCycleControl
import io.github.originosfcmguardian.core.doze.GmsDozeCycleLedgerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeCycleLedgerPort
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Strict new cycle ledger at the existing global path, never a legacy migration or alternate root.
 * Each CAS durably publishes PENDING, ledger, then matching permanent CLEAN. Every atomic step
 * fsyncs its file and directory and reads back exact bytes. Interruption artifacts remain untouched.
 * A throwing call permanently closes its shared process owner, even if CLEAN became visible.
 * A new process may read only a complete valid CLEAN snapshot; the coordinator decides authority.
 * Failure before durable PENDING can leave the previous CLEAN across restart: no stronger OFF
 * guarantee is claimed, and no post-failure marker repair is attempted.
 */
internal class GmsDozeCycleLedgerStore private constructor(
    private val access: GmsDozeCycleLedgerFileAccess,
    directoryPath: String,
    registry: GmsDozeCycleLedgerProcessRegistry,
) : GmsDozeCycleLedgerPort {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)

    private constructor(directory: File) : this(
        AndroidGmsDozeCycleLedgerFileAccess(directory.canonicalFile), directory.canonicalPath, processRegistry,
    )

    /** Host real-file seam; production accepts only its Context's canonical noBackup directory. */
    internal constructor(access: GmsDozeCycleLedgerFileAccess, directory: File,
        registry: GmsDozeCycleLedgerProcessRegistry) : this(access, directory.canonicalPath, registry)

    private val directory = File(directoryPath).canonicalFile
    private val ledger = File(directory, FILE_NAME)
    private val marker = File(directory, MARKER_NAME)
    private val storageLock = File(directory, STORAGE_LOCK_NAME)
    private val owner = registry.owners.computeIfAbsent(directory.path) { GmsDozeCycleLedgerStorageOwner() }
    override val control: GmsDozeCycleControl get() = owner.control

    init {
        require(directory.isDirectory && access.canonicalDirectory == directory.path) {
            "Cycle ledger must use its fixed private directory"
        }
    }

    override fun load(): ByteArray? = locked { readSnapshot()?.clone() }

    override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
        val expectedBytes = expected?.clone()
        val candidate = replacement.clone()
        return locked {
            val before = readSnapshot()
            if (!sameBytes(before, expectedBytes)) return@locked false
            GmsDozeCycleLedgerCodec.validateTransition(before, candidate)
            val oldMarker = readOptional(GmsDozeCycleLedgerWrite.CLEAN, GmsDozeCycleLedgerMarkerCodec.MAX_BYTES)
            val previous = before?.let(::snapshot)
            val next = snapshot(candidate)
            val pending = GmsDozeCycleLedgerMarkerCodec.encode(GmsDozeCycleLedgerMarker(true, previous, next))
            // Stage 1 is the durable barrier. No ledger write begins until its exact readback.
            atomicWrite(GmsDozeCycleLedgerWrite.PENDING, oldMarker, pending)
            atomicWrite(GmsDozeCycleLedgerWrite.LEDGER, before, candidate)
            val clean = GmsDozeCycleLedgerMarkerCodec.encode(GmsDozeCycleLedgerMarker(false, null, next))
            atomicWrite(GmsDozeCycleLedgerWrite.CLEAN, pending, clean)
            if (!sameBytes(readSnapshot(), candidate)) throw IOException("Cycle ledger final readback differs")
            true
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(owner) {
        if (owner.terminal) throw IOException("Cycle ledger process owner permanently closed")
        try {
            requireFixedPaths()
            RandomAccessFile(storageLock, "rw").use { handle ->
                val lock = try {
                    handle.channel.tryLock() ?: throw IOException("Cycle ledger storage busy")
                } catch (failure: OverlappingFileLockException) {
                    throw IOException("Cycle ledger storage busy", failure)
                }
                lock.use { action() }
            }
        } catch (failure: Throwable) {
            owner.terminal = true
            // Storage may poison local admission, never the inverse: control methods cannot
            // acquire this storage monitor or perform RPC, storage, streams or child waits.
            control.failClosed()
            throw failure
        }
    }

    private fun readSnapshot(): ByteArray? {
        requireFixedPaths()
        rejectRetainedArtifacts()
        val bytes = readOptional(GmsDozeCycleLedgerWrite.LEDGER, GmsDozeCycleLedgerCodec.MAX_BYTES)
        val fence = readOptional(GmsDozeCycleLedgerWrite.CLEAN, GmsDozeCycleLedgerMarkerCodec.MAX_BYTES)
        if (bytes == null && fence == null) {
            if (owner.established) throw IOException("Established cycle ledger and marker are missing")
            return null
        }
        if (bytes == null || fence == null) throw IOException("Cycle ledger or permanent marker is missing")
        val decoded = GmsDozeCycleLedgerMarkerCodec.decode(fence)
        if (decoded.pending || decoded.candidate != snapshot(bytes))
            throw IOException("Cycle ledger PENDING or marker identity/revision/hash differs")
        owner.established = true
        return bytes
    }

    private fun snapshot(bytes: ByteArray): GmsDozeCycleLedgerSnapshot {
        val value = GmsDozeCycleLedgerCodec.decode(bytes)
        return GmsDozeCycleLedgerSnapshot(value.ledgerId, value.revision, GmsDozeCycleLedgerCodec.sha256(bytes))
    }

    private fun atomicWrite(stage: GmsDozeCycleLedgerWrite, previous: ByteArray?, bytes: ByteArray) {
        requireFixedPaths()
        rejectRetainedArtifacts()
        val cap = if (stage == GmsDozeCycleLedgerWrite.LEDGER) GmsDozeCycleLedgerCodec.MAX_BYTES
            else GmsDozeCycleLedgerMarkerCodec.MAX_BYTES
        if (!sameBytes(readOptional(stage, cap), previous)) throw IOException("Cycle ledger stage target changed")
        var output: FileOutputStream? = null
        try {
            output = access.startWrite(stage) // Exclusive fixed .new; never overwrite a retained temp.
            output.write(bytes)
            output.flush()
            access.sync(stage, output)
            requireFixedPaths()
            // Reconfirm exactly the base this protocol is authorized to replace.
            if (!sameBytes(readOptional(stage, cap), previous)) throw IOException("Cycle ledger base changed before rename")
            access.finishWrite(stage, output)
            output = null
            access.syncDirectory(stage)
            requireFixedPaths()
            rejectRetainedArtifacts()
            if (!sameBytes(readOptional(stage, cap), bytes)) throw IOException("Cycle ledger stage exact readback differs")
        } finally {
            output?.close() // Retain temp and PENDING; no failWrite, recovery, deletion or retry.
        }
    }

    private fun readOptional(stage: GmsDozeCycleLedgerWrite, cap: Int): ByteArray? {
        val base = if (stage == GmsDozeCycleLedgerWrite.LEDGER) ledger else marker
        val attributes = attributes(base) ?: return null
        if (!attributes.isRegularFile) throw IOException("Cycle ledger artifact is not a regular file")
        return access.readStrictlyBounded(stage, cap).clone()
    }

    private fun rejectRetainedArtifacts() {
        for (name in RETIRED_AUTHORITY_NAMES + TEMP_NAMES) {
            if (attributes(File(directory, name)) != null)
                throw IOException("Retained cycle/legacy authority requires explicit recovery: $name")
        }
    }

    private fun requireFixedPaths() {
        if (directory.canonicalFile != directory || !directory.isDirectory)
            throw IOException("Cycle ledger directory changed")
        val names = listOf(FILE_NAME, MARKER_NAME, STORAGE_LOCK_NAME,
            GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME, GmsDozeBrokerRecordStore.STORAGE_LOCK_NAME) +
            RETIRED_AUTHORITY_NAMES + TEMP_NAMES
        for (name in names) {
            val file = File(directory, name)
            if (file.canonicalPath != file.path || attributes(file)?.isSymbolicLink == true)
                throw IOException("Cycle ledger fixed artifact path changed")
        }
    }

    companion object {
        const val FILE_NAME = "gms-global-doze-v1.bin"
        const val STORAGE_LOCK_NAME = "gms-global-broker-v2.storage.lock"
        const val MARKER_NAME = "gms-global-doze-cycle.commit-fence"
        internal val RETIRED_AUTHORITY_NAMES = listOf(
            "gms-global-doze-v1.initialized", "gms-global-doze-v1.commit-pending",
            "gms-doze-broker-v1.bin", "gms-doze-broker-v1.session-fence",
            "gms-doze-broker-v2.bin", "gms-global-broker-v2.session-fence",
            DozeAtomicJournalStore.FILE_NAME,
        ).flatMap { listOf(it, "$it.new", "$it.bak") }
        internal val TEMP_NAMES = listOf("$FILE_NAME.new", "$FILE_NAME.bak", "$MARKER_NAME.new", "$MARKER_NAME.bak")
        private val processRegistry = GmsDozeCycleLedgerProcessRegistry()
        private fun sameBytes(left: ByteArray?, right: ByteArray?) = when {
            left == null || right == null -> left == null && right == null
            else -> left.contentEquals(right)
        }
        /** Only NoSuchFile is confirmed absence; access/IO errors and broken symlinks stay blocked. */
        private fun attributes(file: File): BasicFileAttributes? = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null }
    }
}

internal class GmsDozeCycleLedgerProcessRegistry {
    internal val owners = ConcurrentHashMap<String, GmsDozeCycleLedgerStorageOwner>()
}
internal class GmsDozeCycleLedgerStorageOwner {
    val control = GmsDozeCycleControl()
    var terminal = false
    var established = false
}

/** Three protocol stages map to exactly two fixed files; no path or arbitrary target input. */
internal enum class GmsDozeCycleLedgerWrite { PENDING, LEDGER, CLEAN }
internal interface GmsDozeCycleLedgerFileAccess {
    val canonicalDirectory: String
    fun startWrite(stage: GmsDozeCycleLedgerWrite): FileOutputStream
    fun sync(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream)
    fun finishWrite(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream)
    fun syncDirectory(stage: GmsDozeCycleLedgerWrite)
    fun readStrictlyBounded(stage: GmsDozeCycleLedgerWrite, maxBytes: Int): ByteArray
}

private class AndroidGmsDozeCycleLedgerFileAccess(private val directory: File) : GmsDozeCycleLedgerFileAccess {
    override val canonicalDirectory = directory.canonicalPath
    private fun base(stage: GmsDozeCycleLedgerWrite) = File(directory,
        if (stage == GmsDozeCycleLedgerWrite.LEDGER) GmsDozeCycleLedgerStore.FILE_NAME else GmsDozeCycleLedgerStore.MARKER_NAME)

    override fun startWrite(stage: GmsDozeCycleLedgerWrite): FileOutputStream {
        val pending = File(base(stage).path + ".new")
        if (!pending.createNewFile()) throw IOException("Cycle ledger pending artifact already exists")
        return FileOutputStream(pending)
    }
    override fun sync(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream) = output.fd.sync()
    override fun finishWrite(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream) {
        output.close()
        val base = base(stage)
        Os.rename(base.path + ".new", base.path)
    }
    override fun syncDirectory(stage: GmsDozeCycleLedgerWrite) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    override fun readStrictlyBounded(stage: GmsDozeCycleLedgerWrite, maxBytes: Int): ByteArray =
        FileInputStream(base(stage)).use { it.readGlobalGmsDocumentBounded(maxBytes) }
}

internal data class GmsDozeCycleLedgerSnapshot(val ledgerId: String, val revision: Long, val sha256: String)
internal data class GmsDozeCycleLedgerMarker(
    val pending: Boolean,
    /** null in PENDING explicitly records confirmed prior absence; CLEAN always has null. */
    val previous: GmsDozeCycleLedgerSnapshot?,
    val candidate: GmsDozeCycleLedgerSnapshot,
)

/** Permanent, bounded, canonical and checksummed commit evidence, never a consent document. */
internal object GmsDozeCycleLedgerMarkerCodec {
    const val MAX_BYTES = 1024
    private const val MAGIC = 0x47434D4B
    private const val VERSION = 1
    private const val HASH_BYTES = 32
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val ledgerIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun encode(value: GmsDozeCycleLedgerMarker): ByteArray {
        check(value.pending || value.previous == null)
        value.previous?.let {
            check(it.ledgerId == value.candidate.ledgerId && it.revision < Long.MAX_VALUE &&
                value.candidate.revision == it.revision + 1)
        }
        if (value.pending && value.previous == null) check(value.candidate.revision == 1L)
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeBoolean(value.pending)
                out.writeBoolean(value.previous != null)
                value.previous?.let { writeSnapshot(out, it) }
                writeSnapshot(out, value.candidate)
            }
        }.toByteArray()
        return (body + digest(body)).also { check(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): GmsDozeCycleLedgerMarker {
        check(bytes.size in (HASH_BYTES + 10)..MAX_BYTES)
        val exact = bytes.clone()
        val body = exact.copyOfRange(0, exact.size - HASH_BYTES)
        check(MessageDigest.isEqual(digest(body), exact.copyOfRange(body.size, exact.size)))
        val value = DataInputStream(ByteArrayInputStream(body)).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == VERSION)
            val pending = input.readBoolean()
            val previous = if (input.readBoolean()) readSnapshot(input) else null
            GmsDozeCycleLedgerMarker(pending, previous, readSnapshot(input)).also { check(input.available() == 0) }
        }
        check(encode(value).contentEquals(exact))
        return value
    }

    private fun writeSnapshot(out: DataOutputStream, value: GmsDozeCycleLedgerSnapshot) {
        check(ledgerIdPattern.matches(value.ledgerId) && value.revision > 0 && hashPattern.matches(value.sha256))
        writeString(out, value.ledgerId, 128)
        out.writeLong(value.revision)
        writeString(out, value.sha256, 64)
    }
    private fun readSnapshot(input: DataInputStream) = GmsDozeCycleLedgerSnapshot(
        readString(input, 128), input.readLong(), readString(input, 64))
    private fun writeString(out: DataOutputStream, text: String, cap: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..cap && strictUtf8(bytes) == text)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readString(input: DataInputStream, cap: Int): String {
        val size = input.readInt()
        check(size in 1..cap && size <= input.available())
        return strictUtf8(ByteArray(size).also(input::readFully))
    }
    private fun strictUtf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
