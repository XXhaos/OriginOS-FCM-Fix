package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Separate V2 envelope; V1 bytes, migration and authority are deliberately outside this codec. */
object GmsDozeBrokerV2Codec {
    const val MAX_BYTES = 32 * 1024
    private const val MAGIC = 0x47444252
    private const val VERSION = 2
    private const val HASH_BYTES = 32
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    private val nonce = Regex("[A-Za-z0-9_-]{1,128}")

    fun initialState() = GmsDozeBrokerV2State(0, null, null)

    fun encode(state: GmsDozeBrokerV2State): ByteArray {
        validateState(state)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(state.revision)
            writeAttempt(out, state.add)
            writeAttempt(out, state.restore)
        }
        val body = buffer.toByteArray()
        return (body + hash(body)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray) = decodeProof(bytes).state

    fun decodeProof(bytes: ByteArray): GmsDozeBrokerV2Proof {
        require(bytes.size in (HASH_BYTES + 18)..MAX_BYTES)
        val exact = bytes.clone()
        val body = exact.copyOfRange(0, exact.size - HASH_BYTES)
        require(MessageDigest.isEqual(exact.copyOfRange(body.size, exact.size), hash(body))) { "V2 checksum invalid" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown V2 broker schema" }
        val state = GmsDozeBrokerV2State(input.readLong(), readAttempt(input), readAttempt(input))
        require(input.available() == 0) { "Trailing V2 bytes" }
        require(encode(state).contentEquals(exact)) { "Noncanonical V2 broker state" }
        return GmsDozeBrokerV2Proof(exact, state)
    }

    fun validate(bytes: ByteArray) { decodeProof(bytes) }

    /** Called inside the required durable adapter's cross-process CAS lock. */
    fun validateTransition(expected: ByteArray?, replacement: ByteArray) {
        val after = decode(replacement)
        if (expected == null) {
            require(after == initialState()) { "V2 absence may only initialize empty" }
            return
        }
        val before = decode(expected)
        require(before.revision < Long.MAX_VALUE && after.revision == before.revision + 1) { "V2 revision invalid" }
        val addIssue = before == initialState() && after.add != null && after.restore == null &&
            after.add == GmsDozeBrokerV2Attempt(after.add.grant)
        val restoreIssue = before.add != null && before.restore == null && after.add == before.add &&
            after.restore != null && after.restore == GmsDozeBrokerV2Attempt(after.restore.grant) && retiredAdd(before.add)
        val addChange = before.add != null && after.add != null && before.restore == after.restore &&
            attemptTransition(before.add, after.add, after.revision)
        val restoreChange = before.restore != null && after.restore != null && before.add == after.add &&
            attemptTransition(before.restore, after.restore, after.revision)
        require(addIssue || restoreIssue || addChange || restoreChange) { "Illegal V2 broker transition" }
    }

    internal fun retiredAdd(add: GmsDozeBrokerV2Attempt): Boolean = add.result != null &&
        add.releaseAcknowledged && add.actualDeathObserved && add.revokeVersion > 0 &&
        (add.acknowledgedRevoke == add.revokeVersion || add.actualDeathObserved)

    internal fun restoreMatches(add: GmsDozeBrokerV2Attempt, restore: GmsDozeBrokerV2Grant): Boolean {
        val original = add.grant
        return restore.operation == GmsGlobalDozeOperation.REMOVE && restore.grantId != original.grantId &&
            restore.globalTransactionId == original.globalTransactionId && restore.transactionEpoch == original.transactionEpoch &&
            restore.baselineSha256 == original.baselineSha256 && restore.appliedSha256 == add.result?.appliedSha256 &&
            restore.identity.gms == original.identity.gms && restore.identity.guardian == original.identity.guardian &&
            restore.identity.environment == original.identity.environment
    }

    private fun attemptTransition(before: GmsDozeBrokerV2Attempt, after: GmsDozeBrokerV2Attempt, revision: Long): Boolean {
        if (before.grant != after.grant) return false
        val pin = before.pin == null && before.revokeVersion == 0L && !before.actualDeathObserved && after.pin != null &&
            after == before.copy(pin = after.pin)
        val prep = before.pin != null && before.preparation == null && before.revokeVersion == 0L &&
            !before.actualDeathObserved && after.preparation?.preparationRevision == revision &&
            after == before.copy(preparation = after.preparation)
        val revoke = before.pin != null && before.revokeVersion < Long.MAX_VALUE &&
            after == before.copy(revokeVersion = before.revokeVersion + 1)
        val ack = before.pin != null && before.revokeVersion > 0 && before.acknowledgedRevoke != before.revokeVersion &&
            after == before.copy(acknowledgedRevoke = before.revokeVersion)
        val death = before.pin != null && !before.actualDeathObserved && after == before.copy(actualDeathObserved = true)
        val result = before.preparation != null && before.result == null && after.result != null &&
            after == before.copy(result = after.result)
        val release = before.result != null && !before.releaseAcknowledged && after == before.copy(releaseAcknowledged = true)
        return pin || prep || revoke || ack || death || result || release
    }

    private fun validateState(state: GmsDozeBrokerV2State) {
        require(state.revision >= 0)
        if (state.add == null) require(state == initialState())
        state.add?.let { validateAttempt(it, state.revision, GmsGlobalDozeOperation.ADD, 1) }
        state.restore?.let { restore ->
            validateAttempt(restore, state.revision, GmsGlobalDozeOperation.REMOVE, 2)
            val add = requireNotNull(state.add)
            require(retiredAdd(add) && restoreMatches(add, restore.grant)) { "REMOVE lacks retired matching ADD" }
            require(restore.pin == null || restore.pin.nonce != add.pin?.nonce) { "V2 nonce reused" }
        }
    }

    private fun validateAttempt(value: GmsDozeBrokerV2Attempt, revision: Long,
        operation: GmsGlobalDozeOperation, generation: Long) {
        val grant = value.grant
        require(revision > 0 && uuid.matches(grant.grantId) && uuid.matches(grant.globalTransactionId) &&
            grant.transactionEpoch > 0 && grant.operation == operation)
        GmsGlobalDozeCodec.validateIdentity(grant.identity, grant.identity.environment.fingerprint)
        require(grant.identity.environment.fingerprint.length <= 4096)
        require(digest.matches(grant.baselineSha256) && digest.matches(grant.businessIntentSha256))
        require(if (operation == GmsGlobalDozeOperation.ADD) grant.appliedSha256 == null
            else grant.appliedSha256 != null && digest.matches(grant.appliedSha256))
        require(value.revokeVersion in 0..revision)
        value.acknowledgedRevoke?.let { require(value.pin != null && it in 1..value.revokeVersion) }
        value.pin?.let { require(nonce.matches(it.nonce) && it.generation == generation) }
        require(value.pin != null || (value.revokeVersion == 0L && !value.actualDeathObserved && value.preparation == null))
        value.preparation?.let { prep ->
            require(prep.key == value.pin && prep.grantId == grant.grantId && prep.preparationRevision in 1..revision &&
                prep.revokeVersion == 0L)
        }
        value.result?.let { result ->
            require(value.preparation != null && result.preparation == value.preparation &&
                digest.matches(result.businessResultSha256) && digest.matches(result.appliedSha256))
            if (operation == GmsGlobalDozeOperation.REMOVE) require(result.appliedSha256 == grant.baselineSha256)
        }
        require(!value.releaseAcknowledged || value.result != null)
    }

    /** The raw value is not parsed, trimmed, reordered or canonicalized. */
    fun rawSha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value)
        return sha256(bytes)
    }
    fun sha256(bytes: ByteArray) = hash(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun writeAttempt(out: DataOutputStream, value: GmsDozeBrokerV2Attempt?) {
        out.writeBoolean(value != null)
        if (value == null) return
        val grant = value.grant
        writeString(out, grant.grantId)
        out.writeByte(if (grant.operation == GmsGlobalDozeOperation.ADD) 1 else 2)
        writeString(out, grant.globalTransactionId)
        out.writeLong(grant.transactionEpoch)
        writeIdentity(out, grant.identity)
        writeString(out, grant.baselineSha256)
        writeNullableString(out, grant.appliedSha256)
        writeString(out, grant.businessIntentSha256)
        out.writeBoolean(value.pin != null)
        value.pin?.let { writeKey(out, it) }
        out.writeLong(value.revokeVersion)
        out.writeBoolean(value.acknowledgedRevoke != null)
        value.acknowledgedRevoke?.let(out::writeLong)
        out.writeBoolean(value.actualDeathObserved)
        out.writeBoolean(value.preparation != null)
        value.preparation?.let { writePreparation(out, it) }
        out.writeBoolean(value.result != null)
        value.result?.let { writePreparation(out, it.preparation); writeString(out, it.businessResultSha256); writeString(out, it.appliedSha256) }
        out.writeBoolean(value.releaseAcknowledged)
    }

    private fun readAttempt(input: DataInputStream): GmsDozeBrokerV2Attempt? {
        if (!readBoolean(input)) return null
        val id = readString(input)
        val op = when (input.readUnsignedByte()) { 1 -> GmsGlobalDozeOperation.ADD; 2 -> GmsGlobalDozeOperation.REMOVE; else -> error("Unknown V2 operation") }
        val grant = GmsDozeBrokerV2Grant(id, op, readString(input), input.readLong(), readIdentity(input),
            readString(input), readNullableString(input), readString(input))
        val pin = if (readBoolean(input)) readKey(input) else null
        val version = input.readLong()
        val ack = if (readBoolean(input)) input.readLong() else null
        val death = readBoolean(input)
        val prep = if (readBoolean(input)) readPreparation(input) else null
        val result = if (readBoolean(input)) GmsDozeBrokerV2Result(readPreparation(input), readString(input), readString(input)) else null
        return GmsDozeBrokerV2Attempt(grant, pin, version, ack, death, prep, result, readBoolean(input))
    }

    private fun writeIdentity(out: DataOutputStream, identity: GmsGlobalDozeIdentity) {
        writeInstallation(out, identity.gms)
        out.writeInt(identity.guardian.userId)
        writeInstallation(out, identity.guardian.installation)
        writeString(out, identity.environment.fingerprint)
        out.writeInt(identity.environment.executionUid)
        out.writeInt(identity.environment.currentUserId)
        val owners = identity.owners as GmsGlobalOwnerEvidence.Qualified
        out.writeInt(owners.proofVersion)
        writeString(out, owners.mappingSha256)
        out.writeInt(owners.exclusiveGmsAppId)
    }
    private fun readIdentity(input: DataInputStream) = GmsGlobalDozeIdentity(readInstallation(input),
        GmsGlobalGuardianBinding(input.readInt(), readInstallation(input)),
        GmsGlobalEnvironment(readString(input), input.readInt(), input.readInt()),
        GmsGlobalOwnerEvidence.Qualified(input.readInt(), readString(input), input.readInt()))
    private fun writeInstallation(out: DataOutputStream, value: GmsGlobalInstallation) {
        out.writeInt(value.appId); out.writeLong(value.versionCode); out.writeLong(value.firstInstallTime); out.writeLong(value.lastUpdateTime)
        out.writeInt(value.signerSha256.size)
        value.signerSha256.forEach { writeString(out, it) }
    }
    private fun readInstallation(input: DataInputStream): GmsGlobalInstallation {
        val id = input.readInt(); val version = input.readLong(); val first = input.readLong(); val last = input.readLong()
        val count = input.readInt()
        require(count in 1..8)
        return GmsGlobalInstallation(id, version, first, last, Collections.unmodifiableList(List(count) { readString(input) }))
    }
    private fun writeKey(out: DataOutputStream, key: GmsDozeBrokerKey) { writeString(out, key.nonce); out.writeLong(key.generation) }
    private fun readKey(input: DataInputStream) = GmsDozeBrokerKey(readString(input), input.readLong())
    private fun writePreparation(out: DataOutputStream, prep: GmsDozeBrokerV2Preparation) {
        writeKey(out, prep.key); writeString(out, prep.grantId); out.writeLong(prep.preparationRevision); out.writeLong(prep.revokeVersion)
    }
    private fun readPreparation(input: DataInputStream) = GmsDozeBrokerV2Preparation(readKey(input), readString(input), input.readLong(), input.readLong())
    private fun writeNullableString(out: DataOutputStream, value: String?) { out.writeBoolean(value != null); value?.let { writeString(out, it) } }
    private fun readNullableString(input: DataInputStream) = if (readBoolean(input)) readString(input) else null
    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..16_384 && Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readString(input: DataInputStream): String {
        val count = input.readInt()
        require(count in 1..16_384 && count <= input.available())
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also {
            require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        }
    }
    private fun readBoolean(input: DataInputStream) = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Noncanonical V2 boolean") }
}
