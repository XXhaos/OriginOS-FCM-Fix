package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

class ProtectionConsentStoreTest {
    private class Storage : ProtectionAuthorityStorage {
        var bytes: ByteArray? = null
        var initialized = false
        var failRead = false
        var failWrite = false
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): ProtectionAuthoritySnapshot? {
            check(!failRead)
            val current = bytes
            if (current == null) { check(!initialized); return null }
            check(initialized)
            return ProtectionAuthorityCodec.decode(current)
        }
        override fun write(value: ProtectionAuthoritySnapshot) {
            check(!failWrite)
            initialized = true
            bytes = ProtectionAuthorityCodec.encode(value)
            check(read() == value)
        }
    }
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private fun clock(elapsed: Long, boot: Long = 1, epoch: Long = 1_000_000 + elapsed) = BudgetClock(boot, elapsed, epoch)
    private fun grant(store: ProtectionConsentStore, elapsed: Long = 0): DurableConsentToken {
        val token = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(elapsed))
        if (store.readMode() != ProtectionConsentMode.ACTIVE) assertTrue(store.enableMaster(store.readSnapshot(), clock(elapsed)))
        return token
    }

    @Test fun `initialization requires explicit consent and binds full identity`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        assertNull(store.readDesired())
        assertEquals(ProtectionConsentMode.DISABLED, store.readMode())
        val token = grant(store)
        assertEquals(identity, token.identity)
        assertEquals(VERIFIED_AUTOSTART_FINGERPRINT, token.fingerprint)
        assertEquals(1L, token.generation)
        assertEquals(clock(0).epoch, token.consentedAtMillis)
        assertEquals(token, ProtectionConsentStore(storage).readDesired())
        assertTrue(ProtectionConsentStore(storage).verifyAuthorized(token))
        assertFalse(store.verifyAuthorized(token.copy(identity = identity.copy(firstInstallTime = 1235))))
        assertThrows(IllegalArgumentException::class.java) {
            store.grantExplicit(identity.copy(packageName = "org.telegram.messenger.web"), VERIFIED_AUTOSTART_FINGERPRINT, clock(0))
        }
        assertThrows(IllegalArgumentException::class.java) { store.grantExplicit(identity, "unsupported", clock(0)) }
        assertEquals(token, store.readDesired())
    }

    @Test fun `global stop after process loss durably invalidates consent`() {
        val storage = Storage()
        val token = grant(ProtectionConsentStore(storage))
        val restarted = ProtectionConsentStore(storage)
        assertTrue(restarted.revoke())
        assertFalse(restarted.verifyAuthorized(token))
        assertNull(ProtectionConsentStore(storage).readDesired())
        assertEquals(ProtectionConsentMode.USER_PAUSED, restarted.readMode())
        assertEquals(listOf(TargetConsent.Legacy(token)), restarted.configuredConsents())
        assertTrue(restarted.take(token, clock(0)) is AutomaticCheckDecision.Pause)
    }

    @Test fun `suspension persists until explicit confirmation and stale retirement cannot revoke new consent`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val old = grant(store)
        assertTrue(store.suspend(old, ProtectionSuspensionReason.PENDING_RECEIPT))
        assertNull(ProtectionConsentStore(storage).readDesired())
        assertEquals(ProtectionConsentMode.SUSPENDED, ProtectionConsentStore(storage).readMode())
        assertEquals(ProtectionSuspensionReason.PENDING_RECEIPT, storage.read()?.suspensionReason)
        val next = grant(store)
        assertTrue(next.generation > old.generation)
        assertNotEquals(next.id, old.id)
        assertFalse(store.revoke(old))
        assertFalse(store.suspend(old, ProtectionSuspensionReason.PREREQUISITES_LOST))
        assertTrue(store.verifyAuthorized(next))
    }

    @Test fun `stop suspend reconfirm and new owners never reset cooldown or hour limit`() {
        val storage = Storage()
        var store = ProtectionConsentStore(storage)
        val first = grant(store)
        assertEquals(AutomaticCheckDecision.Recover, store.take(first, clock(0)))
        assertTrue(store.revoke(first))
        val second = grant(store, 1)
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), store.take(second, clock(1)))
        assertEquals(AutomaticCheckDecision.Recover, store.take(second, clock(300_000)))
        assertTrue(store.suspend(second, ProtectionSuspensionReason.PREREQUISITES_LOST))
        store = ProtectionConsentStore(storage)
        val third = grant(store, 600_000)
        assertEquals(AutomaticCheckDecision.Recover, store.take(third, clock(600_000)))
        val fourth = grant(store, 900_000)
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000), store.take(fourth, clock(900_000)))
        assertEquals(AutomaticCheckDecision.Recover, store.take(fourth, clock(3_600_000)))
    }

    @Test fun `reboot backwards clocks and wall clock divergence quarantine across confirmation`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val token = grant(store, 600_000)
        assertEquals(AutomaticCheckDecision.Recover, store.take(token, clock(600_000)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), store.take(token, clock(0, 2)))
        store.revoke(token)
        val next = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(1, 2))
        assertFalse(store.enableMaster(store.readSnapshot(), clock(1, 2))) // Backward master acknowledgement rejected.
        assertTrue(store.take(next, clock(1, 2)) is AutomaticCheckDecision.Pause)
        assertEquals(listOf(TargetConsent.Legacy(next)), store.configuredConsents())
        assertTrue(store.take(next, clock(-1)) is AutomaticCheckDecision.Pause)
    }

    @Test fun `commit failures issue neither grant nor permit and corrupt records cannot be replaced`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { grant(store) }
        assertNull(storage.bytes)
        storage.failWrite = false
        val token = grant(store)
        val previous = storage.bytes!!.copyOf()
        storage.failWrite = true
        assertTrue(store.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertArrayEquals(previous, storage.bytes)
        assertThrows(IllegalStateException::class.java) { store.revoke(token) }
        storage.failWrite = false
        storage.bytes = byteArrayOf(1, 2, 3)
        assertFalse(store.verifyAuthorized(token))
        assertTrue(store.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertThrows(Exception::class.java) { grant(store) }
        assertArrayEquals(byteArrayOf(1, 2, 3), storage.bytes)
        storage.failRead = true
        assertFalse(store.verifyAuthorized(token))
    }

    @Test fun `loss of budget with live consent or loss of state after initialization cannot create fresh permit`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val token = grant(store)
        val payload = storage.bytes!!.copyOfRange(0, storage.bytes!!.size - 32)
        val budgetSize = BudgetSnapshotCodec.encode(storage.read()!!.budget).size
        // Retain valid consent bytes, remove mandatory budget, and supply a valid checksum.
        val missingBudget = payload.copyOf(payload.size - budgetSize - 4)
        storage.bytes = missingBudget + MessageDigest.getInstance("SHA-256").digest(missingBudget)
        assertFalse(store.verifyAuthorized(token))
        assertTrue(store.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertThrows(Exception::class.java) { grant(store) }
        storage.bytes = null
        assertFalse(store.verifyAuthorized(token))
        assertThrows(Exception::class.java) { grant(store) }
        // Even loss of the marker and full authority cannot revive an in-memory old token.
        storage.initialized = false
        assertTrue(store.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertNull(storage.bytes)
    }

    @Test fun `strict codec rejects every truncation byte corruption and unknown schema`() {
        val storage = Storage()
        grant(ProtectionConsentStore(storage))
        val bytes = storage.bytes!!.copyOf()
        assertArrayEquals(bytes, ProtectionAuthorityCodec.encode(ProtectionAuthorityCodec.decode(bytes)))
        for (cut in 0 until bytes.size) {
            assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(bytes.copyOf(cut)) }
        }
        for (index in bytes.indices) {
            val changed = bytes.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(changed) }
        }
        assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(bytes + 0) }
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        payload[7] = 2
        assertThrows(Exception::class.java) {
            ProtectionAuthorityCodec.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        }
        assertThrows(Exception::class.java) { ProtectionAuthorityCodec.decode(ByteArray(8193)) }
    }

    @Test fun `two process owners reserve only one attempt and revoke wins for subsequent work`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val token = grant(store)
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<AutomaticCheckDecision>())
        val threads = List(2) {
            Thread { start.await(); results.add(ProtectionConsentStore(storage).take(token, clock(0))) }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it == AutomaticCheckDecision.Recover })
        assertEquals(listOf(0L), storage.read()!!.budget.attempts)
        assertTrue(store.revoke(token))
        assertTrue(ProtectionConsentStore(storage).take(token, clock(300_000)) is AutomaticCheckDecision.Pause)
        assertFalse(store.verifyAuthorized(token))
    }

    @Test fun `restart policy requires new explicit grant and preserves historical reservations`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val historical = grant(store)
        assertEquals(1, historical.policyVersion)
        assertEquals(AutomaticCheckDecision.Recover, store.take(historical, clock(0)))
        val before = store.readSnapshot()!!.budget
        val upgraded = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(1),
            policyVersion = RESTART_CONSENT_POLICY_VERSION)
        assertEquals(2, ProtectionConsentStore(storage).readSnapshot()!!.token!!.policyVersion)
        assertEquals(before, store.readSnapshot()!!.budget)
        assertFalse(store.verifyAuthorized(historical))
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), store.checkBudget(upgraded, clock(1)))
        assertEquals(listOf(0L), store.readSnapshot()!!.budget.attempts)
        val bytes = storage.bytes!!.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(2), policyVersion = 3)
        }
        assertArrayEquals(bytes, storage.bytes)
    }

    @Test fun `startup checks neither initialize absent authority nor consume attempts`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        assertNull(store.readSnapshot())
        assertNull(storage.bytes)
        val token = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(0), 2)
        repeat(3) {
            assertEquals(AutomaticCheckDecision.Recover, ProtectionConsentStore(storage).checkBudget(token, clock(it.toLong())))
            assertTrue(store.readSnapshot()!!.budget.attempts.isEmpty())
        }
        assertEquals(AutomaticCheckDecision.Recover, store.take(token, clock(3)))
        assertEquals(listOf(3L), store.readSnapshot()!!.budget.attempts)
        assertTrue(store.revoke(token))
        val before = storage.bytes!!.copyOf()
        assertTrue(store.checkBudget(token, clock(4)) is AutomaticCheckDecision.Pause)
        assertArrayEquals(before, storage.bytes)
    }

    @Test fun `snapshot lists are detached and immutable and missing mandatory state fails closed`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val token = grant(store)
        store.take(token, clock(0))
        val snapshot = store.readSnapshot()!!
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.budget.attempts as MutableList<Long>).add(300_000L)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.token!!.identity.signerSha256 as MutableList<String>).clear()
        }
        assertEquals(listOf(0L), store.readSnapshot()!!.budget.attempts)
        storage.bytes = null
        assertThrows(Exception::class.java) { store.readSnapshot() }
        assertTrue(store.checkBudget(token, clock(1)) is AutomaticCheckDecision.Pause)
        assertNull(storage.bytes)
    }

    @Test fun `codec accepts both known policies and rejects checksummed unknown policy`() {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        for (policy in 1..2) {
            val token = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(0), policy)
            assertEquals(token, ProtectionAuthorityCodec.decode(storage.bytes!!).token)
        }
        val bytes = storage.bytes!!
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        java.nio.ByteBuffer.wrap(payload).putInt(26, 3)
        assertThrows(Exception::class.java) {
            ProtectionAuthorityCodec.decode(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        }
    }
}
