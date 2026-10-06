package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Separate envelope: schema2 journal bytes are never converted, cleared or rewritten. */
object GmsGlobalDozeCodec {
    const val MAX_BYTES = 320 * 1024
    private const val MAGIC = 0x47444F5A
    private const val VERSION = 1
    private const val HASH_BYTES = 32
    private val sha256 = Regex("[0-9a-f]{64}")
    private val transactionId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun encode(document: GmsGlobalDozeDocument): ByteArray {
        validateDocument(document)
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                val consent = document.consent
                out.writeBoolean(consent.enabled)
                out.writeLong(consent.epoch)
                out.writeLong(consent.generation)
                out.writeLong(consent.confirmedAtMillis)
                writeInstallation(out, consent.gms)
                out.writeInt(consent.guardian.userId)
                writeInstallation(out, consent.guardian.installation)
                writeString(out, consent.pinnedFingerprint)
                out.writeBoolean(document.transaction != null)
                document.transaction?.let { transaction ->
                    writeString(out, transaction.id)
                    out.writeLong(transaction.epoch)
                    writeInstallation(out, transaction.gms)
                    out.writeInt(transaction.ownerProofVersion)
                    writeString(out, transaction.baseline)
                    out.writeBoolean(transaction.applied != null)
                    transaction.applied?.let { writeString(out, it) }
                    writeString(out, transaction.phase.name)
                }
            }
        }.toByteArray()
        return (body + MessageDigest.getInstance("SHA-256").digest(body)).also {
            require(it.size <= MAX_BYTES) { "Global GMS document oversized" }
        }
    }

    fun decode(bytes: ByteArray): GmsGlobalDozeDocument {
        require(bytes.size in (HASH_BYTES + 8)..MAX_BYTES) { "Global GMS document size invalid" }
        val body = bytes.copyOfRange(0, bytes.size - HASH_BYTES)
        require(MessageDigest.isEqual(bytes.copyOfRange(body.size, bytes.size),
            MessageDigest.getInstance("SHA-256").digest(body))) { "Global GMS checksum invalid" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown global GMS format" }
        val consent = GmsGlobalDozeConsent(readBoolean(input), input.readLong(), input.readLong(),
            input.readLong(), readInstallation(input),
            GmsGlobalGuardianBinding(input.readInt(), readInstallation(input)), readString(input))
        val transaction = if (readBoolean(input)) {
            val id = readString(input)
            val epoch = input.readLong()
            val installation = readInstallation(input)
            val proofVersion = input.readInt()
            val baseline = readString(input)
            val applied = if (readBoolean(input)) readString(input) else null
            val phaseName = readString(input)
            val phase = DozePhase.entries.singleOrNull { it.name == phaseName }
                ?: error("Unknown global GMS phase")
            GmsGlobalDozeTransaction(id, epoch, installation, proofVersion, baseline, applied, phase)
        } else null
        require(input.available() == 0) { "Trailing global GMS document bytes" }
        return GmsGlobalDozeDocument(consent, transaction).also(::validateDocument)
    }

    fun validate(bytes: ByteArray) { decode(bytes) }

    /** Storage adapters must invoke this inside their atomic CAS lock. */
    fun validateTransition(expected: ByteArray?, replacement: ByteArray) {
        val next = decode(replacement)
        val old = expected?.let(::decode)
        if (old == null) {
            require(next.consent.enabled && next.consent.epoch == 1L &&
                next.consent.generation == 1L && next.transaction == null) { "Initial document must be explicit consent" }
            return
        }
        require(old.consent.generation < Long.MAX_VALUE &&
            next.consent.generation == old.consent.generation + 1) { "Global GMS revision invalid" }
        val unchangedAuthority = next.consent.copy(generation = old.consent.generation) == old.consent
        if (!unchangedAuthority) {
            require(old.consent.epoch < Long.MAX_VALUE && next.consent.epoch == old.consent.epoch + 1) {
                "Global GMS consent epoch invalid"
            }
            require(old.transaction == next.transaction) { "Consent change replaced retained transaction" }
            if (next.consent.enabled) {
                require(old.transaction == null && next.consent.confirmedAtMillis > old.consent.confirmedAtMillis) {
                    "Enable needs new confirmation and no retained transaction"
                }
            } else {
                require(next.consent.copy(enabled = old.consent.enabled, epoch = old.consent.epoch,
                    generation = old.consent.generation) == old.consent) { "OFF changed identity or confirmation" }
            }
            return
        }
        val before = old.transaction
        val after = next.transaction ?: error("Retained transaction removed")
        if (before == null) {
            require(old.consent.enabled && after.phase == DozePhase.ADD_INTENT &&
                after.epoch == old.consent.epoch && after.gms == old.consent.gms) { "Invalid global GMS add intent" }
            return
        }
        require(before.copy(phase = after.phase, applied = after.applied) == after) {
            "Retained transaction identity or baseline changed"
        }
        val allowed = when (before.phase) {
            DozePhase.ADD_INTENT -> after.phase == DozePhase.APPLIED
            DozePhase.APPLIED -> after.phase == DozePhase.RESTORE_INTENT || after.phase == DozePhase.OBSERVED_RESTORED
            DozePhase.RESTORE_INTENT -> after.phase == DozePhase.RESTORED
            DozePhase.RESTORED, DozePhase.OBSERVED_RESTORED -> false
        }
        require(allowed) { "Illegal global GMS transaction transition" }
        require(before.phase == DozePhase.ADD_INTENT || before.applied == after.applied) { "Applied evidence replaced" }
    }

    internal fun validateInstallation(value: GmsGlobalInstallation) {
        require(value.appId in 10_000..99_999 && value.versionCode >= 0 && value.firstInstallTime > 0 &&
            value.lastUpdateTime >= value.firstInstallTime) { "Invalid installation identity" }
        require(value.signerSha256.size in 1..8 && value.signerSha256 == value.signerSha256.distinct().sorted() &&
            value.signerSha256.all { sha256.matches(it) }) { "Noncanonical signer identity" }
    }

    internal fun validateIdentity(value: GmsGlobalDozeIdentity, fingerprint: String) {
        validateInstallation(value.gms)
        validateInstallation(value.guardian.installation)
        require(value.guardian.userId == 0 && value.gms.appId != value.guardian.installation.appId) { "Invalid Guardian binding" }
        require(value.environment == GmsGlobalEnvironment(fingerprint, 2000, 0) && fingerprint.isNotBlank()) {
            "Unknown global GMS execution environment"
        }
        val owners = value.owners as? GmsGlobalOwnerEvidence.Qualified ?: error("Unknown effective owner mapping")
        require(owners.proofVersion == GMS_GLOBAL_OWNER_PROOF_VERSION && sha256.matches(owners.mappingSha256) &&
            owners.exclusiveGmsAppId == value.gms.appId) { "Unqualified effective owner mapping" }
    }

    private fun validateDocument(value: GmsGlobalDozeDocument) {
        val consent = value.consent
        require(consent.epoch > 0 && consent.generation > 0 && consent.confirmedAtMillis > 0 &&
            consent.pinnedFingerprint.isNotBlank() && consent.pinnedFingerprint.length <= 4096) { "Invalid global GMS consent" }
        validateInstallation(consent.gms)
        validateInstallation(consent.guardian.installation)
        require(consent.guardian.userId == 0 && consent.gms.appId != consent.guardian.installation.appId) { "Invalid Guardian binding" }
        value.transaction?.let { transaction ->
            require(transactionId.matches(transaction.id) && transaction.epoch in 1..consent.epoch &&
                transaction.gms == consent.gms && transaction.ownerProofVersion == GMS_GLOBAL_OWNER_PROOF_VERSION) {
                "Invalid global GMS retained transaction"
            }
            val rows = StrictWhitelist.parse(transaction.baseline)
            require(rows.none { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }) { "Global GMS baseline contains GMS" }
            require((transaction.phase == DozePhase.ADD_INTENT) == (transaction.applied == null)) { "Phase evidence mismatch" }
            transaction.applied?.let {
                require(StrictWhitelist.parse(it) == rows + Membership(MembershipSource.USER,
                    GMS_GLOBAL_DOZE_PACKAGE, transaction.gms.appId)) { "Applied evidence is not the exact GMS delta" }
            }
        }
    }

    private fun writeInstallation(out: DataOutputStream, value: GmsGlobalInstallation) {
        out.writeInt(value.appId)
        out.writeLong(value.versionCode)
        out.writeLong(value.firstInstallTime)
        out.writeLong(value.lastUpdateTime)
        out.writeInt(value.signerSha256.size)
        value.signerSha256.forEach { writeString(out, it) }
    }

    private fun readInstallation(input: DataInputStream): GmsGlobalInstallation {
        val appId = input.readInt()
        val version = input.readLong()
        val first = input.readLong()
        val last = input.readLong()
        val size = input.readInt()
        require(size in 1..8) { "Invalid signer count" }
        return GmsGlobalInstallation(appId, version, first, last, List(size) { readString(input) })
    }

    private fun readBoolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("Noncanonical boolean")
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= StrictWhitelist.MAX_RAW_BYTES) { "Global GMS field oversized" }
        require(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value) { "Invalid UTF-8 string" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..StrictWhitelist.MAX_RAW_BYTES && size <= input.available()) { "Global GMS field length invalid" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also {
            require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid UTF-8 field" }
        }
    }
}
