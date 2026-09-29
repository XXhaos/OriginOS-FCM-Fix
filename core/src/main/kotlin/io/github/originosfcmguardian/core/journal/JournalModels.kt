package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.AppOpScope
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.DozeMembership
import io.github.originosfcmguardian.core.OverrideProvenance
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.Presence

const val BASELINE_SCHEMA_VERSION: Int = 1
const val JOURNAL_SCHEMA_VERSION: Int = 1

enum class CaptureSource {
    SHIZUKU_READ_ONLY,
    ORDINARY_API_READ_ONLY,
    TEST_FIXTURE,
    UNKNOWN,
}

enum class MetadataFieldSource {
    ANDROID_BUILD_API,
    SHELL_REPORT,
    EXECUTOR,
    UNKNOWN,
}

/** Metadata finalized when the sequential detection completes; the probes are not an atomic snapshot. */
data class CaptureMetadata(
    val capturedAtEpochMillis: Long,
    val captureSource: CaptureSource,
    val buildFingerprint: String?,
    val buildFingerprintSource: MetadataFieldSource,
    val androidApiFingerprint: String?,
    val buildId: String?,
    val buildDisplay: String?,
    val sdkInt: Int?,
    val buildFieldsSource: MetadataFieldSource,
    val userId: Int?,
    val userIdSource: MetadataFieldSource,
    val executorUid: Int?,
    val executorUidSource: MetadataFieldSource,
    val gmsAppId: Int?,
) {
    init {
        require(capturedAtEpochMillis >= 0)
        require(sdkInt == null || sdkInt in 1..10_000)
        require(userId == null || userId >= 0)
        require(executorUid == null || executorUid >= 0)
        require(gmsAppId == null || gmsAppId > 0)
    }
}

/** Lossless transport result without a Java/Kotlin object-serialization dependency. */
data class RecordedCommandResult(
    val operationId: String,
    val uidParameter: Int?,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
)

data class BaselineSnapshot(
    val schemaVersion: Int = BASELINE_SCHEMA_VERSION,
    val metadata: CaptureMetadata,
    val report: DetectionReport,
    val commandEvidence: List<RecordedCommandResult>,
    val evidenceOverflowed: Boolean = false,
) {
    /** A read-only capture can never authorize a later system mutation. */
    val eligibleForMutation: Boolean = false
}

enum class JournalComponent {
    APP_OPS,
    DEVICE_IDLE,
    VIVO_PEM,
}

data class AppOpPolicyValue(
    val scope: AppOpScope,
    val operationRecordPresence: Presence,
    val reportedMode: AppOpMode?,
    val reportedDefault: AppOpMode?,
    val overrideProvenance: OverrideProvenance,
)

sealed interface PolicyObservation {
    data class AppOps(
        val operation: AppOp,
        val packageValue: AppOpPolicyValue,
        val uidValue: AppOpPolicyValue,
    ) : PolicyObservation {
        init {
            require(packageValue.scope is AppOpScope.Package)
            require(uidValue.scope is AppOpScope.Uid)
        }
    }

    /** [rawOutput] is intentionally retained and compared exactly for the complete whitelist. */
    data class DeviceIdle(
        val memberships: Set<DozeMembership>,
        val rawOutput: String,
        val evidenceComplete: Boolean,
    ) : PolicyObservation

    data class VivoPem(
        val packagePresence: Presence,
        val listState: PemListState,
        val entries: Set<String>,
        val rawListOutput: String,
        val evidenceComplete: Boolean,
    ) : PolicyObservation
}

enum class WriteIntentState {
    NONE,
    RECORDED_NOT_DISPATCHED,
    DISPATCH_UNCERTAIN,
    DISPATCHED,
}

enum class OperationVerification {
    NOT_ATTEMPTED,
    UNCONFIRMED,
    VERIFIED_SUCCESS,
    FAILED,
}

/** Future journal state only. It deliberately contains no command or execution API. */
data class JournalOperation(
    val operationId: String,
    val component: JournalComponent,
    val before: PolicyObservation,
    val expectedAfter: PolicyObservation?,
    val writeIntent: WriteIntentState,
    val verification: OperationVerification,
) {
    init {
        require(operationId.isNotBlank())
        require(expectedAfter == null || observationComponent(expectedAfter) == component)
        require(observationComponent(before) == component)
        if (before is PolicyObservation.AppOps && expectedAfter is PolicyObservation.AppOps) {
            require(before.operation == expectedAfter.operation)
            require(before.packageValue.scope == expectedAfter.packageValue.scope)
            require(before.uidValue.scope == expectedAfter.uidValue.scope)
        }
    }

    val executionCapable: Boolean = false
}

data class JournalDocument(
    val schemaVersion: Int = JOURNAL_SCHEMA_VERSION,
    val baseline: BaselineSnapshot,
    val operations: List<JournalOperation> = emptyList(),
) {
    val executionCapable: Boolean = false
}

internal fun observationComponent(observation: PolicyObservation): JournalComponent = when (observation) {
    is PolicyObservation.AppOps -> JournalComponent.APP_OPS
    is PolicyObservation.DeviceIdle -> JournalComponent.DEVICE_IDLE
    is PolicyObservation.VivoPem -> JournalComponent.VIVO_PEM
}
