package io.github.originosfcmguardian.pushactivation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Exact Guardian installation, read locally from PackageManager; never an arbitrary target package. */
internal class PushActivationInstallation(
    val uid: Int,
    val firstInstallTime: Long,
    val versionCode: Long,
    val lastUpdateTime: Long,
    signerSha256: List<String>,
) {
    val signerSha256: List<String> = Collections.unmodifiableList(ArrayList(signerSha256))

    init {
        require(uid in 10_000..99_999 && firstInstallTime > 0 && versionCode > 0)
        require(lastUpdateTime >= firstInstallTime)
        require(this.signerSha256.size in 1..16 && this.signerSha256 == this.signerSha256.distinct().sorted())
        require(this.signerSha256.all { it.matches(SHA256) })
    }

    override fun equals(other: Any?): Boolean = this === other || other is PushActivationInstallation &&
        uid == other.uid && firstInstallTime == other.firstInstallTime && versionCode == other.versionCode &&
        lastUpdateTime == other.lastUpdateTime && signerSha256 == other.signerSha256

    override fun hashCode(): Int {
        var result = uid
        result = 31 * result + firstInstallTime.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + lastUpdateTime.hashCode()
        return 31 * result + signerSha256.hashCode()
    }

    private companion object { val SHA256 = Regex("[a-f0-9]{64}") }
}

internal enum class PushActivationMode { PENDING, DISPATCHING, BLOCKED, COMPLETE, RETIRED }
enum class PushActivationReason {
    NONE, UNKNOWN_RESULT, STORAGE_UNAVAILABLE, IDENTITY_CHANGED, OWNER_CHANGED,
    RECONCILIATION_REQUIRED, PRIVILEGE_REVOKED, CANCELLED, MASTER_OFF,
}

/** An explicitly confirmed intent only. It grants no shell or recovery authority by itself. */
internal data class PushActivationSnapshot(
    val generation: Long,
    val ticketId: String?,
    val installation: PushActivationInstallation?,
    val confirmedAtEpochMillis: Long,
    val mode: PushActivationMode,
    val reason: PushActivationReason = PushActivationReason.NONE,
    /** Null means a legacy record did not retain admissions, not zero attempts. */
    val recordedDispatches: Int? = null,
    val lastDispatch: PushActivationDispatch? = null,
    val lastAttempt: PushActivationAttemptDiagnostic? = null,
) {
    init {
        require(generation > 0)
        require((ticketId == null) == (installation == null))
        if (ticketId != null) {
            require(ticketId.length == 36 && runCatching { UUID.fromString(ticketId).toString() == ticketId }.getOrDefault(false))
            require(confirmedAtEpochMillis > 0)
        } else require(mode == PushActivationMode.RETIRED && confirmedAtEpochMillis == 0L)
        require(mode == PushActivationMode.RETIRED || ticketId != null)
        require(recordedDispatches == null || recordedDispatches >= 0)
        require(lastAttempt == null || lastDispatch != null)
        when (mode) {
            PushActivationMode.PENDING, PushActivationMode.DISPATCHING, PushActivationMode.COMPLETE ->
                require(reason == PushActivationReason.NONE)
            PushActivationMode.BLOCKED -> require(reason != PushActivationReason.NONE && reason != PushActivationReason.CANCELLED)
            PushActivationMode.RETIRED -> require(reason == PushActivationReason.CANCELLED)
        }
    }
}

/** Unknown/interrupted reads and writes throw. Only successful absence may return null. */
internal interface PushActivationStorage {
    fun read(): PushActivationSnapshot?
    fun write(value: PushActivationSnapshot)
    fun <T> exclusive(action: () -> T): T
}

/** Every mutation is exact-CAS and independently read back. No read normalizes a pending attempt. */
internal class PushActivationStore(private val storage: PushActivationStorage) {
    fun read(): PushActivationSnapshot? = storage.exclusive { storage.read() }

    /** Call only for a new explicit unified ON confirmation, never for saved master ON alone. */
    fun begin(installation: PushActivationInstallation, requestId: String, confirmedAtEpochMillis: Long): PushActivationSnapshot =
        storage.exclusive {
            val previous = storage.read()
            check(previous?.ticketId != requestId) { "Activation request already used" }
            commit(PushActivationSnapshot(nextGeneration(previous), requestId, installation,
                confirmedAtEpochMillis, PushActivationMode.PENDING, recordedDispatches = 0))
        }

    /** A late result cannot reopen a retired/replaced ticket. BLOCKED may only settle COMPLETE
     * after the caller freshly observes existing configuration; it cannot be replayed. */
    fun transition(expected: PushActivationSnapshot, mode: PushActivationMode,
        reason: PushActivationReason = PushActivationReason.NONE): PushActivationSnapshot? = storage.exclusive {
        val previous = storage.read()
        if (previous != expected || !allowedTransition(expected.mode, mode)) return@exclusive null
        val admission = mode == PushActivationMode.DISPATCHING
        commit(expected.copy(generation = nextGeneration(previous), mode = mode, reason = reason,
            recordedDispatches = if (admission) Math.addExact(expected.recordedDispatches ?: 0, 1) else expected.recordedDispatches,
            lastDispatch = if (admission) null else expected.lastDispatch,
            lastAttempt = if (admission) null else expected.lastAttempt))
    }

    /** Diagnostic receipt only. A late result cannot overwrite OFF or a newer explicit ON. */
    fun recordAttempt(expected: PushActivationSnapshot, result: PushActivationDispatch,
        diagnostic: PushActivationAttemptDiagnostic?): PushActivationSnapshot? = storage.exclusive {
        if (storage.read() != expected || expected.mode != PushActivationMode.DISPATCHING) return@exclusive null
        commit(expected.copy(generation = nextGeneration(expected), lastDispatch = result, lastAttempt = diagnostic))
    }

    /** Durable negative intent, including explicit OFF before the first unified confirmation. */
    fun retire(): PushActivationSnapshot = storage.exclusive {
        val previous = storage.read()
        commit(PushActivationSnapshot(nextGeneration(previous), previous?.ticketId, previous?.installation,
            previous?.confirmedAtEpochMillis ?: 0L, PushActivationMode.RETIRED, PushActivationReason.CANCELLED,
            previous?.recordedDispatches, previous?.lastDispatch, previous?.lastAttempt))
    }

    private fun commit(value: PushActivationSnapshot): PushActivationSnapshot {
        storage.write(value)
        check(storage.read() == value) { "Activation write readback unavailable" }
        return value
    }

    private fun nextGeneration(previous: PushActivationSnapshot?): Long = Math.addExact(previous?.generation ?: 0L, 1L)

    private fun allowedTransition(before: PushActivationMode, after: PushActivationMode): Boolean = when (before) {
        PushActivationMode.PENDING -> after in setOf(PushActivationMode.DISPATCHING, PushActivationMode.BLOCKED, PushActivationMode.COMPLETE)
        PushActivationMode.DISPATCHING -> after in setOf(PushActivationMode.PENDING, PushActivationMode.BLOCKED, PushActivationMode.COMPLETE)
        PushActivationMode.BLOCKED -> after == PushActivationMode.COMPLETE
        else -> false
    }
}

/** App-private bounded record. A checksum detects interruption; it is not an authorization signature. */
internal object PushActivationCodec {
    const val MAX_BYTES = 2048
    private const val MAGIC = 0x47504131
    private const val VERSION = 2
    private const val HASH_BYTES = 32

    fun encode(value: PushActivationSnapshot): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeLong(value.generation)
            output.writeByte(value.mode.ordinal); output.writeByte(value.reason.ordinal)
            output.writeLong(value.confirmedAtEpochMillis)
            output.writeBoolean(value.ticketId != null)
            value.ticketId?.let { ticket ->
                output.writeUTF(ticket)
                val installation = requireNotNull(value.installation)
                output.writeInt(installation.uid); output.writeLong(installation.firstInstallTime)
                output.writeLong(installation.versionCode); output.writeLong(installation.lastUpdateTime)
                output.writeByte(installation.signerSha256.size)
                installation.signerSha256.forEach(output::writeUTF)
            }
            output.writeInt(value.recordedDispatches ?: -1)
            output.writeInt(value.lastDispatch?.ordinal ?: -1)
            output.writeBoolean(value.lastAttempt != null)
            value.lastAttempt?.let { attempt ->
                output.writeUTF(attempt.remoteOutcome); output.writeUTF(attempt.remoteReason)
                output.writeBoolean(attempt.preparationReason != null)
                attempt.preparationReason?.let(output::writeUTF)
                output.writeBoolean(attempt.readUnknown != null)
                attempt.readUnknown?.let(output::writeUTF)
            }
        } }.toByteArray()
        return (payload + digest(payload)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): PushActivationSnapshot {
        require(bytes.size in (HASH_BYTES + 27)..MAX_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - HASH_BYTES)
        require(MessageDigest.isEqual(digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt().also { require(it in 1..VERSION) }
            val generation = input.readLong()
            val mode = PushActivationMode.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown activation mode")
            val reason = PushActivationReason.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown activation reason")
            val confirmedAt = input.readLong()
            val present = input.readUnsignedByte().also { require(it in 0..1) } == 1
            val ticket = if (present) input.readUTF().also { require(it.length == 36) } else null
            val installation = if (present) {
                val uid = input.readInt(); val installed = input.readLong(); val version = input.readLong(); val updated = input.readLong()
                val signerCount = input.readUnsignedByte().also { require(it in 1..16) }
                val signers = List(signerCount) { input.readUTF().also { require(it.length == 64) } }
                PushActivationInstallation(uid, installed, version, updated, signers)
            } else null
            val dispatches = if (version >= 2) input.readInt().also { require(it >= -1) }.takeUnless { it == -1 } else null
            val result = if (version >= 2) input.readInt().let {
                if (it == -1) null else PushActivationDispatch.entries.getOrNull(it) ?: error("Unknown activation result")
            } else null
            val attempt = if (version >= 2 && input.readUnsignedByte().also { require(it in 0..1) } == 1) {
                val outcome = input.readUTF(); val reasonName = input.readUTF()
                val preparation = if (input.readUnsignedByte().also { require(it in 0..1) } == 1) input.readUTF() else null
                val unknown = if (input.readUnsignedByte().also { require(it in 0..1) } == 1) input.readUTF() else null
                PushActivationAttemptDiagnostic(outcome, reasonName, preparation, unknown)
            } else null
            require(input.available() == 0)
            PushActivationSnapshot(generation, ticket, installation, confirmedAt, mode, reason, dispatches, result, attempt)
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
