package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class DozeRecord(
    val target: DozeTarget,
    val identity: DozeIdentity,
    val baseline: String,
    val applied: String?,
    val phase: DozePhase,
)

/** Independent version-2 transaction format; never shares the immutable v1 baseline. */
object DozeJournalCodec {
    const val MAX_BYTES = 320 * 1024
    private const val MAGIC = 0x444F5A45
    private const val VERSION = 2
    private const val HASH_SIZE = 32

    /** Validate a loaded record without changing or normalizing its bytes. */
    fun validate(bytes: ByteArray) { decode(bytes) }

    internal fun encode(record: DozeRecord): ByteArray {
        validateRecord(record)
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                writeString(out, record.target.name)
                writeString(out, record.phase.name)
                val id = record.identity
                writeString(out, id.fingerprint)
                out.writeInt(id.userId)
                out.writeInt(id.executionUid)
                writeString(out, id.packageName)
                out.writeInt(id.appId)
                out.writeBoolean(id.enabled)
                writeSet(out, id.installedUserIds.map(Int::toString))
                out.writeInt(id.pccId)
                writeSet(out, id.uidOwnerPackages)
                writeString(out, record.baseline)
                out.writeBoolean(record.applied != null)
                record.applied?.let { writeString(out, it) }
            }
        }.toByteArray()
        val bytes = body + MessageDigest.getInstance("SHA-256").digest(body)
        require(bytes.size <= MAX_BYTES) { "Doze journal is oversized" }
        return bytes
    }

    internal fun decode(bytes: ByteArray): DozeRecord {
        require(bytes.size in (HASH_SIZE + 8)..MAX_BYTES) { "Doze journal size invalid" }
        val body = bytes.copyOfRange(0, bytes.size - HASH_SIZE)
        val hash = bytes.copyOfRange(bytes.size - HASH_SIZE, bytes.size)
        require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(body))) {
            "Doze journal checksum invalid"
        }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown Doze journal format" }
        val targetName = readString(input)
        val target = DozeTarget.entries.singleOrNull { it.name == targetName }
            ?: throw IllegalArgumentException("Unknown Doze target")
        val phaseName = readString(input)
        val phase = DozePhase.entries.singleOrNull { it.name == phaseName }
            ?: throw IllegalArgumentException("Unknown Doze phase")
        val id = DozeIdentity(
            fingerprint = readString(input),
            userId = input.readInt(),
            executionUid = input.readInt(),
            packageName = readString(input),
            appId = input.readInt(),
            enabled = input.readBoolean(),
            installedUserIds = readSet(input).map {
                val parsed = it.toIntOrNull() ?: error("Invalid installed user")
                require(parsed.toString() == it) { "Noncanonical installed user" }
                parsed
            }.toSet(),
            pccId = input.readInt(),
            uidOwnerPackages = readSet(input).toSet(),
        )
        val baseline = readString(input)
        val applied = if (input.readBoolean()) readString(input) else null
        require(input.available() == 0) { "Trailing Doze journal data" }
        return DozeRecord(target, id, baseline, applied, phase).also(::validateRecord)
    }

    /** Check under the same app-private lock used for durable compare-and-set. */
    fun validateTransition(expected: ByteArray?, replacement: ByteArray) {
        val old = expected?.let(::decode)
        val next = decode(replacement)
        if (old == null) {
            require(next.phase == DozePhase.ADD_INTENT) { "First Doze record must be add intent" }
            return
        }
        require(old.target == next.target && old.identity == next.identity && old.baseline == next.baseline) {
            "Doze transaction identity or baseline changed"
        }
        val valid = when (old.phase) {
            DozePhase.ADD_INTENT -> next.phase == DozePhase.APPLIED && next.applied != null
            DozePhase.APPLIED -> next.phase == DozePhase.RESTORE_INTENT ||
                next.phase == DozePhase.OBSERVED_RESTORED
            DozePhase.RESTORE_INTENT -> next.phase == DozePhase.RESTORED
            DozePhase.RESTORED, DozePhase.OBSERVED_RESTORED -> false
        }
        require(valid) { "Illegal Doze transaction transition" }
        if (old.phase != DozePhase.ADD_INTENT) {
            require(old.applied == next.applied) { "Verified addition changed" }
        }
    }

    private fun validateRecord(record: DozeRecord) {
        require(record.identity.packageName == record.target.packageName) { "Doze target mismatch" }
        require(record.identity.fingerprint.isNotBlank()) { "Missing fingerprint" }
        require(record.identity.userId == 0 && record.identity.executionUid == 2000) { "Wrong execution scope" }
        require(record.identity.appId in 10000..99999) { "Invalid target appId" }
        require(record.identity.enabled && record.identity.pccId == -1) { "Target not write-eligible" }
        require(record.identity.installedUserIds == setOf(0)) { "Target installed in another user" }
        require(record.identity.uidOwnerPackages == setOf(record.target.packageName)) { "UID ownership ambiguous" }
        val baseline = StrictWhitelist.parse(record.baseline)
        require(baseline.none { it.packageName == record.target.packageName }) { "Baseline contains target" }
        val applied = record.applied
        require((record.phase == DozePhase.ADD_INTENT) == (applied == null)) { "Phase evidence mismatch" }
        if (applied != null) {
            val row = Membership(MembershipSource.USER, record.target.packageName, record.identity.appId)
            require(StrictWhitelist.parse(applied) == baseline + row) { "Addition is not the exact target delta" }
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= StrictWhitelist.MAX_RAW_BYTES) { "Doze field oversized" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..StrictWhitelist.MAX_RAW_BYTES && size <= input.available()) { "Doze field length invalid" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val result = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(result.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8 field" }
        return result
    }

    private fun writeSet(out: DataOutputStream, values: Collection<String>) {
        require(values.size <= 32) { "Too many identity entries" }
        out.writeInt(values.size)
        values.sorted().forEach { writeString(out, it) }
    }

    private fun readSet(input: DataInputStream): List<String> {
        val size = input.readInt()
        require(size in 0..32) { "Identity set size invalid" }
        val values = List(size) { readString(input) }
        require(values == values.sorted() && values.size == values.toSet().size) { "Identity set invalid" }
        return values
    }
}
