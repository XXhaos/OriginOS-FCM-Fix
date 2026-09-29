package io.github.originosfcmguardian.core

enum class AppOpsWriteCapability {
    /** Historical target shell guard observation while official Shizuku was running. */
    SHELL_GUARD_WHILE_SHIZUKU_RUNNING,
    UNKNOWN,
}

enum class AdvisoryEvidence {
    HISTORICAL_NOT_LIVE,
    NONE,
}

data class AppOpsWriteAdvisory(
    val capability: AppOpsWriteCapability,
    val evidence: AdvisoryEvidence,
)

/** Only an exact previously investigated build receives a historical capability advisory. */
object KnownBuildAdvisories {
    private const val VERIFIED_FINGERPRINT =
        "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"

    fun appOpsWrite(shellFingerprint: String?): AppOpsWriteAdvisory =
        if (shellFingerprint == VERIFIED_FINGERPRINT) {
            AppOpsWriteAdvisory(
                capability = AppOpsWriteCapability.SHELL_GUARD_WHILE_SHIZUKU_RUNNING,
                evidence = AdvisoryEvidence.HISTORICAL_NOT_LIVE,
            )
        } else {
            AppOpsWriteAdvisory(
                capability = AppOpsWriteCapability.UNKNOWN,
                evidence = AdvisoryEvidence.NONE,
            )
        }
}
