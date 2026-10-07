package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class AutomaticProtectionInterruptionTest {
    private val identity = SelectedTargetIdentity("example.app", 10123, 10, listOf("a".repeat(64)))
    private val consent = TargetConsent.Recipient(RecipientConsentToken(
        "12345678-1234-1234-1234-123456789abc", 1, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val active = ProtectionAuthoritySnapshot(2, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 0, 1000), emptyList(), null), listOf(consent), 3,
        masterEnabledAtMillis = 2000)

    private class Storage(root: ProtectionAuthoritySnapshot) : ProtectionAuthorityStorage {
        var bytes = ProtectionAuthorityCodec.encode(root)
        var writes = 0
        override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot = ProtectionAuthorityCodec.decode(bytes)
        override fun write(value: ProtectionAuthoritySnapshot) {
            writes++
            bytes = ProtectionAuthorityCodec.encode(value)
        }
    }

    @Test fun `unexpected interruption revokes live owner and tickets while retaining exact durable intent`() {
        val storage = Storage(active)
        val bytesBefore = storage.bytes.copyOf()
        val store = ProtectionConsentStore(storage)
        val leases = store.desiredLeases()
        val grant = AutomaticProtectionGrant("old", leases, store)
        val gate = AutomaticSessionGate()
        assertTrue(gate.reserve(grant.id))
        assertTrue(gate.claim(grant.id))
        grant.writes.open()
        val ticket = grant.writes.ticket()
        assertTrue(grant.writes.permits(ticket))
        val prior = configuredAutomaticProtectionState(AutomaticProtectionState(), active, "ready").copy(
            enabled = true, busy = true, waitingForPrivilege = true, waitingForReconciliation = true,
            lastCheckedAtMillis = 3000, lastOutcome = "NO_WRITE_NEEDED")

        val interrupted = interruptAutomaticProtectionGrant(grant, gate, prior, "interrupted")

        assertFalse(grant.live.get())
        assertFalse(gate.active(grant.id))
        assertNull(grant.writes.ticket())
        assertFalse(grant.writes.permits(ticket))
        grant.writes.open()
        assertFalse(grant.writes.permits(ticket)) // An obsolete completion cannot reuse an old ticket.
        assertFalse(interrupted.enabled)
        assertFalse(interrupted.busy)
        assertFalse(interrupted.waitingForPrivilege)
        assertFalse(interrupted.waitingForReconciliation)
        assertTrue(interrupted.masterEnabled)
        assertTrue(interrupted.savedAuthorization)
        assertEquals(prior.configuredIdentities, interrupted.configuredIdentities)
        assertEquals(prior.authorizedIdentities, interrupted.authorizedIdentities)
        assertEquals(prior.lastCheckedAtMillis, interrupted.lastCheckedAtMillis)
        assertEquals(prior.lastOutcome, interrupted.lastOutcome)
        assertArrayEquals(bytesBefore, storage.bytes)
        assertEquals(0, storage.writes)
        assertEquals(leases, store.desiredLeases())
        assertTrue(store.verifyAuthorized(leases.single()))
        assertTrue(gate.reserve("replacement"))
        assertFalse(gate.claim(grant.id))
    }

    @Test fun `interruption never promotes an OFF or suspended durable root`() {
        for (root in listOf(active.copy(mode = ProtectionConsentMode.USER_PAUSED),
            active.copy(mode = ProtectionConsentMode.SUSPENDED,
                suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE))) {
            val storage = Storage(root)
            val bytesBefore = storage.bytes.copyOf()
            val store = ProtectionConsentStore(storage)
            val grant = AutomaticProtectionGrant("obsolete", listOf(TargetConsentLease(active.generation, consent)), store)
            val gate = AutomaticSessionGate()
            assertTrue(gate.reserve(grant.id))
            val prior = configuredAutomaticProtectionState(AutomaticProtectionState(), root, "blocked")

            val interrupted = interruptAutomaticProtectionGrant(grant, gate, prior, "interrupted")

            assertFalse(interrupted.masterEnabled)
            assertFalse(interrupted.savedAuthorization)
            assertFalse(interrupted.enabled)
            assertEquals(listOf(identity), interrupted.configuredIdentities)
            assertTrue(store.desiredLeases().isEmpty())
            assertFalse(store.verifyAuthorized(grant.leases.single()))
            assertArrayEquals(bytesBefore, storage.bytes)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun `captured recovery revision rejects OFF pause and replacement before or after probe`() {
        assertTrue(recoveryRevisionMatches(7, 7))
        assertFalse(recoveryRevisionMatches(7, 8))
        assertFalse(recoveryRevisionMatches(7, 9))
        assertTrue(recoveryRevisionMatches(null, 9)) // Existing non-tile triggers capture internally.
    }
}
