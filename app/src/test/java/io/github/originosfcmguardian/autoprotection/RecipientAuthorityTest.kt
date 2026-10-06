package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class RecipientAuthorityTest {
    private val telegram = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val other = SelectedTargetIdentity("example.recipient", 10435, 101, listOf("b".repeat(64)))
    private fun clock(elapsed: Long = 0, boot: Long = 14) = BudgetClock(boot, elapsed, 1_000_000 + elapsed)
    private val budget = BudgetSnapshot(clock(600_000), listOf(0L, 300_000L, 600_000L), null)
    private val legacy = DurableConsentToken("b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 7, 90,
        telegram, VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
    private class Storage(initial: ProtectionAuthoritySnapshot? = null) : ProtectionAuthorityStorage {
        var bytes = initial?.let(ProtectionAuthorityCodec::encode)
        var failed = false
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot? { check(!failed); return bytes?.let(ProtectionAuthorityCodec::decode) }
        override fun write(value: ProtectionAuthoritySnapshot) { check(!failed); bytes = ProtectionAuthorityCodec.encode(value); check(read() == value) }
    }
    private fun initial(mode: ProtectionConsentMode = ProtectionConsentMode.ACTIVE) = ProtectionAuthoritySnapshot(
        7, mode, if (mode == ProtectionConsentMode.ACTIVE) legacy else null,
        if (mode == ProtectionConsentMode.SUSPENDED) ProtectionSuspensionReason.SERVICE_UNAVAILABLE else null, budget)
    private fun grant(store: ProtectionConsentStore, identity: SelectedTargetIdentity = other, elapsed: Long = 600_000): TargetConsentLease {
        val added = store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(elapsed))
        if (store.readMode() != ProtectionConsentMode.ACTIVE) assertTrue(store.enableMaster(store.readSnapshot(), clock(elapsed)))
        return store.desiredLeases().single { it.consent == added.consent }
    }

    @Test fun `legacy off and active readonly inspection preserves schema1 bytes exactly`() {
        for (mode in ProtectionConsentMode.entries.take(3)) {
            val storage = Storage(initial(mode)); val before = storage.bytes!!.clone()
            val store = ProtectionConsentStore(storage)
            store.readSnapshot(); val leases = store.desiredLeases()
            assertEquals(if (mode == ProtectionConsentMode.ACTIVE) 1 else 0, leases.size)
            assertArrayEquals(before, storage.bytes)
            assertArrayEquals(before, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(before)))
        }
    }

    @Test fun `explicit recipient grant preserves legacy token and shared budget with new root lease`() {
        val storage = Storage(initial()); val store = ProtectionConsentStore(storage)
        val oldLease = store.desiredLeases().single()
        val added = grant(store)
        val state = store.readSnapshot()!!
        assertEquals(3, state.schemaVersion); assertEquals(8L, state.generation)
        assertEquals(legacy.consentedAtMillis, state.masterEnabledAtMillis)
        assertEquals(budget, state.budget)
        assertEquals(legacy, (state.targetConsents.single { it is TargetConsent.Legacy } as TargetConsent.Legacy).value)
        assertFalse(store.verifyAuthorized(oldLease)); assertTrue(store.verifyAuthorized(added))
        assertEquals(4, added.consent.policyVersion)
        assertTrue(store.desiredLeases().all { store.verifyAuthorized(it) && it.rootGeneration == 8L })
    }

    @Test fun `off suspended and policy1 do not gain legacy restart through new target`() {
        for (prior in listOf(initial(ProtectionConsentMode.DISABLED), initial(ProtectionConsentMode.SUSPENDED),
            initial().copy(token = legacy.copy(policyVersion = 1)))) {
            val store = ProtectionConsentStore(Storage(prior))
            store.grantRecipientExplicit(other, VERIFIED_AUTOSTART_FINGERPRINT, clock(600_000))
            assertEquals(if (prior.mode == ProtectionConsentMode.ACTIVE) listOf(other) else emptyList<SelectedTargetIdentity>(),
                store.desiredLeases().map { it.consent.identity })
            assertEquals(budget, store.readSnapshot()!!.budget)
        }
    }

    @Test fun `exact target removal keeps other token and root invalidates every old lease`() {
        val store = ProtectionConsentStore(Storage(initial())); grant(store)
        val leases = store.desiredLeases(); val survivor = leases.single { it.consent.identity == telegram }.consent
        assertTrue(store.revokeTarget(other))
        assertTrue(leases.none { store.verifyAuthorized(it) })
        assertEquals(survivor, store.desiredLeases().single().consent)
        assertEquals(budget, store.readSnapshot()!!.budget)
        assertTrue(store.revoke()); assertTrue(store.desiredLeases().isEmpty())
        assertEquals(budget, store.readSnapshot()!!.budget)
    }

    @Test fun `replacement needs explicit grant and stale removal never retires new install`() {
        val store = ProtectionConsentStore(Storage()); val old = grant(store, other, 0)
        val replacement = other.copy(uid = 10436, firstInstallTime = 102, signerSha256 = listOf("c".repeat(64)))
        val fresh = grant(store, replacement, 1)
        assertFalse(store.verifyAuthorized(old)); assertTrue(store.verifyAuthorized(fresh))
        assertTrue(store.revokeTarget(other)); assertTrue(store.verifyAuthorized(fresh))
        assertEquals(listOf(replacement), store.desiredLeases().map { it.consent.identity })
    }

    @Test fun `global cooldown and hourly ceiling survive target additions removal and regrant`() {
        val store = ProtectionConsentStore(Storage())
        val first = grant(store, telegram, 0)
        assertEquals(AutomaticCheckDecision.Recover, store.take(first, clock()))
        grant(store, other, 1)
        val second = store.desiredLeases().single { it.consent.identity == other }
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), store.take(second, clock(1)))
        assertEquals(AutomaticCheckDecision.Recover, store.take(second, clock(300_000)))
        store.revokeTarget(other); grant(store, other, 300_001)
        val third = store.desiredLeases().single { it.consent.identity == other }
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), store.take(third, clock(300_001)))
        assertEquals(AutomaticCheckDecision.Recover, store.take(third, clock(600_000)))
        grant(store, telegram, 900_000)
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000), store.take(store.desiredLeases().first(), clock(900_000)))
        assertEquals(listOf(0L, 300_000L, 600_000L), store.readSnapshot()!!.budget.attempts)
    }

    @Test fun `reboot quarantines old global use and target changes cannot reset quarantine`() {
        val store = ProtectionConsentStore(Storage(initial())); grant(store)
        val lease = store.desiredLeases().first()
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), store.take(lease, clock(0, 15)))
        store.revokeTarget(other)
        store.grantRecipientExplicit(other, VERIFIED_AUTOSTART_FINGERPRINT, clock(1, 15))
        assertEquals(AutomaticCheckDecision.Cooldown(3_599_999), store.take(store.desiredLeases().first(), clock(1, 15)))
    }

    @Test fun `hour wait preserves both exact consents across reconstructed owners and explicit off still wins`() {
        val storage = Storage(initial())
        val owner = ProtectionConsentStore(storage)
        grant(owner)
        val leases = owner.desiredLeases()
        val before = owner.readSnapshot()!!
        for (elapsed in listOf(900_000L, 1_800_000L, 3_599_999L)) {
            val reconstructed = ProtectionConsentStore(storage)
            for (lease in leases) {
                assertEquals(AutomaticCheckDecision.Cooldown(3_600_000 - elapsed), reconstructed.checkBudget(lease, clock(elapsed)))
                assertEquals(AutomaticCheckDecision.Cooldown(3_600_000 - elapsed), reconstructed.take(lease, clock(elapsed)))
                assertTrue(reconstructed.verifyAuthorized(lease))
            }
            val after = reconstructed.readSnapshot()!!
            assertEquals(before.copy(budget = after.budget), after)
            assertEquals(before.budget.attempts, after.budget.attempts)
            assertEquals(leases, reconstructed.desiredLeases())
        }
        assertTrue(owner.revoke())
        val afterOff = ProtectionConsentStore(storage)
        assertTrue(afterOff.desiredLeases().isEmpty())
        leases.forEach { lease ->
            assertFalse(afterOff.verifyAuthorized(lease))
            assertTrue(afterOff.take(lease, clock(3_600_000)) is AutomaticCheckDecision.Pause)
        }
        assertEquals(before.budget.attempts, afterOff.readSnapshot()!!.budget.attempts)
    }

    @Test fun `late forward boot expires only old budget and keeps exact recipient consents`() {
        val storage = Storage(initial())
        val owner = ProtectionConsentStore(storage)
        grant(owner)
        val leases = owner.desiredLeases()
        val before = owner.readSnapshot()!!
        val now = clock(4_000_000, 15)
        val restarted = ProtectionConsentStore(storage)
        for (lease in leases) {
            assertEquals(AutomaticCheckDecision.Recover, restarted.checkBudget(lease, now))
            assertTrue(restarted.verifyAuthorized(lease))
        }
        val after = restarted.readSnapshot()!!
        assertEquals(before.copy(budget = after.budget), after)
        assertEquals(leases, restarted.desiredLeases())
        assertTrue(after.budget.attempts.isEmpty())
        assertNull(after.budget.quarantineUntil)
        assertEquals(AutomaticCheckDecision.Recover, restarted.take(leases.first(), now))
        assertEquals(AutomaticCheckDecision.Cooldown(300_000), restarted.take(leases.last(), now))
        assertTrue(restarted.revoke())
        leases.forEach { assertTrue(restarted.take(it, clock(4_300_000, 15)) is AutomaticCheckDecision.Pause) }
        assertEquals(listOf(4_000_000L), restarted.readSnapshot()!!.budget.attempts)
    }

    @Test fun `failure and stale generation issue no budget permit or replacement authority`() {
        val storage = Storage(initial()); val store = ProtectionConsentStore(storage); val lease = grant(store)
        val bytes = storage.bytes!!.clone(); storage.failed = true
        assertFalse(store.verifyAuthorized(lease)); assertTrue(store.take(lease, clock(900_000)) is AutomaticCheckDecision.Pause)
        assertThrows(IllegalStateException::class.java) { grant(store) }
        assertArrayEquals(bytes, storage.bytes)
        storage.failed = false; grant(store, telegram, 900_000)
        assertFalse(store.suspendLease(lease, ProtectionSuspensionReason.SERVICE_UNAVAILABLE))
        assertTrue(store.desiredLeases().isNotEmpty())
    }

    @Test fun `schema2 strict decoding rejects unknown scope policy duplicate package and every truncation`() {
        val store = ProtectionConsentStore(Storage(initial())); grant(store)
        val state = store.readSnapshot()!!.copy(schemaVersion = 2, masterEnabledAtMillis = null)
        val bytes = ProtectionAuthorityCodec.encode(state)
        assertArrayEquals(bytes, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(bytes)))
        for (size in bytes.indices) assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(bytes.copyOf(size)) }
        val payload = bytes.copyOf(bytes.size - 32)
        // schema2 fixed root header is 21 bytes, followed by target count and first scope.
        payload[25] = 2
        assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload)) }
        assertThrows(IllegalArgumentException::class.java) { (state.targetConsents.filterIsInstance<TargetConsent.Recipient>().single().value).copy(policyVersion = 3) }
        assertThrows(IllegalArgumentException::class.java) { state.copy(targetConsents = state.targetConsents + state.targetConsents.first()) }
        assertThrows(IllegalArgumentException::class.java) { state.copy(generation = 1) }
        assertEquals(2, ByteBuffer.wrap(bytes).getInt(4))
    }

    @Test fun `store snapshots freeze signer target and budget lists without app-count cap`() {
        val storage = Storage(); val store = ProtectionConsentStore(storage)
        val signers = mutableListOf("b".repeat(64)); grant(store, other.copy(signerSha256 = signers), 0)
        signers[0] = "c".repeat(64)
        for (index in 1..20) grant(store, other.copy(packageName = "example.recipient$index", uid = 11000 + index), index.toLong())
        val snapshot = store.readSnapshot()!!
        assertEquals(21, snapshot.targetConsents.size)
        assertEquals(other, snapshot.targetConsents.single { it.identity.packageName == other.packageName }.identity)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.targetConsents as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.targetConsents.first().identity.signerSha256 as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.budget.attempts as MutableList<*>).clear() }
    }

    @Test fun `partial active target removal or replacement requires durable marker and retires old file`() {
        val store = ProtectionConsentStore(Storage(initial())); grant(store)
        val prior = store.readSnapshot()!!
        store.revokeTarget(other)
        val removed = store.readSnapshot()!!
        assertEquals(ProtectionConsentMode.ACTIVE, removed.mode)
        assertTrue(requiresAuthorityRetirementMarker(prior, removed))
        grant(store, other); val added = store.readSnapshot()!!
        assertFalse(requiresAuthorityRetirementMarker(removed, added))
        grant(store, other, 600_001); val replaced = store.readSnapshot()!!
        assertTrue(requiresAuthorityRetirementMarker(added, replaced))
        val partialRetirement = authorityWithRetirementMarker(ProtectionAuthorityCodec.decode(ProtectionAuthorityCodec.encode(prior)))
        assertEquals(ProtectionConsentMode.SUSPENDED, partialRetirement.mode)
        assertEquals(prior.consents(), partialRetirement.consents())
        assertTrue(partialRetirement.storageBlocked)
        assertTrue(partialRetirement.runnableConsents().isEmpty())
        assertEquals(prior.generation, partialRetirement.generation)
        assertEquals(prior.budget, partialRetirement.budget)
        assertFalse(requiresAuthorityRetirementMarker(added, added.copy(budget = added.budget.copy(clock = clock(600_001)))))
    }

    @Test fun `one recipient recovery never becomes another recipient check evidence`() {
        val state = AutomaticProtectionState(targets = listOf(
            AutomaticProtectionTargetState(telegram, savedAuthorization = true),
            AutomaticProtectionTargetState(other, savedAuthorization = true)))
        val recovered = updateAutomaticProtectionState(state, "recovered", checkedAtMillis = 1000,
            outcome = "FLAG_CLEARED", identity = telegram)
        val checked = updateAutomaticProtectionState(recovered, "checked", checkedAtMillis = 2000, identity = other)
        assertEquals("FLAG_CLEARED", checked.lastOutcome)
        assertEquals(2000L, checked.lastCheckedAtMillis)
        assertEquals("FLAG_CLEARED", checked.targets[0].lastOutcome)
        assertEquals(1000L, checked.targets[0].lastCheckedAtMillis)
        assertNull(checked.targets[1].lastOutcome)
        assertEquals(2000L, checked.targets[1].lastCheckedAtMillis)
        val busy = updateAutomaticProtectionState(checked, "recovering", busy = true, identity = other)
        assertNull(busy.targets[1].lastOutcome)
        assertEquals(2000L, busy.targets[1].lastCheckedAtMillis)
    }

    @Test fun `general waiting status preserves each recipients own outcome and timestamp`() {
        val state = AutomaticProtectionState(lastOutcome = "FLAG_CLEARED", lastCheckedAtMillis = 2000,
            targets = listOf(AutomaticProtectionTargetState(telegram, true, lastOutcome = "FLAG_CLEARED", lastCheckedAtMillis = 1000),
                AutomaticProtectionTargetState(other, true, lastOutcome = "NO_WRITE_NEEDED", lastCheckedAtMillis = 2000)))
        val waiting = updateAutomaticProtectionState(state, "waiting")
        assertEquals(listOf("FLAG_CLEARED", "NO_WRITE_NEEDED"), waiting.targets.map { it.lastOutcome })
        assertEquals(listOf(1000L, 2000L), waiting.targets.map { it.lastCheckedAtMillis })
    }
}
