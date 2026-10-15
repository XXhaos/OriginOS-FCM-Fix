package io.github.originosfcmguardian.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Exact staged command material, not a recipe for generating new consent/session/request IDs.
 * Persisted before the App can commit its next pointer. Its presence never grants dispatch;
 * restart still requires fresh authenticated validation, a new nonce and App pointer readback.
 */
internal object DaemonControlProposalCodec {
    val MAX_BYTES = DaemonDomainRole.CONTROL_PROPOSAL.maximumBytes
    private const val MAGIC = 0x47445031

    fun encode(plan: DaemonControlTransitionPlan): ByteArray {
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC); out.writeInt(2)
                out.writeUTF(plan.requestId); out.record(plan.expected); out.record(plan.next)
                out.writeInt(plan.transition.ordinal); out.writeUTF(plan.patchDigest)
                val patch = plan.patch()
                out.writeInt(patch.size)
                for (role in DaemonDomainRole.entries.filter(patch::containsKey)) {
                    val bytes = patch[role]
                    out.writeInt(role.ordinal); out.writeInt(bytes?.size ?: -1)
                    if (bytes != null) out.write(bytes)
                }
                val native = plan.nativeHeartbeatDerivation()
                out.writeInt(native?.size ?: -1)
                if (native != null) out.write(native)
            }
        }.toByteArray()
        require(body.size + 32 <= MAX_BYTES) { "Staged control proposal exceeds bound" }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    fun decode(bytes: ByteArray): DaemonControlTransitionPlan {
        require(bytes.size in 40..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),
            bytes.copyOfRange(body.size, bytes.size))) { "Staged control proposal checksum" }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt().also { require(it in 1..2) }
            val requestId = input.text()
            val expected = input.record(); val next = input.record()
            val transition = DaemonControlTransition.entries.getOrNull(input.readInt()) ?: error("Unknown control transition")
            val digest = input.text()
            val count = input.readInt().also { require(it in 0..DaemonDomainRole.entries.size) }
            val patch = LinkedHashMap<DaemonDomainRole, ByteArray?>()
            var previousOrdinal = -1
            repeat(count) {
                val ordinal = input.readInt()
                val role = DaemonDomainRole.entries.getOrNull(ordinal) ?: error("Unknown proposal role")
                require(ordinal > previousOrdinal) { "Duplicate/unordered proposal role" }
                previousOrdinal = ordinal
                val size = input.readInt().also { require(it in -1..role.maximumBytes && it <= input.available()) }
                patch[role] = if (size == -1) null else ByteArray(size).also(input::readFully)
            }
            val native = if (version == 1) null else {
                val size = input.readInt().also { require(it in -1..DaemonDomainRole.HEARTBEAT_DERIVATION.maximumBytes && it <= input.available()) }
                if (size == -1) null else ByteArray(size).also(input::readFully)
            }
            require(input.available() == 0)
            DaemonControlTransitionPlan(requestId, expected, next, transition, patch, native).also {
                require(it.patchDigest == digest) { "Staged control patch digest" }
            }
        }
    }

    private fun DataOutputStream.record(value: DaemonControlRecord) {
        writeUTF(value.owner.installInstance); writeLong(value.owner.ownerEpoch); writeLong(value.revision)
        writeUTF(value.authorityDigest); writeInt(value.mode.ordinal); writeUTF(value.migrationId)
    }
    private fun DataInputStream.record() = DaemonControlRecord(DaemonOwnerKey(text(), readLong()), readLong(),
        text(), DaemonControlMode.entries.getOrNull(readInt()) ?: error("Unknown proposal mode"), text())
    private fun DataInputStream.text() = readUTF().also { require(it.isNotBlank() && it.length <= 128) }
}
