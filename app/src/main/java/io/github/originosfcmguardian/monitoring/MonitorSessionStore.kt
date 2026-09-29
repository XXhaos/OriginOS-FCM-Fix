package io.github.originosfcmguardian.monitoring

import android.content.Context
import android.util.AtomicFile
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface MonitorSessionLoadResult {
    data object Empty : MonitorSessionLoadResult
    data class Available(val session: MonitorSession) : MonitorSessionLoadResult
    data object Corrupt : MonitorSessionLoadResult
}

class MonitorSessionStore private constructor(
    private val access: MonitorSessionFileAccess,
) {
    constructor(context: Context) : this(
        AndroidMonitorSessionFileAccess(
            File(context.applicationContext.noBackupFilesDir, FILE_NAME),
        ),
    )

    internal constructor(baseFile: File, access: MonitorSessionFileAccess) : this(access) {
        require(baseFile.canonicalPath == access.canonicalPath)
    }

    private val lock = lockFor(access.canonicalPath)

    fun load(): MonitorSessionLoadResult = synchronized(lock) { loadLocked() }

    /** Replaces the single latest session, but never overwrites an unreviewed corrupt artifact. */
    fun save(session: MonitorSession): Boolean = synchronized(lock) {
        if (loadLocked() == MonitorSessionLoadResult.Corrupt) return@synchronized false
        writeLocked(session)
    }

    /** Explicit user action. This removes only this store's AtomicFile artifacts. */
    fun clear(): Boolean = synchronized(lock) {
        runCatching {
            access.clearArtifacts()
            !access.hasAnyArtifact()
        }.getOrDefault(false)
    }

    private fun loadLocked(): MonitorSessionLoadResult {
        if (!access.hasAnyArtifact()) return MonitorSessionLoadResult.Empty
        val encoded = runCatching { access.readBounded(MAX_FILE_BYTES) }.getOrNull()
            ?: return MonitorSessionLoadResult.Corrupt
        val session = MonitorSessionCodec.decode(encoded) ?: return MonitorSessionLoadResult.Corrupt
        val recovered = runCatching {
            access.confirmValidatedRead(encoded, MAX_FILE_BYTES)
        }.getOrDefault(false)
        if (!recovered) return MonitorSessionLoadResult.Corrupt
        return MonitorSessionLoadResult.Available(session)
    }

    private fun writeLocked(session: MonitorSession): Boolean {
        val encoded = runCatching { MonitorSessionCodec.encode(session) }.getOrNull() ?: return false
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

    companion object {
        const val MAX_FILE_BYTES = 128 * 1024
        const val FILE_NAME = "deep-monitor-session-v1.txt"

        private val locks = ConcurrentHashMap<String, Any>()
        private fun lockFor(path: String): Any = locks.computeIfAbsent(path) { Any() }
    }
}

internal object MonitorSessionCodec {
    private const val MAGIC = "GUARDIAN_DEEP_MONITOR_SESSION_V1"
    private const val MIN_ENCODED_SIGNER_BYTES = 66
    private const val MAX_SIGNERS = MonitorSessionStore.MAX_FILE_BYTES / MIN_ENCODED_SIGNER_BYTES

    fun encode(session: MonitorSession): ByteArray {
        require(session.identity.signerSha256.size <= MAX_SIGNERS) { "Too many signers" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(MAGIC)
            output.writeUTF(session.id)
            output.writeUTF(session.identity.packageName)
            output.writeInt(session.identity.uid)
            output.writeLong(session.identity.firstInstallTime)
            output.writeInt(session.identity.signerSha256.size)
            session.identity.signerSha256.forEach(output::writeUTF)
            output.writeLong(session.startedAtEpochMillis)
            output.writeLong(session.deadlineEpochMillis)
            output.writeUTF(session.status.name)
            output.writeInt(session.pollCount)
            output.writeInt(session.events.size)
            session.events.forEach { event ->
                output.writeLong(event.timestampEpochMillis)
                output.writeUTF(event.target.packageName)
                output.writeInt(event.target.uid)
                output.writeUTF(event.kind.name)
                output.writeBoolean(event.pid != null)
                event.pid?.let(output::writeInt)
            }
        }
        return bytes.toByteArray().also {
            require(it.size <= MonitorSessionStore.MAX_FILE_BYTES) { "Monitor session exceeds storage limit" }
        }
    }

    fun decode(encoded: ByteArray): MonitorSession? {
        if (encoded.isEmpty() || encoded.size > MonitorSessionStore.MAX_FILE_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readUTF() == MAGIC)
                val id = input.readUTF()
                val packageName = input.readUTF()
                val uid = input.readInt()
                val firstInstallTime = input.readLong()
                val signerCount = input.readInt()
                require(signerCount in 1..MAX_SIGNERS)
                val signers = List(signerCount) { input.readUTF() }
                val startedAt = input.readLong()
                val deadline = input.readLong()
                val statusName = input.readUTF()
                val status = MonitorSessionStatus.entries.single { it.name == statusName }
                val pollCount = input.readInt()
                val eventCount = input.readInt()
                require(eventCount in 0..MonitorSession.MAX_EVENTS)
                val events = List(eventCount) {
                    val timestamp = input.readLong()
                    val eventPackage = input.readUTF()
                    val eventUid = input.readInt()
                    val kindName = input.readUTF()
                    val kind = ProcessTraceKind.entries.single { it.name == kindName }
                    val pid = if (input.readBoolean()) input.readInt() else null
                    ProcessTraceEvent(
                        timestampEpochMillis = timestamp,
                        target = ProcessTraceTarget(eventPackage, eventUid),
                        kind = kind,
                        pid = pid,
                    )
                }
                require(input.read() == -1) { "Trailing monitor session bytes" }
                MonitorSession(
                    id = id,
                    identity = SelectedTargetIdentity(packageName, uid, firstInstallTime, signers),
                    startedAtEpochMillis = startedAt,
                    deadlineEpochMillis = deadline,
                    status = status,
                    events = events,
                    pollCount = pollCount,
                )
            }
        }.getOrNull()
    }
}

internal interface MonitorSessionFileAccess {
    val canonicalPath: String
    fun hasAnyArtifact(): Boolean
    fun startWrite(): FileOutputStream
    fun finishWrite(output: FileOutputStream)
    fun failWrite(output: FileOutputStream?)
    fun readBounded(maxBytes: Int): ByteArray
    fun confirmValidatedRead(expected: ByteArray, maxBytes: Int): Boolean
    fun clearArtifacts()
}

private class AndroidMonitorSessionFileAccess(
    private val baseFile: File,
) : MonitorSessionFileAccess {
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

    /**
     * Reads the last committed candidate without invoking AtomicFile recovery. Legacy `.bak` is
     * authoritative; `.new` is never a committed candidate. Cleanup is deferred until the codec
     * has validated these bytes.
     */
    override fun readBounded(maxBytes: Int): ByteArray = when {
        backupFile.exists() -> FileInputStream(backupFile).use { it.readStrictlyBounded(maxBytes) }
        baseFile.exists() -> FileInputStream(baseFile).use { it.readStrictlyBounded(maxBytes) }
        else -> throw IOException("Monitor session has no committed base")
    }

    /** Runs platform AtomicFile recovery only after the selected committed candidate is valid. */
    override fun confirmValidatedRead(expected: ByteArray, maxBytes: Int): Boolean {
        val recovered = atomicFile.openRead().use { it.readStrictlyBounded(maxBytes) }
        return recovered.contentEquals(expected) && !backupFile.exists() && !newFile.exists()
    }

    override fun clearArtifacts() {
        atomicFile.delete()
        if (newFile.exists() && !newFile.delete()) throw IOException("Cannot clear pending monitor session")
    }
}

private fun FileInputStream.readStrictlyBounded(maxBytes: Int): ByteArray {
    val size = channel.size()
    if (size < 0 || size > maxBytes) throw IOException("Monitor session exceeds size limit")
    val bytes = ByteArray(size.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) throw IOException("Monitor session ended early")
        offset += read
    }
    if (read() != -1) throw IOException("Monitor session grew while reading")
    return bytes
}
