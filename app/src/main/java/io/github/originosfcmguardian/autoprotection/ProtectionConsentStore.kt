package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.util.UUID
import java.util.Collections

internal interface ProtectionAuthorityStorage {
    fun <T> exclusive(action: () -> T): T
    /** null means genuinely uninitialized storage only. Corruption and partial loss must throw. */
    fun read(): ProtectionAuthoritySnapshot?
    /** Atomic, durable commit and read-back before returning. All calls are under exclusive. */
    fun write(value: ProtectionAuthoritySnapshot)
}

/** Call on IO. Every transaction includes a cross-process read/check/commit lock. */
internal class ProtectionConsentStore(private val storage: ProtectionAuthorityStorage) {
    fun grantExplicit(identity: SelectedTargetIdentity, fingerprint: String, clock: BudgetClock,
        policyVersion: Int = 1): DurableConsentToken =
        storage.exclusive {
            require(clock.boot >= 0 && clock.elapsed >= 0 && clock.epoch > 0)
            val prior = storage.read()
            requireWritable(prior)
            require(prior == null || prior.consents().all { it is TargetConsent.Legacy }) { "Legacy grant cannot replace recipient authority" }
            val generation = Math.addExact(prior?.generation ?: 0, 1)
            val token = DurableConsentToken(UUID.randomUUID().toString(), generation, clock.epoch,
                identity.copy(signerSha256 = immutableCopy(identity.signerSha256)), fingerprint,
                policyVersion = policyVersion)
            val budget = prior?.budget ?: BudgetSnapshot(clock, emptyList(), null)
            if (prior == null || prior.schemaVersion == 1) {
                storage.write(ProtectionAuthoritySnapshot(generation, ProtectionConsentMode.ACTIVE, token, null, budget))
            } else {
                storage.write(prior.copy(generation = generation, targetConsents = listOf(TargetConsent.Legacy(token)),
                    invalidatedConsentIds = emptyList()))
            }
            token
        }

    /** Suspended and disabled state require another explicit confirmation. */
    fun readDesired(): DurableConsentToken? = readSnapshot()?.let { value ->
        if (value.storageBlocked || value.mode != ProtectionConsentMode.ACTIVE) null
        else value.consents().filter { it.id !in value.invalidatedConsentIds }
            .filterIsInstance<TargetConsent.Legacy>().singleOrNull()?.value
    }

    fun configuredConsents(): List<TargetConsent> = readSnapshot()?.consents().orEmpty()

    /** Complete mandatory state under the same lock; never initializes or upgrades authority. */
    fun readSnapshot(): ProtectionAuthoritySnapshot? = storage.exclusive {
        storage.read()?.let { value ->
            value.copy(token = value.token?.let { token ->
                token.copy(identity = token.identity.copy(signerSha256 = immutableCopy(token.identity.signerSha256)))
            }, budget = value.budget.copy(attempts = immutableCopy(value.budget.attempts)),
                targetConsents = immutableCopy(value.targetConsents.map(::copyConsent)),
                invalidatedConsentIds = immutableCopy(value.invalidatedConsentIds))
        }
    }

    fun readMode(): ProtectionConsentMode = storage.exclusive {
        storage.read()?.mode ?: ProtectionConsentMode.DISABLED
    }

    fun verifyAuthorized(token: DurableConsentToken): Boolean = try {
        storage.exclusive { legacyMatches(storage.read(), token) }
    } catch (_: Exception) { false }

    /** Compatibility name for master OFF; choices and exact consent tokens are retained. */
    fun revoke(expectedToken: DurableConsentToken? = null): Boolean =
        retire(expectedToken, ProtectionConsentMode.USER_PAUSED, null)

    fun pauseMaster(): Boolean = revoke()

    /** Allows explicit OFF to persist even before the first application choice exists. */
    fun pauseMaster(clock: BudgetClock): Boolean = storage.exclusive {
        val prior = storage.read()
        requireWritable(prior)
        if (prior != null) {
            storage.write(prior.configuredSchema3().copy(mode = ProtectionConsentMode.USER_PAUSED,
                suspensionReason = null, generation = Math.addExact(prior.generation, 1)))
            return@exclusive true
        }
        require(clock.boot >= 0 && clock.elapsed >= 0 && clock.epoch > 0)
        storage.write(ProtectionAuthoritySnapshot(1, ProtectionConsentMode.USER_PAUSED, null, null,
            BudgetSnapshot(clock, emptyList(), null), schemaVersion = 3))
        true
    }

    /** Explicit master ON reuses choices; exact root comparison prevents stale ON from winning. */
    fun enableMaster(expected: ProtectionAuthoritySnapshot?, clock: BudgetClock): Boolean = storage.exclusive {
        val prior = storage.read()
        requireWritable(prior)
        if (prior != expected || !validMasterActionClock(prior, clock)) return@exclusive false
        val base = prior?.configuredSchema3() ?: ProtectionAuthoritySnapshot(1,
            ProtectionConsentMode.USER_PAUSED, null, null, BudgetSnapshot(clock, emptyList(), null), schemaVersion = 3)
        val generation = Math.addExact(prior?.generation ?: 0, 1)
        storage.write(base.copy(generation = generation, mode = ProtectionConsentMode.ACTIVE,
            suspensionReason = null, masterEnabledAtMillis = clock.epoch))
        true
    }

    fun suspend(expectedToken: DurableConsentToken?, reason: ProtectionSuspensionReason): Boolean =
        retire(expectedToken, ProtectionConsentMode.SUSPENDED, reason)

    private fun retire(expectedToken: DurableConsentToken?, mode: ProtectionConsentMode,
        reason: ProtectionSuspensionReason?): Boolean = storage.exclusive {
        val prior = storage.read() ?: return@exclusive expectedToken == null
        requireWritable(prior)
        if (expectedToken != null && !legacyMatches(prior, expectedToken)) return@exclusive false
        storage.write(prior.configuredSchema3().copy(mode = mode, suspensionReason = reason,
            generation = Math.addExact(prior.generation, 1)))
        true
    }

    /** Historical budget helper for compatibility tests; production recovery never reserves attempts. */
    fun take(token: DurableConsentToken, clock: BudgetClock): AutomaticCheckDecision = budgetDecision(token, clock, true)

    /** Historical budget helper only; production startup never normalizes retained budget clocks. */
    fun checkBudget(token: DurableConsentToken, clock: BudgetClock): AutomaticCheckDecision = budgetDecision(token, clock, false)

    private fun budgetDecision(token: DurableConsentToken, clock: BudgetClock, reserve: Boolean): AutomaticCheckDecision = try {
        storage.exclusive {
            val prior = storage.read()
            if (prior == null || !legacyMatches(prior, token)) return@exclusive unavailable()
            // Reuse the tested budget algorithm while retaining the authority transaction lock.
            val adapter = object : AttemptBudgetStorage {
                override fun <T> exclusive(action: () -> T): T = action()
                override fun read(): BudgetSnapshot = prior.budget
                override fun write(value: BudgetSnapshot) { storage.write(prior.copy(budget = value)) }
            }
            val budget = PersistentAttemptBudget(adapter)
            if (reserve) budget.take(clock) else budget.check(clock)
        }
    } catch (_: Exception) { unavailable() }

    private fun unavailable() = AutomaticCheckDecision.Pause("保护不可用：持久授权或限频记录无法确认，请重新确认")

    /** Explicitly confirms exactly one current eligible identity; unrelated grants retain their tokens. */
    fun grantRecipientExplicit(identity: SelectedTargetIdentity, fingerprint: String, clock: BudgetClock): TargetConsentLease =
        storage.exclusive {
            require(clock.boot >= 0 && clock.elapsed >= 0 && clock.epoch > 0)
            val prior = storage.read()
            requireWritable(prior)
            val generation = Math.addExact(prior?.generation ?: 0, 1)
            val fresh = TargetConsent.Recipient(RecipientConsentToken(UUID.randomUUID().toString(), generation,
                clock.epoch, identity.copy(signerSha256 = immutableCopy(identity.signerSha256)), fingerprint))
            val retained = prior?.consents().orEmpty().filterNot { it.identity.packageName == identity.packageName }
            val targets = immutableCopy((retained + fresh).sortedBy { it.identity.packageName }.map(::copyConsent))
            val budget = prior?.budget ?: BudgetSnapshot(clock, emptyList(), null)
            val base = prior?.configuredSchema3() ?: ProtectionAuthoritySnapshot(generation,
                ProtectionConsentMode.USER_PAUSED, null, null, budget, schemaVersion = 3)
            storage.write(base.copy(generation = generation, targetConsents = targets,
                invalidatedConsentIds = immutableCopy(base.invalidatedConsentIds.filter { id -> targets.any { it.id == id } })))
            TargetConsentLease(generation, fresh)
        }

    fun desiredLeases(): List<TargetConsentLease> = readSnapshot()?.let { state ->
        immutableCopy(state.runnableConsents().map { TargetConsentLease(state.generation, it) })
    }.orEmpty()

    fun verifyAuthorized(lease: TargetConsentLease): Boolean = try {
        storage.exclusive { matches(storage.read(), lease) }
    } catch (_: Exception) { false }

    /** One current locked snapshot for a whole-group gate, never cached across calls or dispatched
     * as write authority. Single-target checks immediately before recovery remain independent. */
    fun verifyAllAuthorized(leases: List<TargetConsentLease>): Boolean {
        // Preserve the previous List.all empty-list semantics; this does not create a grant.
        if (leases.isEmpty()) return true
        return try {
            storage.exclusive {
                val current = storage.read()
                leases.all { matches(current, it) }
            }
        } catch (_: Exception) { false }
    }

    /**
     * Fresh production admission under the authority lock. Reads the exact current root and consent,
     * without clocks, budget normalization or writes. Recover is eligibility only: dispatch must still
     * validate the write gate, connection, identity, stopped baseline and recovery journal.
     */
    fun checkRecoveryAuthorization(lease: TargetConsentLease): AutomaticCheckDecision = try {
        storage.exclusive {
            if (matches(storage.read(), lease)) AutomaticCheckDecision.Recover else recoveryUnavailable()
        }
    } catch (_: Exception) { recoveryUnavailable() }

    private fun recoveryUnavailable() = AutomaticCheckDecision.Pause("保护不可用：当前持久授权无法确认，请重新确认")

    /** Exact identity only; a removed old install must not revoke a newly confirmed replacement. */
    fun revokeTarget(identity: SelectedTargetIdentity): Boolean = storage.exclusive {
        val prior = storage.read() ?: return@exclusive true
        requireWritable(prior)
        if (prior.consents().none { it.identity == identity }) return@exclusive true
        val generation = Math.addExact(prior.generation, 1)
        val base = prior.configuredSchema3()
        val targets = immutableCopy(base.targetConsents.filterNot { it.identity == identity })
        storage.write(base.copy(generation = generation, targetConsents = targets,
            invalidatedConsentIds = immutableCopy(base.invalidatedConsentIds.filter { id -> targets.any { it.id == id } })))
        true
    }

    fun suspendLease(lease: TargetConsentLease?, reason: ProtectionSuspensionReason): Boolean = storage.exclusive {
        val prior = storage.read() ?: return@exclusive lease == null
        requireWritable(prior)
        if (lease != null && !matches(prior, lease)) return@exclusive false
        storage.write(prior.configuredSchema3().copy(mode = ProtectionConsentMode.SUSPENDED,
            suspensionReason = reason, generation = Math.addExact(prior.generation, 1)))
        true
    }

    /** Automatic negative observation is bound to the whole observed root, not just package/identity. */
    fun revokeConfirmedTargets(expected: List<TargetConsentLease>, invalid: List<TargetConsentLease>): Boolean = storage.exclusive {
        val prior = storage.read() ?: return@exclusive false
        requireWritable(prior)
        val current = prior.runnableConsents().map { TargetConsentLease(prior.generation, it) }
        if (prior.mode != ProtectionConsentMode.ACTIVE || expected.isEmpty() || expected != current ||
            invalid.isEmpty() || invalid.distinct().size != invalid.size || !expected.containsAll(invalid)) return@exclusive false
        val generation = Math.addExact(prior.generation, 1)
        val base = prior.configuredSchema3()
        storage.write(base.copy(generation = generation,
            invalidatedConsentIds = immutableCopy((base.invalidatedConsentIds + invalid.map { it.consent.id }).distinct().sorted())))
        true
    }

    /** Historical budget helpers for compatibility tests; neither is production recovery admission. */
    fun take(lease: TargetConsentLease, clock: BudgetClock): AutomaticCheckDecision = budgetDecision(lease, clock, true)
    fun checkBudget(lease: TargetConsentLease, clock: BudgetClock): AutomaticCheckDecision = budgetDecision(lease, clock, false)

    private fun budgetDecision(lease: TargetConsentLease, clock: BudgetClock, reserve: Boolean): AutomaticCheckDecision = try {
        storage.exclusive {
            val prior = storage.read()
            if (!matches(prior, lease)) return@exclusive unavailable()
            val state = requireNotNull(prior)
            val adapter = object : AttemptBudgetStorage {
                override fun <T> exclusive(action: () -> T): T = action()
                override fun read(): BudgetSnapshot = state.budget
                override fun write(value: BudgetSnapshot) { storage.write(state.copy(budget = value)) }
            }
            val budget = PersistentAttemptBudget(adapter)
            if (reserve) budget.take(clock) else budget.check(clock)
        }
    } catch (_: Exception) { unavailable() }

    private fun matches(state: ProtectionAuthoritySnapshot?, lease: TargetConsentLease): Boolean =
        state != null && state.generation == lease.rootGeneration && !state.storageBlocked &&
            state.mode == ProtectionConsentMode.ACTIVE && lease.consent.restartConsented() &&
            lease.consent.id !in state.invalidatedConsentIds && state.consents().contains(lease.consent)

    private fun legacyMatches(state: ProtectionAuthoritySnapshot?, token: DurableConsentToken): Boolean =
        state != null && !state.storageBlocked && state.mode == ProtectionConsentMode.ACTIVE &&
            token.id !in state.invalidatedConsentIds && state.consents().contains(TargetConsent.Legacy(token))

    private fun requireWritable(state: ProtectionAuthoritySnapshot?) {
        check(state?.storageBlocked != true) { "Interrupted authority operation requires storage recovery; ordinary changes are blocked" }
    }

    private fun validMasterActionClock(prior: ProtectionAuthoritySnapshot?, clock: BudgetClock): Boolean {
        if (clock.boot < 0 || clock.elapsed < 0 || clock.epoch <= 0) return false
        if (prior == null) return true
        val recorded = prior.budget.clock
        if (clock.boot < recorded.boot || clock.epoch < recorded.epoch ||
            (clock.boot == recorded.boot && clock.elapsed < recorded.elapsed)) return false
        if (clock.epoch < (prior.masterEnabledAtMillis ?: 0) ||
            prior.consents().any { clock.epoch < it.consentedAtMillis }) return false
        // On the same boot wall time must agree with monotonic progression (existing 5s tolerance).
        if (clock.boot == recorded.boot) {
            val elapsedDelta = clock.elapsed - recorded.elapsed
            val epochDelta = clock.epoch - recorded.epoch
            if (elapsedDelta > epochDelta && elapsedDelta - epochDelta > 5_000L ||
                epochDelta > elapsedDelta && epochDelta - elapsedDelta > 5_000L) return false
        }
        return true
    }

    private fun copyConsent(consent: TargetConsent): TargetConsent = when (consent) {
        is TargetConsent.Legacy -> TargetConsent.Legacy(consent.value.copy(identity = consent.identity.copy(signerSha256 = immutableCopy(consent.identity.signerSha256))))
        is TargetConsent.Recipient -> TargetConsent.Recipient(consent.value.copy(identity = consent.identity.copy(signerSha256 = immutableCopy(consent.identity.signerSha256))))
    }

    private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
