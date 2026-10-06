package io.github.originosfcmguardian.protection

import io.github.originosfcmguardian.core.isSafeAndroidPackageName

enum class ProtectionCheckReason {
    MANUAL,
    APP_START,
    SHIZUKU_AVAILABLE,
    BUILD_CHANGED,
    GMS_VERSION_CHANGED,
    GUARDIAN_VERSION_CHANGED,
    DAILY_FALLBACK,
}

enum class ProtectionCheckStatus {
    HEALTHY_READ_ONLY,
    NEEDS_USER_ACTION,
    UNKNOWN,
    BUILD_CHANGED,
}

enum class TargetCheckStatus {
    /** Current package identity only; conveys no OEM permission state. */
    IDENTITY_MATCH,
    // Retained solely to decode historical daily_protection_report_v1 records.
    ALLOWED,
    NEEDS_USER_ACTION,
    UNKNOWN,
    IDENTITY_CHANGED,
    MISSING,
    UNAVAILABLE,
}

data class TargetCheckResult(
    val packageName: String,
    val status: TargetCheckStatus,
) {
    init {
        require(isSafeAndroidPackageName(packageName))
    }
}

data class DailyProtectionReport(
    val checkedAtEpochMillis: Long,
    val reason: ProtectionCheckReason,
    val fingerprint: String,
    val status: ProtectionCheckStatus,
    val summary: String,
    val targets: List<TargetCheckResult> = emptyList(),
) {
    /** Immutable history remains readable but must not supply current permission conclusions. */
    val containsRetiredAutostartEvidence: Boolean
        get() = summary.contains("Google Play") || summary.contains("后台权限") ||
            summary.contains("待机白名单") || summary.contains("GMS=") || summary.contains("自启动") || targets.any {
            it.status in setOf(
                TargetCheckStatus.ALLOWED,
                TargetCheckStatus.NEEDS_USER_ACTION,
                TargetCheckStatus.UNKNOWN,
                TargetCheckStatus.UNAVAILABLE,
            )
        }

    init {
        require(checkedAtEpochMillis >= 0)
        require(fingerprint.length <= 512)
        require(summary.length <= MAX_SUMMARY_CHARS)
    }

    companion object {
        const val MAX_SUMMARY_CHARS = 800
    }
}
