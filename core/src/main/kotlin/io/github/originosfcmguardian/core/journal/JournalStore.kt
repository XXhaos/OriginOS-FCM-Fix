package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpCheck
import io.github.originosfcmguardian.core.AppOpScope
import io.github.originosfcmguardian.core.AppOpsParser
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.DeviceIdleParser
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.core.GmsPackageParser
import io.github.originosfcmguardian.core.OverrideProvenance
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.PemParser
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.SafeCommand

interface JournalStore {
    /** Null means no artifact. A corrupt or interrupted artifact must throw. */
    fun read(): ByteArray?

    /** Atomically preserves any existing artifact under concurrent callers. */
    fun writeIfAbsent(encoded: ByteArray): StoreWriteResult
}

enum class StoreWriteResult { STORED, ALREADY_EXISTS }

enum class BaselineStoreFailure {
    INCOMPLETE_SNAPSHOT,
    READ_FAILED,
    EXISTING_CORRUPT,
    ENCODE_FAILED,
    WRITE_FAILED,
    RACE_WINNER_UNREADABLE,
}

sealed interface BaselineCaptureResult {
    data class Stored(val document: JournalDocument) : BaselineCaptureResult
    data class Preserved(val existing: JournalDocument) : BaselineCaptureResult
    data class FailClosed(
        val reason: BaselineStoreFailure,
        val codecFailure: CodecFailure? = null,
        val semanticIssues: Set<SemanticIssue> = emptySet(),
    ) : BaselineCaptureResult
}

class FirstBaselineRepository(private val store: JournalStore) {
    fun captureFirst(snapshot: BaselineSnapshot): BaselineCaptureResult {
        val existingBytes = try {
            store.read()
        } catch (_: Throwable) {
            return BaselineCaptureResult.FailClosed(BaselineStoreFailure.READ_FAILED)
        }
        if (existingBytes != null) return decodeExisting(existingBytes, BaselineStoreFailure.EXISTING_CORRUPT)

        val semantic = JournalSemanticValidator.validateBaseline(snapshot)
        if (!semantic.isValid) {
            return BaselineCaptureResult.FailClosed(
                BaselineStoreFailure.INCOMPLETE_SNAPSHOT,
                semanticIssues = semantic.issues,
            )
        }

        // Round-trip freezes caller-owned mutable collections at the persistence boundary.
        val normalized = try {
            JournalCodec.decode(JournalCodec.encode(JournalDocument(baseline = snapshot)))
        } catch (error: JournalCodecException) {
            return BaselineCaptureResult.FailClosed(BaselineStoreFailure.ENCODE_FAILED, error.failure)
        } catch (_: Throwable) {
            return BaselineCaptureResult.FailClosed(BaselineStoreFailure.ENCODE_FAILED)
        }
        val encoded = try {
            JournalCodec.encode(normalized)
        } catch (error: JournalCodecException) {
            return BaselineCaptureResult.FailClosed(BaselineStoreFailure.ENCODE_FAILED, error.failure)
        }

        return try {
            when (store.writeIfAbsent(encoded)) {
                StoreWriteResult.STORED -> BaselineCaptureResult.Stored(normalized)
                StoreWriteResult.ALREADY_EXISTS -> {
                    val winner = try {
                        store.read()
                    } catch (_: Throwable) {
                        null
                    } ?: return BaselineCaptureResult.FailClosed(
                        BaselineStoreFailure.RACE_WINNER_UNREADABLE,
                    )
                    decodeExisting(winner, BaselineStoreFailure.RACE_WINNER_UNREADABLE)
                }
            }
        } catch (_: Throwable) {
            BaselineCaptureResult.FailClosed(BaselineStoreFailure.WRITE_FAILED)
        }
    }

    private fun decodeExisting(
        bytes: ByteArray,
        failure: BaselineStoreFailure,
    ): BaselineCaptureResult = try {
        BaselineCaptureResult.Preserved(JournalCodec.decode(bytes))
    } catch (error: JournalCodecException) {
        BaselineCaptureResult.FailClosed(failure, error.failure)
    } catch (_: Throwable) {
        BaselineCaptureResult.FailClosed(failure)
    }
}

enum class SemanticIssue {
    UNKNOWN_SCHEMA,
    PERSISTED_OPERATIONS_UNSUPPORTED,
    IDENTITY_FIELD_MISSING,
    IDENTITY_SOURCE_INVALID,
    FINGERPRINT_MISMATCH,
    USER_MISMATCH,
    EXECUTOR_NOT_SHELL,
    GMS_UID_MISMATCH,
    REPORT_INCOMPLETE,
    APP_OPS_INCOMPLETE,
    DEVICE_IDLE_INCOMPLETE,
    PEM_UNKNOWN,
    COMMAND_EVIDENCE_MISSING,
    COMMAND_EVIDENCE_DUPLICATE,
    COMMAND_EVIDENCE_UNEXPECTED,
    COMMAND_EVIDENCE_FAILED,
    COMMAND_EVIDENCE_OVERFLOW,
    COMMAND_EVIDENCE_REPORT_MISMATCH,
}

data class SemanticValidation(val issues: Set<SemanticIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

object JournalSemanticValidator {
    fun validatePersistable(document: JournalDocument): SemanticValidation {
        val issues = validateBaseline(document.baseline).issues.toMutableSet()
        if (document.schemaVersion != JOURNAL_SCHEMA_VERSION) issues += SemanticIssue.UNKNOWN_SCHEMA
        if (document.operations.isNotEmpty()) issues += SemanticIssue.PERSISTED_OPERATIONS_UNSUPPORTED
        return SemanticValidation(issues)
    }

    fun validateBaseline(snapshot: BaselineSnapshot): SemanticValidation {
        val issues = linkedSetOf<SemanticIssue>()
        val metadata = snapshot.metadata
        val report = snapshot.report
        if (snapshot.schemaVersion != BASELINE_SCHEMA_VERSION) issues += SemanticIssue.UNKNOWN_SCHEMA
        if (metadata.captureSource != CaptureSource.SHIZUKU_READ_ONLY &&
            metadata.captureSource != CaptureSource.TEST_FIXTURE
        ) {
            issues += SemanticIssue.IDENTITY_SOURCE_INVALID
        }
        if (listOf(
                metadata.buildFingerprint,
                metadata.androidApiFingerprint,
                metadata.buildId,
                metadata.buildDisplay,
            ).any { it.isNullOrBlank() } || metadata.sdkInt == null
        ) {
            issues += SemanticIssue.IDENTITY_FIELD_MISSING
        }
        if (metadata.buildFingerprintSource != MetadataFieldSource.SHELL_REPORT ||
            metadata.buildFieldsSource != MetadataFieldSource.ANDROID_BUILD_API ||
            metadata.userIdSource != MetadataFieldSource.SHELL_REPORT ||
            metadata.executorUidSource != MetadataFieldSource.EXECUTOR
        ) {
            issues += SemanticIssue.IDENTITY_SOURCE_INVALID
        }
        if (metadata.buildFingerprint == null ||
            metadata.buildFingerprint != report.buildFingerprint ||
            metadata.buildFingerprint != metadata.androidApiFingerprint
        ) {
            issues += SemanticIssue.FINGERPRINT_MISMATCH
        }
        if (metadata.userId == null || metadata.userId != report.currentUser || metadata.userId != 0) {
            issues += SemanticIssue.USER_MISMATCH
        }
        if (metadata.executorUid != 2_000 || report.privilegedAccess != PrivilegedAccessState.READY_SHELL) {
            issues += SemanticIssue.EXECUTOR_NOT_SHELL
        }
        if (metadata.gmsAppId == null || metadata.gmsAppId != report.gms.appId) {
            issues += SemanticIssue.GMS_UID_MISMATCH
        }
        if (report.problems.isNotEmpty()) issues += SemanticIssue.REPORT_INCOMPLETE
        if (!appOpsComplete(snapshot)) issues += SemanticIssue.APP_OPS_INCOMPLETE
        if (report.doze.status == EvidenceStatus.UNKNOWN || report.doze.problems.isNotEmpty() ||
            report.doze.rawOutput.isBlank()
        ) {
            issues += SemanticIssue.DEVICE_IDLE_INCOMPLETE
        }
        if (report.pem.listState == PemListState.UNKNOWN ||
            report.pem.packagePresence == Presence.UNKNOWN || report.pem.problems.isNotEmpty()
        ) {
            issues += SemanticIssue.PEM_UNKNOWN
        }
        if (snapshot.evidenceOverflowed) issues += SemanticIssue.COMMAND_EVIDENCE_OVERFLOW

        val expected = expectedEvidence(metadata.gmsAppId)
        val grouped = snapshot.commandEvidence.groupBy { it.operationId to it.uidParameter }
        if (expected.any { it !in grouped }) issues += SemanticIssue.COMMAND_EVIDENCE_MISSING
        if (grouped.any { (_, values) -> values.size != 1 }) issues += SemanticIssue.COMMAND_EVIDENCE_DUPLICATE
        if (grouped.keys.any { it !in expected }) issues += SemanticIssue.COMMAND_EVIDENCE_UNEXPECTED
        if (snapshot.commandEvidence.any {
                it.exitCode != 0 || it.timedOut || it.outputTruncated || it.stderr.isNotBlank()
            }
        ) {
            issues += SemanticIssue.COMMAND_EVIDENCE_FAILED
        }
        if (!evidenceMatchesReport(snapshot)) issues += SemanticIssue.COMMAND_EVIDENCE_REPORT_MISMATCH
        return SemanticValidation(issues)
    }

    private fun appOpsComplete(snapshot: BaselineSnapshot): Boolean {
        val report = snapshot.report
        val gmsAppId = snapshot.metadata.gmsAppId ?: return false
        if (report.appOps.keys != AppOp.entries.toSet()) return false
        return report.appOps.all { (key, check) ->
            val packageScope = check.packageState.scope as? AppOpScope.Package
            val uidScope = check.uidState.scope as? AppOpScope.Uid
            key == check.operation && key == check.packageState.operation && key == check.uidState.operation &&
                packageScope?.packageName == GMS_PACKAGE_NAME && packageScope.userId == 0 &&
                uidScope?.appId == gmsAppId && uidScope.userId == 0 &&
                check.status != EvidenceStatus.UNKNOWN && check.problems.isEmpty() &&
                check.packageState.problems.isEmpty() && check.uidState.problems.isEmpty() &&
                check.packageState.operationRecordPresence != Presence.UNKNOWN &&
                check.uidState.operationRecordPresence != Presence.UNKNOWN &&
                check.packageState.overrideProvenance != OverrideProvenance.UNKNOWN &&
                check.uidState.overrideProvenance != OverrideProvenance.UNKNOWN &&
                check.packageState.hasKnownMode() && check.uidState.hasKnownMode()
        }
    }

    private fun evidenceMatchesReport(snapshot: BaselineSnapshot): Boolean {
        val report = snapshot.report
        val evidence = snapshot.commandEvidence.associateBy { it.operationId to it.uidParameter }
        fun result(command: SafeCommand, uid: Int? = null): CommandResult? {
            val recorded = evidence[command.operationId to uid] ?: return null
            return CommandResult(
                command = command,
                exitCode = recorded.exitCode,
                stdout = recorded.stdout,
                stderr = recorded.stderr,
                timedOut = recorded.timedOut,
                outputTruncated = recorded.outputTruncated,
            )
        }
        if (result(SafeCommand.GetCurrentUser)?.stdout?.trim()?.toIntOrNull() != report.currentUser) {
            return false
        }
        if (result(SafeCommand.GetBuildFingerprint)?.stdout?.trim() != report.buildFingerprint) return false
        val installed = result(SafeCommand.GetGmsPackage) ?: return false
        val enabled = result(SafeCommand.GetGmsEnabledPackage) ?: return false
        if (GmsPackageParser.parse(installed, enabled) != report.gms) return false
        val deviceIdle = result(SafeCommand.GetDeviceIdleWhitelist) ?: return false
        if (DeviceIdleParser.parse(deviceIdle) != report.doze) return false
        val pemList = result(SafeCommand.GetVivoPemList) ?: return false
        val pemPath = result(SafeCommand.GetVivoPemPackagePath) ?: return false
        if (PemParser.parse(pemList, pemPath) != report.pem) return false
        val gmsAppId = snapshot.metadata.gmsAppId ?: return false
        return AppOp.entries.all { operation ->
            val expected = report.appOps[operation] ?: return@all false
            val packageResult = result(SafeCommand.GetGmsAppOpByPackage(operation)) ?: return@all false
            val uidResult = result(
                SafeCommand.GetAppOpByUid(gmsAppId, operation),
                gmsAppId,
            ) ?: return@all false
            val packageState = AppOpsParser.parse(AppOpScope.Package(), operation, packageResult)
            val uidState = AppOpsParser.parse(AppOpScope.Uid(gmsAppId), operation, uidResult)
            combine(operation, packageState, uidState) == expected
        }
    }

    private fun combine(
        operation: AppOp,
        packageState: io.github.originosfcmguardian.core.AppOpState,
        uidState: io.github.originosfcmguardian.core.AppOpState,
    ): AppOpCheck {
        val problems = mutableListOf<String>()
        problems += packageState.problems.map { "Package scope: $it" }
        problems += uidState.problems.map { "UID scope: $it" }
        val packageMode = packageState.reportedMode ?: packageState.reportedDefault
        val uidMode = uidState.reportedMode ?: uidState.reportedDefault
        if (packageMode != null && uidMode != null && packageMode != uidMode) {
            problems += "Package and UID scopes report conflicting modes"
        }
        val status = when {
            problems.isNotEmpty() -> EvidenceStatus.UNKNOWN
            packageState.status == EvidenceStatus.UNKNOWN || uidState.status == EvidenceStatus.UNKNOWN ->
                EvidenceStatus.UNKNOWN
            packageState.status == EvidenceStatus.CONFORMING &&
                uidState.status == EvidenceStatus.CONFORMING -> EvidenceStatus.CONFORMING
            else -> EvidenceStatus.ANOMALOUS
        }
        return AppOpCheck(operation, packageState, uidState, status, problems.distinct())
    }

    private fun expectedEvidence(gmsAppId: Int?): Set<Pair<String, Int?>> = buildSet {
        add(SafeCommand.GetCurrentUser.operationId to null)
        add(SafeCommand.GetBuildFingerprint.operationId to null)
        add(SafeCommand.GetGmsPackage.operationId to null)
        add(SafeCommand.GetGmsEnabledPackage.operationId to null)
        add(SafeCommand.GetDeviceIdleWhitelist.operationId to null)
        add(SafeCommand.GetVivoPemList.operationId to null)
        add(SafeCommand.GetVivoPemPackagePath.operationId to null)
        AppOp.entries.forEach { operation ->
            add(SafeCommand.GetGmsAppOpByPackage(operation).operationId to null)
            if (gmsAppId != null) add(
                SafeCommand.GetAppOpByUid(gmsAppId, operation).operationId to gmsAppId,
            )
        }
    }

    private fun io.github.originosfcmguardian.core.AppOpState.hasKnownMode(): Boolean = when (
        operationRecordPresence
    ) {
        Presence.PRESENT -> reportedMode != null
        Presence.ABSENT -> reportedDefault != null
        Presence.UNKNOWN -> false
    }
}
