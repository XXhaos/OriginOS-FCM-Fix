package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalState
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class RecipientProtectionPlanTest {
    private val telegram = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val other = SelectedTargetIdentity("example.recipient", 10435, 101, listOf("b".repeat(64)))
    private val budget = BudgetSnapshot(BudgetClock(14, 600_000, 1_000_000), listOf(0, 300_000, 600_000), null)
    private val token = DurableConsentToken("b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 7, 90,
        telegram, VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
    private val active = ProtectionAuthoritySnapshot(7, ProtectionConsentMode.ACTIVE, token, null, budget)
    private val record = StoppedRecoveryRecord(11, telegram, 200, 100, VERIFIED_AUTOSTART_FINGERPRINT,
        0, true, true, true, StoppedRecoveryStage.FINISHED, StoppedRecoveryOutcome.FLAG_CLEARED, "manual receipt")
    private val history = RecoveryJournalState(11, record)

    private fun plan(authority: ProtectionAuthoritySnapshot? = active,
        watched: List<SelectedTargetIdentity> = listOf(telegram, other),
        fresh: List<SelectedTargetIdentity> = watched,
        journal: RecoveryJournalState = history, generation: Long? = null) = recipientProtectionPlan(
        Result.success(authority), Result.success(watched), Result.success(fresh), Result.success(journal), generation)

    @Test fun `active policy2 carries only exact legacy scope and every other app is unqualified`() {
        val result = plan()
        assertEquals(RecipientPlanStatus.CLASSIFIED, result.status)
        assertEquals(RecipientCompatibility.EXISTING_LEGACY_SCOPE, result.recipients[0].compatibility)
        assertEquals(LegacyConsentReference(token.id, 7, 2), result.recipients[0].legacyConsent)
        assertEquals(RecipientCompatibility.NEEDS_SUPPORT_AND_EXPLICIT_CONSENT, result.recipients[1].compatibility)
        assertNull(result.recipients[1].legacyConsent)
        assertNull(plan(watched = listOf(other)).recipients.single().legacyConsent)
        val systemName = other.copy(packageName = "com.android.example")
        assertEquals(RecipientCompatibility.NEEDS_SUPPORT_AND_EXPLICIT_CONSENT,
            plan(watched = listOf(systemName)).recipients.single().compatibility)
    }

    @Test fun `disabled suspended absent and policy1 never become active intent`() {
        for (mode in listOf(ProtectionConsentMode.DISABLED, ProtectionConsentMode.SUSPENDED)) {
            val authority = active.copy(mode = mode, token = null,
                suspensionReason = if (mode == ProtectionConsentMode.SUSPENDED) ProtectionSuspensionReason.PENDING_RECEIPT else null)
            val result = plan(authority)
            assertTrue(result.recipients.all { it.legacyConsent == null })
            assertTrue(result.recipients.all { it.compatibility == if (mode == ProtectionConsentMode.DISABLED)
                RecipientCompatibility.OFF else RecipientCompatibility.SUSPENDED })
            assertEquals(mode, (result.authority as LegacyAuthorityView.Retained).mode)
        }
        assertTrue(plan(null).recipients.all { it.compatibility == RecipientCompatibility.NEEDS_SUPPORT_AND_EXPLICIT_CONSENT && it.legacyConsent == null })
        assertTrue(plan(active.copy(token = token.copy(policyVersion = 1))).recipients.all {
            it.compatibility == RecipientCompatibility.BLOCKED_LEGACY_POLICY && it.legacyConsent == null })
    }

    @Test fun `watched identities and manual history never create consent`() {
        val result = plan(null)
        assertEquals(LegacyAuthorityView.Absent, result.authority)
        assertEquals(history, (result.journal as LegacyJournalView.Retained).state)
        assertTrue(result.recipients.all { it.legacyConsent == null })
    }

    @Test fun `uid install and signer replacements cannot inherit legacy reference`() {
        for (changed in listOf(telegram.copy(uid = 10436), telegram.copy(firstInstallTime = 102),
            telegram.copy(signerSha256 = listOf("c".repeat(64))))) {
            assertEquals(RecipientCompatibility.BLOCKED_IDENTITY,
                plan(watched = listOf(changed)).recipients.single().compatibility)
            assertEquals(RecipientCompatibility.BLOCKED_IDENTITY,
                plan(watched = listOf(telegram), fresh = listOf(changed)).recipients.single().compatibility)
        }
        assertEquals(RecipientCompatibility.BLOCKED_IDENTITY,
            plan(watched = listOf(telegram), fresh = emptyList()).recipients.single().compatibility)
    }

    @Test fun `duplicate packages reject selection instead of choosing first or last`() {
        for (duplicate in listOf(telegram, telegram.copy(uid = 10436))) {
            val result = plan(watched = listOf(telegram, duplicate))
            assertEquals(RecipientPlanStatus.BLOCKED_SELECTION, result.status)
            assertTrue(result.recipients.isEmpty())
            val freshDuplicate = plan(watched = listOf(telegram), fresh = listOf(telegram, duplicate))
            assertEquals(RecipientPlanStatus.BLOCKED_SELECTION, freshDuplicate.status)
            assertNull(freshDuplicate.recipients.single().legacyConsent)
        }
    }

    @Test fun `unavailable authority selection and journal reads remain distinct from absence`() {
        val failure = IllegalStateException("unavailable")
        fun unavailable(authority: Result<ProtectionAuthoritySnapshot?> = Result.success(active),
            watched: Result<List<SelectedTargetIdentity>> = Result.success(listOf(telegram)),
            journal: Result<RecoveryJournalState> = Result.success(history)) = recipientProtectionPlan(
                authority, watched, Result.success(listOf(telegram)), journal)
        val authority = unavailable(authority = Result.failure(failure))
        assertEquals(RecipientPlanStatus.BLOCKED_AUTHORITY, authority.status)
        assertEquals(LegacyAuthorityView.Unavailable, authority.authority)
        assertNull(authority.recipients.single().legacyConsent)
        assertEquals(RecipientPlanStatus.BLOCKED_SELECTION, unavailable(watched = Result.failure(failure)).status)
        val journal = unavailable(journal = Result.failure(failure))
        assertEquals(RecipientPlanStatus.BLOCKED_JOURNAL, journal.status)
        assertEquals(LegacyJournalView.Unavailable, journal.journal)
        assertNull(journal.recipients.single().legacyConsent)
        assertEquals(LegacyJournalView.Retained(RecoveryJournalState()), plan(journal = RecoveryJournalState()).journal)
    }

    @Test fun `pending global journal blocks all references and preserves receipt metadata`() {
        for (stage in listOf(StoppedRecoveryStage.PREPARED, StoppedRecoveryStage.CLAIMED)) {
            val pending = history.copy(claimedHighWater = if (stage == StoppedRecoveryStage.PREPARED) 10 else 11,
                latest = record.copy(stage = stage, outcome = null))
            val result = plan(journal = pending)
            assertEquals(RecipientPlanStatus.BLOCKED_JOURNAL, result.status)
            assertTrue(result.recipients.all { it.compatibility == RecipientCompatibility.BLOCKED_INPUT && it.legacyConsent == null })
            assertEquals(pending, (result.journal as LegacyJournalView.Retained).state)
        }
    }

    @Test fun `global budget generation and history survive target edits without per-app reset`() {
        for (selection in listOf(emptyList(), listOf(other), listOf(telegram), listOf(telegram, other))) {
            val result = plan(watched = selection)
            val retained = result.authority as LegacyAuthorityView.Retained
            assertEquals(7L, retained.generation)
            assertEquals(budget, retained.sharedBudget)
            assertEquals(history, (result.journal as LegacyJournalView.Retained).state)
        }
        val many = (1..30).map { other.copy(packageName = "example.recipient$it", uid = 11000 + it) }
        assertEquals(30, plan(watched = many).recipients.size)
    }

    @Test fun `known stale generation blocks inert reference without discarding retained state`() {
        val result = plan(generation = 8)
        assertEquals(RecipientPlanStatus.BLOCKED_STALE_GENERATION, result.status)
        assertTrue(result.recipients.all { it.legacyConsent == null })
        assertEquals(7L, (result.authority as LegacyAuthorityView.Retained).generation)
        assertEquals(RecipientCompatibility.EXISTING_LEGACY_SCOPE, plan(generation = 7).recipients.first().compatibility)
    }

    @Test fun `caller mutable identities attempts and selection cannot change returned plan`() {
        val signers = mutableListOf("a".repeat(64))
        val identity = telegram.copy(signerSha256 = signers)
        val attempts = mutableListOf(0L, 300_000L, 600_000L)
        val watched = mutableListOf(identity, other)
        val mutableAuthority = active.copy(token = token.copy(identity = identity), budget = budget.copy(attempts = attempts))
        val mutableHistory = history.copy(latest = record.copy(identity = identity))
        val result = plan(mutableAuthority, watched, watched, mutableHistory)
        signers[0] = "c".repeat(64)
        attempts.clear()
        watched.clear()
        assertEquals(telegram, result.recipients.first().identity)
        assertEquals(budget, (result.authority as LegacyAuthorityView.Retained).sharedBudget)
        assertEquals(history, (result.journal as LegacyJournalView.Retained).state)
        assertThrows(UnsupportedOperationException::class.java) { (result.recipients as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.recipients.first().identity.signerSha256 as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { ((result.authority as LegacyAuthorityView.Retained).sharedBudget.attempts as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { ((result.journal as LegacyJournalView.Retained).state.latest!!.identity.signerSha256 as MutableList<*>).clear() }
    }

    @Test fun `strict legacy authority bytes and decoded journal are untouched by planning`() {
        val states = listOf(active, active.copy(token = token.copy(policyVersion = 1)),
            active.copy(mode = ProtectionConsentMode.DISABLED, token = null),
            active.copy(mode = ProtectionConsentMode.SUSPENDED, token = null, suspensionReason = ProtectionSuspensionReason.PENDING_RECEIPT))
        for (state in states) {
            val bytes = ProtectionAuthorityCodec.encode(state)
            val before = bytes.clone()
            val decoded = ProtectionAuthorityCodec.decode(bytes)
            plan(decoded)
            assertArrayEquals(before, bytes)
            assertArrayEquals(before, ProtectionAuthorityCodec.encode(decoded))
            assertEquals(state, decoded)
        }
        val bytes = RecoveryJournalCodec.encode(history)
        val before = bytes.clone()
        val decoded = RecoveryJournalCodec.decode(bytes)
        assertEquals(decoded, (plan(journal = decoded).journal as LegacyJournalView.Retained).state)
        assertArrayEquals(before, bytes)
    }

    @Test fun `strict codec failures cannot be promoted by the planner`() {
        val bytes = ProtectionAuthorityCodec.encode(active)
        val corrupt = bytes.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val payload = bytes.copyOf(bytes.size - 32)
        ByteBuffer.wrap(payload).putInt(4, 2)
        val unknownSchema = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        for (invalid in listOf(corrupt, bytes.copyOf(bytes.size - 1), unknownSchema)) {
            val read = runCatching { ProtectionAuthorityCodec.decode(invalid) }
            assertTrue(read.isFailure)
            val result = recipientProtectionPlan(read, Result.success(listOf(telegram)),
                Result.success(listOf(telegram)), Result.success(history))
            assertEquals(RecipientPlanStatus.BLOCKED_AUTHORITY, result.status)
            assertNull(result.recipients.single().legacyConsent)
        }
    }
}
