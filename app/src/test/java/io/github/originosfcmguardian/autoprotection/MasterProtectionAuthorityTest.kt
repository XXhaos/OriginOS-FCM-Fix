package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Pure authority/codec tests: no Android service, device, journal mutation or privileged dispatch. */
class MasterProtectionAuthorityTest {
    private fun clock(elapsed: Long = 600_000) = BudgetClock(14, elapsed, 1_000_000 + elapsed)
    private val budget = BudgetSnapshot(clock(), listOf(0L, 300_000L, 600_000L), null)
    private fun identity(index: Int) = SelectedTargetIdentity("example.recipient$index", 11000 + index,
        100L + index, listOf("a".repeat(64)))
    private fun consent(index: Int) = TargetConsent.Recipient(RecipientConsentToken(
        UUID.nameUUIDFromBytes("target$index".toByteArray()).toString(), 1, 1_000L + index,
        identity(index), VERIFIED_AUTOSTART_FINGERPRINT))
    private fun schema2() = ProtectionAuthoritySnapshot(20, ProtectionConsentMode.ACTIVE, null, null,
        budget, (0..16).map(::consent).sortedBy { it.identity.packageName }, 2)
    private class Storage(initial: ProtectionAuthoritySnapshot? = null) : ProtectionAuthorityStorage {
        var bytes = initial?.let(ProtectionAuthorityCodec::encode)
        var failWrite = false
        var marker = false
        var writes = 0
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot? = bytes?.let(ProtectionAuthorityCodec::decode)?.let {
            if (marker) authorityWithRetirementMarker(it) else it
        }
        override fun write(value: ProtectionAuthoritySnapshot) {
            check(!marker && !failWrite)
            val encoded = ProtectionAuthorityCodec.encode(value)
            check(ProtectionAuthorityCodec.decode(encoded) == value)
            bytes = encoded
            writes++
        }
    }

    @Test fun schema1And2ReadAndRoundTripNeverMigrateOrWrite() {
        val legacyIdentity = identity(0).copy(packageName = "org.telegram.messenger")
        val legacy = DurableConsentToken(UUID.randomUUID().toString(), 1, 1_000, legacyIdentity,
            VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
        for (initial in listOf(ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, legacy, null, budget), schema2())) {
            val storage = Storage(initial)
            val before = storage.bytes!!.clone()
            val store = ProtectionConsentStore(storage)
            assertEquals(initial.consents(), store.configuredConsents())
            assertEquals(initial, store.readSnapshot())
            store.desiredLeases()
            assertEquals(0, storage.writes)
            assertArrayEquals(before, storage.bytes)
            assertArrayEquals(before, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(before)))
        }
    }

    @Test fun seventeenChoicesSurviveMasterOffReloadOnWithExactTokensAndBudget() {
        val initial = schema2()
        val storage = Storage(initial)
        val store = ProtectionConsentStore(storage)
        val old = store.desiredLeases()
        assertTrue(store.pauseMaster())
        val paused = store.readSnapshot()!!
        assertEquals(3, paused.schemaVersion)
        assertEquals(ProtectionConsentMode.USER_PAUSED, paused.mode)
        assertEquals(21L, paused.generation)
        assertEquals(1_000L, paused.masterEnabledAtMillis)
        assertEquals(initial.consents(), paused.targetConsents)
        assertEquals(initial.budget, paused.budget)
        val restarted = ProtectionConsentStore(storage)
        assertEquals(17, restarted.configuredConsents().size)
        assertTrue(restarted.desiredLeases().isEmpty())
        old.forEach { assertFalse(restarted.verifyAuthorized(it)) }
        assertTrue(restarted.enableMaster(restarted.readSnapshot(), clock(600_001)))
        val enabled = restarted.readSnapshot()!!
        assertEquals(22L, enabled.generation)
        assertEquals(clock(600_001).epoch, enabled.masterEnabledAtMillis)
        assertEquals(initial.consents(), enabled.targetConsents)
        assertEquals(initial.budget, enabled.budget)
        old.forEach { assertFalse(restarted.verifyAuthorized(it)) }
        assertEquals(17, restarted.desiredLeases().size)
        assertTrue(restarted.desiredLeases().all(restarted::verifyAuthorized))
    }

    @Test fun addInitiallyOffAndWhileSuspendedNeverEnablesMaster() {
        for (initial in listOf(null, schema2().configuredSchema3().copy(mode = ProtectionConsentMode.USER_PAUSED),
            schema2().configuredSchema3().copy(mode = ProtectionConsentMode.SUSPENDED,
                suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE))) {
            val store = ProtectionConsentStore(Storage(initial))
            val added = store.grantRecipientExplicit(identity(99), VERIFIED_AUTOSTART_FINGERPRINT, clock())
            assertEquals(initial?.mode ?: ProtectionConsentMode.USER_PAUSED, store.readMode())
            assertTrue(store.desiredLeases().isEmpty())
            assertFalse(store.verifyAuthorized(added))
            assertTrue(store.configuredConsents().contains(added.consent))
            assertEquals(initial?.masterEnabledAtMillis, store.readSnapshot()!!.masterEnabledAtMillis)
        }
    }

    @Test fun activeAddPreservesOldestHistoryBoundaryAndEveryExistingToken() {
        val initial = schema2()
        val store = ProtectionConsentStore(Storage(initial))
        store.grantRecipientExplicit(identity(99), VERIFIED_AUTOSTART_FINGERPRINT, clock())
        val after = store.readSnapshot()!!
        assertEquals(ProtectionConsentMode.ACTIVE, after.mode)
        assertEquals(initial.consents().minOf { it.consentedAtMillis }, after.masterEnabledAtMillis)
        assertTrue(after.targetConsents.containsAll(initial.consents()))
        assertEquals(initial.budget, after.budget)
        assertEquals(18, store.desiredLeases().size)
    }

    @Test fun exactTargetOffNeverFlipsMasterIncludingRemovingLastChoice() {
        for (mode in listOf(ProtectionConsentMode.ACTIVE, ProtectionConsentMode.USER_PAUSED,
            ProtectionConsentMode.SUSPENDED)) {
            val base = schema2().configuredSchema3().copy(mode = mode,
                suspensionReason = if (mode == ProtectionConsentMode.SUSPENDED) ProtectionSuspensionReason.PENDING_RECEIPT else null)
            val store = ProtectionConsentStore(Storage(base))
            val old = store.desiredLeases()
            for (target in base.targetConsents) assertTrue(store.revokeTarget(target.identity))
            assertEquals(mode, store.readMode())
            assertTrue(store.configuredConsents().isEmpty())
            assertTrue(store.desiredLeases().isEmpty())
            assertEquals(base.budget, store.readSnapshot()!!.budget)
            old.forEach { assertFalse(store.verifyAuthorized(it)) }
        }
    }

    @Test fun suspensionKeepsAllExactChoicesAndInvalidatesOldLease() {
        val store = ProtectionConsentStore(Storage(schema2()))
        val old = store.desiredLeases()
        assertTrue(store.suspendLease(old.first(), ProtectionSuspensionReason.PENDING_RECEIPT))
        assertEquals(schema2().consents(), store.configuredConsents())
        assertTrue(store.desiredLeases().isEmpty())
        old.forEach { assertFalse(store.verifyAuthorized(it)) }
        assertFalse(store.suspendLease(old.first(), ProtectionSuspensionReason.SERVICE_UNAVAILABLE))
    }

    @Test fun invalidationKeepsCheckboxAndCannotDispatchOldBindingEvenAfterMasterOn() {
        val store = ProtectionConsentStore(Storage(schema2()))
        val old = store.desiredLeases()
        val invalid = old.first()
        assertTrue(store.revokeConfirmedTargets(old, listOf(invalid)))
        assertEquals(schema2().consents(), store.configuredConsents())
        assertFalse(store.verifyAuthorized(invalid))
        assertEquals(16, store.desiredLeases().size)
        store.pauseMaster()
        assertTrue(store.enableMaster(store.readSnapshot(), clock()))
        assertEquals(16, store.desiredLeases().size)
        assertFalse(store.desiredLeases().any { it.consent == invalid.consent })
        val changed = invalid.consent.identity.copy(firstInstallTime = 999)
        val newConsent = store.grantRecipientExplicit(changed, VERIFIED_AUTOSTART_FINGERPRINT, clock())
        assertTrue(store.verifyAuthorized(newConsent))
        assertTrue(store.readSnapshot()!!.invalidatedConsentIds.isEmpty())
        assertEquals(17, store.configuredConsents().size)
        assertFalse(store.configuredConsents().contains(invalid.consent))
    }

    @Test fun ambiguousMarkerRetainsReadableChoicesButRejectsEveryOrdinaryMutation() {
        val storage = Storage(schema2()).apply { marker = true }
        val before = storage.bytes!!.clone()
        val store = ProtectionConsentStore(storage)
        val blocked = store.readSnapshot()!!
        assertTrue(blocked.storageBlocked)
        assertEquals(schema2().consents(), store.configuredConsents())
        assertEquals(schema2().budget, blocked.budget)
        assertTrue(store.desiredLeases().isEmpty())
        for (mutation in listOf<() -> Unit>(
            { store.enableMaster(blocked, clock()) }, { store.pauseMaster() }, { store.pauseMaster(clock()) },
            { store.grantRecipientExplicit(identity(99), VERIFIED_AUTOSTART_FINGERPRINT, clock()) },
            { store.revokeTarget(identity(0)) }, { store.suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE) },
            { store.revokeConfirmedTargets(emptyList(), emptyList()) })) {
            assertThrows(IllegalStateException::class.java) { mutation() }
            assertArrayEquals(before, storage.bytes)
        }
        assertThrows(IllegalArgumentException::class.java) { ProtectionAuthorityCodec.encode(blocked) }
        assertTrue(storage.marker)
        assertEquals(0, storage.writes)
    }

    @Test fun staleOnClockRegressionAndWriteFailureCannotReplaceAuthority() {
        val storage = Storage(schema2())
        val store = ProtectionConsentStore(storage)
        val old = store.readSnapshot()
        store.pauseMaster()
        val paused = store.readSnapshot()
        val bytes = storage.bytes!!.clone()
        assertFalse(store.enableMaster(old, clock()))
        for (bad in listOf(clock().copy(boot = 13), clock().copy(elapsed = -1), clock().copy(epoch = 0),
            clock(599_999), clock().copy(epoch = clock().epoch - 1), clock().copy(epoch = clock().epoch + 5_001))) {
            assertFalse(store.enableMaster(paused, bad))
            assertArrayEquals(bytes, storage.bytes)
        }
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.enableMaster(paused, clock()) }
        assertThrows(IllegalStateException::class.java) { store.pauseMaster() }
        assertArrayEquals(bytes, storage.bytes)
        assertTrue(store.desiredLeases().isEmpty())
        assertEquals(17, store.configuredConsents().size)
    }

    @Test fun emptyMasterStatePersistsAndLegacyEmptyOffNeverManufacturesChoices() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        assertTrue(store.pauseMaster(clock()))
        assertEquals(ProtectionConsentMode.USER_PAUSED, store.readMode())
        assertTrue(store.enableMaster(store.readSnapshot(), clock()))
        assertEquals(ProtectionConsentMode.ACTIVE, store.readMode())
        assertTrue(store.desiredLeases().isEmpty())
        assertTrue(store.configuredConsents().isEmpty())
        for (mode in listOf(ProtectionConsentMode.DISABLED, ProtectionConsentMode.SUSPENDED)) {
            val initial = ProtectionAuthoritySnapshot(20, mode, null,
                if (mode == ProtectionConsentMode.SUSPENDED) ProtectionSuspensionReason.SERVICE_UNAVAILABLE else null, budget)
            val oldStore = ProtectionConsentStore(Storage(initial))
            assertTrue(oldStore.enableMaster(oldStore.readSnapshot(), clock()))
            assertTrue(oldStore.configuredConsents().isEmpty())
            assertTrue(oldStore.desiredLeases().isEmpty())
        }
    }

    @Test fun competingMasterOnRequestsConsumeOnlyTheirExactObservedRoot() {
        val storage = Storage(schema2())
        val store = ProtectionConsentStore(storage)
        store.pauseMaster()
        val expected = store.readSnapshot()!!
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val owners = List(2) {
            Thread {
                start.await()
                results.add(ProtectionConsentStore(storage).enableMaster(expected, clock()))
            }.apply { start() }
        }
        start.countDown()
        owners.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it })
        assertEquals(expected.generation + 1, store.readSnapshot()!!.generation)
        assertEquals(expected.targetConsents, store.configuredConsents())
        assertEquals(expected.budget, store.readSnapshot()!!.budget)
        // A later OFF invalidates the winning generation; stale ON cannot reverse it.
        val admitted = store.desiredLeases()
        store.pauseMaster()
        assertFalse(store.enableMaster(expected, clock()))
        admitted.forEach { assertFalse(store.verifyAuthorized(it)) }
        assertEquals(ProtectionConsentMode.USER_PAUSED, store.readMode())
    }
}
