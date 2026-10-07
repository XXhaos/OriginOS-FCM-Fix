package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint

internal const val STARTUP_EXIT_HISTORY_LIMIT = 64
internal enum class StartupExitReason { USER_REQUESTED, USER_STOPPED, CRASH, CRASH_NATIVE, ANR, INITIALIZATION_FAILURE, OTHER }
internal data class StartupExitRecord(val reason: StartupExitReason, val timestampMillis: Long)
internal enum class StartupExitHistoryStatus { CLEAR, USER_STOPPED, CRASH_LOOP, UNKNOWN }

/** Android caller supplies only this app's relevant processes, with non-main crashes mapped OTHER. */
internal fun startupExitHistoryDecision(
    consentedAtMillis: Long,
    nowMillis: Long,
    records: List<StartupExitRecord>?,
    historySaturated: Boolean = records?.size == STARTUP_EXIT_HISTORY_LIMIT,
): StartupExitHistoryStatus {
    if (consentedAtMillis <= 0 || nowMillis < consentedAtMillis || records == null ||
        records.size > STARTUP_EXIT_HISTORY_LIMIT ||
        records.any { it.timestampMillis <= 0 || it.timestampMillis > nowMillis }) return StartupExitHistoryStatus.UNKNOWN
    val newer = records.filter { it.timestampMillis >= consentedAtMillis }
    if (newer.any { it.reason == StartupExitReason.USER_REQUESTED || it.reason == StartupExitReason.USER_STOPPED }) {
        return StartupExitHistoryStatus.USER_STOPPED
    }
    if (newer.count { it.reason in setOf(StartupExitReason.CRASH, StartupExitReason.CRASH_NATIVE,
            StartupExitReason.ANR, StartupExitReason.INITIALIZATION_FAILURE) } >= 2) return StartupExitHistoryStatus.CRASH_LOOP
    // A full bounded result must reach before consent; otherwise an omitted stop cannot be excluded.
    if (historySaturated && records.none { it.timestampMillis < consentedAtMillis }) {
        return StartupExitHistoryStatus.UNKNOWN
    }
    return StartupExitHistoryStatus.CLEAR
}

internal data class StartupEligibilityInputs(
    val authorityReadSuccessful: Boolean = false,
    val authority: ProtectionAuthoritySnapshot? = null,
    val userUnlocked: Boolean = false,
    val exitHistory: StartupExitHistoryStatus = StartupExitHistoryStatus.UNKNOWN,
    val fingerprint: String? = null,
    val currentUser: Int? = null,
    val freshIdentity: SelectedTargetIdentity? = null,
    val selectedIdentity: SelectedTargetIdentity? = null,
    val journal: RestartJournalStatus = RestartJournalStatus.UNKNOWN,
    val budget: AutomaticCheckDecision? = null,
    val privilege: RestartPrivilegeEvidence = RestartPrivilegeEvidence(),
    val targets: List<StartupTargetEvidence>? = null,
    val targetReadUnavailable: Boolean = false,
)

internal data class StartupTargetEvidence(val lease: TargetConsentLease, val freshIdentity: SelectedTargetIdentity?,
    val selectedIdentity: SelectedTargetIdentity?, val qualified: Boolean)

/** Negative authority only; null identity/qualification failures never prove an uninstall. */
internal fun confirmedInvalidTargetLeases(
    input: StartupEligibilityInputs,
    selectionReadSuccessful: Boolean,
): List<TargetConsentLease> {
    val root = input.authority ?: return emptyList()
    val expected = root.runnableConsents()
    val targets = input.targets ?: return emptyList()
    if (input.targetReadUnavailable || !input.authorityReadSuccessful || !input.userUnlocked || !selectionReadSuccessful ||
        root.mode != ProtectionConsentMode.ACTIVE || expected.isEmpty() ||
        input.exitHistory != StartupExitHistoryStatus.CLEAR ||
        !validRuntimeFingerprint(input.fingerprint) || input.currentUser != 0 ||
        expected.any { it.fingerprint != input.fingerprint } || !input.privilege.shellReady ||
        input.journal != RestartJournalStatus.CLEAR ||
        (input.budget != AutomaticCheckDecision.Recover && input.budget !is AutomaticCheckDecision.Cooldown) ||
        targets.map { it.lease.consent } != expected || targets.any { it.lease.rootGeneration != root.generation }) return emptyList()
    val invalid = targets.filter { target ->
        target.selectedIdentity == null || target.freshIdentity?.let {
            it.packageName == target.lease.consent.identity.packageName && it != target.lease.consent.identity
        } == true
    }
    if (invalid.isEmpty() || targets.filterNot { it in invalid }.any {
        !it.qualified || it.freshIdentity != it.lease.consent.identity || it.selectedIdentity != it.lease.consent.identity
    }) return emptyList()
    return java.util.Collections.unmodifiableList(invalid.map { it.lease })
}

internal enum class StartupEligibilityStatus {
    OFF, WAITING_UNLOCK, NEEDS_EXPLICIT_CONSENT, BLOCKED_STORAGE, STOPPED_BY_USER,
    BLOCKED_CRASH_LOOP, BLOCKED_IDENTITY, BLOCKED_RECONCILIATION, BLOCKED_BUDGET,
    WAITING_PRIVILEGE, WAITING_BUDGET, WAITING_RECONCILIATION, WAITING_TARGET_READ, READY_TO_START,
}

/** Startup classification grants no write authority and consumes no budget. */
internal fun startupEligibility(input: StartupEligibilityInputs): StartupEligibilityStatus {
    val eligible = input.authority?.runnableConsents().orEmpty()
    val targets = input.targets ?: eligible.map { consent -> StartupTargetEvidence(
        TargetConsentLease(requireNotNull(input.authority).generation, consent), input.freshIdentity, input.selectedIdentity, true) }
    return when {
        !input.userUnlocked -> StartupEligibilityStatus.WAITING_UNLOCK
        !input.authorityReadSuccessful -> StartupEligibilityStatus.BLOCKED_STORAGE
        input.authority?.storageBlocked == true -> StartupEligibilityStatus.BLOCKED_STORAGE
        input.authority == null || input.authority.mode == ProtectionConsentMode.DISABLED -> StartupEligibilityStatus.OFF
        input.authority.mode == ProtectionConsentMode.USER_PAUSED -> StartupEligibilityStatus.OFF
        input.authority.mode != ProtectionConsentMode.ACTIVE || eligible.isEmpty() -> StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT
        targets.map { it.lease.consent } != eligible || targets.any { it.lease.rootGeneration != input.authority.generation } ->
            StartupEligibilityStatus.BLOCKED_STORAGE
        input.exitHistory == StartupExitHistoryStatus.USER_STOPPED -> StartupEligibilityStatus.STOPPED_BY_USER
        input.exitHistory == StartupExitHistoryStatus.CRASH_LOOP -> StartupEligibilityStatus.BLOCKED_CRASH_LOOP
        input.exitHistory != StartupExitHistoryStatus.CLEAR -> StartupEligibilityStatus.BLOCKED_STORAGE
        !validRuntimeFingerprint(input.fingerprint) || input.currentUser != 0 ||
            targets.any { input.fingerprint != it.lease.consent.fingerprint ||
                it.selectedIdentity != it.lease.consent.identity || (!input.targetReadUnavailable &&
                    (!it.qualified || it.freshIdentity != it.lease.consent.identity)) } ->
            StartupEligibilityStatus.BLOCKED_IDENTITY
        input.journal !in setOf(RestartJournalStatus.CLEAR, RestartJournalStatus.PENDING) -> StartupEligibilityStatus.BLOCKED_RECONCILIATION
        input.budget != AutomaticCheckDecision.Recover && input.budget !is AutomaticCheckDecision.Cooldown ->
            StartupEligibilityStatus.BLOCKED_BUDGET
        !input.privilege.shellReady -> StartupEligibilityStatus.WAITING_PRIVILEGE
        input.targetReadUnavailable -> StartupEligibilityStatus.WAITING_TARGET_READ
        input.journal == RestartJournalStatus.PENDING -> StartupEligibilityStatus.WAITING_RECONCILIATION
        input.budget is AutomaticCheckDecision.Cooldown -> StartupEligibilityStatus.WAITING_BUDGET
        else -> StartupEligibilityStatus.READY_TO_START
    }
}
