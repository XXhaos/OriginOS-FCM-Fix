package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DaemonCycleMetricsTest {
    private val id = "12345678-1234-4234-9234-123456789abc"
    private fun session(name: String = id) = DaemonDiagnosticsSession(name,
        "abcdefab-abcd-4abc-9abc-abcdefabcdef", 42, 123, 93, 1000, 10, 1000, 10, DaemonDiagnosticsState.BEGIN)
    private fun metrics() = DaemonCycleMetrics(startedAtMillis = 1000, startedElapsedMillis = 10,
        lastCheckpointAtMillis = 1000, lastCheckpointElapsedMillis = 10)
    private class Storage : DaemonDiagnosticsStorage {
        @Volatile var value: DaemonDiagnosticsSnapshot? = null
        @Volatile var writes = 0
        var beforeWrite: (() -> Unit)? = null
        val closed = CountDownLatch(1)
        override fun read() = value
        override fun write(value: DaemonDiagnosticsSnapshot) { beforeWrite?.invoke(); this.value = value; writes++ }
        override fun close() { closed.countDown() }
    }
    private fun writer(storage: Storage, name: String = id) = DaemonDiagnosticsJournal(storage) {
        fullSample(name, it.atMillis, it.elapsedMillis)
    }
    private fun fullSample(name: String, wall: Long = 2000, elapsed: Long = 20) = DaemonDiagnosticsSample(
        name, wall, elapsed, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
        Long.MAX_VALUE, Long.MAX_VALUE, 100, "NOT_CHARGING", true, true)

    @Test fun everyReturnedStatusHasItsOwnSaturatingCounterAndLatencyIsIndependentOfOutcome() {
        var value = metrics()
        DaemonCycleStage.entries.forEach { stage -> DaemonCycleOutcome.entries.forEachIndexed { index, outcome ->
            value = value.record(stage, index.toLong(), outcome, 2000, 20)
        } }
        listOf(value.stopped, value.fcm).forEach {
            assertEquals(7, it.completedCount); assertEquals(21, it.totalElapsedMillis); assertEquals(6, it.maxElapsedMillis)
            assertEquals(List(7) { 1L }, it.outcomes.counts())
        }
        val saturated = DaemonCycleStageMetrics(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            DaemonCycleOutcomes(checked = Long.MAX_VALUE)).record(20, DaemonCycleOutcome.CHECKED)
        assertEquals(Long.MAX_VALUE, saturated.completedCount); assertEquals(Long.MAX_VALUE, saturated.totalElapsedMillis)
        assertEquals(Long.MAX_VALUE, saturated.maxElapsedMillis); assertEquals(Long.MAX_VALUE, saturated.outcomes.checked)
        metrics().copy(stopped = saturated).validate()
        value.validate()
    }

    @Test fun v1HistoryDecodesWithAbsentMetricsAndMigrationRetainsItBesideExplicitZero() {
        val legacy = DaemonDiagnosticsCodec.decode(v1())
        assertNull(legacy.sessions.single().cycleMetrics)
        val storage = Storage().apply { value = legacy }
        val migrated = writer(storage, "new-session").begin(session("new-session").copy(cycleMetrics = metrics()))
        assertEquals(2, migrated.sessions.size)
        assertNull(migrated.sessions.first().cycleMetrics)
        assertEquals(0, migrated.sessions.last().cycleMetrics!!.stopped.completedCount)
        assertEquals(DaemonDiagnosticsState.UNKNOWN_END, migrated.sessions.first().state)
        val encoded = DaemonDiagnosticsCodec.encode(migrated)
        assertEquals(2, java.nio.ByteBuffer.wrap(encoded).getInt(4))
        assertEquals(migrated, DaemonDiagnosticsCodec.decode(encoded))
        val unknown = encoded.copyOf().apply { java.nio.ByteBuffer.wrap(this).putInt(4, 3) }
        val body = unknown.copyOf(unknown.size - 32)
        MessageDigest.getInstance("SHA-256").digest(body).copyInto(unknown, body.size)
        fails { DaemonDiagnosticsCodec.decode(unknown) }
        val badMetrics = migrated.copy(sessions = listOf(session().copy(cycleMetrics = metrics().copy(version = 2))))
        fails { DaemonDiagnosticsCodec.encode(badMetrics) }
        fails { DaemonDiagnosticsCodec.decode(encoded.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }) }
    }

    @Test fun fullProductionUuidRingsAllOptionalsAndMaximumMetricsFitWithoutEviction() {
        val maxStage = DaemonCycleStageMetrics(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            DaemonCycleOutcomes(checked = Long.MAX_VALUE, reconciled = Long.MAX_VALUE, waiting = Long.MAX_VALUE,
                busy = Long.MAX_VALUE, throttled = Long.MAX_VALUE, pending = Long.MAX_VALUE, unavailable = Long.MAX_VALUE))
        val summary = session().copy(state = DaemonDiagnosticsState.NORMAL_END, endedAtMillis = Long.MAX_VALUE,
            endedElapsedMillis = Long.MAX_VALUE, stoppedCheckedAtMillis = Long.MAX_VALUE, fcmCheckedAtMillis = Long.MAX_VALUE,
            maxPollGapMillis = Long.MAX_VALUE, cycleMetrics = metrics().copy(stopped = maxStage, fcm = maxStage))
        val sessions = List(32) { summary.copy(sessionId = "%08d-1234-4234-9234-123456789abc".format(it)) }
        val value = DaemonDiagnosticsSnapshot(DaemonDiagnosticsAvailability.AVAILABLE, currentSessionId = sessions.last().sessionId,
            sessions = sessions, samples = List(2016) { fullSample(sessions[it % 32].sessionId) },
            events = List(256) { DaemonDiagnosticsEvent(sessions[it % 32].sessionId, Long.MAX_VALUE, Long.MAX_VALUE, DaemonDiagnosticsState.READY) })
        assertSame(value, DaemonDiagnosticsCodec.bounded(value))
        val encoded = DaemonDiagnosticsCodec.encode(value)
        assertTrue(encoded.size <= DaemonDiagnosticsCodec.MAX_BYTES)
        assertEquals(value, DaemonDiagnosticsCodec.decode(encoded))
    }

    @Test fun validUnicodeLegacyIdsAreTrimmedDeterministicallyBeforePublicationAndGrowthContinues() {
        val name = "界".repeat(128)
        val storage = Storage()
        storage.value = DaemonDiagnosticsSnapshot(sessions = listOf(session(name)), samples = List(1000) { fullSample(name) })
        val journal = writer(storage, name)
        // New process uses another valid legacy-sized identifier; retaining old samples is allowed.
        journal.begin(session("另".repeat(128)).copy(cycleMetrics = metrics()))
        // Capture uses old ID intentionally: valid retained legacy rows still consume actual bytes.
        var snapshot = storage.value!!
        repeat(200) { index -> snapshot = journal.observe(DaemonDiagnosticsOpportunity(3000L + index,
            30L + index * 300_000L, DaemonDiagnosticsState.READY), 0, metrics()) }
        assertTrue(snapshot.samples.size < 1200)
        assertTrue(snapshot.evictedSamples > 0)
        assertEquals(1200L, snapshot.samples.size + snapshot.evictedSamples)
        assertTrue(DaemonDiagnosticsCodec.encode(snapshot).size <= DaemonDiagnosticsCodec.MAX_BYTES)
        assertEquals(snapshot, DaemonDiagnosticsCodec.bounded(snapshot))
        assertEquals(snapshot, DaemonDiagnosticsCodec.decode(DaemonDiagnosticsCodec.encode(snapshot)))
    }

    @Test fun unchangedOpportunitiesShareImmutableRingsAndMetricsCauseNoExtraWrites() {
        val storage = Storage(); val journal = writer(storage)
        journal.begin(session().copy(cycleMetrics = metrics()))
        val first = journal.observe(DaemonDiagnosticsOpportunity(2000, 20, DaemonDiagnosticsState.READY), 0, metrics())
        val nextMetrics = metrics().record(DaemonCycleStage.FCM_CYCLE, 7, DaemonCycleOutcome.UNAVAILABLE, 2001, 21)
        val next = journal.observe(DaemonDiagnosticsOpportunity(2001, 21, DaemonDiagnosticsState.READY), 0, nextMetrics)
        assertEquals(2, storage.writes)
        assertSame(first.samples, next.samples); assertSame(first.events, next.events)
        assertEquals(0, first.sessions.last().cycleMetrics!!.fcm.completedCount)
        assertEquals(1, next.sessions.last().cycleMetrics!!.fcm.outcomes.unavailable)
        fails { (next.samples as MutableList<DaemonDiagnosticsSample>).clear() }
        fails { (next.events as MutableList<DaemonDiagnosticsEvent>).clear() }
        fails { (next.sessions as MutableList<DaemonDiagnosticsSession>).clear() }
        journal.observe(DaemonDiagnosticsOpportunity(2002, 22, DaemonDiagnosticsState.NORMAL_END), 0, nextMetrics)
        assertEquals(3, storage.writes); assertEquals(nextMetrics, storage.value!!.sessions.last().cycleMetrics)
    }

    @Test fun unchangedPollFastPathDoesNotInspectRetainedHistoryEvenWhenMetricsAppear() {
        val storage = Storage(); var budgetChecks = 0
        val journal = DaemonDiagnosticsJournal(storage,
            budget = { budgetChecks++; DaemonDiagnosticsCodec.bounded(it) },
            sample = { fullSample(id, it.atMillis, it.elapsedMillis) })
        journal.begin(session())
        journal.observe(DaemonDiagnosticsOpportunity(2000, 20, DaemonDiagnosticsState.READY), 0)
        assertEquals(2, budgetChecks)
        var summary = metrics()
        repeat(9) { index ->
            summary = summary.record(DaemonCycleStage.FCM_CYCLE, 3, DaemonCycleOutcome.CHECKED, 2001L + index, 21L + index)
            journal.observe(DaemonDiagnosticsOpportunity(2001L + index, 21L + index * 30_000L,
                DaemonDiagnosticsState.READY), 0, summary)
        }
        assertEquals(2, budgetChecks); assertEquals(2, storage.writes)
        journal.observe(DaemonDiagnosticsOpportunity(3000, 300_020, DaemonDiagnosticsState.READY), 0, summary)
        assertEquals(3, budgetChecks); assertEquals(3, storage.writes)
        journal.observe(DaemonDiagnosticsOpportunity(3001, 300_021, DaemonDiagnosticsState.NORMAL_END), 0, summary)
        assertEquals(4, budgetChecks); assertEquals(4, storage.writes)
    }

    @Test fun blockedWriterAndCoalescingDoNotLoseCycleCompletionsOrDelayTheirReadOnlySnapshot() {
        val storage = Storage(); val now = AtomicLong(20)
        val runtime = DaemonDiagnosticsRuntime(session(), { storage }, { fullSample(id, it.atMillis, it.elapsedMillis) },
            wall = { 1000 + now.get() }, elapsed = now::get)
        await { storage.writes == 1 }
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        storage.beforeWrite = { entered.countDown(); check(gate.await(3, TimeUnit.SECONDS)) }
        runtime.opportunity(DaemonDiagnosticsState.READY, null, null)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(40) {
            now.incrementAndGet()
            runtime.cycleCompleted(DaemonCycleStage.STOPPED_CYCLE, 2, DaemonCycleOutcome.CHECKED)
            runtime.cycleCompleted(DaemonCycleStage.FCM_CYCLE, 3, DaemonCycleOutcome.PENDING)
            runtime.opportunity(DaemonDiagnosticsState.READY, null, null)
        }
        val live = runtime.snapshot().sessions.single().cycleMetrics!!
        assertEquals(40, live.stopped.completedCount); assertEquals(80, live.stopped.totalElapsedMillis)
        assertEquals(40, live.fcm.outcomes.pending); assertEquals(120, live.fcm.totalElapsedMillis)
        assertEquals(1, storage.writes)
        runtime.normalEnd(); gate.countDown()
        assertTrue(storage.closed.await(3, TimeUnit.SECONDS))
        assertEquals(live, storage.value!!.sessions.single().cycleMetrics)
        assertTrue(storage.value!!.skippedSamples > 0)
        assertEquals(3, storage.writes)
    }

    /** Frozen original format fixture: this deliberately never calls the new encoder. */
    private fun v1(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x47444431); out.writeInt(1); out.writeInt(DaemonDiagnosticsAvailability.AVAILABLE.ordinal)
            out.writeLong(2000); out.writeLong(20); out.writeBoolean(true); out.writeUTF(id); out.writeLong(1)
            repeat(6) { out.writeLong(0) }; out.writeInt(1)
            val s = session()
            out.writeUTF(s.sessionId); out.writeUTF(s.bootId); out.writeInt(s.processId); out.writeBoolean(true); out.writeLong(123)
            listOf(93L, 1000L, 10L, 1000L, 10L).forEach(out::writeLong); out.writeInt(DaemonDiagnosticsState.READY.ordinal)
            repeat(4) { out.writeBoolean(false) }; out.writeLong(0)
            out.writeInt(0); out.writeInt(0)
        }
        val body = bytes.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Timed out awaiting diagnostic worker", condition())
    }
    private fun fails(action: () -> Unit) { try { action() } catch (_: Exception) { return }; fail("Expected rejection") }
}
