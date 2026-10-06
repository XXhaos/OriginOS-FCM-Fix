package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test

class PersistentAttemptBudgetTest {
    private class Store : AttemptBudgetStorage {
        var value: BudgetSnapshot? = null
        var failRead = false
        var failWrite = false
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read(): BudgetSnapshot? { check(!failRead); return value }
        override fun write(value: BudgetSnapshot) { check(!failWrite); this.value = value }
    }
    private fun clock(elapsed: Long, boot: Long = 1, epoch: Long = 1_000_000 + elapsed) = BudgetClock(boot, elapsed, epoch)

    @Test fun `new owner preserves cooldown and hour budget`() {
        val store = Store()
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(0)))
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), PersistentAttemptBudget(store).take(clock(1)))
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(300_000)))
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(600_000)))
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000), PersistentAttemptBudget(store).take(clock(900_000)))
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(3_600_000)))
    }

    @Test fun `hour limit reads and denied reservations wait only until oldest attempt expires`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(600_000), listOf(0, 300_000, 600_000), null)
        for (elapsed in listOf(900_000L, 1_800_000L, 3_599_999L)) {
            assertEquals(AutomaticCheckDecision.Cooldown(3_600_000 - elapsed), PersistentAttemptBudget(store).check(clock(elapsed)))
            assertEquals(AutomaticCheckDecision.Cooldown(3_600_000 - elapsed), PersistentAttemptBudget(store).take(clock(elapsed)))
            assertEquals(listOf(0L, 300_000L, 600_000L), store.value!!.attempts)
        }
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(clock(3_600_000)))
        assertEquals(listOf(300_000L, 600_000L), store.value!!.attempts)
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(3_600_000)))
        assertEquals(listOf(300_000L, 600_000L, 3_600_000L), store.value!!.attempts)
        assertEquals(AutomaticCheckDecision.Cooldown(300_000), PersistentAttemptBudget(store).take(clock(3_600_000)))
    }

    @Test fun `reboot and clock uncertainty persist conservative quarantine`() {
        val store = Store()
        PersistentAttemptBudget(store).take(clock(600_000))
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).take(clock(0, 2)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_599_999), PersistentAttemptBudget(store).take(clock(1, 2)))
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(3_600_000, 2)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).take(clock(3_600_001, 2, 1)))
    }

    @Test fun `save must succeed before granting an attempt`() {
        val store = Store()
        store.failWrite = true
        assertTrue(PersistentAttemptBudget(store).take(clock(0)) is AutomaticCheckDecision.Pause)
        assertNull(store.value)
        store.failWrite = false; store.failRead = true
        assertTrue(PersistentAttemptBudget(store).take(clock(0)) is AutomaticCheckDecision.Pause)
    }

    @Test fun `malformed persisted state cannot reset the budget`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(500), listOf(0, 1), null)
        assertTrue(PersistentAttemptBudget(store).take(clock(1_000)) is AutomaticCheckDecision.Pause)
        store.value = BudgetSnapshot(clock(500), emptyList(), Long.MAX_VALUE)
        assertTrue(PersistentAttemptBudget(store).take(clock(1_000)) is AutomaticCheckDecision.Pause)
    }

    @Test fun `codec rejects truncation unknown versions and trailing bytes`() {
        val snapshot = BudgetSnapshot(clock(600_000), listOf(0, 300_000, 600_000), null)
        val bytes = BudgetSnapshotCodec.encode(snapshot)
        assertEquals(snapshot, BudgetSnapshotCodec.decode(bytes))
        for (cut in 0 until bytes.size) {
            assertThrows(Exception::class.java) { BudgetSnapshotCodec.decode(bytes.copyOf(cut)) }
        }
        assertThrows(Exception::class.java) { BudgetSnapshotCodec.decode(bytes + 0) }
        bytes[7] = 2
        assertThrows(Exception::class.java) { BudgetSnapshotCodec.decode(bytes) }
    }

    @Test fun `two owners share one reservation transaction`() {
        val store = Store()
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<AutomaticCheckDecision>())
        val threads = List(2) {
            Thread { start.await(); results.add(PersistentAttemptBudget(store).take(clock(0))) }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it == AutomaticCheckDecision.Recover })
        assertEquals(1, store.value?.attempts?.size)
    }

    @Test fun `failed quarantine commit never releases previous reservation`() {
        val store = Store()
        PersistentAttemptBudget(store).take(clock(0))
        val before = store.value
        store.failWrite = true
        assertTrue(PersistentAttemptBudget(store).take(clock(0, 2)) is AutomaticCheckDecision.Pause)
        assertEquals(before, store.value)
        store.failWrite = false
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).take(clock(0, 2)))
    }

    @Test fun `readiness never initializes or reserves and pruning releases only expired attempts`() {
        val store = Store()
        assertTrue(PersistentAttemptBudget(store).check(clock(0)) is AutomaticCheckDecision.Pause)
        assertNull(store.value)
        store.value = BudgetSnapshot(clock(0), emptyList(), null)
        repeat(3) {
            assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(clock(it.toLong())))
            assertTrue(store.value!!.attempts.isEmpty())
        }
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(3)))
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), PersistentAttemptBudget(store).check(clock(4)))
        assertEquals(listOf(3L), store.value!!.attempts)
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(clock(3_600_003)))
        assertTrue(store.value!!.attempts.isEmpty())
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(3_600_003)))
        assertEquals(listOf(3_600_003L), store.value!!.attempts)
    }

    @Test fun `only valid empty unquarantined state rebases immediately after clock discontinuity`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(600_000), emptyList(), null)
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(clock(0, 2)))
        assertEquals(BudgetSnapshot(clock(0, 2), emptyList(), null), store.value)
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(clock(0, 2)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).check(clock(0, 3)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).check(clock(0, 4)))
        assertEquals(3_600_000L, store.value!!.quarantineUntil)
        assertTrue(store.value!!.attempts.isEmpty())
    }

    @Test fun `forward boot uptime bounds even inherited quarantine`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(0), emptyList(), 3_600_000)
        assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(clock(4_000_000, 2)))
        assertNull(store.value!!.quarantineUntil)
        assertTrue(store.value!!.attempts.isEmpty())
    }

    @Test fun `late first check after forward reboot waits only remaining boot hour`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(600_000), listOf(0, 300_000, 600_000), null)
        val now = clock(900_000, 2, 2_000_000)
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000), PersistentAttemptBudget(store).check(now))
        assertEquals(BudgetSnapshot(now, emptyList(), 3_600_000), store.value)
        val later = clock(1_800_000, 2, 2_900_000)
        assertEquals(AutomaticCheckDecision.Cooldown(1_800_000), PersistentAttemptBudget(store).take(later))
        assertEquals(3_600_000L, store.value!!.quarantineUntil)
    }

    @Test fun `forward boot past deadline prunes old debt but take reserves new debt durably`() {
        for (elapsed in listOf(3_600_000L, 4_000_000L)) {
            val store = Store()
            store.value = BudgetSnapshot(clock(600_000), listOf(0, 300_000, 600_000), null)
            val now = clock(elapsed, 2)
            assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).check(now))
            assertEquals(BudgetSnapshot(now, emptyList(), null), store.value)
            assertEquals(AutomaticCheckDecision.Recover, PersistentAttemptBudget(store).take(now))
            assertEquals(listOf(elapsed), store.value!!.attempts)
            assertEquals(AutomaticCheckDecision.Cooldown(300_000), PersistentAttemptBudget(store).take(now))
        }
    }

    @Test fun `uncertain clocks cannot use forward boot expiry shortcut`() {
        val prior = BudgetSnapshot(clock(600_000), listOf(600_000), null)
        for (now in listOf(clock(4_000_000, 0), clock(4_000_000, 2, 1),
            clock(4_000_000, 1, 1), clock(4_000_000, 1, 9_000_000), clock(500_000, 1, 1_700_000))) {
            val store = Store().also { it.value = prior }
            assertEquals(AutomaticCheckDecision.Cooldown(3_600_000), PersistentAttemptBudget(store).check(now))
            assertEquals(now.elapsed + 3_600_000, store.value!!.quarantineUntil)
        }
    }

    @Test fun `forward reboot expiry commit failure preserves original debt`() {
        for (elapsed in listOf(900_000L, 4_000_000L)) {
            for (reserve in listOf(false, true)) {
                val prior = BudgetSnapshot(clock(600_000), listOf(600_000), null)
                val store = Store().also { it.value = prior; it.failWrite = true }
                val budget = PersistentAttemptBudget(store)
                val now = clock(elapsed, 2, 6_000_000)
                assertTrue((if (reserve) budget.take(now) else budget.check(now)) is AutomaticCheckDecision.Pause)
                assertEquals(prior, store.value)
            }
        }
    }

    @Test fun `another reboot cannot erase inherited waiting debt early`() {
        val store = Store()
        store.value = BudgetSnapshot(clock(600_000), listOf(600_000), null)
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000),
            PersistentAttemptBudget(store).check(clock(900_000, 2, 2_000_000)))
        assertEquals(AutomaticCheckDecision.Cooldown(3_540_000),
            PersistentAttemptBudget(store).check(clock(60_000, 3, 2_100_000)))
        assertEquals(3_600_000L, store.value!!.quarantineUntil)
    }

    @Test fun `two owners after forward boot expiry obtain only one reservation`() {
        val store = Store().also {
            it.value = BudgetSnapshot(clock(600_000), listOf(0, 300_000, 600_000), null)
        }
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<AutomaticCheckDecision>())
        val threads = List(2) {
            Thread {
                start.await()
                results.add(PersistentAttemptBudget(store).take(clock(4_000_000, 2)))
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, results.count { it == AutomaticCheckDecision.Recover })
        assertEquals(1, results.count { it == AutomaticCheckDecision.Cooldown(300_000) })
        assertEquals(listOf(4_000_000L), store.value!!.attempts)
    }

    @Test fun `readiness commit failure retains reservations and returns unavailable`() {
        val store = Store()
        PersistentAttemptBudget(store).take(clock(0))
        val before = store.value
        store.failWrite = true
        assertTrue(PersistentAttemptBudget(store).check(clock(300_000)) is AutomaticCheckDecision.Pause)
        assertEquals(before, store.value)
        assertTrue(PersistentAttemptBudget(store).check(clock(0, 2)) is AutomaticCheckDecision.Pause)
        assertEquals(before, store.value)
    }
}
