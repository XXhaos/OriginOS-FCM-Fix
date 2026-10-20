package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

/** The allocation-free membership predicate must retain the old runnable-list semantics. */
class ProtectionConsentReadEquivalenceTest {
    private val telegram = SelectedTargetIdentity("org.telegram.messenger", 10123, 100, listOf("a".repeat(64)))
    private val recipient = SelectedTargetIdentity("example.recipient", 10124, 200, listOf("b".repeat(64)))
    private val budget = BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null)
    private val legacyOne = TargetConsent.Legacy(DurableConsentToken(
        "10000000-0000-0000-0000-000000000001", 3, 1_000, telegram, VERIFIED_AUTOSTART_FINGERPRINT))
    private val legacyTwo = TargetConsent.Legacy(legacyOne.value.copy(policyVersion = 2))
    private val fresh = TargetConsent.Recipient(RecipientConsentToken(
        "10000000-0000-0000-0000-000000000002", 2, 1_000, recipient, VERIFIED_AUTOSTART_FINGERPRINT))

    private class Storage(var value: ProtectionAuthoritySnapshot?) : ProtectionAuthorityStorage {
        var reads = 0
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot? { reads++; return value }
        override fun write(value: ProtectionAuthoritySnapshot) = error("Read-only comparison must not write")
    }

    private fun states(): List<ProtectionAuthoritySnapshot?> {
        val result = mutableListOf<ProtectionAuthoritySnapshot?>(null)
        for (token in listOf(legacyOne.value, legacyTwo.value)) {
            result += ProtectionAuthoritySnapshot(3, ProtectionConsentMode.ACTIVE, token, null, budget)
        }
        for (mode in listOf(ProtectionConsentMode.DISABLED, ProtectionConsentMode.SUSPENDED)) {
            result += ProtectionAuthoritySnapshot(3, mode, null,
                ProtectionSuspensionReason.PREREQUISITES_LOST.takeIf { mode == ProtectionConsentMode.SUSPENDED }, budget)
        }
        for (token in listOf(legacyOne, legacyTwo)) {
            result += ProtectionAuthoritySnapshot(3, ProtectionConsentMode.ACTIVE, null, null, budget,
                listOf(fresh, token), schemaVersion = 2)
        }
        result += ProtectionAuthoritySnapshot(3, ProtectionConsentMode.DISABLED, null, null, budget, schemaVersion = 2)
        for (mode in ProtectionConsentMode.entries) {
            for (invalidated in listOf(emptyList(), listOf(fresh.id), listOf(legacyTwo.id), listOf(legacyTwo.id, fresh.id))) {
                if (mode == ProtectionConsentMode.DISABLED && invalidated.isNotEmpty()) continue
                val consents = if (mode == ProtectionConsentMode.DISABLED) emptyList() else listOf(fresh, legacyTwo)
                result += ProtectionAuthoritySnapshot(3, mode, null,
                    ProtectionSuspensionReason.IDENTITY_CHANGED.takeIf { mode == ProtectionConsentMode.SUSPENDED },
                    budget, consents, schemaVersion = 3,
                    masterEnabledAtMillis = 1_000, invalidatedConsentIds = invalidated)
            }
        }
        return result.flatMap { state -> if (state == null) listOf(null)
            else listOf(state, state.copy(storageBlocked = true)) }
    }

    private fun candidates(): List<TargetConsent> = listOf(legacyOne, legacyTwo, fresh,
        TargetConsent.Legacy(legacyTwo.value.copy(identity = telegram.copy(firstInstallTime = 101))),
        TargetConsent.Legacy(legacyTwo.value.copy(generation = 2)),
        TargetConsent.Recipient(fresh.value.copy(id = "10000000-0000-0000-0000-000000000003")),
        TargetConsent.Recipient(fresh.value.copy(identity = recipient.copy(uid = 10125))),
        TargetConsent.Recipient(fresh.value.copy(identity = recipient.copy(signerSha256 = listOf("c".repeat(64))))),
        TargetConsent.Recipient(fresh.value.copy(consentedAtMillis = 1_001)),
        TargetConsent.Recipient(fresh.value.copy(fingerprint = "other/device/product:17/BUILD/123:user/release-keys")))

    @Test fun everySchemaAndGateMatchesThePreviousRunnableListPredicate() {
        for (state in states()) {
            val storage = Storage(state)
            val store = ProtectionConsentStore(storage)
            for (generation in listOf(2L, 3L, 4L)) {
                for (consent in candidates()) {
                    val lease = TargetConsentLease(generation, consent)
                    val expected = state != null && state.generation == generation && state.runnableConsents().contains(consent)
                    assertEquals("schema=${state?.schemaVersion} mode=${state?.mode} blocked=${state?.storageBlocked} lease=$lease",
                        expected, store.verifyAuthorized(lease))
                }
            }
            assertEquals(3 * candidates().size, storage.reads)
        }
    }

    @Test fun repeatedChecksReReadOffInvalidationAndGenerationRatherThanCachingAnAnswer() {
        val active = ProtectionAuthoritySnapshot(3, ProtectionConsentMode.ACTIVE, null, null, budget,
            listOf(fresh), schemaVersion = 3, masterEnabledAtMillis = 1_000)
        val storage = Storage(active)
        val store = ProtectionConsentStore(storage)
        val lease = TargetConsentLease(3, fresh)
        assertTrue(store.verifyAuthorized(lease))
        storage.value = active.copy(mode = ProtectionConsentMode.USER_PAUSED)
        assertFalse(store.verifyAuthorized(lease))
        storage.value = active.copy(invalidatedConsentIds = listOf(fresh.id))
        assertFalse(store.verifyAuthorized(lease))
        storage.value = active.copy(generation = 4)
        assertFalse(store.verifyAuthorized(lease))
        storage.value = active.copy(storageBlocked = true)
        assertFalse(store.verifyAuthorized(lease))
        storage.value = active
        assertTrue(store.verifyAuthorized(lease))
        assertEquals(6, storage.reads)
    }
}
