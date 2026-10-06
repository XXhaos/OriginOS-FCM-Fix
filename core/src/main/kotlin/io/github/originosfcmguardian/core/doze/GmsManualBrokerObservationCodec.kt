package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

/** Bounded read-only IPC envelope, never a consent document or a durable write ticket. */
object GmsManualBrokerObservationCodec {
    const val MAX_BYTES = 144 * 1024
    private const val MAGIC = 0x474D4F42
    private const val VERSION = 1
    private const val HASH_BYTES = 32
    private const val MAX_RAW_BYTES = 128 * 1024

    fun encode(value: GmsGlobalDozeObservation, expectedFingerprint: String): ByteArray {
        validate(value, expectedFingerprint)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            writeInstallation(out, value.identity.gms)
            out.writeInt(value.identity.guardian.userId)
            writeInstallation(out, value.identity.guardian.installation)
            writeString(out, value.identity.environment.fingerprint, 4096)
            out.writeInt(value.identity.environment.executionUid)
            out.writeInt(value.identity.environment.currentUserId)
            val owner = value.identity.owners as GmsGlobalOwnerEvidence.Qualified
            out.writeInt(owner.proofVersion)
            writeString(out, owner.mappingSha256, 64)
            out.writeInt(owner.exclusiveGmsAppId)
            writeString(out, value.rawWhitelist, MAX_RAW_BYTES)
        }
        val body = buffer.toByteArray()
        return (body + digest(body)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray, expectedFingerprint: String): GmsGlobalDozeObservation {
        require(bytes.size in (HASH_BYTES + 8)..MAX_BYTES) { "Invalid observation size" }
        val exact = bytes.clone()
        val body = exact.copyOfRange(0, exact.size - HASH_BYTES)
        require(MessageDigest.isEqual(exact.copyOfRange(body.size, exact.size), digest(body))) {
            "Invalid observation checksum"
        }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown observation schema" }
        val gms = readInstallation(input)
        val guardian = GmsGlobalGuardianBinding(input.readInt(), readInstallation(input))
        val environment = GmsGlobalEnvironment(readString(input, 4096), input.readInt(), input.readInt())
        val owners = GmsGlobalOwnerEvidence.Qualified(input.readInt(), readString(input, 64), input.readInt())
        val value = GmsGlobalDozeObservation(GmsGlobalDozeIdentity(gms, guardian, environment, owners),
            readString(input, MAX_RAW_BYTES))
        require(input.available() == 0) { "Trailing observation bytes" }
        require(encode(value, expectedFingerprint).contentEquals(exact)) { "Noncanonical observation" }
        return value
    }

    private fun validate(value: GmsGlobalDozeObservation, expectedFingerprint: String) {
        GmsGlobalDozeCodec.validateIdentity(value.identity, expectedFingerprint)
        val rows = StrictWhitelist.parse(value.rawWhitelist)
        require(rows.filter { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }.all {
            it.appId == value.identity.gms.appId
        }) { "GMS whitelist binding differs" }
    }

    private fun writeInstallation(out: DataOutputStream, value: GmsGlobalInstallation) {
        out.writeInt(value.appId)
        out.writeLong(value.versionCode)
        out.writeLong(value.firstInstallTime)
        out.writeLong(value.lastUpdateTime)
        out.writeInt(value.signerSha256.size)
        value.signerSha256.forEach { writeString(out, it, 64) }
    }

    private fun readInstallation(input: DataInputStream): GmsGlobalInstallation {
        val appId = input.readInt()
        val version = input.readLong()
        val first = input.readLong()
        val last = input.readLong()
        val count = input.readInt()
        require(count in 1..8) { "Invalid signer count" }
        val signers = Collections.unmodifiableList(List(count) { readString(input, 64) })
        return GmsGlobalInstallation(appId, version, first, last, signers)
    }

    private fun writeString(out: DataOutputStream, text: String, cap: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..cap) { "Observation field oversized or empty" }
        // Require exact UTF-8: unpaired UTF-16 must not silently become replacement bytes.
        require(strictUtf8(bytes) == text) { "Observation text is not exact UTF-8" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, cap: Int): String {
        val length = input.readInt()
        require(length in 1..cap && length <= input.available()) { "Invalid observation field length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return strictUtf8(bytes)
    }

    private fun strictUtf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
