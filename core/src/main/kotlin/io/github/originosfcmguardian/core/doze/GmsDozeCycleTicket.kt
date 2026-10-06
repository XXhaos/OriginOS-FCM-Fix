package io.github.originosfcmguardian.core.doze

/** Bounded result summary: full actual cleanup/success identity and exact raw readback hash. */
data class GmsDozeCycleTicketResult(
    val ref: GmsDozeCycleAttemptRef,
    val cleanup: GmsDozeCycleCleanup,
    val exitCode: Int,
    val commandOutputValid: Boolean,
    val identity: GmsGlobalDozeIdentity,
    val rawWhitelistSha256: String,
)

/**
 * A separately versioned current-attempt transport document, never ledger/V2 conversion or an
 * independently authenticated storage proof. Factory input must be exact committed ledger bytes
 * with readback verified by the trusted App adapter. SHA/checksum alone cannot prove that fact.
 */
data class GmsDozeCycleTicket(
    val ledgerId: String,
    val ledgerRevision: Long,
    val ledgerSha256: String,
    val grant: GmsDozeCycleGrant,
    val runningIntent: Boolean,
    val runningGeneration: Long,
    val startIntent: Boolean,
    val requestedRevoke: Long,
    val restoreConsent: GmsDozeCycleRestoreConsent?,
    val resultSummary: GmsDozeCycleTicketResult?,
    val releaseAck: GmsDozeCycleReleaseAck?,
    val closure: GmsDozeCycleClosure?,
) {
    val ref: GmsDozeCycleAttemptRef get() = grant.ref
}
