package io.github.originosfcmguardian.autoprotection

/** Only a separately validated, explicit long-term opt-in can be VALID. */
internal enum class RestartConsentStatus { ABSENT, DISABLED, VALID, REVOKED, CORRUPT, UNKNOWN }

/** Stop/revocation entries here must be newer than the consent; NONE requires a successful read. */
internal enum class RestartSuspensionStatus { NONE, USER_STOP, SYSTEM_STOP, PERMISSION_REVOKED, UNKNOWN }

/** VERIFIED includes the full current target identity, supported firmware and current user. */
internal enum class RestartIdentityStatus { VERIFIED, CHANGED, UNSUPPORTED, UNKNOWN }

/** CLEAR requires a successful read; PREPARED/CLAIMED operations are PENDING, never replayable. */
internal enum class RestartJournalStatus { CLEAR, PENDING, CORRUPT, UNKNOWN }

/** READY means the durable budget has been read and its cross-process/time basis validated. */
internal enum class RestartBudgetStatus { READY, COOLDOWN, EXHAUSTED, UNCERTAIN, UNKNOWN }

internal data class RestartPrivilegeEvidence(
    val binderAlive: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val uid: Int? = null,
    val currentUser: Int? = null,
    /** Read on the current connection generation, not restored from storage or an old binder. */
    val freshForCurrentConnection: Boolean = false,
) {
    val shellReady: Boolean get() = binderAlive == true && permissionGranted == true &&
        uid == 2000 && currentUser == 0 && freshForCurrentConnection
}

internal enum class RestartServicePhase { NOT_STARTED, START_REQUESTED, ACTIVE, FAILED, UNKNOWN }

internal data class RestartServiceEvidence(
    val phase: RestartServicePhase = RestartServicePhase.UNKNOWN,
    /** Live controller evidence tied to the current consent/session/connection, not a saved flag. */
    val freshForCurrentSession: Boolean = false,
)

internal data class ProtectionRestartInputs(
    // Absence is OFF. v5 in-memory grants and manual/automatic receipts are not durable consent.
    val consent: RestartConsentStatus = RestartConsentStatus.ABSENT,
    val userUnlocked: Boolean? = null,
    val suspension: RestartSuspensionStatus = RestartSuspensionStatus.UNKNOWN,
    val identity: RestartIdentityStatus = RestartIdentityStatus.UNKNOWN,
    val journal: RestartJournalStatus = RestartJournalStatus.UNKNOWN,
    val budget: RestartBudgetStatus = RestartBudgetStatus.UNKNOWN,
    val privilege: RestartPrivilegeEvidence = RestartPrivilegeEvidence(),
    val service: RestartServiceEvidence = RestartServiceEvidence(),
)

internal enum class ProtectionRestartStatus {
    OFF,
    STOPPED_BY_USER,
    WAITING_UNLOCK,
    WAITING_PRIVILEGE,
    BLOCKED_STORAGE,
    BLOCKED_RECONCILIATION,
    BLOCKED_IDENTITY,
    BLOCKED_BUDGET,
    BLOCKED_CONFIGURATION,
    READY_TO_START,
    STARTING,
    ACTIVE,
}

internal data class ProtectionRestartDecision(
    /** Durable desire only; this does not mean service, privilege or protection is ready. */
    val desiredEnabled: Boolean,
    val status: ProtectionRestartStatus,
)

/**
 * Pure, fail-closed display/startup classifier. No storage, scheduling, budget consumption or writes.
 * Callers must validate durable consent (schema, target/firmware binding and revocation generation),
 * read newer stop records, and supply fresh readiness evidence. This snapshot neither persists consent
 * nor solves generation races: a future controller must recheck and atomically claim current authority
 * and the durable budget before dispatching any existing, independently guarded recovery transaction.
 * ACTIVE describes current service readiness, not verified FCM delivery or uninterrupted protection.
 */
internal fun protectionRestartDecision(input: ProtectionRestartInputs): ProtectionRestartDecision {
    val desired = input.consent == RestartConsentStatus.VALID
    val status = when {
        input.consent == RestartConsentStatus.ABSENT || input.consent == RestartConsentStatus.DISABLED ->
            ProtectionRestartStatus.OFF
        input.consent == RestartConsentStatus.REVOKED -> ProtectionRestartStatus.STOPPED_BY_USER
        input.consent == RestartConsentStatus.CORRUPT -> ProtectionRestartStatus.BLOCKED_STORAGE
        input.suspension in setOf(RestartSuspensionStatus.USER_STOP, RestartSuspensionStatus.SYSTEM_STOP,
            RestartSuspensionStatus.PERMISSION_REVOKED) -> ProtectionRestartStatus.STOPPED_BY_USER
        input.userUnlocked != true -> ProtectionRestartStatus.WAITING_UNLOCK
        !desired || input.suspension == RestartSuspensionStatus.UNKNOWN -> ProtectionRestartStatus.BLOCKED_STORAGE
        input.journal != RestartJournalStatus.CLEAR -> ProtectionRestartStatus.BLOCKED_RECONCILIATION
        input.identity != RestartIdentityStatus.VERIFIED -> ProtectionRestartStatus.BLOCKED_IDENTITY
        input.budget != RestartBudgetStatus.READY -> ProtectionRestartStatus.BLOCKED_BUDGET
        !input.privilege.shellReady -> ProtectionRestartStatus.WAITING_PRIVILEGE
        input.service.phase == RestartServicePhase.NOT_STARTED -> ProtectionRestartStatus.READY_TO_START
        !input.service.freshForCurrentSession -> ProtectionRestartStatus.BLOCKED_CONFIGURATION
        input.service.phase == RestartServicePhase.START_REQUESTED -> ProtectionRestartStatus.STARTING
        input.service.phase == RestartServicePhase.ACTIVE -> ProtectionRestartStatus.ACTIVE
        else -> ProtectionRestartStatus.BLOCKED_CONFIGURATION
    }
    return ProtectionRestartDecision(desired, status)
}
