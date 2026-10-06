package io.github.originosfcmguardian.activation

import org.junit.Assert.*
import org.junit.Test
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT

class WirelessTransactionTest {
    private class Fixture : WirelessPort, WirelessLeaseStore {
        var setting = 0
        var permission = true
        var bootCount = 1
        var time = 100L
        var record: WirelessLease? = null
        var writes = mutableListOf<Int>()
        var saveFails = false
        var writeFails = false
        var environment = ActivationPreflight(VERIFIED_AUTOSTART_FINGERPRINT, 0, true, 1, true)
        override fun preflight() = environment
        override fun granted() = permission
        override fun value() = setting
        override fun write(value: Int): Boolean { writes += value; if (writeFails) return false; setting = value; return true }
        override fun boot() = bootCount
        override fun elapsed() = time
        override fun read() = record
        override fun save(lease: WirelessLease?) { check(!saveFails); record = lease }
        fun transaction() = WirelessTransaction(this, this, "process")
    }
    @Test fun baselineOffIsJournaledBeforeEnableAndRestored() {
        val f = Fixture(); val tx = f.transaction()
        tx.begin(); assertEquals(1, f.setting); assertNotNull(f.record)
        assertTrue(tx.cleanup()); assertEquals(listOf(1, 0), f.writes); assertNull(f.record)
    }
    @Test fun baselineOnIsPreserved() {
        val f = Fixture(); f.setting = 1; val tx = f.transaction()
        tx.begin(); assertTrue(tx.cleanup()); assertEquals(1, f.setting); assertTrue(f.writes.isEmpty())
    }
    /** Known-risk characterization, NOT a noninterference safety pass.
     * A new client can depend on the shared ON value without changing it or the valid lease.
     * Unlike baselineOnIsPreserved, Guardian enabled this shared switch itself.
     */
    @Test fun knownRiskCleanupDisablesSharedWirelessWhileExternalClientStillNeedsIt() {
        val f = Fixture()
        val tx = f.transaction()
        assertEquals(0, f.setting)
        tx.begin()
        assertEquals(1, f.setting)

        // The external client starts using the already-enabled setting; it makes no write.
        val externalNeedsWireless = true
        assertEquals(listOf(1), f.writes)
        val lease = requireNotNull(f.record)
        assertTrue(ActivationPolicy.cleanupAllowed(lease, "process", f.bootCount, f.time))

        assertTrue(tx.cleanup())
        // Reproduces interference: valid cleanup turns OFF a resource the other client needs.
        assertTrue(externalNeedsWireless)
        assertEquals(0, f.setting)
        assertEquals(listOf(1, 0), f.writes)
        assertNull(f.record)
    }
    @Test fun missingPermissionAndFailedJournalPreventWrites() {
        val f = Fixture(); f.permission = false
        assertTrue(runCatching { f.transaction().begin() }.isFailure); assertTrue(f.writes.isEmpty())
        f.permission = true; f.saveFails = true
        assertTrue(runCatching { f.transaction().begin() }.isFailure); assertTrue(f.writes.isEmpty())
    }
    @Test fun unsupportedEnvironmentNeverJournalsOrWritesEvenWithBaselineOn() {
        val supported = Fixture().environment
        for (environment in listOf(supported.copy(fingerprint = "other"), supported.copy(userId = 10),
            supported.copy(unlocked = false), supported.copy(usbDebugging = 0),
            supported.copy(usbDebugging = -1), supported.copy(wifiTransport = false))) {
            for (baseline in listOf(0, 1)) {
                val f = Fixture(); f.environment = environment; f.setting = baseline
                assertTrue(runCatching { f.transaction().begin() }.isFailure)
                assertTrue(f.writes.isEmpty()); assertNull(f.record)
            }
        }
    }
    @Test fun unknownBaselineAndBootNeverEnableWireless() {
        for (change in listOf<(Fixture) -> Unit>({ it.setting = -1 }, { it.bootCount = -1 })) {
            val f = Fixture(); change(f)
            assertTrue(runCatching { f.transaction().begin() }.isFailure)
            assertTrue(f.writes.isEmpty()); assertNull(f.record)
        }
    }
    @Test fun lostCleanupPermissionPreservesManualLease() {
        val f = Fixture(); val tx = f.transaction(); tx.begin(); f.permission = false
        assertTrue(runCatching { tx.cleanup() }.isFailure)
        assertEquals(listOf(1), f.writes); assertNotNull(f.record)
    }
    @Test fun unconfirmedWriteRemainsManualAndCannotBeReplayed() {
        val f = Fixture(); f.writeFails = true; val tx = f.transaction()
        assertTrue(runCatching { tx.begin() }.isFailure); assertFalse(tx.cleanup()); assertNotNull(f.record)
        assertTrue(runCatching { f.transaction().begin() }.isFailure)
        tx.acknowledgeManualCleanup(); assertNull(f.record); assertEquals(listOf(1), f.writes)
    }
    @Test fun userAlreadyDisabledIsNeverReenabled() {
        val f = Fixture(); val tx = f.transaction(); tx.begin(); f.setting = 0
        assertTrue(tx.cleanup()); assertEquals(listOf(1), f.writes)
    }
    @Test fun staleCrossBootAndChangedRecordDoNotWriteCleanup() {
        for (change in listOf<(Fixture) -> Unit>({ it.time = 100_000 }, { it.bootCount = 2 }, { it.record = it.record?.copy(owner = "other") })) {
            val f = Fixture(); val tx = f.transaction(); tx.begin(); change(f)
            assertTrue(runCatching { tx.cleanup() }.isFailure); assertEquals(listOf(1), f.writes)
            tx.abandonUncertainOwnership(); f.setting = 0; tx.acknowledgeManualCleanup(); assertNull(f.record)
        }
    }
}
