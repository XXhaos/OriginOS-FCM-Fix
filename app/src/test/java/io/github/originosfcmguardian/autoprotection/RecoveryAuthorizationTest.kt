package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the production admission, rather than the retained historical budget algorithms. */
class RecoveryAuthorizationTest {
    private class Storage(snapshot: ProtectionAuthoritySnapshot? = null) : ProtectionAuthorityStorage {
        var bytes: ByteArray? = snapshot?.let(ProtectionAuthorityCodec::encode)
        var initialized = snapshot != null
        var failRead = false
        var failLock = false
        var retirementPending = false
        var reads = 0
        var writes = 0
        private var locked = false

        @Synchronized override fun <T> exclusive(action: () -> T): T {
            check(!failLock && !locked)
            locked = true
            return try { action() } finally { locked = false }
        }

        override fun read(): ProtectionAuthoritySnapshot? {
            check(locked)
            reads++
            check(!failRead)
            val current = bytes ?: return null.also { check(!initialized) }
            check(initialized)
            val value = ProtectionAuthorityCodec.decode(current)
            return if (retirementPending) authorityWithRetirementMarker(value) else value
        }

        override fun write(value: ProtectionAuthoritySnapshot) {
            check(locked)
            writes++
            bytes = ProtectionAuthorityCodec.encode(value)
            initialized = true
        }
    }

    private val telegram = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private val recipient = SelectedTargetIdentity("com.example.recipient", 10124, 1235, listOf("b".repeat(64)))
    private val fullBudget = BudgetSnapshot(BudgetClock(7, 900_000, 1_900_000),
        listOf(0, 300_000, 600_000), null)
    private val quarantinedBudget = BudgetSnapshot(BudgetClock(1, 0, 0), emptyList(), 3_600_000)
    private val legacy = DurableConsentToken("00000000-0000-0000-0000-000000000001", 1,
        1_000_000, telegram, VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
    private val recipientConsent = TargetConsent.Recipient(RecipientConsentToken(
        "00000000-0000-0000-0000-000000000002", 2, 1_000_001, recipient,
        VERIFIED_AUTOSTART_FINGERPRINT))

    private fun legacyRoot(budget: BudgetSnapshot = fullBudget) = ProtectionAuthoritySnapshot(
        1, ProtectionConsentMode.ACTIVE, legacy, null, budget)

    private fun recipientRoot(budget: BudgetSnapshot = fullBudget) = ProtectionAuthoritySnapshot(
        2, ProtectionConsentMode.ACTIVE, null, null, budget,
        listOf(recipientConsent, TargetConsent.Legacy(legacy)).sortedBy { it.identity.packageName }, 2)

    private fun leases(root: ProtectionAuthoritySnapshot) = root.consents().map { TargetConsentLease(root.generation, it) }

    private fun assertReadOnly(storage: Storage, expected: ByteArray?, writes: Int = 0) {
        assertEquals(writes, storage.writes)
        if (expected == null) assertNull(storage.bytes) else assertArrayEquals(expected, storage.bytes)
    }

    @Test fun `immediate repeated same and different leases exceed old quota across reconstructed owners`() {
        for (root in listOf(legacyRoot(), recipientRoot())) {
            val storage = Storage(root)
            val before = storage.bytes!!.copyOf()
            repeat(50) {
                for (lease in leases(root)) {
                    assertEquals(AutomaticCheckDecision.Recover,
                        ProtectionConsentStore(storage).checkRecoveryAuthorization(lease))
                }
            }
            assertEquals(50 * leases(root).size, storage.reads)
            assertReadOnly(storage, before)
            assertEquals(root, ProtectionAuthorityCodec.decode(storage.bytes!!))
        }
    }

    @Test fun `full old budget quarantine and previous boot clock never normalize or block admission`() {
        val priorBoot = BudgetSnapshot(BudgetClock(0, 600_000, 1), listOf(0, 300_000, 600_000), null)
        for (budget in listOf(fullBudget, quarantinedBudget, priorBoot)) {
            val root = recipientRoot(budget)
            val storage = Storage(root)
            val before = storage.bytes!!.copyOf()
            repeat(8) {
                leases(root).forEach { lease ->
                    assertEquals(AutomaticCheckDecision.Recover,
                        ProtectionConsentStore(storage).checkRecoveryAuthorization(lease))
                }
            }
            assertReadOnly(storage, before)
            assertEquals(budget, ProtectionAuthorityCodec.decode(storage.bytes!!).budget)
        }
    }

    @Test fun `uninitialized unreadable missing corrupt and busy storage fail without initialization or writes`() {
        val lease = leases(legacyRoot()).single()
        val absent = Storage()
        assertTrue(ProtectionConsentStore(absent).checkRecoveryAuthorization(lease) is AutomaticCheckDecision.Pause)
        assertFalse(absent.initialized)
        assertReadOnly(absent, null)

        val failures = listOf(Storage(legacyRoot()).apply { failRead = true },
            Storage(legacyRoot()).apply { failLock = true },
            Storage(legacyRoot()).apply { bytes = null },
            Storage(legacyRoot()).apply { bytes!![0] = (bytes!![0].toInt() xor 1).toByte() })
        for (storage in failures) {
            val before = storage.bytes?.copyOf()
            assertTrue(ProtectionConsentStore(storage).checkRecoveryAuthorization(lease) is AutomaticCheckDecision.Pause)
            assertReadOnly(storage, before)
        }
    }

    @Test fun `OFF suspended and interrupted retirement never admit old leases`() {
        for (root in listOf(legacyRoot(), recipientRoot())) {
            for (mode in listOf(ProtectionConsentMode.DISABLED, ProtectionConsentMode.SUSPENDED)) {
                val retired = root.retired(mode,
                    if (mode == ProtectionConsentMode.SUSPENDED) ProtectionSuspensionReason.SERVICE_UNAVAILABLE else null)
                val storage = Storage(retired)
                val before = storage.bytes!!.copyOf()
                leases(root).forEach { lease ->
                    assertTrue(ProtectionConsentStore(storage).checkRecoveryAuthorization(lease) is AutomaticCheckDecision.Pause)
                }
                assertReadOnly(storage, before)
            }
            val interrupted = Storage(root).apply { retirementPending = true }
            val before = interrupted.bytes!!.copyOf()
            leases(root).forEach { lease ->
                assertTrue(ProtectionConsentStore(interrupted).checkRecoveryAuthorization(lease) is AutomaticCheckDecision.Pause)
            }
            assertTrue(interrupted.retirementPending)
            assertReadOnly(interrupted, before)
        }
    }

    @Test fun `fresh root exact token and exact identity are mandatory including retained target after root change`() {
        val root = recipientRoot()
        val storage = Storage(root)
        val before = storage.bytes!!.copyOf()
        val current = TargetConsentLease(root.generation, recipientConsent)
        val stale = listOf(current.copy(rootGeneration = root.generation - 1),
            current.copy(consent = TargetConsent.Recipient(recipientConsent.value.copy(
                id = "00000000-0000-0000-0000-000000000003"))),
            current.copy(consent = TargetConsent.Recipient(recipientConsent.value.copy(
                identity = recipient.copy(firstInstallTime = recipient.firstInstallTime + 1)))))
        stale.forEach { assertTrue(ProtectionConsentStore(storage).checkRecoveryAuthorization(it) is AutomaticCheckDecision.Pause) }
        assertEquals(AutomaticCheckDecision.Recover, ProtectionConsentStore(storage).checkRecoveryAuthorization(current))
        assertReadOnly(storage, before)

        val store = ProtectionConsentStore(storage)
        assertTrue(store.revokeTarget(telegram))
        val afterRemoval = storage.bytes!!.copyOf()
        assertTrue(store.checkRecoveryAuthorization(current) is AutomaticCheckDecision.Pause)
        val fresh = store.desiredLeases().single()
        assertEquals(current.consent, fresh.consent)
        assertEquals(AutomaticCheckDecision.Recover, store.checkRecoveryAuthorization(fresh))
        assertTrue(store.revokeTarget(recipient))
        val afterRevocation = storage.bytes!!.copyOf()
        assertTrue(store.checkRecoveryAuthorization(fresh) is AutomaticCheckDecision.Pause)
        assertReadOnly(storage, afterRevocation, 2)
        assertFalse(afterRemoval.contentEquals(afterRevocation))
    }

    @Test fun `policy2 legacy restart consent admits but policy1 cannot silently gain restart scope`() {
        val root = legacyRoot()
        val storage = Storage(root)
        assertEquals(AutomaticCheckDecision.Recover,
            ProtectionConsentStore(storage).checkRecoveryAuthorization(leases(root).single()))
        val oldRoot = root.copy(token = legacy.copy(policyVersion = 1))
        val oldStorage = Storage(oldRoot)
        val before = oldStorage.bytes!!.copyOf()
        assertTrue(ProtectionConsentStore(oldStorage).checkRecoveryAuthorization(leases(oldRoot).single()) is AutomaticCheckDecision.Pause)
        assertReadOnly(oldStorage, before)
    }

    @Test fun `admission alone cannot open a blocked write gate or grant dispatch authority`() {
        val root = recipientRoot()
        val storage = Storage(root)
        val gate = AutomaticWriteGate()
        val before = storage.bytes!!.copyOf()
        assertEquals(AutomaticCheckDecision.Recover,
            ProtectionConsentStore(storage).checkRecoveryAuthorization(leases(root).first()))
        assertNull(gate.ticket())
        assertFalse(gate.permits(null))
        assertReadOnly(storage, before)
    }

    @Test fun `production startup and recovery wiring never call historical quota helpers`() {
        fun source(name: String): String {
            val relative = "src/main/java/io/github/originosfcmguardian/autoprotection/$name.kt"
            val local = File(relative)
            return (if (local.isFile) local else File("app/$relative")).readText()
        }
        val startup = source("ProtectionStartupProbe")
        val runtime = source("AutomaticProtectionRuntime")
        val service = source("AutomaticProtectionService")
        assertTrue(startup.contains("store.checkRecoveryAuthorization(leases.first())"))
        assertFalse(startup.contains("budgetClock("))
        assertFalse(startup.contains("checkBudget("))
        assertTrue(runtime.contains("current.store.checkRecoveryAuthorization(lease)"))
        assertTrue(service.contains("AutomaticProtectionRuntime.checkRecoveryAuthorization(grant, lease)"))
        assertTrue(service.contains("authorization != AutomaticCheckDecision.Recover"))
        for (text in listOf(startup, runtime, service)) {
            assertFalse(text.contains("reserveAttempt("))
            assertFalse(Regex("(?:store|budget)\\.(?:take|checkBudget)\\(").containsMatchIn(text))
            assertFalse(text.contains("PersistentAttemptBudget("))
            assertFalse(text.contains("AutomaticAttemptBudget("))
        }
        assertTrue(service.contains("private const val POLL_MILLIS = 30_000L"))
        assertTrue(service.contains("delay(POLL_MILLIS)"))
    }
}
