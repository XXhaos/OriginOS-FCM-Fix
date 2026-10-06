package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Fixed current-attempt transport schema; old ledgers/V2 tickets never decode as this protocol. */
object GmsDozeCycleTicketCodec {
    const val MAX_BYTES = 32 * 1024
    private const val MAGIC = 0x47444354
    private const val VERSION = 1
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val sha = Regex("[0-9a-f]{64}")
    private val nonce = Regex("[A-Za-z0-9_-]{1,128}")

    /** Caller verifies actual commit/readback; this factory cannot read disk or authenticate it. */
    fun fromCommittedLedger(exactLedgerBytes: ByteArray, ref: GmsDozeCycleAttemptRef): GmsDozeCycleTicket {
        val exact = exactLedgerBytes.clone()
        val ledger = GmsDozeCycleLedgerCodec.decode(exact)
        val cycle = ledger.cycles.lastOrNull() ?: error("Current cycle absent")
        val attempt = cycle.remove ?: cycle.add
        require(attempt.grant.ref == ref) { "Only exact current attempt may be ticketed" }
        val result = attempt.result?.let {
            GmsDozeCycleTicketResult(it.ref, it.cleanup, it.exitCode, it.commandOutputValid, it.identity,
                GmsDozeBrokerV2Codec.rawSha256(it.rawWhitelist))
        }
        val ticket = GmsDozeCycleTicket(ledger.ledgerId, ledger.revision, GmsDozeCycleLedgerCodec.sha256(exact),
            attempt.grant, ledger.runningIntent, ledger.runningGeneration, attempt.startIntent, attempt.requestedRevoke,
            if (ref.operation == GmsGlobalDozeOperation.REMOVE) cycle.restoreConsent else null, result, attempt.releaseAck, attempt.closure)
        return decode(encode(ticket))
    }

    fun encode(value: GmsDozeCycleTicket): ByteArray {
        validateTicket(value)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            string(out, value.ledgerId); out.writeLong(value.ledgerRevision); string(out, value.ledgerSha256)
            ref(out, value.ref); identity(out, value.grant.identity); string(out, value.grant.baselineSha256); nullable(out, value.grant.appliedSha256)
            out.writeBoolean(value.runningIntent); out.writeLong(value.runningGeneration); out.writeBoolean(value.startIntent); out.writeLong(value.requestedRevoke)
            out.writeBoolean(value.restoreConsent != null)
            value.restoreConsent?.let { consent ->
                string(out, consent.consentId); string(out, consent.ledgerId); string(out, consent.cycleId); string(out, consent.transactionId)
                out.writeLong(consent.transactionEpoch); out.writeLong(consent.confirmedAtMillis)
            }
            out.writeBoolean(value.resultSummary != null)
            value.resultSummary?.let { result ->
                ref(out, result.ref); cleanup(out, result.cleanup); out.writeInt(result.exitCode); out.writeBoolean(result.commandOutputValid)
                identity(out, result.identity); string(out, result.rawWhitelistSha256)
            }
            out.writeBoolean(value.releaseAck != null); value.releaseAck?.let { ref(out, it.ref) }
            out.writeBoolean(value.closure != null)
            value.closure?.let { ref(out, it.ref); out.writeByte(it.kind.ordinal + 1); out.writeLong(it.revokeVersion) }
        }
        val body = buffer.toByteArray()
        return (body + hash(body)).also { require(it.size <= MAX_BYTES) { "Cycle ticket oversized" } }
    }

    fun decode(bytes: ByteArray): GmsDozeCycleTicket {
        require(bytes.size in 41..MAX_BYTES)
        val exact = bytes.clone(); val body = exact.copyOfRange(0, exact.size - 32)
        require(MessageDigest.isEqual(exact.copyOfRange(body.size, exact.size), hash(body))) { "Cycle ticket checksum invalid" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown cycle ticket schema" }
        val ledgerId = string(input); val revision = input.readLong(); val ledgerHash = string(input)
        val grant = GmsDozeCycleGrant(ref(input), identity(input), string(input), nullable(input))
        val running = boolean(input); val generation = input.readLong(); val start = boolean(input); val revoke = input.readLong()
        val consent = if (boolean(input)) GmsDozeCycleRestoreConsent(string(input), string(input), string(input), string(input), input.readLong(), input.readLong()) else null
        val result = if (boolean(input)) GmsDozeCycleTicketResult(ref(input), cleanup(input), input.readInt(), boolean(input), identity(input), string(input)) else null
        val release = if (boolean(input)) GmsDozeCycleReleaseAck(ref(input)) else null
        val closure = if (boolean(input)) {
            val reference = ref(input); val kind = GmsDozeCycleClosureKind.entries.getOrNull(input.readUnsignedByte() - 1) ?: error("Unknown cycle closure")
            GmsDozeCycleClosure(reference, kind, input.readLong())
        } else null
        val value = GmsDozeCycleTicket(ledgerId, revision, ledgerHash, grant, running, generation, start, revoke, consent, result, release, closure)
        require(input.available() == 0 && encode(value).contentEquals(exact)) { "Noncanonical cycle ticket" }
        return value
    }

    fun validate(bytes: ByteArray) { decode(bytes) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun validateTicket(value: GmsDozeCycleTicket) {
        val ref = value.ref
        require(uuid.matches(value.ledgerId) && ref.ledgerId == value.ledgerId && value.ledgerRevision > 0 &&
            sha.matches(value.ledgerSha256) && value.runningGeneration in 1..value.ledgerRevision && value.requestedRevoke in 0..value.ledgerRevision)
        require(listOf(ref.ledgerId, ref.cycleId, ref.transactionId, ref.grantId).let { ids -> ids.all(uuid::matches) && ids.distinct().size == 4 })
        require(ref.transactionEpoch in 1..value.ledgerRevision && ref.key.generation in 1..value.ledgerRevision && nonce.matches(ref.key.nonce))
        GmsGlobalDozeCodec.validateIdentity(value.grant.identity, value.grant.identity.environment.fingerprint)
        require(value.grant.identity.environment.fingerprint.length <= 4096 && sha.matches(value.grant.baselineSha256))
        if (ref.operation == GmsGlobalDozeOperation.ADD) {
            require(value.grant.appliedSha256 == null && value.restoreConsent == null)
        } else {
            require(value.grant.appliedSha256 != null && sha.matches(value.grant.appliedSha256))
            val consent = requireNotNull(value.restoreConsent)
            require(uuid.matches(consent.consentId) && consent.consentId !in listOf(ref.ledgerId, ref.cycleId, ref.transactionId, ref.grantId) &&
                consent.ledgerId == ref.ledgerId && consent.cycleId == ref.cycleId && consent.transactionId == ref.transactionId &&
                consent.transactionEpoch == ref.transactionEpoch && consent.confirmedAtMillis > 0)
        }
        value.resultSummary?.let { result ->
            require(value.startIntent && result.ref == ref && result.cleanup.ref == ref && result.cleanup.complete() &&
                result.exitCode == 0 && result.commandOutputValid && result.identity == value.grant.identity && sha.matches(result.rawWhitelistSha256))
            if (ref.operation == GmsGlobalDozeOperation.REMOVE) require(result.rawWhitelistSha256 == value.grant.baselineSha256)
        }
        value.releaseAck?.let { require(value.resultSummary != null && it.ref == ref) }
        value.closure?.let { closure ->
            require(closure.ref == ref && closure.revokeVersion == value.requestedRevoke)
            if (closure.kind == GmsDozeCycleClosureKind.REVOKE_ACK) require(value.requestedRevoke > 0)
        }
    }

    private fun ref(out: DataOutputStream, value: GmsDozeCycleAttemptRef) {
        string(out, value.ledgerId); string(out, value.cycleId); string(out, value.transactionId); out.writeLong(value.transactionEpoch)
        string(out, value.grantId); out.writeByte(if (value.operation == GmsGlobalDozeOperation.ADD) 1 else 2); string(out, value.key.nonce); out.writeLong(value.key.generation)
    }
    private fun ref(input: DataInputStream): GmsDozeCycleAttemptRef {
        val ledger = string(input); val cycle = string(input); val tx = string(input); val epoch = input.readLong(); val grant = string(input)
        val operation = when (input.readUnsignedByte()) { 1 -> GmsGlobalDozeOperation.ADD; 2 -> GmsGlobalDozeOperation.REMOVE; else -> error("Unknown cycle operation") }
        return GmsDozeCycleAttemptRef(ledger, cycle, tx, epoch, grant, operation, GmsDozeBrokerKey(string(input), input.readLong()))
    }
    private fun cleanup(out: DataOutputStream, value: GmsDozeCycleCleanup) {
        ref(out, value.ref); out.writeBoolean(value.childExited); out.writeBoolean(value.stdoutEofAndDrained); out.writeBoolean(value.stderrEofAndDrained)
        out.writeBoolean(value.collectorsTerminated); out.writeBoolean(value.handlesClosed)
    }
    private fun cleanup(input: DataInputStream) = GmsDozeCycleCleanup(ref(input), boolean(input), boolean(input), boolean(input), boolean(input), boolean(input))
    private fun identity(out: DataOutputStream, value: GmsGlobalDozeIdentity) {
        installation(out, value.gms); out.writeInt(value.guardian.userId); installation(out, value.guardian.installation)
        string(out, value.environment.fingerprint); out.writeInt(value.environment.executionUid); out.writeInt(value.environment.currentUserId)
        val owners = value.owners as GmsGlobalOwnerEvidence.Qualified
        out.writeInt(owners.proofVersion); string(out, owners.mappingSha256); out.writeInt(owners.exclusiveGmsAppId)
    }
    private fun identity(input: DataInputStream) = GmsGlobalDozeIdentity(installation(input), GmsGlobalGuardianBinding(input.readInt(), installation(input)),
        GmsGlobalEnvironment(string(input), input.readInt(), input.readInt()), GmsGlobalOwnerEvidence.Qualified(input.readInt(), string(input), input.readInt()))
    private fun installation(out: DataOutputStream, value: GmsGlobalInstallation) {
        out.writeInt(value.appId); out.writeLong(value.versionCode); out.writeLong(value.firstInstallTime); out.writeLong(value.lastUpdateTime)
        out.writeInt(value.signerSha256.size); value.signerSha256.forEach { string(out, it) }
    }
    private fun installation(input: DataInputStream): GmsGlobalInstallation {
        val id = input.readInt(); val version = input.readLong(); val first = input.readLong(); val last = input.readLong(); val size = input.readInt(); require(size in 1..8)
        return GmsGlobalInstallation(id, version, first, last, Collections.unmodifiableList(List(size) { string(input) }))
    }
    private fun nullable(out: DataOutputStream, value: String?) { out.writeBoolean(value != null); value?.let { string(out, it) } }
    private fun nullable(input: DataInputStream) = if (boolean(input)) string(input) else null
    private fun string(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..16_384 && Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun string(input: DataInputStream): String {
        val size = input.readInt(); require(size in 1..16_384 && size <= input.available())
        val bytes = ByteArray(size); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
    private fun boolean(input: DataInputStream) = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Noncanonical ticket boolean") }
}
