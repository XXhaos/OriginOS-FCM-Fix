package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DaemonDiagnosticsTest {
    private fun session(id: String = "session-1", boot: String = "boot-1", elapsed: Long = 10) =
        DaemonDiagnosticsSession(id, boot, 42, 123, 85, 1000, elapsed, 1000, elapsed, DaemonDiagnosticsState.BEGIN)
    private class MemoryStorage : DaemonDiagnosticsStorage {
        var value: DaemonDiagnosticsSnapshot? = null
        var writes = 0
        var failWrite = false
        val began = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read() = value
        override fun write(value: DaemonDiagnosticsSnapshot) {
            if (failWrite) error("diagnostic write only")
            this.value = value; writes++; began.countDown()
        }
        override fun close() { closed.countDown() }
    }
    private fun journal(storage: MemoryStorage, id: String = "session-1") = DaemonDiagnosticsJournal(storage) {
        DaemonDiagnosticsSample(id, it.atMillis, it.elapsedMillis, cpuTimeMillis = it.elapsedMillis,
            rssKiB = 30000, heapUsedBytes = 10000, heapMaxBytes = 100000, threads = 4,
            batteryPercent = 90, chargingStatus = "DISCHARGING", interactive = false, deviceIdle = true)
    }
    @Test fun codecRoundTripBoundsAndCorruptionNeverMasqueradeAsEmptyHistory() {
        val storage = MemoryStorage(); val writer = journal(storage)
        writer.begin(session())
        val value = writer.observe(DaemonDiagnosticsOpportunity(2000, 20, DaemonDiagnosticsState.READY, 1900, 1950), 0)
            .copy(generatedAtMillis = 2001, generatedElapsedMillis = 21)
        assertEquals(value, DaemonDiagnosticsCodec.decode(DaemonDiagnosticsCodec.encode(value)))
        val bytes = DaemonDiagnosticsCodec.encode(value)
        fails { DaemonDiagnosticsCodec.decode(bytes.copyOf(bytes.size - 1)) }
        fails { DaemonDiagnosticsCodec.decode(bytes.copyOf().apply { this[30] = (this[30].toInt() xor 1).toByte() }) }
        fails { DaemonDiagnosticsCodec.encode(value.copy(samples = List(DaemonDiagnosticsCodec.MAX_SAMPLES + 1) { value.samples.single() })) }
        fails { DaemonDiagnosticsCodec.encode(value.copy(samples = value.samples.map { it.copy(batteryPercent = 101) })) }
        fails { DaemonDiagnosticsCodec.decode(ByteArray(DaemonDiagnosticsCodec.MAX_BYTES + 1)) }
    }
    @Test fun unchangedPollsStayInMemoryAndSamplingUsesElapsedTimeAcrossWallClockRollback() {
        val storage = MemoryStorage(); val writer = journal(storage); writer.begin(session())
        writer.observe(DaemonDiagnosticsOpportunity(2000, 20, DaemonDiagnosticsState.READY, 1900, 1950), 0)
        assertEquals(2, storage.writes)
        val live = writer.observe(DaemonDiagnosticsOpportunity(1000, 30_020, DaemonDiagnosticsState.READY, 999, 999), 0)
        assertEquals(2, storage.writes); assertEquals(1, live.samples.size)
        assertEquals(30_020, live.sessions.single().lastCheckpointElapsedMillis)
        writer.observe(DaemonDiagnosticsOpportunity(1001, 300_020, DaemonDiagnosticsState.READY), 0)
        assertEquals(3, storage.writes); assertEquals(2, storage.value!!.samples.size)
        assertEquals(1, storage.value!!.unknownIntervals)
    }
    @Test fun reconstructedHealthySessionIsUnknownWithoutInventedDeathOrCrossBootElapsedTime() {
        val storage = MemoryStorage(); journal(storage).begin(session())
        val restarted = journal(storage, "session-2").begin(session("session-2", "boot-2", 1))
        val old = restarted.sessions.first()
        assertEquals(DaemonDiagnosticsState.UNKNOWN_END, old.state)
        assertNull(old.endedAtMillis); assertNull(old.endedElapsedMillis)
        assertEquals(1, restarted.unknownIntervals)
        assertEquals("boot-2", restarted.sessions.last().bootId)
    }
    @Test fun normalDrainEndIsRetainedAndDoesNotBecomeUnknownOnNextHost() {
        val storage = MemoryStorage(); val writer = journal(storage); writer.begin(session())
        writer.observe(DaemonDiagnosticsOpportunity(2000, 20, DaemonDiagnosticsState.OFF), 0)
        writer.observe(DaemonDiagnosticsOpportunity(2001, 21, DaemonDiagnosticsState.CLOSING), 0)
        writer.observe(DaemonDiagnosticsOpportunity(2002, 22, DaemonDiagnosticsState.NORMAL_END), 0)
        val next = journal(storage, "session-2").begin(session("session-2"))
        assertEquals(0, next.unknownIntervals)
        assertEquals(2002L, next.sessions.first().endedAtMillis)
        assertEquals(listOf(DaemonDiagnosticsState.BEGIN, DaemonDiagnosticsState.OFF, DaemonDiagnosticsState.CLOSING,
            DaemonDiagnosticsState.NORMAL_END, DaemonDiagnosticsState.BEGIN), next.events.map { it.state })
    }
    @Test fun sevenDaySampleRingAndSessionEvictionAreBoundedAndCounted() {
        val storage = MemoryStorage(); val writer = journal(storage); writer.begin(session())
        repeat(DaemonDiagnosticsCodec.MAX_SAMPLES + 2) { i ->
            writer.observe(DaemonDiagnosticsOpportunity(2000L + i, 20L + i * 300_000L, DaemonDiagnosticsState.READY), 0)
        }
        assertEquals(DaemonDiagnosticsCodec.MAX_SAMPLES, storage.value!!.samples.size)
        assertEquals(2, storage.value!!.evictedSamples)
        // Maximum production ring fits its fixed Binder/storage bound.
        assertTrue(DaemonDiagnosticsCodec.encode(storage.value!!).size <= DaemonDiagnosticsCodec.MAX_BYTES)
        repeat(DaemonDiagnosticsCodec.MAX_SESSIONS + 1) { i -> journal(storage, "next-$i").begin(session("next-$i")) }
        assertEquals(DaemonDiagnosticsCodec.MAX_SESSIONS, storage.value!!.sessions.size)
        assertEquals(2, storage.value!!.evictedSessions)
        assertTrue(storage.value!!.samples.isEmpty())
    }
    @Test fun blockedDiagnosticIoCannotBlockOffersSnapshotOrLoseReservedEnd() {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1); val storage = MemoryStorage()
        val now = AtomicLong(20)
        val runtime = DaemonDiagnosticsRuntime(session(), open = {
            entered.countDown(); check(gate.await(3, TimeUnit.SECONDS)); storage
        }, capture = { DaemonDiagnosticsSample("session-1", it.atMillis, it.elapsedMillis) },
            wall = { 1000 + now.get() }, elapsed = now::get)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(40) { i -> now.incrementAndGet(); runtime.opportunity(
            if (i % 2 == 0) DaemonDiagnosticsState.READY else DaemonDiagnosticsState.WAITING, null, null) }
        runtime.normalEnd()
        assertEquals(DaemonDiagnosticsAvailability.INITIALIZING, runtime.snapshot().availability)
        gate.countDown(); assertTrue(storage.closed.await(3, TimeUnit.SECONDS))
        val result = runtime.snapshot()
        assertEquals(DaemonDiagnosticsState.NORMAL_END, result.sessions.single().state)
        assertEquals(DaemonDiagnosticsState.BEGIN, result.events.first().state)
        assertEquals(DaemonDiagnosticsState.NORMAL_END, result.events.last().state)
        assertTrue(result.skippedSamples > 0)
        fails { (result.sessions as MutableList<DaemonDiagnosticsSession>).clear() }
    }
    @Test fun corruptionAndLockDenialDisableOnlyDiagnosticsAndNeverOverwriteHistory() {
        for ((failure, expected) in listOf(
            DaemonDiagnosticsCorrupt(IllegalArgumentException()) to DaemonDiagnosticsAvailability.CORRUPT,
            DaemonDiagnosticsLocked() to DaemonDiagnosticsAvailability.LOCKED)) {
            val runtime = DaemonDiagnosticsRuntime(session(), open = { throw failure },
                capture = { error("must not sample") }, wall = { 1000 }, elapsed = { 10 })
            // A diagnostic failure is observed asynchronously; no protection operation waits for it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (runtime.snapshot().availability == DaemonDiagnosticsAvailability.INITIALIZING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(expected, runtime.snapshot().availability)
            runtime.opportunity(DaemonDiagnosticsState.READY, null, null); runtime.normalEnd()
            assertEquals(expected, runtime.snapshot().availability)
        }
    }
    @Test fun handoverLockRetriesOnlyOnExistingOpportunitiesAndCollectsAfterOldWriterReleases() {
        val oldWriterLocked = AtomicBoolean(true); val attempts = AtomicInteger()
        val storage = MemoryStorage(); val now = AtomicLong(20)
        val runtime = DaemonDiagnosticsRuntime(session("session-2"), open = {
            attempts.incrementAndGet()
            if (oldWriterLocked.get()) throw DaemonDiagnosticsLocked()
            storage
        }, capture = { DaemonDiagnosticsSample("session-2", it.atMillis, it.elapsedMillis) },
            wall = { 1000 + now.get() }, elapsed = now::get)
        await { runtime.snapshot().availability == DaemonDiagnosticsAvailability.LOCKED }
        assertEquals(1, attempts.get())
        // A still-locked opportunity makes one finite attempt and stops again.
        now.incrementAndGet(); runtime.opportunity(DaemonDiagnosticsState.WAITING, null, null)
        await { attempts.get() == 2 && runtime.snapshot().failedOperations == 2L }
        assertEquals(DaemonDiagnosticsAvailability.LOCKED, runtime.snapshot().availability)
        oldWriterLocked.set(false)
        now.incrementAndGet(); runtime.opportunity(DaemonDiagnosticsState.READY, 1010, 1011)
        await { runtime.snapshot().availability == DaemonDiagnosticsAvailability.AVAILABLE && runtime.snapshot().samples.isNotEmpty() }
        assertEquals(3, attempts.get()); assertEquals("session-2", runtime.snapshot().currentSessionId)
        assertEquals(2L, runtime.snapshot().failedOperations)
        runtime.normalEnd(); assertTrue(storage.closed.await(3, TimeUnit.SECONDS))
        assertEquals(DaemonDiagnosticsState.NORMAL_END, runtime.snapshot().sessions.single().state)
    }
    @Test fun drainingWhileWaitingForOldWriterDoesNotRetryOrInventNormalEnd() {
        val attempts = AtomicInteger()
        val runtime = DaemonDiagnosticsRuntime(session(), open = {
            attempts.incrementAndGet(); throw DaemonDiagnosticsLocked()
        }, capture = { error("No diagnostic ownership") }, wall = { 1000 }, elapsed = { 20 })
        await { runtime.snapshot().availability == DaemonDiagnosticsAvailability.LOCKED }
        runtime.normalEnd()
        assertTrue(runtime.isFinished()); assertEquals(1, attempts.get())
        assertTrue(runtime.snapshot().sessions.isEmpty()); assertTrue(runtime.snapshot().events.isEmpty())
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Timed out awaiting diagnostic worker", condition())
    }
    private fun fails(action: () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected rejection") }
}
