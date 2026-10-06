package io.github.originosfcmguardian.core.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Independent finite schema. Checksums/shape never authenticate consent or actual executor facts. */
object GmsDozeCycleLedgerCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_CYCLES = 64
    private const val MAGIC = 0x4744434C
    private const val VERSION = 3
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val hashText = Regex("[0-9a-f]{64}")
    private val nonce = Regex("[A-Za-z0-9_-]{1,128}")

    fun encode(value: GmsDozeCycleLedger): ByteArray {
        validateLedger(value)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            string(out, value.ledgerId); out.writeLong(value.revision)
            out.writeBoolean(value.runningIntent); out.writeLong(value.runningGeneration)
            string(out, value.automaticConsent.consentId); out.writeLong(value.automaticConsent.confirmedAtMillis)
            out.writeInt(value.automaticConsent.version); out.writeByte(1)
            installation(out, value.binding.gms); out.writeInt(value.binding.guardian.userId)
            installation(out, value.binding.guardian.installation); environment(out, value.binding.environment)
            out.writeBoolean(value.latestReenable != null)
            value.latestReenable?.let { confirmation ->
                string(out, confirmation.confirmationId); out.writeLong(confirmation.confirmedAtMillis); out.writeInt(confirmation.version)
                string(out, confirmation.target.ledgerId); out.writeLong(confirmation.target.revision)
                string(out, confirmation.target.sha256); out.writeLong(confirmation.target.runningGeneration)
                out.writeLong(confirmation.toRunningGeneration)
            }
            out.writeBoolean(value.automaticSessionConsent != null)
            value.automaticSessionConsent?.let { consent ->
                string(out, consent.confirmationId); out.writeLong(consent.confirmedAtMillis); out.writeInt(consent.version)
                out.writeByte(1) // Fixed qualified-existing-host, ADD-only scope; no caller-selected operation.
                string(out, consent.target.ledgerId); out.writeLong(consent.target.revision)
                string(out, consent.target.sha256); out.writeLong(consent.target.runningGeneration)
            }
            out.writeInt(value.cycles.size)
            value.cycles.forEach { cycle(out, it) }
        }
        val body = buffer.toByteArray()
        return (body + digest(body)).also { require(it.size <= MAX_BYTES) { "Cycle ledger capacity reached" } }
    }

    fun decode(bytes: ByteArray): GmsDozeCycleLedger {
        require(bytes.size in 41..MAX_BYTES)
        val exact = bytes.clone()
        val body = exact.copyOfRange(0, exact.size - 32)
        require(MessageDigest.isEqual(exact.copyOfRange(body.size, exact.size), digest(body))) { "Cycle checksum invalid" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown cycle schema" }
        val id = string(input); val revision = input.readLong()
        val running = boolean(input); val generation = input.readLong()
        val consentId = string(input); val confirmed = input.readLong(); val consentVersion = input.readInt()
        require(input.readUnsignedByte() == 1) { "Unknown automatic consent source" }
        val consent = GmsDozeCycleConsent(consentId, confirmed, version = consentVersion)
        val binding = GmsDozeCycleBinding(installation(input),
            GmsGlobalGuardianBinding(input.readInt(), installation(input)), environment(input))
        val reenable = if (boolean(input)) {
            val confirmationId = string(input); val time = input.readLong(); val version = input.readInt()
            GmsDozeCycleReenableConfirmation(confirmationId, time,
                GmsDozeCycleOffTarget(string(input), input.readLong(), string(input), input.readLong()), input.readLong(), version)
        } else null
        val automatic = if (boolean(input)) {
            val confirmationId = string(input); val time = input.readLong(); val version = input.readInt()
            require(input.readUnsignedByte() == 1) { "Unknown automatic host scope" }
            GmsDozeCycleAutomaticConsent(confirmationId, time,
                GmsDozeCycleAutomaticTarget(string(input), input.readLong(), string(input), input.readLong()), version)
        } else null
        val size = input.readInt(); require(size in 0..MAX_CYCLES)
        val value = GmsDozeCycleLedger(id, revision, running, generation, consent, binding,
            Collections.unmodifiableList(List(size) { cycle(input) }), reenable, automatic)
        require(input.available() == 0) { "Trailing cycle bytes" }
        require(encode(value).contentEquals(exact)) { "Noncanonical cycle ledger" }
        return value
    }

    fun validate(bytes: ByteArray) { decode(bytes) }
    fun sha256(bytes: ByteArray) = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun validateTransition(expected: ByteArray?, replacement: ByteArray) {
        val after = decode(replacement)
        if (expected == null) {
            require(after.revision == 1L && after.runningIntent && after.runningGeneration == 1L && after.cycles.isEmpty() &&
                after.latestReenable == null && after.automaticSessionConsent == null) {
                "Initialization requires new explicit automatic consent without history"
            }
            return
        }
        val before = decode(expected)
        require(before.revision < Long.MAX_VALUE && after.revision == before.revision + 1)
        require(before.ledgerId == after.ledgerId && before.automaticConsent == after.automaticConsent && before.binding == after.binding)
        val next = before.copy(revision = after.revision)
        val off = before.runningIntent && before.runningGeneration < Long.MAX_VALUE &&
            after == next.copy(runningIntent = false, runningGeneration = before.runningGeneration + 1)
        val reenable = !before.runningIntent && before.runningGeneration < Long.MAX_VALUE && after.latestReenable != null &&
            after.latestReenable.target == GmsDozeCycleOffTarget(before.ledgerId, before.revision, sha256(expected), before.runningGeneration) &&
            after.latestReenable.confirmedAtMillis > newestEnableTime(before) &&
            after.latestReenable.confirmationId != before.latestReenable?.confirmationId &&
            cleanForReenable(before) && after == next.copy(runningIntent = true, runningGeneration = before.runningGeneration + 1,
                latestReenable = after.latestReenable)
        val automatic = before.runningIntent && cleanForReenable(before) && after.automaticSessionConsent != null &&
            after.automaticSessionConsent.target == GmsDozeCycleAutomaticTarget(before.ledgerId, before.revision, sha256(expected), before.runningGeneration) &&
            after.automaticSessionConsent.confirmedAtMillis > maxOf(newestEnableTime(before), before.automaticSessionConsent?.confirmedAtMillis ?: 0) &&
            after.automaticSessionConsent.confirmationId != before.automaticSessionConsent?.confirmationId &&
            after == next.copy(automaticSessionConsent = after.automaticSessionConsent)
        val appended = after.cycles.size == before.cycles.size + 1 && before.cycles.size < MAX_CYCLES &&
            after.cycles.dropLast(1) == before.cycles && before.runningIntent &&
            (before.cycles.isEmpty() || terminal(before.cycles.last())) &&
            after == next.copy(cycles = after.cycles) && after.cycles.last().let {
                it.phase == GmsDozeCyclePhase.ADD_INTENT && it.add == GmsDozeCycleAttempt(it.add.grant)
            }
        val advanced = before.cycles.isNotEmpty() && after.cycles.size == before.cycles.size &&
            before.cycles.dropLast(1) == after.cycles.dropLast(1) && after == next.copy(cycles = after.cycles) &&
            cycleTransition(before.cycles.last(), after.cycles.last(), before.runningIntent, newestEnableTime(before))
        require(off || reenable || automatic || appended || advanced) { "Illegal cycle ledger transition" }
    }

    internal fun settled(attempt: GmsDozeCycleAttempt) = attempt.result != null && attempt.releaseAck != null && attempt.closure != null
    internal fun terminal(cycle: GmsDozeCycleRecord) = cycle.phase == GmsDozeCyclePhase.RESTORED || cycle.phase == GmsDozeCyclePhase.OBSERVED_RESTORED
    internal fun phase(cycle: GmsDozeCycleRecord): GmsDozeCyclePhase = when {
        cycle.remove != null -> if (settled(cycle.remove)) GmsDozeCyclePhase.RESTORED
            else if (cycle.remove.result != null) GmsDozeCyclePhase.RESTORED_PENDING else GmsDozeCyclePhase.RESTORE_INTENT
        settled(cycle.add) -> GmsDozeCyclePhase.APPLIED_SETTLED
        cycle.add.result != null -> GmsDozeCyclePhase.APPLIED_PENDING
        else -> GmsDozeCyclePhase.ADD_INTENT
    }

    internal fun newestEnableTime(ledger: GmsDozeCycleLedger) = ledger.latestReenable?.confirmedAtMillis ?: ledger.automaticConsent.confirmedAtMillis
    internal fun cleanForReenable(ledger: GmsDozeCycleLedger) = ledger.cycles.all { cycle ->
        settled(cycle.add) && (cycle.remove == null || settled(cycle.remove)) &&
            (terminal(cycle) || cycle.phase == GmsDozeCyclePhase.APPLIED_SETTLED)
    }

    private fun cycleTransition(before: GmsDozeCycleRecord, after: GmsDozeCycleRecord, running: Boolean, enableTime: Long): Boolean {
        if (terminal(before)) return false
        if (before.phase == GmsDozeCyclePhase.APPLIED_SETTLED &&
            after == before.copy(phase = GmsDozeCyclePhase.OBSERVED_RESTORED)) return true
        if (!running && before.phase == GmsDozeCyclePhase.APPLIED_SETTLED && after.remove != null &&
            after.restoreConsent != null && after.restoreConsent.confirmedAtMillis > enableTime && after.remove == GmsDozeCycleAttempt(after.remove.grant) &&
            after == before.copy(remove = after.remove, restoreConsent = after.restoreConsent, phase = GmsDozeCyclePhase.RESTORE_INTENT)) return true
        if (before.remove == null && after.remove == null && before.restoreConsent == after.restoreConsent &&
            attemptTransition(before.add, after.add)) {
            val applied = after.add.result?.rawWhitelist
            return (before.applied == null || before.applied == applied) &&
                after == before.copy(add = after.add, applied = applied, phase = phase(after))
        }
        return before.remove != null && after.remove != null && attemptTransition(before.remove, after.remove) &&
            after == before.copy(remove = after.remove, phase = phase(after))
    }

    private fun attemptTransition(before: GmsDozeCycleAttempt, after: GmsDozeCycleAttempt): Boolean {
        if (before.grant != after.grant || before.closure != null && after.closure != before.closure) return false
        val start = !before.startIntent && before.requestedRevoke == 0L && before.closure == null &&
            after == before.copy(startIntent = true)
        val result = before.startIntent && before.result == null && after.result != null && after == before.copy(result = after.result)
        val release = before.result != null && before.releaseAck == null && after.releaseAck != null && after == before.copy(releaseAck = after.releaseAck)
        val revoke = before.closure == null && before.requestedRevoke < Long.MAX_VALUE && after == before.copy(requestedRevoke = before.requestedRevoke + 1)
        val closure = before.closure == null && after.closure != null && after == before.copy(closure = after.closure)
        return start || result || release || revoke || closure
    }

    private fun validateLedger(value: GmsDozeCycleLedger) {
        require(uuid.matches(value.ledgerId) && value.revision > 0 && value.runningGeneration in 1..value.revision)
        require(uuid.matches(value.automaticConsent.consentId) && value.automaticConsent.confirmedAtMillis > 0 &&
            value.automaticConsent.version == 1 && value.automaticConsent.source == GmsDozeCycleConsentSource.EXPLICIT_AUTOMATIC_V1)
        val binding = value.binding
        GmsGlobalDozeCodec.validateInstallation(binding.gms); GmsGlobalDozeCodec.validateInstallation(binding.guardian.installation)
        require(binding.guardian.userId == 0 && binding.gms.appId != binding.guardian.installation.appId &&
            binding.environment.executionUid == 2000 && binding.environment.currentUserId == 0 &&
            binding.environment.fingerprint.isNotBlank() && binding.environment.fingerprint.length <= 4096)
        require(value.cycles.size <= MAX_CYCLES)
        val ids = mutableSetOf(value.ledgerId, value.automaticConsent.consentId)
        require(ids.size == 2)
        value.latestReenable?.let { confirmation ->
            val target = confirmation.target
            require(confirmation.version == 1 && uuid.matches(confirmation.confirmationId) && ids.add(confirmation.confirmationId) &&
                confirmation.confirmedAtMillis > value.automaticConsent.confirmedAtMillis && target.ledgerId == value.ledgerId &&
                target.revision in 1 until value.revision && hashText.matches(target.sha256) && target.runningGeneration in 2 until Long.MAX_VALUE &&
                confirmation.toRunningGeneration == target.runningGeneration + 1 &&
                value.runningGeneration == confirmation.toRunningGeneration + (if (value.runningIntent) 0 else 1))
        } ?: require(value.runningGeneration == if (value.runningIntent) 1L else 2L)
        value.automaticSessionConsent?.let { consent ->
            val target = consent.target
            require(consent.version == 1 && uuid.matches(consent.confirmationId) && ids.add(consent.confirmationId) &&
                consent.confirmedAtMillis > value.automaticConsent.confirmedAtMillis && target.ledgerId == value.ledgerId &&
                target.revision in 1 until value.revision && hashText.matches(target.sha256) &&
                target.runningGeneration in 1..value.runningGeneration)
            // Historical consent remains decodable after OFF/re-enable; current-generation
            // applicability and new-confirmation freshness are checked separately, not rewritten.
        }
        val nonces = mutableSetOf<String>()
        var grantGeneration = 0L
        value.cycles.forEachIndexed { index, cycle ->
            require(uuid.matches(cycle.cycleId) && ids.add(cycle.cycleId) && uuid.matches(cycle.transactionId) && ids.add(cycle.transactionId))
            require(cycle.transactionEpoch == index.toLong() + 1 && (index == value.cycles.lastIndex || terminal(cycle)))
            val baseline = StrictWhitelist.parse(cycle.baseline)
            require(baseline.none { it.packageName == GMS_GLOBAL_DOZE_PACKAGE })
            cycle.applied?.let { require(StrictWhitelist.parse(it) == baseline + Membership(MembershipSource.USER, GMS_GLOBAL_DOZE_PACKAGE, binding.gms.appId)) }
            fun attempt(attempt: GmsDozeCycleAttempt, operation: GmsGlobalDozeOperation) {
                grantGeneration++
                val grant = attempt.grant; val ref = grant.ref
                require(uuid.matches(ref.grantId) && ids.add(ref.grantId) && ref.ledgerId == value.ledgerId &&
                    ref.cycleId == cycle.cycleId && ref.transactionId == cycle.transactionId && ref.transactionEpoch == cycle.transactionEpoch && ref.operation == operation)
                require(nonce.matches(ref.key.nonce) && nonces.add(ref.key.nonce) && ref.key.generation == grantGeneration)
                GmsGlobalDozeCodec.validateIdentity(grant.identity, binding.environment.fingerprint)
                require(grant.identity.gms == binding.gms && grant.identity.guardian == binding.guardian && grant.identity.environment == binding.environment)
                require(hashText.matches(grant.baselineSha256) && grant.baselineSha256 == GmsDozeBrokerV2Codec.rawSha256(cycle.baseline))
                require(if (operation == GmsGlobalDozeOperation.ADD) grant.appliedSha256 == null
                    else cycle.applied != null && grant.appliedSha256 == GmsDozeBrokerV2Codec.rawSha256(cycle.applied))
                require(attempt.requestedRevoke in 0..value.revision)
                attempt.result?.let { result ->
                    require(attempt.startIntent && result.ref == ref && result.cleanup.ref == ref && result.cleanup.complete() &&
                        result.exitCode == 0 && result.commandOutputValid && result.identity == grant.identity)
                    if (operation == GmsGlobalDozeOperation.ADD) require(result.rawWhitelist == cycle.applied)
                    else require(result.rawWhitelist == cycle.baseline)
                }
                attempt.releaseAck?.let { require(attempt.result != null && it.ref == ref) }
                attempt.closure?.let { closure ->
                    require(closure.ref == ref && closure.revokeVersion >= 0)
                    if (closure.kind == GmsDozeCycleClosureKind.REVOKE_ACK)
                        require(attempt.requestedRevoke > 0 && closure.revokeVersion == attempt.requestedRevoke)
                    else require(closure.revokeVersion == attempt.requestedRevoke)
                }
            }
            attempt(cycle.add, GmsGlobalDozeOperation.ADD)
            cycle.remove?.let { attempt(it, GmsGlobalDozeOperation.REMOVE) }
            cycle.restoreConsent?.let { consent ->
                require(cycle.remove != null && uuid.matches(consent.consentId) && ids.add(consent.consentId) &&
                    consent.ledgerId == value.ledgerId && consent.cycleId == cycle.cycleId && consent.transactionId == cycle.transactionId &&
                    consent.transactionEpoch == cycle.transactionEpoch && consent.confirmedAtMillis > value.automaticConsent.confirmedAtMillis)
            }
            require((cycle.remove == null) == (cycle.restoreConsent == null))
            require(if (cycle.phase == GmsDozeCyclePhase.OBSERVED_RESTORED)
                settled(cycle.add) && cycle.remove == null && cycle.applied != null else cycle.phase == phase(cycle))
            require((cycle.add.result == null) == (cycle.applied == null))
            if (cycle.remove != null) require(settled(cycle.add))
        }
    }

    private fun cycle(out: DataOutputStream, value: GmsDozeCycleRecord) {
        string(out, value.cycleId); string(out, value.transactionId); out.writeLong(value.transactionEpoch)
        string(out, value.baseline); nullable(out, value.applied); out.writeByte(value.phase.ordinal + 1)
        attempt(out, value.add); out.writeBoolean(value.restoreConsent != null)
        value.restoreConsent?.let { string(out, it.consentId); string(out, it.ledgerId); string(out, it.cycleId); string(out, it.transactionId); out.writeLong(it.transactionEpoch); out.writeLong(it.confirmedAtMillis) }
        out.writeBoolean(value.remove != null); value.remove?.let { attempt(out, it) }
    }
    private fun cycle(input: DataInputStream): GmsDozeCycleRecord {
        val id = string(input); val tx = string(input); val epoch = input.readLong(); val baseline = string(input); val applied = nullable(input)
        val phase = GmsDozeCyclePhase.entries.getOrNull(input.readUnsignedByte() - 1) ?: error("Unknown cycle phase")
        val add = attempt(input)
        val consent = if (boolean(input)) GmsDozeCycleRestoreConsent(string(input), string(input), string(input), string(input), input.readLong(), input.readLong()) else null
        val remove = if (boolean(input)) attempt(input) else null
        return GmsDozeCycleRecord(id, tx, epoch, baseline, applied, phase, add, consent, remove)
    }
    private fun attempt(out: DataOutputStream, value: GmsDozeCycleAttempt) {
        ref(out, value.grant.ref); identity(out, value.grant.identity); string(out, value.grant.baselineSha256); nullable(out, value.grant.appliedSha256)
        out.writeBoolean(value.startIntent); out.writeBoolean(value.result != null)
        value.result?.let { result ->
            ref(out, result.ref); ref(out, result.cleanup.ref)
            out.writeBoolean(result.cleanup.childExited); out.writeBoolean(result.cleanup.stdoutEofAndDrained); out.writeBoolean(result.cleanup.stderrEofAndDrained)
            out.writeBoolean(result.cleanup.collectorsTerminated); out.writeBoolean(result.cleanup.handlesClosed)
            out.writeInt(result.exitCode); out.writeBoolean(result.commandOutputValid); identity(out, result.identity); string(out, result.rawWhitelist)
        }
        out.writeBoolean(value.releaseAck != null); value.releaseAck?.let { ref(out, it.ref) }
        out.writeLong(value.requestedRevoke); out.writeBoolean(value.closure != null)
        value.closure?.let { ref(out, it.ref); out.writeByte(it.kind.ordinal + 1); out.writeLong(it.revokeVersion) }
    }
    private fun attempt(input: DataInputStream): GmsDozeCycleAttempt {
        val grant = GmsDozeCycleGrant(ref(input), identity(input), string(input), nullable(input)); val start = boolean(input)
        val result = if (boolean(input)) {
            val reference = ref(input); val cleanup = GmsDozeCycleCleanup(ref(input), boolean(input), boolean(input), boolean(input), boolean(input), boolean(input))
            GmsDozeCycleResult(reference, cleanup, input.readInt(), boolean(input), identity(input), string(input))
        } else null
        val release = if (boolean(input)) GmsDozeCycleReleaseAck(ref(input)) else null; val version = input.readLong()
        val closure = if (boolean(input)) {
            val reference = ref(input); val kind = GmsDozeCycleClosureKind.entries.getOrNull(input.readUnsignedByte() - 1) ?: error("Unknown closure")
            GmsDozeCycleClosure(reference, kind, input.readLong())
        } else null
        return GmsDozeCycleAttempt(grant, start, result, release, version, closure)
    }
    private fun ref(out: DataOutputStream, value: GmsDozeCycleAttemptRef) {
        string(out, value.ledgerId); string(out, value.cycleId); string(out, value.transactionId); out.writeLong(value.transactionEpoch)
        string(out, value.grantId); out.writeByte(if (value.operation == GmsGlobalDozeOperation.ADD) 1 else 2)
        string(out, value.key.nonce); out.writeLong(value.key.generation)
    }
    private fun ref(input: DataInputStream): GmsDozeCycleAttemptRef {
        val ledger = string(input); val cycle = string(input); val tx = string(input); val epoch = input.readLong(); val grant = string(input)
        val operation = when (input.readUnsignedByte()) { 1 -> GmsGlobalDozeOperation.ADD; 2 -> GmsGlobalDozeOperation.REMOVE; else -> error("Unknown cycle operation") }
        return GmsDozeCycleAttemptRef(ledger, cycle, tx, epoch, grant, operation, GmsDozeBrokerKey(string(input), input.readLong()))
    }
    private fun identity(out: DataOutputStream, value: GmsGlobalDozeIdentity) {
        installation(out, value.gms); out.writeInt(value.guardian.userId); installation(out, value.guardian.installation); environment(out, value.environment)
        val owners = value.owners as GmsGlobalOwnerEvidence.Qualified
        out.writeInt(owners.proofVersion); string(out, owners.mappingSha256); out.writeInt(owners.exclusiveGmsAppId)
    }
    private fun identity(input: DataInputStream) = GmsGlobalDozeIdentity(installation(input), GmsGlobalGuardianBinding(input.readInt(), installation(input)),
        environment(input), GmsGlobalOwnerEvidence.Qualified(input.readInt(), string(input), input.readInt()))
    private fun environment(out: DataOutputStream, value: GmsGlobalEnvironment) { string(out, value.fingerprint); out.writeInt(value.executionUid); out.writeInt(value.currentUserId) }
    private fun environment(input: DataInputStream) = GmsGlobalEnvironment(string(input), input.readInt(), input.readInt())
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
        require(bytes.size in 1..StrictWhitelist.MAX_RAW_BYTES && Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() == value)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun string(input: DataInputStream): String {
        val size = input.readInt(); require(size in 1..StrictWhitelist.MAX_RAW_BYTES && size <= input.available())
        val bytes = ByteArray(size); input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
    private fun boolean(input: DataInputStream) = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Noncanonical cycle boolean") }
}
