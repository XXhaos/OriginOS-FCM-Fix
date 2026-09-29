package io.github.originosfcmguardian.protection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.GuardianAutostartRead

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
        require(packageName.length <= 255)
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
    init {
        require(checkedAtEpochMillis >= 0)
        require(fingerprint.length <= 512)
        require(summary.length <= MAX_SUMMARY_CHARS)
        require(targets.size <= MAX_TARGETS)
    }

    companion object {
        const val MAX_SUMMARY_CHARS = 800
        const val MAX_TARGETS = 10
    }
}

internal interface ProtectionReadPort : AutoCloseable {
    suspend fun detectCore(): DetectionReport
    suspend fun readTarget(identity: SelectedTargetIdentity): GuardianAutostartRead
    override fun close()
}
