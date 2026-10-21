package io.github.originosfcmguardian.daemon

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Fixed roles only. Payloads retain the existing codecs/consent bytes; CONTROL includes receipts. */
internal enum class DaemonRecordRole(val fileName: String, val maximumBytes: Int = 256 * 1024) {
    CONTROL("control", 2 * 1024 * 1024), AUTHORITY("authority"),
    AUTHORITY_INITIALIZED("authority-initialized"), AUTHORITY_STOP_PENDING("authority-stop-pending"),
    SELECTION("selection"), STOPPED_JOURNAL("stopped-journal"), STOPPED_BLOCKED("stopped-blocked"),
    RECOVERY_COUNTS("recovery-counts", 1024 * 1024),
    FCM_PROTECTION("fcm-protection"), FCM_PROTECTION_HALT("fcm-protection-halt"),
    FCM_RECOVERY("fcm-recovery"), FCM_CONSUMED("fcm-consumed"), FCM_MCS_CONSUMED("fcm-mcs-consumed"),
    FCM_RECOVERY_HALT("fcm-recovery-halt"), FCM_EPISODES("fcm-episodes", 1024 * 1024),
    HEARTBEAT("heartbeat"), HEARTBEAT_INITIALIZED("heartbeat-initialized"),
    HEARTBEAT_HALT("heartbeat-halt"), HEARTBEAT_PENDING("heartbeat-pending"),
}

internal data class DaemonFileStat(val uid: Int, val mode: Int, val device: Long, val inode: Long,
    val size: Long, val links: Long = 1)
internal enum class DaemonFileOpen { READ, CREATE_EXCLUSIVE, OWNER_LOCK }
internal interface DaemonFileHandle : AutoCloseable {
    fun stat(): DaemonFileStat
    /** Reads at most maximum+1 bytes, rejecting longer or changing files. */
    fun read(maximum: Int): ByteArray
    fun write(bytes: ByteArray)
    fun sync()
    fun tryLock(): AutoCloseable?
}
/** Path arguments are constructed solely by DaemonShellStorage, never copied from IPC. */
internal interface DaemonShellFilePort {
    fun uid(): Int
    fun lstat(path: String): DaemonFileStat?
    fun mkdir(path: String)
    fun open(path: String, mode: DaemonFileOpen): DaemonFileHandle
    fun rename(from: String, to: String)
    fun unlink(path: String)
    fun syncDirectory(path: String)
}

/**
 * Shell-owned durable bytes, with one device-wide lifetime owner lock across UUIDs/epochs.
 * Provision is called ONLY after authenticated install bootstrap; this class does not authenticate IPC.
 * App-private permissions are never changed. Neither a Context nor a caller-controlled path is used.
 * Same-UID shell code is the OS trust boundary. The fixed ancestors must be real directories.
 * A lock or close failure requires owner teardown; kernel startup remains UNRECONCILED regardless.
 */
internal class DaemonShellStorage private constructor(
    instance: String, private val fs: DaemonShellFilePort, provision: Boolean,
) : AutoCloseable {
    private val directory: String
    private var parentIdentity: DaemonFileStat? = null
    private var directoryIdentity: DaemonFileStat? = null
    private var lockHandle: DaemonFileHandle? = null
    private var ownerLock: AutoCloseable? = null
    private val reservation = Any()
    private var reserved = false
    private var closed = false
    private var uncertain = false

    init {
        val id = UUID.fromString(instance)
        require(id.toString() == instance && id.version() == 4 && id.variant() == 2)
        directory = "$PARENT/$instance"
        check(fs.uid() == SHELL_UID)
        try {
            // POSIX record locks may be dropped by closing ANY same-process descriptor to the
            // inode. Reject a second local owner before it can even open owner.lock.
            synchronized(RESERVATION_MONITOR) {
                check(processReservation == null) { "Daemon storage already owned in this process" }
                processReservation = reservation; reserved = true
            }
            listOf("/data", "/data/local", "/data/local/tmp").forEach {
                check(requireNotNull(fs.lstat(it)).mode and TYPE_MASK == DIRECTORY)
            }
            parentIdentity = ensureDirectory(PARENT, provision, "/data/local/tmp")
            // Acquire before creating/opening an instance: different installations cannot coexist.
            val handle = openChecked("$PARENT/owner.lock", DaemonFileOpen.OWNER_LOCK)
            lockHandle = handle
            ownerLock = handle.tryLock() ?: error("Daemon storage already owned")
            handle.sync()
            checkIdentity(PARENT, requireNotNull(parentIdentity))
            fs.syncDirectory(PARENT)
            directoryIdentity = ensureDirectory(directory, provision, PARENT)
            uncertain = fs.lstat(fencePath())?.let { checkFile(it); true } ?: false
        } catch (failure: Exception) {
            runCatching { ownerLock?.close() }; runCatching { lockHandle?.close() }
            releaseReservation()
            closed = true
            throw failure
        }
    }

    @Synchronized fun isUncertain(): Boolean { checkOpen(); return uncertain }

    /** Final command-start callbacks run under this lease; close cannot release ownership midway. */
    @Synchronized fun <T> withOwnerLease(action: () -> T): T {
        checkOpen(); check(!uncertain); check(ownerLock != null)
        return action()
    }

    @Synchronized fun read(role: DaemonRecordRole): ByteArray? {
        checkOpen(); check(!uncertain) { "Daemon storage requires explicit reconciliation" }
        return guarded { readRecord(role) }
    }

    /** Diagnostic/reconciliation bytes confer no write authority, even if the payload says ACTIVE. */
    @Synchronized fun inspectForReconciliation(role: DaemonRecordRole): ByteArray? {
        checkOpen()
        return guarded { readRecord(role) }
    }

    @Synchronized fun write(role: DaemonRecordRole, payload: ByteArray) {
        checkOpen(); check(!uncertain) { "Daemon storage requires explicit reconciliation" }
        require(payload.size <= role.maximumBytes)
        val retainedPayload = payload.copyOf()
        val bytes = encode(role, retainedPayload)
        guarded {
            persistFence()
            val target = recordPath(role)
            fs.lstat(target)?.let(::checkFile)
            var temporary: Pair<String, DaemonFileStat>? = null
            try {
                val path = "$directory/.write-${UUID.randomUUID()}"
                openChecked(path, DaemonFileOpen.CREATE_EXCLUSIVE).use { handle ->
                    temporary = path to handle.stat()
                    handle.write(bytes); handle.sync()
                    check(handle.stat().size == bytes.size.toLong())
                }
                verifyDirectories()
                fs.lstat(target)?.let(::checkFile)
                checkIdentity(path, requireNotNull(temporary).second)
                fs.rename(path, target)
                temporary = null // Renamed committed files are never removed by cleanup.
                fs.syncDirectory(directory)
                check(readRecord(role)?.contentEquals(retainedPayload) == true) { "Daemon storage readback mismatch" }
                clearFence()
            } finally {
                temporary?.let { (path, identity) ->
                    // Only this invocation's still-identical uncommitted temporary may be removed.
                    runCatching {
                        verifyDirectories()
                        val actual = fs.lstat(path)
                        if (actual != null && sameObject(actual, identity)) {
                            checkFile(actual); fs.unlink(path); fs.syncDirectory(directory)
                        }
                    }
                }
            }
        }
    }

    /** Marker removal has the same uncertainty barrier as a commit; it cannot clear another role. */
    @Synchronized fun delete(role: DaemonRecordRole) {
        checkOpen(); check(!uncertain) { "Daemon storage requires explicit reconciliation" }
        guarded {
            persistFence()
            val path = recordPath(role)
            fs.lstat(path)?.let { checkFile(it); fs.unlink(path) }
            fs.syncDirectory(directory)
            check(fs.lstat(path) == null)
            clearFence()
        }
    }

    /**
     * Called only by the authenticated control adapter after pointer/pending-journal reconciliation.
     * Require an exact complete snapshot, including absent roles; no backup is ever selected here.
     */
    @Synchronized fun acknowledgeReconciliation(expected: Map<DaemonRecordRole, ByteArray?>) {
        checkOpen(); require(expected.keys == DaemonRecordRole.entries.toSet())
        val retained = expected.mapValues { it.value?.copyOf() }
        guarded {
            for (role in DaemonRecordRole.entries) {
                val actual = readRecord(role); val wanted = retained[role]
                check(if (wanted == null) actual == null else actual?.contentEquals(wanted) == true)
            }
            if (fs.lstat(fencePath()) != null) clearFence(reconciled = true)
            uncertain = false
        }
    }

    private fun readRecord(role: DaemonRecordRole): ByteArray? {
        verifyDirectories()
        val path = recordPath(role)
        if (fs.lstat(path) == null) return null
        return openChecked(path, DaemonFileOpen.READ).use { handle ->
            val maximum = role.maximumBytes + ENVELOPE_BYTES
            check(handle.stat().size in ENVELOPE_BYTES.toLong()..maximum.toLong())
            val bytes = handle.read(maximum)
            check(handle.stat().size == bytes.size.toLong())
            decode(role, bytes)
        }
    }

    private inline fun <T> guarded(action: () -> T): T = try { action() } catch (failure: Exception) {
        uncertain = true
        // If the durable fence cannot be saved, the in-memory gate remains closed. Restart still
        // starts UNRECONCILED and must independently reconcile the app pointer and all journals.
        runCatching { persistFence() }
        throw failure
    }

    private fun persistFence() {
        verifyDirectories()
        val path = fencePath()
        if (fs.lstat(path) == null) {
            openChecked(path, DaemonFileOpen.CREATE_EXCLUSIVE).use { it.write(byteArrayOf(1)); it.sync() }
        } else openChecked(path, DaemonFileOpen.READ).use { check(it.read(1).contentEquals(byteArrayOf(1))) }
        fs.syncDirectory(directory)
    }
    private fun clearFence(reconciled: Boolean = false) {
        verifyDirectories()
        openChecked(fencePath(), DaemonFileOpen.READ).use {
            // An interrupted fence creation may leave zero bytes. Only explicit complete
            // reconciliation may retire that malformed marker; an ordinary commit cannot.
            if (!reconciled) check(it.read(1).contentEquals(byteArrayOf(1)))
        }
        fs.unlink(fencePath()); fs.syncDirectory(directory)
    }
    private fun ensureDirectory(path: String, create: Boolean, parent: String): DaemonFileStat {
        if (fs.lstat(path) == null) { check(create); fs.mkdir(path); fs.syncDirectory(parent) }
        return requireNotNull(fs.lstat(path)).also { checkDirectory(it) }
    }
    private fun openChecked(path: String, mode: DaemonFileOpen): DaemonFileHandle {
        fs.lstat(path)?.let { checkFile(it); check(mode != DaemonFileOpen.CREATE_EXCLUSIVE) }
        val handle = fs.open(path, mode)
        try {
            val descriptor = handle.stat(); checkFile(descriptor)
            checkIdentity(path, descriptor)
            return handle
        } catch (failure: Exception) { runCatching { handle.close() }; throw failure }
    }
    private fun verifyDirectories() {
        check(fs.uid() == SHELL_UID)
        checkIdentity(PARENT, requireNotNull(parentIdentity)); checkIdentity(directory, requireNotNull(directoryIdentity))
    }
    private fun checkIdentity(path: String, expected: DaemonFileStat) {
        val actual = requireNotNull(fs.lstat(path))
        if (expected.mode and TYPE_MASK == DIRECTORY) checkDirectory(actual) else checkFile(actual)
        check(sameObject(actual, expected))
    }
    private fun checkDirectory(stat: DaemonFileStat) {
        check(stat.uid == SHELL_UID && stat.mode and TYPE_MASK == DIRECTORY && stat.mode and 0xfff == 0x1c0)
    }
    private fun checkFile(stat: DaemonFileStat) {
        check(stat.uid == SHELL_UID && stat.mode and TYPE_MASK == REGULAR && stat.mode and 0xfff == 0x180 && stat.links == 1L)
    }
    private fun sameObject(a: DaemonFileStat, b: DaemonFileStat) = a.device == b.device && a.inode == b.inode
    private fun recordPath(role: DaemonRecordRole) = "$directory/${role.fileName}.bin"
    private fun fencePath() = "$directory/.uncertain"
    private fun checkOpen() { check(!closed) }
    private fun releaseReservation() = synchronized(RESERVATION_MONITOR) {
        if (reserved && processReservation === reservation) processReservation = null
        reserved = false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { try { ownerLock?.close() } finally { lockHandle?.close() } } finally { releaseReservation() }
    }

    companion object {
        internal const val PARENT = "/data/local/tmp/originos-fcm-guardian"
        private const val SHELL_UID = 2000
        private const val TYPE_MASK = 0xf000
        private const val DIRECTORY = 0x4000
        private const val REGULAR = 0x8000
        private const val ENVELOPE_BYTES = 44
        private val RESERVATION_MONITOR = Any()
        private var processReservation: Any? = null
        fun provisionAuthenticated(instance: String, fs: DaemonShellFilePort = AndroidDaemonShellFilePort()) =
            DaemonShellStorage(instance, fs, true)
        fun openExisting(instance: String, fs: DaemonShellFilePort = AndroidDaemonShellFilePort()) =
            DaemonShellStorage(instance, fs, false)

        private fun encode(role: DaemonRecordRole, payload: ByteArray): ByteArray {
            val body = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
                it.writeInt(0x47445331); it.writeInt(role.ordinal); it.writeInt(payload.size); it.write(payload)
            } }.toByteArray()
            return body + MessageDigest.getInstance("SHA-256").digest(body)
        }
        private fun decode(role: DaemonRecordRole, bytes: ByteArray): ByteArray {
            check(bytes.size in ENVELOPE_BYTES..role.maximumBytes + ENVELOPE_BYTES)
            val bodySize = bytes.size - 32
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, bodySize) }.digest()
            check(MessageDigest.isEqual(digest, bytes.copyOfRange(bodySize, bytes.size)))
            return DataInputStream(ByteArrayInputStream(bytes, 0, bodySize)).use {
                check(it.readInt() == 0x47445331 && it.readInt() == role.ordinal)
                val size = it.readInt(); check(size in 0..role.maximumBytes && size == bodySize - 12)
                ByteArray(size).also { payload -> it.readFully(payload); check(it.read() == -1) }
            }
        }
    }
}

/** Platform flags only; directory type is checked on the opened descriptor before fsync. */
internal class AndroidDaemonShellFilePort : DaemonShellFilePort {
    override fun uid() = Os.getuid()
    override fun lstat(path: String): DaemonFileStat? = try { metadata(Os.lstat(path)) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
    override fun mkdir(path: String) = Os.mkdir(path, 0x1c0)
    override fun open(path: String, mode: DaemonFileOpen): DaemonFileHandle {
        val flags = when (mode) {
            DaemonFileOpen.READ -> OsConstants.O_RDONLY
            DaemonFileOpen.CREATE_EXCLUSIVE -> OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL
            DaemonFileOpen.OWNER_LOCK -> OsConstants.O_RDWR or OsConstants.O_CREAT
        } or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
        return AndroidHandle(Os.open(path, flags, 0x180))
    }
    override fun rename(from: String, to: String) = Os.rename(from, to)
    override fun unlink(path: String) {
        // Public Android SDK exposes remove(), not unlink(). Refuse directories explicitly;
        // callers additionally validate the fixed target's ownership/mode/identity.
        val stat = Os.lstat(path)
        check(stat.st_mode and 0xf000 == 0x8000 && stat.st_uid == 2000 &&
            stat.st_mode and 0xfff == 0x180 && stat.st_nlink == 1L)
        Os.remove(path)
    }
    override fun syncDirectory(path: String) {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { check(Os.fstat(fd).st_mode and 0xf000 == 0x4000); Os.fsync(fd) } finally { Os.close(fd) }
    }
    private class AndroidHandle(private val fd: FileDescriptor) : DaemonFileHandle {
        override fun stat() = metadata(Os.fstat(fd))
        override fun read(maximum: Int): ByteArray {
            require(maximum >= 0)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(minOf(8192, maximum + 1))
            while (true) {
                val count = Os.read(fd, buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                if (count == 0) break
                output.write(buffer, 0, count); check(output.size() <= maximum)
            }
            return output.toByteArray()
        }
        override fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(fd, bytes, offset, bytes.size - offset); check(count > 0); offset += count
            }
        }
        override fun sync() = Os.fsync(fd)
        override fun tryLock(): AutoCloseable? {
            val stream = FileOutputStream(Os.dup(fd))
            try {
                val lock = stream.channel.tryLock() ?: run { stream.close(); return null }
                return AutoCloseable { try { lock.release() } finally { stream.close() } }
            } catch (failure: Exception) { stream.close(); throw failure }
        }
        override fun close() = Os.close(fd)
    }
    companion object {
        private fun metadata(stat: android.system.StructStat) = DaemonFileStat(stat.st_uid, stat.st_mode,
            stat.st_dev, stat.st_ino, stat.st_size, stat.st_nlink)
    }
}
