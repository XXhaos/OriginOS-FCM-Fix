package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint

internal enum class ProtectionConsentMode { DISABLED, ACTIVE, SUSPENDED, USER_PAUSED }
internal enum class ProtectionSuspensionReason { PREREQUISITES_LOST, IDENTITY_CHANGED, PENDING_RECEIPT, RECOVERY_BUSY, SERVICE_UNAVAILABLE, BUDGET_UNAVAILABLE }
internal const val RESTART_CONSENT_POLICY_VERSION = 2
internal const val RECIPIENT_CONSENT_POLICY_VERSION = 4

/** Explicit automatic consent; manual recovery receipts and legacy sessions are never inputs. */
internal data class DurableConsentToken(
    val id: String,
    val generation: Long,
    val consentedAtMillis: Long,
    val identity: SelectedTargetIdentity,
    val fingerprint: String,
    val schemaVersion: Int = 1,
    val policyVersion: Int = 1,
    val userId: Int = 0,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(generation > 0 && consentedAtMillis > 0)
        require(identity.packageName == "org.telegram.messenger" && identity.signerSha256.size <= 16)
        require(validRuntimeFingerprint(fingerprint))
        require(schemaVersion == 1 && policyVersion in 1..RESTART_CONSENT_POLICY_VERSION && userId == 0)
    }
}

/** Fresh per-recipient consent. Legacy policy2 tokens never acquire this scope. */
internal data class RecipientConsentToken(
    val id: String,
    val generation: Long,
    val consentedAtMillis: Long,
    val identity: SelectedTargetIdentity,
    val fingerprint: String,
    val policyVersion: Int = RECIPIENT_CONSENT_POLICY_VERSION,
    val userId: Int = 0,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(generation > 0 && consentedAtMillis > 0 && identity.signerSha256.size <= 16)
        require(validRuntimeFingerprint(fingerprint) && policyVersion == RECIPIENT_CONSENT_POLICY_VERSION && userId == 0)
    }
}

internal sealed interface TargetConsent {
    val identity: SelectedTargetIdentity
    val id: String
    val generation: Long
    val consentedAtMillis: Long
    val policyVersion: Int
    val fingerprint: String
    data class Legacy(val value: DurableConsentToken) : TargetConsent {
        override val identity get() = value.identity
        override val id get() = value.id
        override val generation get() = value.generation
        override val consentedAtMillis get() = value.consentedAtMillis
        override val policyVersion get() = value.policyVersion
        override val fingerprint get() = value.fingerprint
    }
    data class Recipient(val value: RecipientConsentToken) : TargetConsent {
        override val identity get() = value.identity
        override val id get() = value.id
        override val generation get() = value.generation
        override val consentedAtMillis get() = value.consentedAtMillis
        override val policyVersion get() = value.policyVersion
        override val fingerprint get() = value.fingerprint
    }
}

/** Both fields must still match the current durable root immediately before dispatch. */
internal data class TargetConsentLease(val rootGeneration: Long, val consent: TargetConsent)
internal fun TargetConsent.restartConsented(): Boolean = when (this) {
    is TargetConsent.Legacy -> policyVersion == RESTART_CONSENT_POLICY_VERSION
    is TargetConsent.Recipient -> policyVersion == RECIPIENT_CONSENT_POLICY_VERSION
}

/** Budget is mandatory even when disabled, and survives every consent generation. */
internal data class ProtectionAuthoritySnapshot(
    val generation: Long,
    val mode: ProtectionConsentMode,
    val token: DurableConsentToken?,
    val suspensionReason: ProtectionSuspensionReason?,
    val budget: BudgetSnapshot,
    /** Schema1 compatibility is kept solely in token; schema2 uses this canonical target list. */
    val targetConsents: List<TargetConsent> = emptyList(),
    val schemaVersion: Int = 1,
    /** Master ON acknowledgement, independent of each exact application's consent time. */
    val masterEnabledAtMillis: Long? = null,
    /** Automatic invalidation keeps the choice visible, but cannot authorize the old binding. */
    val invalidatedConsentIds: List<String> = emptyList(),
    /** In-memory projection of an ambiguous interrupted storage operation; never persisted. */
    val storageBlocked: Boolean = false,
) {
    init {
        require(generation > 0)
        require(schemaVersion in 1..3)
        if (schemaVersion < 3) {
            require(mode != ProtectionConsentMode.USER_PAUSED && masterEnabledAtMillis == null && invalidatedConsentIds.isEmpty())
        }
        if (schemaVersion == 1) {
            require(targetConsents.isEmpty())
            require((mode == ProtectionConsentMode.ACTIVE) == (token != null))
            require(token == null || token.generation == generation)
        } else {
            require(token == null)
            if (schemaVersion == 2) require((mode == ProtectionConsentMode.ACTIVE) == targetConsents.isNotEmpty())
            else require(mode != ProtectionConsentMode.DISABLED || targetConsents.isEmpty())
            require(targetConsents.map { it.identity.packageName } == targetConsents.map { it.identity.packageName }.distinct().sorted())
            require(targetConsents.all { it.generation <= generation })
            if (schemaVersion == 3) require(targetConsents.map { it.id }.distinct().size == targetConsents.size)
        }
        require((mode == ProtectionConsentMode.SUSPENDED) == (suspensionReason != null))
        require(masterEnabledAtMillis == null || masterEnabledAtMillis > 0)
        require(schemaVersion != 3 || mode != ProtectionConsentMode.ACTIVE || masterEnabledAtMillis != null)
        require(invalidatedConsentIds == invalidatedConsentIds.distinct().sorted())
        require(invalidatedConsentIds.all { id -> targetConsents.any { it.id == id } })
        require(validAuthorityBudget(budget))
    }
}

internal fun ProtectionAuthoritySnapshot.consents(): List<TargetConsent> =
    if (schemaVersion == 1) token?.let { listOf(TargetConsent.Legacy(it)) }.orEmpty() else targetConsents

/** Upgrade only on a write. Legacy ACTIVE keeps the oldest exact consent as its history boundary. */
internal fun ProtectionAuthoritySnapshot.configuredSchema3(): ProtectionAuthoritySnapshot =
    if (schemaVersion == 3) this else copy(token = null, targetConsents = consents(), schemaVersion = 3,
        mode = if (mode == ProtectionConsentMode.DISABLED) ProtectionConsentMode.USER_PAUSED else mode,
        masterEnabledAtMillis = if (mode == ProtectionConsentMode.ACTIVE) consents().minOf { it.consentedAtMillis } else null)

internal fun ProtectionAuthoritySnapshot.runnableConsents(): List<TargetConsent> =
    if (storageBlocked || mode != ProtectionConsentMode.ACTIVE) emptyList()
    else consents().filter { it.restartConsented() && it.id !in invalidatedConsentIds }

internal fun ProtectionAuthoritySnapshot.retired(mode: ProtectionConsentMode, reason: ProtectionSuspensionReason?,
    nextGeneration: Long = generation): ProtectionAuthoritySnapshot = copy(generation = nextGeneration,
    mode = mode, token = null, suspensionReason = reason, targetConsents = emptyList())

internal fun validAuthorityBudget(value: BudgetSnapshot): Boolean = value.clock.boot >= 0 &&
    value.clock.elapsed >= 0 && value.clock.epoch >= 0 && value.attempts.size <= 3 &&
    value.attempts.all { it in 0..value.clock.elapsed } &&
    value.attempts.zipWithNext().all { (a, b) -> b - a >= 300_000L } &&
    (value.quarantineUntil == null || (value.attempts.isEmpty() && value.quarantineUntil >= value.clock.elapsed &&
        value.quarantineUntil - value.clock.elapsed <= 3_600_000L))
