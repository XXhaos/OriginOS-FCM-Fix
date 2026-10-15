package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class WholeConsentAuthorizationTest {
    private fun consent(index: Int) = TargetConsent.Recipient(RecipientConsentToken(
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", 1, 100,
        SelectedTargetIdentity("example.app${index.toString().padStart(2, '0')}", 10000 + index,
            90, listOf("a".repeat(64))), VERIFIED_AUTOSTART_FINGERPRINT))
    private fun state() = ProtectionAuthoritySnapshot(4, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 10, 100), emptyList(), null),
        targetConsents = (1..21).map(::consent), schemaVersion = 3, masterEnabledAtMillis = 100)
    private fun leases(value: ProtectionAuthoritySnapshot) = value.targetConsents.map { TargetConsentLease(value.generation, it) }
    private class Storage(var value: ProtectionAuthoritySnapshot?) : ProtectionAuthorityStorage {
        var reads = 0; var locks = 0; var locked = false
        var failRead = false; var failLock = false
        override fun <T> exclusive(action: () -> T): T {
            locks++; check(!failLock); check(!locked); locked = true
            return try { action() } finally { locked = false }
        }
        override fun read(): ProtectionAuthoritySnapshot? { check(locked); reads++; check(!failRead); return value }
        override fun write(value: ProtectionAuthoritySnapshot) = error("Read-only gate cannot write")
    }

    @Test fun `twenty one leases read one fresh snapshot in one lock without writing`() {
        val current = state(); val storage = Storage(current)
        assertTrue(ProtectionConsentStore(storage).verifyAllAuthorized(leases(current)))
        assertEquals(1, storage.reads); assertEquals(1, storage.locks); assertEquals(current, storage.value)
    }

    @Test fun `each valid and invalid list agrees with former single target gates`() {
        val current = state(); val storage = Storage(current); val store = ProtectionConsentStore(storage)
        val full = leases(current)
        val cases = listOf(full, full.take(3), full + full.first(),
            full.dropLast(1) + full.last().copy(rootGeneration = 3),
            full.dropLast(1) + TargetConsentLease(4, consent(22)))
        for (test in cases) assertEquals(test.all { store.verifyAuthorized(it) }, store.verifyAllAuthorized(test))
    }

    @Test fun `off suspension and interrupted storage block the complete gate`() {
        val current = state(); val full = leases(current)
        for (blocked in listOf(current.copy(mode = ProtectionConsentMode.USER_PAUSED),
            current.copy(mode = ProtectionConsentMode.SUSPENDED, suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE),
            current.copy(storageBlocked = true), current.copy(invalidatedConsentIds = listOf(current.targetConsents.last().id)))) {
            assertFalse(ProtectionConsentStore(Storage(blocked)).verifyAllAuthorized(full))
        }
    }

    @Test fun `replaced identity or exact token and mixed roots cannot pass`() {
        val current = state(); val full = leases(current)
        val old = current.targetConsents.first() as TargetConsent.Recipient
        val replaced = old.copy(value = old.value.copy(identity = old.identity.copy(firstInstallTime = 91)))
        val storage = Storage(current.copy(targetConsents = listOf(replaced) + current.targetConsents.drop(1)))
        assertFalse(ProtectionConsentStore(storage).verifyAllAuthorized(full))
        storage.value = current.copy(targetConsents = listOf(old.copy(value = old.value.copy(
            id = "11111111-1111-1111-1111-111111111111"))) + current.targetConsents.drop(1))
        assertFalse(ProtectionConsentStore(storage).verifyAllAuthorized(full))
        storage.value = current
        assertFalse(ProtectionConsentStore(storage).verifyAllAuthorized(full.dropLast(1) + full.last().copy(rootGeneration = 5)))
    }

    @Test fun `a new call sees off removal and marker projection rather than prior success`() {
        val current = state(); val storage = Storage(current); val store = ProtectionConsentStore(storage); val full = leases(current)
        assertTrue(store.verifyAllAuthorized(full))
        storage.value = current.copy(mode = ProtectionConsentMode.USER_PAUSED, generation = 5)
        assertFalse(store.verifyAllAuthorized(full))
        storage.value = current
        assertTrue(store.verifyAllAuthorized(full))
        storage.value = current.copy(targetConsents = current.targetConsents.dropLast(1))
        assertFalse(store.verifyAllAuthorized(full))
        storage.value = current.copy(storageBlocked = true)
        assertFalse(store.verifyAllAuthorized(full))
        assertEquals(5, storage.reads)
    }

    @Test fun `missing unreadable and unavailable lock fail closed`() {
        val full = leases(state())
        assertFalse(ProtectionConsentStore(Storage(null)).verifyAllAuthorized(full))
        assertFalse(ProtectionConsentStore(Storage(state()).apply { failRead = true }).verifyAllAuthorized(full))
        assertFalse(ProtectionConsentStore(Storage(state()).apply { failLock = true }).verifyAllAuthorized(full))
    }

    @Test fun `empty list retains vacuous semantics without storage access or grant`() {
        val storage = Storage(null).apply { failRead = true; failLock = true }
        assertTrue(ProtectionConsentStore(storage).verifyAllAuthorized(emptyList()))
        assertEquals(0, storage.reads); assertEquals(0, storage.locks); assertNull(storage.value)
    }
}
