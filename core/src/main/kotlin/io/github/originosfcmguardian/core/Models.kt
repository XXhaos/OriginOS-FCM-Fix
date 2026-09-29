package io.github.originosfcmguardian.core

enum class EvidenceStatus {
    CONFORMING,
    ANOMALOUS,
    UNKNOWN,
}

enum class Presence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

enum class EnabledState {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

enum class AppOpMode {
    ALLOW,
    IGNORE,
    DENY,
    FOREGROUND,
    DEFAULT,
    ERRORED,
    ASK,
}

sealed interface AppOpScope {
    data class Package(
        val packageName: String = GMS_PACKAGE_NAME,
        val userId: Int = 0,
    ) : AppOpScope {
        init {
            require(packageName == GMS_PACKAGE_NAME)
            require(userId == 0)
        }
    }

    data class Uid(val appId: Int, val userId: Int = 0) : AppOpScope {
        init {
            require(userId == 0)
            require(isUserZeroAppId(appId))
        }
    }
}

enum class OverrideProvenance {
    ABSENT,
    UNPROVEN,
    UNKNOWN,
}

data class GmsPackageState(
    val presence: Presence,
    val enabled: EnabledState,
    val appId: Int?,
    val rawInstalledOutput: String,
    val rawEnabledOutput: String,
    val problems: List<String> = emptyList(),
) {
    val status: EvidenceStatus
        get() = when {
            presence == Presence.PRESENT && enabled == EnabledState.ENABLED && appId != null ->
                EvidenceStatus.CONFORMING
            presence == Presence.ABSENT || enabled == EnabledState.DISABLED -> EvidenceStatus.ANOMALOUS
            else -> EvidenceStatus.UNKNOWN
        }
}

data class AppOpState(
    val scope: AppOpScope,
    val operation: AppOp,
    val operationRecordPresence: Presence,
    val reportedMode: AppOpMode?,
    val reportedDefault: AppOpMode?,
    val overrideProvenance: OverrideProvenance,
    val rawOutput: String,
    val problems: List<String> = emptyList(),
) {
    val status: EvidenceStatus
        get() = when {
            problems.isNotEmpty() -> EvidenceStatus.UNKNOWN
            operationRecordPresence == Presence.ABSENT && reportedDefault == AppOpMode.ALLOW ->
                EvidenceStatus.CONFORMING
            operationRecordPresence == Presence.PRESENT && reportedMode == AppOpMode.ALLOW ->
                EvidenceStatus.CONFORMING
            operationRecordPresence == Presence.ABSENT && reportedDefault != null ->
                EvidenceStatus.ANOMALOUS
            operationRecordPresence == Presence.PRESENT && reportedMode != null ->
                EvidenceStatus.ANOMALOUS
            else -> EvidenceStatus.UNKNOWN
        }
}

data class AppOpCheck(
    val operation: AppOp,
    val packageState: AppOpState,
    val uidState: AppOpState,
    val status: EvidenceStatus,
    val problems: List<String> = emptyList(),
)

enum class DozeSource(val wireName: String) {
    SYSTEM_EXCIDLE("system-excidle"),
    SYSTEM("system"),
    USER("user"),
}

data class DozeMembership(
    val source: DozeSource,
    val packageName: String,
    val appId: Int,
)

data class DozeState(
    val memberships: Set<DozeMembership>,
    val rawOutput: String,
    val status: EvidenceStatus,
    val problems: List<String> = emptyList(),
) {
    val sources: Set<DozeSource>
        get() = memberships.mapTo(linkedSetOf()) { it.source }
}

enum class PemListState {
    VALUE_REPORTED,
    NULL_OR_MISSING_AMBIGUOUS,
    UNKNOWN,
}

data class PemState(
    val packagePresence: Presence,
    val listState: PemListState,
    val entries: Set<String>,
    val rawPackageOutput: String,
    val rawListOutput: String,
    val problems: List<String> = emptyList(),
) {
    /** PEM semantics remain unverified even when a value is readable. */
    val status: EvidenceStatus = EvidenceStatus.UNKNOWN
}

enum class PrivilegedAccessState {
    READY_SHELL,
    UNAVAILABLE,
    ROOT_REJECTED,
    NON_SHELL_REJECTED,
    UNKNOWN,
}

enum class FcmHealthConclusion {
    UNKNOWN,
}

data class DetectionReport(
    val privilegedAccess: PrivilegedAccessState,
    val currentUser: Int?,
    val buildFingerprint: String?,
    val gms: GmsPackageState,
    val appOps: Map<AppOp, AppOpCheck>,
    val doze: DozeState,
    val pem: PemState,
    val fcmHealth: FcmHealthConclusion = FcmHealthConclusion.UNKNOWN,
    val problems: List<String> = emptyList(),
) {
    /** Derived from the shell fingerprint; never persisted as live device evidence. */
    val appOpsWriteAdvisory: AppOpsWriteAdvisory
        get() = KnownBuildAdvisories.appOpsWrite(buildFingerprint)
}

enum class PlanDisposition {
    NO_WRITE,
    NEEDS_VALIDATION,
}

enum class RepairComponent {
    GMS_PACKAGE,
    APP_OP_PACKAGE,
    APP_OP_UID,
    DEVICE_IDLE,
    VIVO_PEM,
    EXECUTION_ENVIRONMENT,
}

data class RepairCandidate(
    val component: RepairComponent,
    val operation: AppOp? = null,
    val reason: String,
) {
    /** No candidate in this read-only milestone is executable. */
    val executable: Boolean = false
}

data class RepairPlan(
    val disposition: PlanDisposition,
    val candidates: List<RepairCandidate>,
    val validationNotes: List<String>,
) {
    /** The core intentionally exposes no write command or executable repair action. */
    val hasExecutableWrites: Boolean = false
}

data class ReadOnlyBaseline(
    val capturedAtEpochMillis: Long,
    val report: DetectionReport,
) {
    val eligibleForMutation: Boolean = false
}
