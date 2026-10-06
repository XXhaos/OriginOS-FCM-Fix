package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalState
import java.util.Collections

/** An inert historical reference, never a token accepted by an executor or consent store. */
internal data class LegacyConsentReference(val id: String, val generation: Long, val policyVersion: Int)

internal sealed interface LegacyAuthorityView {
    data object Unavailable : LegacyAuthorityView
    data object Absent : LegacyAuthorityView
    data class Retained(val generation: Long, val mode: ProtectionConsentMode,
        val suspensionReason: ProtectionSuspensionReason?, val sharedBudget: BudgetSnapshot) : LegacyAuthorityView
}

internal sealed interface LegacyJournalView {
    data object Unavailable : LegacyJournalView
    /** Successfully read absence is the existing empty state, distinct from an unavailable read. */
    data class Retained(val state: RecoveryJournalState) : LegacyJournalView
}

internal enum class RecipientPlanStatus {
    CLASSIFIED, BLOCKED_AUTHORITY, BLOCKED_SELECTION, BLOCKED_JOURNAL, BLOCKED_STALE_GENERATION,
}

internal enum class RecipientCompatibility {
    EXISTING_LEGACY_SCOPE, NEEDS_SUPPORT_AND_EXPLICIT_CONSENT, OFF, SUSPENDED,
    BLOCKED_LEGACY_POLICY, BLOCKED_IDENTITY, BLOCKED_INPUT,
}

internal data class RecipientProtectionEntry(
    val identity: SelectedTargetIdentity,
    val compatibility: RecipientCompatibility,
    val legacyConsent: LegacyConsentReference? = null,
)

/** No readiness/dispatch authority, scheduling, migration, persistence or budget reservation. */
internal data class RecipientProtectionPlan(
    val status: RecipientPlanStatus,
    val authority: LegacyAuthorityView,
    val journal: LegacyJournalView,
    val recipients: List<RecipientProtectionEntry>,
)

/**
 * C2 compatibility groundwork only. Inputs are successful, strictly validated existing reads
 * (or their failures), not cached readiness or manual recovery receipts interpreted as consent.
 * Old ACTIVE policy2 describes only its exact Telegram identity; selection grants nothing.
 * The one retained global budget is never split, reset, normalized or consumed here.
 * A generation comparison can reject a known stale snapshot; this plan cannot guard a later write.
 */
internal fun recipientProtectionPlan(
    authorityRead: Result<ProtectionAuthoritySnapshot?>,
    watchedRead: Result<List<SelectedTargetIdentity>>,
    freshRead: Result<List<SelectedTargetIdentity>>,
    journalRead: Result<RecoveryJournalState>,
    expectedAuthorityGeneration: Long? = null,
): RecipientProtectionPlan {
    val authoritySnapshot = authorityRead.getOrNull()
    val legacyIdentity = runCatching {
        authoritySnapshot?.token?.identity?.let {
            require(it.signerSha256.size <= 16)
            copyIdentity(it)
        }
    }
    val authority = when {
        authorityRead.isFailure -> LegacyAuthorityView.Unavailable
        authoritySnapshot == null -> LegacyAuthorityView.Absent
        else -> runCatching<LegacyAuthorityView> {
            require(validAuthorityBudget(authoritySnapshot.budget))
            legacyIdentity.getOrThrow()
            LegacyAuthorityView.Retained(authoritySnapshot.generation, authoritySnapshot.mode,
                authoritySnapshot.suspensionReason,
                authoritySnapshot.budget.copy(attempts = immutableCopy(authoritySnapshot.budget.attempts)))
        }.getOrDefault(LegacyAuthorityView.Unavailable)
    }
    val journal: LegacyJournalView = journalRead.mapCatching { state ->
        state.copy(latest = state.latest?.let { it.copy(identity = copyIdentity(it.identity)) })
    }.fold({ LegacyJournalView.Retained(it) }, { LegacyJournalView.Unavailable })
    val watched = watchedRead.mapCatching(::copySelection)
    val fresh = freshRead.mapCatching(::copySelection)
    val status = when {
        authority == LegacyAuthorityView.Unavailable -> RecipientPlanStatus.BLOCKED_AUTHORITY
        watched.isFailure || fresh.isFailure -> RecipientPlanStatus.BLOCKED_SELECTION
        expectedAuthorityGeneration != null &&
            (authority as? LegacyAuthorityView.Retained)?.generation != expectedAuthorityGeneration ->
            RecipientPlanStatus.BLOCKED_STALE_GENERATION
        journal == LegacyJournalView.Unavailable ||
            (journal as? LegacyJournalView.Retained)?.state?.latest?.requiresReconcile == true ->
            RecipientPlanStatus.BLOCKED_JOURNAL
        else -> RecipientPlanStatus.CLASSIFIED
    }
    val token = authoritySnapshot?.token
    val freshByPackage = fresh.getOrNull().orEmpty().associateBy { it.packageName }
    val recipients = watched.getOrNull().orEmpty().map { identity ->
        val compatibility = when {
            status != RecipientPlanStatus.CLASSIFIED -> RecipientCompatibility.BLOCKED_INPUT
            authoritySnapshot?.mode == ProtectionConsentMode.DISABLED -> RecipientCompatibility.OFF
            authoritySnapshot?.mode == ProtectionConsentMode.SUSPENDED -> RecipientCompatibility.SUSPENDED
            freshByPackage[identity.packageName] != identity -> RecipientCompatibility.BLOCKED_IDENTITY
            token != null && token.policyVersion != RESTART_CONSENT_POLICY_VERSION ->
                RecipientCompatibility.BLOCKED_LEGACY_POLICY
            token != null && legacyIdentity.getOrNull()?.packageName == identity.packageName && legacyIdentity.getOrNull() != identity ->
                RecipientCompatibility.BLOCKED_IDENTITY
            token != null && legacyIdentity.getOrNull() == identity -> RecipientCompatibility.EXISTING_LEGACY_SCOPE
            // A syntactically valid app identity is not proof of runtime support.
            else -> RecipientCompatibility.NEEDS_SUPPORT_AND_EXPLICIT_CONSENT
        }
        val reference = token?.takeIf { compatibility == RecipientCompatibility.EXISTING_LEGACY_SCOPE }
            ?.let { LegacyConsentReference(it.id, it.generation, it.policyVersion) }
        RecipientProtectionEntry(identity, compatibility, reference)
    }
    return RecipientProtectionPlan(status, authority, journal, immutableCopy(recipients))
}

private fun copyIdentity(identity: SelectedTargetIdentity): SelectedTargetIdentity =
    identity.copy(signerSha256 = immutableCopy(identity.signerSha256))

private fun copySelection(identities: List<SelectedTargetIdentity>): List<SelectedTargetIdentity> =
    identities.map(::copyIdentity).also {
        // Existing byte bound and uniqueness contract; no new per-user app-count limit.
        TargetSelectionCodec.encode(it)
    }.let(::immutableCopy)

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
