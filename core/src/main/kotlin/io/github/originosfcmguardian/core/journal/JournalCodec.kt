package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpCheck
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.AppOpScope
import io.github.originosfcmguardian.core.AppOpState
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.DozeMembership
import io.github.originosfcmguardian.core.DozeSource
import io.github.originosfcmguardian.core.DozeState
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.FcmHealthConclusion
import io.github.originosfcmguardian.core.GmsPackageState
import io.github.originosfcmguardian.core.OverrideProvenance
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.PemState
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.SafeCommandFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class CodecFailure {
    BAD_MAGIC,
    UNKNOWN_VERSION,
    TRUNCATED,
    SIZE_LIMIT,
    HASH_MISMATCH,
    MALFORMED,
}

class JournalCodecException(
    val failure: CodecFailure,
    cause: Throwable? = null,
) : IllegalArgumentException("Journal codec failure: $failure", cause)

/** Strict, length-bounded binary codec. It never invokes Java object deserialization. */
object JournalCodec {
    const val MAX_ENCODED_BYTES: Int = 512 * 1024

    private const val MAGIC = 0x4f46434a // OFCJ
    private const val ENVELOPE_VERSION = 1
    private const val HASH_BYTES = 32
    private const val MAX_PAYLOAD_BYTES = MAX_ENCODED_BYTES - 44
    private const val MAX_STRING_BYTES = 128 * 1024
    private const val MAX_COLLECTION_ITEMS = 512

    fun encode(document: JournalDocument): ByteArray {
        validatePersistable(document)
        val payload = BoundedByteArrayOutputStream(MAX_PAYLOAD_BYTES).use { bytes ->
            DataOutputStream(bytes).use { out -> Writer(out).writeDocument(document) }
            bytes.toByteArray()
        }
        if (payload.size > MAX_PAYLOAD_BYTES) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
        val digest = sha256(payload)
        return ByteArrayOutputStream(payload.size + 44).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(ENVELOPE_VERSION)
                out.writeInt(payload.size)
                out.write(payload)
                out.write(digest)
            }
            bytes.toByteArray()
        }
    }

    fun decode(encoded: ByteArray): JournalDocument {
        if (encoded.size > MAX_PAYLOAD_BYTES + 44) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
        if (encoded.size < 44) throw JournalCodecException(CodecFailure.TRUNCATED)
        val header = ByteBuffer.wrap(encoded, 0, 12)
        if (header.int != MAGIC) throw JournalCodecException(CodecFailure.BAD_MAGIC)
        if (header.int != ENVELOPE_VERSION) throw JournalCodecException(CodecFailure.UNKNOWN_VERSION)
        val payloadSize = header.int
        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_BYTES) {
            throw JournalCodecException(CodecFailure.SIZE_LIMIT)
        }
        val expectedSize = 12L + payloadSize + HASH_BYTES
        if (encoded.size.toLong() < expectedSize) throw JournalCodecException(CodecFailure.TRUNCATED)
        if (encoded.size.toLong() != expectedSize) throw JournalCodecException(CodecFailure.MALFORMED)
        val payload = encoded.copyOfRange(12, 12 + payloadSize)
        val actualHash = encoded.copyOfRange(12 + payloadSize, encoded.size)
        if (!MessageDigest.isEqual(actualHash, sha256(payload))) {
            throw JournalCodecException(CodecFailure.HASH_MISMATCH)
        }
        return try {
            val input = DataInputStream(ByteArrayInputStream(payload))
            val document = Reader(input).readDocument()
            if (input.available() != 0) throw JournalCodecException(CodecFailure.MALFORMED)
            validatePersistable(document)
            document
        } catch (error: JournalCodecException) {
            throw error
        } catch (error: EOFException) {
            throw JournalCodecException(CodecFailure.TRUNCATED, error)
        } catch (error: RuntimeException) {
            throw JournalCodecException(CodecFailure.MALFORMED, error)
        }
    }

    private fun validatePersistable(document: JournalDocument) {
        if (document.schemaVersion != JOURNAL_SCHEMA_VERSION ||
            document.baseline.schemaVersion != BASELINE_SCHEMA_VERSION
        ) {
            throw JournalCodecException(CodecFailure.UNKNOWN_VERSION)
        }
        if (!JournalSemanticValidator.validatePersistable(document).isValid) {
            throw JournalCodecException(CodecFailure.MALFORMED)
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private class BoundedByteArrayOutputStream(private val maximum: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length < 0 || offset < 0 || offset + length > bytes.size) {
                throw IndexOutOfBoundsException()
            }
            ensureCapacityFor(length)
            super.write(bytes, offset, length)
        }

        private fun ensureCapacityFor(additional: Int) {
            if (additional > maximum - size()) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
        }
    }

    private class Writer(private val out: DataOutputStream) {
        fun writeDocument(value: JournalDocument) {
            out.writeInt(value.schemaVersion)
            writeBaseline(value.baseline)
            list(value.operations) { writeOperation(it) }
        }

        private fun writeBaseline(value: BaselineSnapshot) {
            out.writeInt(value.schemaVersion)
            writeMetadata(value.metadata)
            writeReport(value.report)
            list(value.commandEvidence) { writeEvidence(it) }
            out.writeBoolean(value.evidenceOverflowed)
        }

        private fun writeMetadata(value: CaptureMetadata) {
            out.writeLong(value.capturedAtEpochMillis)
            enum(value.captureSource)
            nullableString(value.buildFingerprint)
            enum(value.buildFingerprintSource)
            nullableString(value.androidApiFingerprint)
            nullableString(value.buildId)
            nullableString(value.buildDisplay)
            nullableInt(value.sdkInt)
            enum(value.buildFieldsSource)
            nullableInt(value.userId)
            enum(value.userIdSource)
            nullableInt(value.executorUid)
            enum(value.executorUidSource)
            nullableInt(value.gmsAppId)
        }

        private fun writeEvidence(value: RecordedCommandResult) {
            string(value.operationId)
            nullableInt(value.uidParameter)
            out.writeInt(value.exitCode)
            string(value.stdout)
            string(value.stderr)
            out.writeBoolean(value.timedOut)
            out.writeBoolean(value.outputTruncated)
        }

        private fun writeReport(value: DetectionReport) {
            enum(value.privilegedAccess)
            nullableInt(value.currentUser)
            nullableString(value.buildFingerprint)
            writeGms(value.gms)
            val entries = value.appOps.entries.sortedBy { it.key.ordinal }
            list(entries) { (operation, check) ->
                enum(operation)
                writeAppOpCheck(check)
            }
            writeDoze(value.doze)
            writePem(value.pem)
            enum(value.fcmHealth)
            strings(value.problems)
        }

        private fun writeGms(value: GmsPackageState) {
            enum(value.presence)
            enum(value.enabled)
            nullableInt(value.appId)
            string(value.rawInstalledOutput)
            string(value.rawEnabledOutput)
            strings(value.problems)
        }

        private fun writeAppOpCheck(value: AppOpCheck) {
            enum(value.operation)
            writeAppOpState(value.packageState)
            writeAppOpState(value.uidState)
            enum(value.status)
            strings(value.problems)
        }

        private fun writeAppOpState(value: AppOpState) {
            writeScope(value.scope)
            enum(value.operation)
            enum(value.operationRecordPresence)
            nullableEnum(value.reportedMode)
            nullableEnum(value.reportedDefault)
            enum(value.overrideProvenance)
            string(value.rawOutput)
            strings(value.problems)
        }

        private fun writeScope(value: AppOpScope) = when (value) {
            is AppOpScope.Package -> {
                out.writeByte(1)
                string(value.packageName)
                out.writeInt(value.userId)
            }
            is AppOpScope.Uid -> {
                out.writeByte(2)
                out.writeInt(value.appId)
                out.writeInt(value.userId)
            }
        }

        private fun writeDoze(value: DozeState) {
            val memberships = value.memberships.sortedWith(
                compareBy<DozeMembership>({ it.source.ordinal }, { it.packageName }, { it.appId }),
            )
            list(memberships) { writeMembership(it) }
            string(value.rawOutput)
            enum(value.status)
            strings(value.problems)
        }

        private fun writeMembership(value: DozeMembership) {
            enum(value.source)
            string(value.packageName)
            out.writeInt(value.appId)
        }

        private fun writePem(value: PemState) {
            enum(value.packagePresence)
            enum(value.listState)
            strings(value.entries.sorted())
            string(value.rawPackageOutput)
            string(value.rawListOutput)
            strings(value.problems)
        }

        private fun writeOperation(value: JournalOperation) {
            string(value.operationId)
            enum(value.component)
            writeObservation(value.before)
            out.writeBoolean(value.expectedAfter != null)
            value.expectedAfter?.let(::writeObservation)
            enum(value.writeIntent)
            enum(value.verification)
        }

        private fun writeObservation(value: PolicyObservation) = when (value) {
            is PolicyObservation.AppOps -> {
                out.writeByte(1)
                enum(value.operation)
                writePolicyValue(value.packageValue)
                writePolicyValue(value.uidValue)
            }
            is PolicyObservation.DeviceIdle -> {
                out.writeByte(2)
                val memberships = value.memberships.sortedWith(
                    compareBy<DozeMembership>({ it.source.ordinal }, { it.packageName }, { it.appId }),
                )
                list(memberships) { writeMembership(it) }
                string(value.rawOutput)
                out.writeBoolean(value.evidenceComplete)
            }
            is PolicyObservation.VivoPem -> {
                out.writeByte(3)
                enum(value.packagePresence)
                enum(value.listState)
                strings(value.entries.sorted())
                string(value.rawListOutput)
                out.writeBoolean(value.evidenceComplete)
            }
        }

        private fun writePolicyValue(value: AppOpPolicyValue) {
            writeScope(value.scope)
            enum(value.operationRecordPresence)
            nullableEnum(value.reportedMode)
            nullableEnum(value.reportedDefault)
            enum(value.overrideProvenance)
        }

        private fun strings(values: Collection<String>) = list(values.toList()) { string(it) }

        private fun <T> list(values: List<T>, write: (T) -> Unit) {
            if (values.size > MAX_COLLECTION_ITEMS) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
            out.writeInt(values.size)
            values.forEach(write)
        }

        private fun string(value: String) {
            if (value.length > MAX_STRING_BYTES) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_STRING_BYTES) throw JournalCodecException(CodecFailure.SIZE_LIMIT)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun nullableString(value: String?) {
            out.writeBoolean(value != null)
            value?.let(::string)
        }

        private fun nullableInt(value: Int?) {
            out.writeBoolean(value != null)
            value?.let(out::writeInt)
        }

        private fun enum(value: Enum<*>) = out.writeInt(value.ordinal)

        private fun nullableEnum(value: Enum<*>?) {
            out.writeBoolean(value != null)
            value?.let(::enum)
        }
    }

    private class Reader(private val input: DataInputStream) {
        fun readDocument(): JournalDocument {
            val version = input.readInt()
            if (version != JOURNAL_SCHEMA_VERSION) throw JournalCodecException(CodecFailure.UNKNOWN_VERSION)
            return JournalDocument(version, readBaseline(), list { readOperation() })
        }

        private fun readBaseline(): BaselineSnapshot {
            val version = input.readInt()
            if (version != BASELINE_SCHEMA_VERSION) throw JournalCodecException(CodecFailure.UNKNOWN_VERSION)
            return BaselineSnapshot(
                version,
                readMetadata(),
                readReport(),
                list { readEvidence() },
                input.readBoolean(),
            )
        }

        private fun readMetadata() = CaptureMetadata(
            capturedAtEpochMillis = input.readLong(),
            captureSource = enum<CaptureSource>(),
            buildFingerprint = nullableString(),
            buildFingerprintSource = enum<MetadataFieldSource>(),
            androidApiFingerprint = nullableString(),
            buildId = nullableString(),
            buildDisplay = nullableString(),
            sdkInt = nullableInt(),
            buildFieldsSource = enum<MetadataFieldSource>(),
            userId = nullableInt(),
            userIdSource = enum<MetadataFieldSource>(),
            executorUid = nullableInt(),
            executorUidSource = enum<MetadataFieldSource>(),
            gmsAppId = nullableInt(),
        )

        private fun readEvidence(): RecordedCommandResult {
            val operationId = string()
            val uid = nullableInt()
            if (SafeCommandFactory.fromIpc(operationId, uid) == null) malformed()
            return RecordedCommandResult(
                operationId = operationId,
                uidParameter = uid,
                exitCode = input.readInt(),
                stdout = string(),
                stderr = string(),
                timedOut = input.readBoolean(),
                outputTruncated = input.readBoolean(),
            )
        }

        private fun readReport(): DetectionReport {
            val access = enum<PrivilegedAccessState>()
            val user = nullableInt()
            val fingerprint = nullableString()
            val gms = readGms()
            val appOpsEntries = list {
                val key = enum<AppOp>()
                key to readAppOpCheck()
            }
            if (appOpsEntries.map { it.first }.toSet().size != appOpsEntries.size) malformed()
            return DetectionReport(
                privilegedAccess = access,
                currentUser = user,
                buildFingerprint = fingerprint,
                gms = gms,
                appOps = appOpsEntries.toMap(),
                doze = readDoze(),
                pem = readPem(),
                fcmHealth = enum<FcmHealthConclusion>(),
                problems = strings(),
            )
        }

        private fun readGms() = GmsPackageState(
            presence = enum<Presence>(),
            enabled = enum<EnabledState>(),
            appId = nullableInt(),
            rawInstalledOutput = string(),
            rawEnabledOutput = string(),
            problems = strings(),
        )

        private fun readAppOpCheck(): AppOpCheck = AppOpCheck(
            operation = enum<AppOp>(),
            packageState = readAppOpState(),
            uidState = readAppOpState(),
            status = enum<EvidenceStatus>(),
            problems = strings(),
        )

        private fun readAppOpState(): AppOpState = AppOpState(
            scope = readScope(),
            operation = enum<AppOp>(),
            operationRecordPresence = enum<Presence>(),
            reportedMode = nullableEnum<AppOpMode>(),
            reportedDefault = nullableEnum<AppOpMode>(),
            overrideProvenance = enum<OverrideProvenance>(),
            rawOutput = string(),
            problems = strings(),
        )

        private fun readScope(): AppOpScope = when (input.readUnsignedByte()) {
            1 -> AppOpScope.Package(string(), input.readInt())
            2 -> AppOpScope.Uid(input.readInt(), input.readInt())
            else -> malformed()
        }

        private fun readDoze() = DozeState(
            memberships = uniqueSet { readMembership() },
            rawOutput = string(),
            status = enum<EvidenceStatus>(),
            problems = strings(),
        )

        private fun readMembership() = DozeMembership(enum<DozeSource>(), string(), input.readInt())

        private fun readPem() = PemState(
            packagePresence = enum<Presence>(),
            listState = enum<PemListState>(),
            entries = uniqueStringSet(),
            rawPackageOutput = string(),
            rawListOutput = string(),
            problems = strings(),
        )

        private fun readOperation(): JournalOperation {
            val id = string()
            val component = enum<JournalComponent>()
            val before = readObservation()
            val after = if (input.readBoolean()) readObservation() else null
            return JournalOperation(
                operationId = id,
                component = component,
                before = before,
                expectedAfter = after,
                writeIntent = enum<WriteIntentState>(),
                verification = enum<OperationVerification>(),
            )
        }

        private fun readObservation(): PolicyObservation = when (input.readUnsignedByte()) {
            1 -> PolicyObservation.AppOps(enum<AppOp>(), readPolicyValue(), readPolicyValue())
            2 -> PolicyObservation.DeviceIdle(uniqueSet { readMembership() }, string(), input.readBoolean())
            3 -> PolicyObservation.VivoPem(
                enum<Presence>(),
                enum<PemListState>(),
                uniqueStringSet(),
                string(),
                input.readBoolean(),
            )
            else -> malformed()
        }

        private fun readPolicyValue() = AppOpPolicyValue(
            scope = readScope(),
            operationRecordPresence = enum<Presence>(),
            reportedMode = nullableEnum<AppOpMode>(),
            reportedDefault = nullableEnum<AppOpMode>(),
            overrideProvenance = enum<OverrideProvenance>(),
        )

        private fun uniqueStringSet(): Set<String> = uniqueSet { string() }

        private fun strings(): List<String> = list { string() }

        private fun <T> uniqueSet(read: () -> T): Set<T> {
            val values = list(read)
            if (values.toSet().size != values.size) malformed()
            return values.toSet()
        }

        private fun <T> list(read: () -> T): List<T> {
            val count = input.readInt()
            if (count < 0 || count > MAX_COLLECTION_ITEMS) sizeLimit()
            return List(count) { read() }
        }

        private fun string(): String {
            val size = input.readInt()
            if (size < 0 || size > MAX_STRING_BYTES) sizeLimit()
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }

        private fun nullableString(): String? = if (input.readBoolean()) string() else null

        private fun nullableInt(): Int? = if (input.readBoolean()) input.readInt() else null

        private inline fun <reified T : Enum<T>> enum(): T {
            val ordinal = input.readInt()
            return enumValues<T>().getOrNull(ordinal) ?: malformed()
        }

        private inline fun <reified T : Enum<T>> nullableEnum(): T? =
            if (input.readBoolean()) enum<T>() else null

        private fun malformed(): Nothing = throw JournalCodecException(CodecFailure.MALFORMED)
        private fun sizeLimit(): Nothing = throw JournalCodecException(CodecFailure.SIZE_LIMIT)
    }
}
