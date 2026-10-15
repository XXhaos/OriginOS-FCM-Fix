package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedStartupTargetReadTest {
    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    @Test fun `noncooperative PM timeout keeps fixed cap and rejects without backlog`() {
        val release = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val entered = AtomicInteger()
        val caller = Executors.newSingleThreadExecutor()
        // Give actual worker entry room, then prove entry before asserting timeout-held capacity.
        // The reader still enforces a real deadline and the original sub-second return bound.
        try {
            BoundedStartupTargetRead(timeoutMillis = 500).use { reader ->
                try {
                    repeat(4) { target ->
                        val actualEntry = CountDownLatch(1)
                        val pending = caller.submit<Pair<StartupTargetReadResult<Int>, Long>> {
                            val started = System.nanoTime()
                            val result = reader.readBatch(listOf(target)) { input ->
                                entered.incrementAndGet()
                                actualEntry.countDown()
                                try { awaitIgnoringInterrupts(release); input } finally { completed.countDown() }
                            }
                            result to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        }
                        assertTrue("The PM callback must actually enter before its timeout is used as occupancy evidence",
                            actualEntry.await(1, TimeUnit.SECONDS))
                        val (result, elapsed) = pending.get(1, TimeUnit.SECONDS)
                        assertEquals(StartupTargetReadResult.Unavailable(StartupTargetReadFailure.TIMED_OUT), result)
                        assertTrue(elapsed < 1_000)
                        assertEquals(target + 1, entered.get())
                        assertEquals(4L, completed.count) // Interrupted PM reads still hold their actual worker slots.
                    }
                    repeat(12) {
                        assertEquals(StartupTargetReadResult.Unavailable(StartupTargetReadFailure.BUSY),
                            reader.readBatch(listOf(it)) { entered.incrementAndGet(); it })
                    }
                    assertEquals(4, entered.get())
                } finally { release.countDown() }
                assertTrue(completed.await(1, TimeUnit.SECONDS))
                assertEquals(4, entered.get()) // No rejected work starts after workers are released.
            }
        } finally { release.countDown(); caller.shutdownNow() }
    }

    @Test fun `idle PM core worker remains available for a later batch`() {
        BoundedStartupTargetRead(timeoutMillis = 1_000, maxWorkers = 1).use { reader ->
            val workers = BoundedStartupTargetRead::class.java.getDeclaredField("workers").let {
                it.isAccessible = true
                it.get(reader) as ThreadPoolExecutor
            }
            assertFalse(workers.allowsCoreThreadTimeOut())
            assertEquals(0L, workers.getKeepAliveTime(TimeUnit.MILLISECONDS))
            assertEquals(1, workers.corePoolSize)
            assertEquals(1, workers.maximumPoolSize)
            assertTrue(workers.queue is SynchronousQueue<*>)
            val first = reader.readBatch(listOf(1)) { Thread.currentThread() }
            assertTrue(first is StartupTargetReadResult.Complete)
            val firstThread = (first as StartupTargetReadResult.Complete).values.single()
            firstThread.join(100)
            assertTrue("A completed PM core worker must remain available while idle", firstThread.isAlive)

            val second = reader.readBatch(listOf(2)) { Thread.currentThread() }
            assertTrue(second is StartupTargetReadResult.Complete)
            val secondThread = (second as StartupTargetReadResult.Complete).values.single()
            assertSame(firstThread, secondThread)
        }
    }

    @Test fun `one batch deadline discards completed prefix and late facts`() {
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val visited = java.util.Collections.synchronizedList(mutableListOf<Int>())
        BoundedStartupTargetRead(timeoutMillis = 50, maxWorkers = 1).use { reader ->
            try {
                val result = reader.readBatch(listOf(1, 2, 3)) {
                    visited.add(it)
                    if (it == 2) { awaitIgnoringInterrupts(release); returned.countDown() }
                    it
                }
                assertEquals(StartupTargetReadResult.Unavailable(StartupTargetReadFailure.TIMED_OUT), result)
                assertEquals(listOf(1, 2), visited.toList())
                release.countDown()
                assertTrue(returned.await(1, TimeUnit.SECONDS))
                // The abandoned worker checks cancellation before the next PM input.
                reader.close()
                assertEquals(listOf(1, 2), visited.toList())
                assertTrue(result !is StartupTargetReadResult.Complete)
            } finally { release.countDown() }
        }
    }

    @Test fun `entire batch shares deadline while healthy empty and negative results complete`() {
        BoundedStartupTargetRead(timeoutMillis = 80).use { reader ->
            val started = System.nanoTime()
            val result = reader.readBatch(listOf(1, 2, 3, 4)) { Thread.sleep(35); it }
            assertEquals(StartupTargetReadResult.Unavailable(StartupTargetReadFailure.TIMED_OUT), result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250)
        }
        BoundedStartupTargetRead().use { reader ->
            assertEquals(StartupTargetReadResult.Complete(emptyList<Int>()), reader.readBatch(emptyList<Int>()) { it })
            assertEquals(StartupTargetReadResult.Complete(listOf(null, null)), reader.readBatch(listOf(1, 2)) { null })
            assertThrows(IllegalStateException::class.java) {
                reader.readBatch(listOf(1)) { error("real PM failure") }
            }
        }
    }

    @Test fun `authority FIFO can save OFF while PM worker stays stuck and stale admission cannot commit`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val revision = java.util.concurrent.atomic.AtomicLong(1)
        val store = ProtectionConsentStore(Storage())
        val identity = SelectedTargetIdentity("example.recipient", 11001, 100, listOf("a".repeat(64)))
        val clock = BudgetClock(14, 600_000, 1_600_000)
        store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        store.enableMaster(store.readSnapshot(), clock)
        val before = store.readSnapshot()!!
        val committed = AtomicInteger()
        BoundedStartupTargetRead(timeoutMillis = 80, maxWorkers = 1).use { reader ->
            try {
                val old = executor.submit {
                    ProtectionStartAdmission().run(isCurrent = { revision.get() == 1L }, preflight = {
                        val result = reader.readBatch(listOf(identity)) {
                            entered.countDown(); awaitIgnoringInterrupts(release); it
                        }
                        if (result is StartupTargetReadResult.Unavailable) "retry" else null
                    }, admit = { true }, commit = { committed.incrementAndGet() }, reject = {},
                        failClosed = { throw AssertionError("stale read must not suspend") }, obsolete = {})
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                revision.incrementAndGet() // Immediate in-memory revocation; durable IO remains FIFO.
                val off = executor.submit<Boolean> { store.pauseMaster(clock) }
                assertTrue(off.get(1, TimeUnit.SECONDS))
                old.get(1, TimeUnit.SECONDS)
                assertEquals(0, committed.get())
                assertEquals(ProtectionConsentMode.USER_PAUSED, store.readMode())
                assertEquals(before.consents(), store.configuredConsents())
                assertTrue(store.desiredLeases().isEmpty())
                assertEquals(1L, release.count) // Durable OFF did not wait for Binder to return.
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    private class Storage : ProtectionAuthorityStorage {
        var bytes: ByteArray? = null
        var writes = 0
        override fun <T> exclusive(action: () -> T): T = action()
        override fun read() = bytes?.let(ProtectionAuthorityCodec::decode)
        override fun write(value: ProtectionAuthoritySnapshot) { writes++; bytes = ProtectionAuthorityCodec.encode(value) }
    }

    @Test fun `master precommit local unavailable skips both enable and failure suspension preserves exact mode bytes`() {
        for (mode in listOf(ProtectionConsentMode.ACTIVE, ProtectionConsentMode.USER_PAUSED, ProtectionConsentMode.SUSPENDED)) {
            for (reason in StartupTargetReadFailure.entries) {
                val storage = Storage()
                val store = ProtectionConsentStore(storage)
                val identity = SelectedTargetIdentity("example.recipient", 11001, 100, listOf("a".repeat(64)))
                val clock = BudgetClock(14, 600_000, 1_600_000)
                store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock)
                if (mode == ProtectionConsentMode.ACTIVE) store.enableMaster(store.readSnapshot(), clock)
                if (mode == ProtectionConsentMode.SUSPENDED) store.suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                val expected = store.readSnapshot()!!
                val before = storage.bytes!!.clone()
                val writes = storage.writes
                val result = runCatching {
                    requireMasterTargetEvidence(StartupTargetReadResult.Unavailable(reason), expected.consents())
                    check(store.enableMaster(expected, clock))
                }
                if (shouldSuspendFailedMasterEnable(result.exceptionOrNull())) {
                    store.suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                }
                assertTrue(result.exceptionOrNull() is StartupTargetReadUnavailableException)
                assertEquals(expected, store.readSnapshot())
                assertArrayEquals(before, storage.bytes)
                assertEquals(writes, storage.writes)
                val projected = configuredAutomaticProtectionState(AutomaticProtectionState(enabled = false, busy = true),
                    expected, "retry").copy(enabled = false, busy = false)
                assertEquals(mode == ProtectionConsentMode.ACTIVE, projected.masterEnabled)
                assertFalse(projected.enabled); assertFalse(projected.busy)
                assertEquals(expected.consents().map { it.identity }, projected.configuredIdentities)
            }
        }
        assertTrue(shouldSuspendFailedMasterEnable(IllegalStateException("real failure")))
        assertFalse(shouldSuspendFailedMasterEnable(null))
    }

    @Test fun `completed missing changed or unqualified master facts remain real failures`() {
        val identity = SelectedTargetIdentity("example.recipient", 11001, 100, listOf("a".repeat(64)))
        val store = ProtectionConsentStore(Storage())
        store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, BudgetClock(14, 0, 1000))
        for (evidence in listOf(StartupPackageEvidence(null, true), StartupPackageEvidence(identity, false),
            StartupPackageEvidence(identity.copy(firstInstallTime = 101), true))) {
            val failure = runCatching { requireMasterTargetEvidence(StartupTargetReadResult.Complete(listOf(evidence)),
                store.configuredConsents()) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(shouldSuspendFailedMasterEnable(failure))
        }
    }
}
