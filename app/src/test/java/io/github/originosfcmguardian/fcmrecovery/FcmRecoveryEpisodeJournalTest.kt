package io.github.originosfcmguardian.fcmrecovery

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FcmRecoveryEpisodeJournalTest {
    private val correlation = "01234567-89ab-cdef-0123-456789abcdef"
    private val otherCorrelation = "11234567-89ab-cdef-0123-456789abcdef"
    private class Storage : FcmRecoveryEpisodeStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var reads = 0
        var writeFails = false
        var readFails = false
        override fun read(): FcmRecoveryEpisodeSnapshot {
            reads++
            if (readFails) throw IOException("unavailable")
            return bytes?.let { try { FcmRecoveryEpisodeCodec.decode(it) } catch (e: Exception) { throw FcmEpisodeCorruption(e) } }
                ?: FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE)
        }
        override fun write(value: FcmRecoveryEpisodeSnapshot) {
            writes++
            if (writeFails) throw IOException("failure")
            bytes = FcmRecoveryEpisodeCodec.encode(value)
        }
    }
    private fun owner(storage: Storage = Storage(), pid: Int = 101, start: Long = 10) =
        FcmRecoveryEpisodeJournal(storage, pid, start, { 1000000 }, { 100 }).also { it.initialize() }
    private fun observation(elapsed: Long, state: GcmConnectionState = GcmConnectionState.SERVICE_ABSENT,
        qualified: Boolean = true, eligible: Boolean = true, action: FcmRecoveryEventAction = FcmRecoveryEventAction.OBSERVATION) =
        FcmRecoveryEvent(1000000 + elapsed, elapsed, action, state,
            if (qualified) SourceCompleteness.COMPLETE else SourceCompleteness.UNAVAILABLE,
            if (state == GcmConnectionState.REPORTED_CONNECTED) SocketObservation.OBSERVED else SocketObservation.NO_MATCH,
            eligible)
    private fun request(elapsed: Long, status: FcmRecoveryStatus = FcmRecoveryStatus.REQUESTED,
        reason: FcmRecoveryReason = FcmRecoveryReason.COMMAND_ACCEPTED) =
        FcmRecoveryEvent(1000000 + elapsed, elapsed, FcmRecoveryEventAction.REQUEST_RESULT,
            status = status, reason = reason)
    private fun FcmRecoveryEpisodeJournal.observeAt(elapsed: Long, state: GcmConnectionState = GcmConnectionState.SERVICE_ABSENT) =
        observe(observation(elapsed, state), true, state == GcmConnectionState.REPORTED_CONNECTED)

    private fun FcmRecoveryEpisodeJournal.acceptedStartup() {
        observeAt(100)
        requestStarted(request(120).copy(action = FcmRecoveryEventAction.ATTEMPT_CONSUMED, status = null, reason = null), correlation)
        requestCompleted(request(150), correlation)
    }

    private fun startupWaiting(elapsed: Long) = observation(elapsed, GcmConnectionState.REPORTED_CONNECTED,
        action = FcmRecoveryEventAction.READBACK).copy(socket = SocketObservation.NO_MATCH)

    @Test fun `actual accepted startup with connected service before socket preserves bounded relation`() {
        val storage = Storage(); val journal = owner(storage)
        journal.acceptedStartup()
        journal.observe(startupWaiting(160), true, false)
        repeat(100) { journal.observe(startupWaiting(161 + it.toLong()), true, false) }
        val waiting = journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, waiting.outcome)
        assertFalse(waiting.requestPending)
        assertFalse(waiting.timeoutObserved)
        assertNull(waiting.endedAtMillis)
        assertEquals(100L, waiting.lastObservedElapsedMillis) // RAM-only until a meaningful transition.
        assertEquals(3, storage.writes)
        journal.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
        val complete = journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, complete.outcome)
        assertEquals(120L, complete.requestElapsedMillis)
        assertEquals(150L, complete.resultElapsedMillis)
        assertEquals(30160L, complete.endedElapsedMillis)
        assertEquals(4, storage.writes)
        repeat(100) { journal.observeAt(30161 + it.toLong(), GcmConnectionState.REPORTED_CONNECTED) }
        assertEquals(4, storage.writes)
    }

    @Test fun `startup waiting deadline stays anchored to request and never grants late credit`() {
        for (lateSocket in listOf(SocketObservation.NO_MATCH, SocketObservation.OBSERVED)) {
            val storage = Storage(); val journal = owner(storage)
            journal.acceptedStartup()
            journal.observe(startupWaiting(160), true, false)
            journal.observe(startupWaiting(120120), true, false)
            assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
            assertEquals(3, storage.writes)
            journal.observe(startupWaiting(121120).copy(socket = lateSocket), true, lateSocket == SocketObservation.OBSERVED)
            val gap = journal.snapshot().episodes.single()
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, gap.outcome)
            assertTrue(gap.timeoutObserved)
            assertEquals(121120L, gap.endedElapsedMillis)
            journal.observeAt(122120, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(gap, journal.snapshot().episodes.single())
            assertEquals(4, storage.writes)
        }
        val exactDeadline = owner(); exactDeadline.acceptedStartup()
        exactDeadline.observe(startupWaiting(160), true, false)
        exactDeadline.observeAt(120120, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, exactDeadline.snapshot().episodes.single().outcome)
    }

    @Test fun `unknown denied unqualified ineligible or lost scope still closes accepted startup gap`() {
        val unsafe = listOf(
            Triple(startupWaiting(170).copy(service = GcmConnectionState.UNKNOWN), true, false),
            Triple(startupWaiting(170).copy(socket = SocketObservation.UNKNOWN), true, false),
            Triple(startupWaiting(170).copy(completeness = SourceCompleteness.DENIED), true, false),
            Triple(startupWaiting(170), false, false),
            Triple(startupWaiting(170).copy(eligible = false), true, false),
        )
        for ((event, qualified, connected) in unsafe) {
            val journal = owner(); journal.acceptedStartup()
            journal.observe(startupWaiting(160), true, false)
            journal.observe(event, qualified, connected)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
            journal.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        }
        val journal = owner(); journal.acceptedStartup()
        journal.observe(startupWaiting(160), true, false)
        journal.coverageGap(1000170, 170)
        assertEquals(160L, journal.snapshot().episodes.single().lastObservedElapsedMillis)
        journal.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
    }

    @Test fun `pending or missing request cannot preserve startup waiting credit`() {
        for (pending in listOf(true, false)) {
            val journal = owner(); journal.observeAt(100)
            if (pending) journal.requestStarted(request(120).copy(action = FcmRecoveryEventAction.ATTEMPT_CONSUMED,
                status = null, reason = null), correlation)
            journal.observe(startupWaiting(160), true, false)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
            if (pending) journal.requestCompleted(request(170), correlation)
            journal.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        }
    }

    @Test fun `startup waiting requires nondecreasing observation clocks and completed result time`() {
        for (event in listOf(startupWaiting(159), startupWaiting(170).copy(atMillis = 1000159))) {
            val journal = owner(); journal.acceptedStartup()
            journal.observe(startupWaiting(160), true, false)
            journal.observe(event, true, false)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
            journal.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        }
        val staleResult = owner(); staleResult.acceptedStartup()
        staleResult.observe(startupWaiting(140), true, false)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, staleResult.snapshot().episodes.single().outcome)
    }

    @Test fun `startup waiting remains a gap after disk reconstruction even within same process scope`() {
        val storage = Storage(); val journal = owner(storage); journal.acceptedStartup()
        journal.observe(startupWaiting(160), true, false)
        val reconstructed = owner(storage)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, reconstructed.snapshot().episodes.single().outcome)
        reconstructed.observeAt(30160, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, reconstructed.snapshot().episodes.single().outcome)
    }

    @Test fun `requested is pending until actual qualified connected readback`() {
        val storage = Storage(); val journal = owner(storage)
        journal.observeAt(100); journal.request(request(110))
        val pending = journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, pending.outcome)
        assertNull(pending.endedAtMillis)
        assertEquals(110L, pending.requestElapsedMillis)
        journal.observe(observation(120, GcmConnectionState.REPORTED_CONNECTED, action = FcmRecoveryEventAction.READBACK), true, true)
        val ended = journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, ended.outcome)
        assertEquals(120L, ended.endedElapsedMillis)
        assertEquals(3, storage.writes)
        journal.observeAt(130, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(3, storage.writes)
    }

    @Test fun `healthy cycles and repeated fault observations do not produce per poll writes`() {
        val storage = Storage(); val journal = owner(storage)
        repeat(100) { journal.observeAt(100 + it.toLong(), GcmConnectionState.REPORTED_CONNECTED) }
        assertTrue(journal.snapshot().episodes.isEmpty()); assertEquals(0, storage.writes)
        journal.observeAt(1000)
        repeat(100) { journal.observeAt(1001 + it.toLong()) }
        assertEquals(1, storage.writes)
        assertEquals(1000L, journal.snapshot().episodes.single().lastObservedElapsedMillis)
        journal.observeAt(1101, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_WITHOUT_REQUEST, journal.snapshot().episodes.single().outcome)
        assertEquals(2, storage.writes)
    }

    @Test fun `absent and disconnected block changes are diagnostic and never dispatch`() {
        val storage = Storage(); val journal = owner(storage)
        journal.observeAt(100, GcmConnectionState.REPORTED_DISCONNECTED)
        assertEquals(FcmRecoveryEpisodeBlock.PRESENT_DISCONNECTED_NOT_ADMITTED, journal.snapshot().episodes.single().lastBlock)
        journal.request(request(110))
        assertNull(journal.snapshot().episodes.single().requestStatus)
        assertEquals(1, storage.writes)
        journal.observe(observation(120), false, false)
        assertEquals(FcmRecoveryEpisodeBlock.NOT_QUALIFIED, journal.snapshot().episodes.single().lastBlock)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        journal.observe(observation(130, eligible = false), true, false)
        assertEquals(2, storage.writes)
        journal.observeAt(140)
        assertEquals(FcmRecoveryEpisodeBlock.NONE, journal.snapshot().episodes.last().lastBlock)
        assertEquals(2, journal.snapshot().episodes.size)
        assertEquals(3, storage.writes)
    }

    @Test fun `request observation window flags deadline and closes gap beyond 120 seconds`() {
        val storage = Storage(); val journal = owner(storage)
        journal.observeAt(100); journal.request(request(200))
        journal.observeAt(120199)
        assertFalse(journal.snapshot().episodes.single().timeoutObserved)
        assertEquals(2, storage.writes)
        journal.observeAt(120200)
        assertTrue(journal.snapshot().episodes.single().timeoutObserved)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
        journal.observeAt(121200)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        assertEquals(4, storage.writes)
        repeat(100) { journal.observeAt(121201 + it.toLong(), GcmConnectionState.REPORTED_CONNECTED) }
        assertEquals(4, storage.writes)
        assertTrue(journal.snapshot().episodes.single().timeoutObserved)
    }

    @Test fun `not sent and unknown results close without granting late connection credit`() {
        for ((status, reason, outcome) in listOf(
            Triple(FcmRecoveryStatus.NOT_SENT, FcmRecoveryReason.NOT_ABSENT, FcmRecoveryEpisodeOutcome.REQUEST_NOT_SENT),
            Triple(FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.BRIDGE_UNAVAILABLE, FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN))) {
            val journal = owner(); journal.observeAt(100); journal.request(request(110, status, reason))
            assertEquals(outcome, journal.snapshot().episodes.single().outcome)
            journal.observeAt(120, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(outcome, journal.snapshot().episodes.single().outcome)
        }
    }

    @Test fun `unknown eligibility loss and explicit coverage gaps never credit later connections`() {
        for (unknown in listOf(true, false)) {
            val journal = owner(); journal.observeAt(100); journal.request(request(110))
            if (unknown) journal.observe(observation(120, GcmConnectionState.UNKNOWN, qualified = false), false, false)
            else journal.coverageGap(1000120, 120)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
            journal.observeAt(130, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        }
        val journal = owner(); journal.observeAt(100); journal.request(request(110))
        journal.observe(observation(120, GcmConnectionState.REPORTED_CONNECTED), false, true)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        val ineligible = owner(); ineligible.observeAt(100); ineligible.request(request(110))
        ineligible.observe(observation(120, GcmConnectionState.REPORTED_CONNECTED, eligible = false), true, true)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, ineligible.snapshot().episodes.single().outcome)
    }

    @Test fun `process identity and process start distinguish reconstructed scope without fabricated duration`() {
        for ((pid, start) in listOf(102 to 10L, 101 to 20L, 102 to 0L, 101 to 10L)) {
            val storage = Storage(); val prior = owner(storage); prior.observeAt(100); prior.request(request(110))
            val next = owner(storage, pid, start)
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, next.snapshot().episodes.single().outcome)
            assertEquals(110L, next.snapshot().episodes.single().endedElapsedMillis)
            next.observeAt(120, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(3, storage.writes)
            next.observeAt(130)
            assertEquals(2, next.snapshot().episodes.size)
            assertEquals(pid, next.snapshot().episodes.last().processId)
            assertEquals(start, next.snapshot().episodes.last().processStartedElapsedMillis)
            next.initialize(); assertEquals(2, storage.reads)
        }
    }

    @Test fun `qualification loss on an absent or disconnected sample cannot preserve request credit`() {
        for (state in listOf(GcmConnectionState.SERVICE_ABSENT, GcmConnectionState.REPORTED_DISCONNECTED)) {
            for (qualified in listOf(true, false)) {
                val storage = Storage(); val journal = owner(storage)
                journal.observeAt(100); journal.request(request(110))
                journal.observe(observation(120, state, eligible = !qualified), qualified, false)
                assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
                repeat(100) { journal.observe(observation(121 + it.toLong(), state, eligible = !qualified), qualified, false) }
                assertEquals(3, storage.writes)
                journal.observeAt(230, GcmConnectionState.REPORTED_CONNECTED)
                assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
            }
        }
    }

    @Test fun `attempt and stale event do not become requests or repair confirmations`() {
        val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
        journal.request(request(110).copy(action = FcmRecoveryEventAction.ATTEMPT_CONSUMED))
        assertNull(journal.snapshot().episodes.single().requestStatus)
        journal.request(request(120)); journal.request(request(130))
        assertEquals(120L, journal.snapshot().episodes.single().requestElapsedMillis)
        journal.observeAt(115, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
        assertEquals(2, storage.writes)
        journal.observe(observation(140, GcmConnectionState.REPORTED_CONNECTED).copy(socket = SocketObservation.NO_MATCH), true, true)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
        assertEquals(2, storage.writes)
    }

    @Test fun `retention is bounded immutable and monotonic`() {
        val storage = Storage(); val journal = owner(storage)
        repeat(40) { journal.observeAt(100 + it * 10L); journal.observeAt(101 + it * 10L, GcmConnectionState.REPORTED_CONNECTED) }
        val value = journal.snapshot()
        assertEquals(32, value.episodes.size); assertEquals(8L, value.evictedEpisodes)
        assertEquals(9L, value.episodes.first().sequence); assertEquals(40L, value.episodes.last().sequence)
        assertThrows(UnsupportedOperationException::class.java) { (value.episodes as MutableList<*>).clear() }
        assertEquals(value, FcmRecoveryEpisodeCodec.decode(storage.bytes!!))
        assertTrue(storage.bytes!!.size <= FcmRecoveryEpisodeCodec.MAX_BYTES)
    }

    @Test fun `writer and read failures are isolated and never overwrite corrupt bytes`() {
        val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
        val saved = storage.bytes!!.clone(); storage.writeFails = true; journal.request(request(110))
        assertEquals(FcmEpisodeAvailability.UNAVAILABLE, journal.snapshot().availability)
        assertEquals(1L, journal.snapshot().writeFailures)
        assertArrayEquals(saved, storage.bytes)
        journal.coverageGap(1000120, 120); journal.observeAt(130); assertEquals(2, storage.writes)
        val corrupt = Storage().also { it.bytes = byteArrayOf(1, 2, 3) }; val damaged = owner(corrupt)
        assertEquals(FcmEpisodeAvailability.CORRUPT, damaged.snapshot().availability)
        damaged.observeAt(100); damaged.request(request(110)); damaged.coverageGap(1000120, 120)
        assertEquals(0, corrupt.writes); assertArrayEquals(byteArrayOf(1, 2, 3), corrupt.bytes)
        val denied = owner(Storage().also { it.readFails = true })
        assertEquals(FcmEpisodeAvailability.UNAVAILABLE, denied.snapshot().availability)
        denied.observeAt(100)
    }

    private fun signed(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
    @Test fun `codec verifies checksum schema full parse truncation flags and closed enum bounds`() {
        val journal = owner(); journal.observeAt(100); val bytes = FcmRecoveryEpisodeCodec.encode(journal.snapshot())
        assertEquals(journal.snapshot(), FcmRecoveryEpisodeCodec.decode(bytes))
        for (cut in bytes.indices) assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(bytes.copyOf(cut)) }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(bytes + 0) }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(ByteArray(FcmRecoveryEpisodeCodec.MAX_BYTES + 1)) }
        val payload = bytes.copyOf(bytes.size - 32)
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(payload + 0)) }
        val changedChecksum = bytes.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(changedChecksum) }
        // Root28; episode: seq8/pid4/process8/start8/elapsed8/kind1/lastAt8/lastElapsed8.
        for (offset in listOf(64, 81, 82, 83, 84, 85, 86, 87, 88)) {
            val bad = payload.clone(); bad[offset] = 255.toByte()
            assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(bad)) }
        }
        for ((offset, number) in listOf(4 to 4, 24 to 33)) {
            val bad = payload.clone(); ByteBuffer.wrap(bad).putInt(offset, number)
            assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(bad)) }
        }
        val negative = payload.clone(); ByteBuffer.wrap(negative).putLong(8, -1)
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(negative)) }
        assertThrows(IllegalArgumentException::class.java) { journal.snapshot().episodes.single().copy(sequence = 0) }
        assertThrows(IllegalArgumentException::class.java) { journal.snapshot().episodes.single().copy(outcome = FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST) }
        journal.request(request(110))
        val requested = FcmRecoveryEpisodeCodec.encode(journal.snapshot()).let { it.copyOf(it.size - 32) }
        for (offset in listOf(98, 99, 100, 101, 102, 103, 104, 105, 106, 107)) {
            val bad = requested.clone(); bad[offset] = 255.toByte()
            assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(bad)) }
        }
        val inconsistent = requested.clone().also { it[100] = FcmRecoveryReason.BUSY.ordinal.toByte() }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(inconsistent)) }
        assertThrows(IllegalArgumentException::class.java) { journal.snapshot().copy(episodes = journal.snapshot().episodes + journal.snapshot().episodes) }
        assertThrows(IllegalArgumentException::class.java) { journal.snapshot().copy(droppedUpdates = -1) }
        // Queue-loss counters belong to the publishing runtime's current process, not disk history.
        assertEquals(0L, FcmRecoveryEpisodeCodec.decode(FcmRecoveryEpisodeCodec.encode(journal.snapshot().copy(droppedUpdates = 9))).droppedUpdates)
    }

    private fun begin(elapsed: Long) = FcmRecoveryEvent(1000000 + elapsed, elapsed, FcmRecoveryEventAction.ATTEMPT_CONSUMED)

    @Test fun `request begun before gap retains each late result without reopening or credit`() {
        for ((status, reason) in listOf(FcmRecoveryStatus.REQUESTED to FcmRecoveryReason.COMMAND_ACCEPTED,
            FcmRecoveryStatus.NOT_SENT to FcmRecoveryReason.AUTHORITY_LOST, FcmRecoveryStatus.UNKNOWN to FcmRecoveryReason.CANCELLED)) {
            val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
            journal.requestStarted(begin(110), correlation)
            assertTrue(journal.snapshot().episodes.single().requestPending)
            assertNull(journal.snapshot().episodes.single().requestStatus)
            journal.coverageGap(1000120, 120)
            journal.requestCompleted(request(130, status, reason), correlation)
            val episode = journal.snapshot().episodes.single()
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, episode.outcome)
            assertEquals(120L, episode.endedElapsedMillis)
            assertEquals(110L, episode.requestElapsedMillis)
            assertEquals(130L, episode.resultElapsedMillis)
            assertEquals(status, episode.requestStatus); assertEquals(reason, episode.reason)
            assertFalse(episode.requestPending); assertEquals(correlation, episode.requestCorrelationId)
            journal.observeAt(140, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(episode, journal.snapshot().episodes.single())
            assertEquals(4, storage.writes)
            assertEquals(journal.snapshot(), FcmRecoveryEpisodeCodec.decode(storage.bytes!!))
        }
    }

    @Test fun `off queued before begin retains actual rpc metadata on that closed gap`() {
        for (beginElapsed in listOf(110L, 130L)) {
            for ((status, reason) in listOf(FcmRecoveryStatus.REQUESTED to FcmRecoveryReason.COMMAND_ACCEPTED,
                FcmRecoveryStatus.NOT_SENT to FcmRecoveryReason.AUTHORITY_LOST, FcmRecoveryStatus.UNKNOWN to FcmRecoveryReason.CANCELLED)) {
                val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
                // Consume happened in the core. Its synchronous record or scheduling lets
                // OFF/hostEnded enqueue this GAP before the optional begin is enqueued.
                journal.coverageGap(1000120, 120)
                journal.requestStarted(begin(beginElapsed), correlation)
                val begun = journal.snapshot().episodes.single()
                assertTrue(begun.requestPending); assertEquals(beginElapsed, begun.requestElapsedMillis)
                assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, begun.outcome)
                assertEquals(120L, begun.endedElapsedMillis)
                journal.requestCompleted(request(140, status, reason), correlation)
                val completed = journal.snapshot().episodes.single()
                assertEquals(status, completed.requestStatus); assertEquals(reason, completed.reason)
                assertEquals(140L, completed.resultElapsedMillis)
                assertEquals(120L, completed.endedElapsedMillis)
                assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, completed.outcome)
                journal.observeAt(150, GcmConnectionState.REPORTED_CONNECTED)
                assertEquals(completed, journal.snapshot().episodes.single())
                assertEquals(4, storage.writes)
                assertEquals(journal.snapshot(), FcmRecoveryEpisodeCodec.decode(storage.bytes!!))
            }
        }
    }

    @Test fun `late begin fallback cannot attach to reconstructed gap or disconnected scope`() {
        val storage = Storage(); val first = owner(storage); first.observeAt(100); first.coverageGap(1000120, 120)
        val reconstructed = owner(storage)
        reconstructed.requestStarted(begin(130), correlation); reconstructed.requestCompleted(request(140), correlation)
        assertNull(reconstructed.snapshot().episodes.single().requestAtMillis)
        assertEquals(2, storage.writes)
        val disconnected = owner(); disconnected.observeAt(100, GcmConnectionState.REPORTED_DISCONNECTED)
        disconnected.coverageGap(1000120, 120); disconnected.requestStarted(begin(130), correlation)
        assertNull(disconnected.snapshot().episodes.single().requestAtMillis)
    }

    @Test fun `in flight completion addresses original closed episode while another fault is active`() {
        val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
        journal.requestStarted(begin(110), correlation); journal.coverageGap(1000120, 120)
        journal.observeAt(130); journal.requestStarted(begin(140), otherCorrelation)
        val active = journal.snapshot().episodes.last()
        journal.requestCompleted(request(150), correlation)
        assertEquals(active, journal.snapshot().episodes.last())
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.first().outcome)
        assertEquals(FcmRecoveryStatus.REQUESTED, journal.snapshot().episodes.first().requestStatus)
        val stable = journal.snapshot(); val writes = storage.writes
        journal.requestCompleted(request(160), correlation)
        journal.requestCompleted(request(160), "21234567-89ab-cdef-0123-456789abcdef")
        journal.requestCompleted(request(139), otherCorrelation)
        journal.requestCompleted(request(160), "not-a-correlation")
        assertEquals(stable, journal.snapshot()); assertEquals(writes, storage.writes)
        journal.requestCompleted(request(170, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.CANCELLED), otherCorrelation)
        assertEquals(FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN, journal.snapshot().episodes.last().outcome)
        assertEquals(140L, journal.snapshot().episodes.last().requestElapsedMillis)
        assertEquals(170L, journal.snapshot().episodes.last().resultElapsedMillis)
    }

    @Test fun `pending reconstruction is unknown even for colliding process scope and closed gaps`() {
        for (gapBeforeReconstruction in listOf(true, false)) {
            val storage = Storage(); val prior = owner(storage); prior.observeAt(100)
            prior.requestStarted(begin(110), correlation)
            if (gapBeforeReconstruction) prior.coverageGap(1000120, 120)
            val next = owner(storage)
            val episode = next.snapshot().episodes.single()
            assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, episode.outcome)
            assertEquals(FcmRecoveryStatus.UNKNOWN, episode.requestStatus)
            assertEquals(FcmRecoveryReason.BRIDGE_UNAVAILABLE, episode.reason)
            assertEquals(correlation, episode.requestCorrelationId); assertEquals(110L, episode.requestElapsedMillis)
            assertFalse(episode.requestPending); assertNull(episode.resultAtMillis)
            val writes = storage.writes
            next.requestCompleted(request(130), correlation); next.observeAt(140, GcmConnectionState.REPORTED_CONNECTED)
            assertEquals(episode, next.snapshot().episodes.single()); assertEquals(writes, storage.writes)
        }
    }

    @Test fun `normal pending flow and cancellation preserve stages without per poll io`() {
        val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
        journal.requestStarted(begin(110), correlation)
        val writes = storage.writes
        journal.requestStarted(begin(111), correlation)
        repeat(100) { journal.observeAt(112 + it.toLong()) }
        assertEquals(writes, storage.writes)
        journal.requestCompleted(request(220), correlation)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
        journal.observeAt(230, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, journal.snapshot().episodes.single().outcome)
        assertEquals(220L, journal.snapshot().episodes.single().resultElapsedMillis)
        repeat(100) { journal.observeAt(231 + it.toLong(), GcmConnectionState.REPORTED_CONNECTED) }
        assertEquals(4, storage.writes)
        val cancelled = owner(); cancelled.observeAt(100); cancelled.requestStarted(begin(110), correlation)
        cancelled.requestCompleted(request(120, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.CANCELLED), correlation)
        assertEquals(FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN, cancelled.snapshot().episodes.single().outcome)
        assertFalse(cancelled.snapshot().episodes.single().requestPending)
    }

    @Test fun `pending connection cannot imply success before result and nonces are strictly validated`() {
        val journal = owner(); journal.observeAt(100)
        journal.requestStarted(begin(110), correlation.uppercase())
        assertNull(journal.snapshot().episodes.single().requestAtMillis)
        journal.requestStarted(begin(110), correlation)
        journal.observeAt(120, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        journal.requestCompleted(request(130), correlation)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, journal.snapshot().episodes.single().outcome)
        val episode = journal.snapshot().episodes.single()
        assertThrows(IllegalArgumentException::class.java) { episode.copy(requestPending = true) }
        assertThrows(IllegalArgumentException::class.java) { episode.copy(resultElapsedMillis = 109) }
        assertThrows(IllegalArgumentException::class.java) { episode.copy(requestCorrelationId = "x") }
    }

    private fun legacyBytes(value: FcmRecoveryEpisodeSnapshot): ByteArray = signed(ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x46455031); out.writeInt(1); out.writeLong(value.evictedEpisodes); out.writeLong(value.writeFailures)
            out.writeInt(value.episodes.size)
            value.episodes.forEach { e ->
                out.writeLong(e.sequence); out.writeInt(e.processId); out.writeLong(e.processStartedElapsedMillis)
                out.writeLong(e.startedAtMillis); out.writeLong(e.startedElapsedMillis); out.writeByte(e.kind.ordinal)
                out.writeLong(e.lastObservedAtMillis); out.writeLong(e.lastObservedElapsedMillis)
                out.writeBoolean(e.requestAtMillis != null)
                if (e.requestAtMillis != null) { out.writeLong(e.requestAtMillis); out.writeLong(e.requestElapsedMillis!!)
                    out.writeByte(e.requestStatus!!.ordinal); out.writeByte(e.reason!!.ordinal) }
                out.writeBoolean(e.endedAtMillis != null)
                if (e.endedAtMillis != null) { out.writeLong(e.endedAtMillis); out.writeLong(e.endedElapsedMillis!!) }
                out.writeByte(e.outcome.ordinal); out.writeBoolean(e.timeoutObserved); out.writeByte(e.lastBlock.ordinal)
            }
        }
    }.toByteArray())

    @Test fun `schema one remains readable while schema two pending fields are bounded and strict`() {
        val journal = owner(); journal.observeAt(100); journal.request(request(110)); journal.coverageGap(1000120, 120)
        val original = journal.snapshot().copy(episodes = journal.snapshot().episodes.map { it.copy(resultAtMillis = null, resultElapsedMillis = null, requestOperation = null) })
        val legacy = legacyBytes(original)
        assertEquals(original, FcmRecoveryEpisodeCodec.decode(legacy))
        assertEquals(original, FcmRecoveryEpisodeCodec.decode(FcmRecoveryEpisodeCodec.encode(FcmRecoveryEpisodeCodec.decode(legacy))))
        val pending = owner(); pending.observeAt(100); pending.requestStarted(begin(110), correlation)
        val bytes = FcmRecoveryEpisodeCodec.encode(pending.snapshot())
        assertEquals(3, ByteBuffer.wrap(bytes).getInt(4)); assertEquals(pending.snapshot(), FcmRecoveryEpisodeCodec.decode(bytes))
        for (cut in bytes.indices) assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(bytes.copyOf(cut)) }
        val payload = bytes.copyOf(bytes.size - 32)
        for (offset in listOf(98, 99, 101, 103, 104, 140, 141)) {
            val bad = payload.clone().also { it[offset] = 255.toByte() }
            assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(bad)) }
        }
        val noPending = payload.clone().also { it[140] = 0 }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(noPending)) }
        val uppercase = payload.clone().also { it[112] = 'A'.code.toByte() }
        assertThrows(Exception::class.java) { FcmRecoveryEpisodeCodec.decode(signed(uppercase)) }
    }

    @Test fun `serialized observations keep one active immutable snapshot under callers`() {
        val storage = Storage(); val journal = owner(storage); journal.observeAt(100)
        val executor = Executors.newFixedThreadPool(4)
        try {
            (0 until 4).map { executor.submit { repeat(50) { journal.observeAt(100); journal.snapshot() } } }
                .forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(1, storage.writes); assertEquals(1, journal.snapshot().episodes.size)
    }
    @Test fun `MCS startup skew preserves accepted operation and state transitions never rename SEND`() {
        val journal = owner()
        val operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS
        journal.observeAt(100, GcmConnectionState.REPORTED_DISCONNECTED)
        journal.requestStarted(begin(110).copy(operation = operation), correlation)
        journal.requestCompleted(request(120).copy(operation = operation), correlation)
        val connectedWithoutSocket = observation(130, GcmConnectionState.REPORTED_CONNECTED).copy(socket = SocketObservation.NO_MATCH)
        journal.observe(connectedWithoutSocket, true, false)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, journal.snapshot().episodes.single().outcome)
        journal.observeAt(140, GcmConnectionState.REPORTED_CONNECTED)
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, journal.snapshot().episodes.single().outcome)
        assertEquals(operation, journal.snapshot().episodes.single().requestOperation)
        assertEquals(journal.snapshot(), FcmRecoveryEpisodeCodec.decode(FcmRecoveryEpisodeCodec.encode(journal.snapshot())))

        val changing = owner()
        changing.observeAt(100); changing.requestStarted(begin(110), correlation)
        changing.requestCompleted(request(120), correlation)
        changing.observeAt(130, GcmConnectionState.REPORTED_DISCONNECTED)
        val episodes = changing.snapshot().episodes
        assertEquals(2, episodes.size)
        assertEquals(FcmRecoveryEpisodeKind.SERVICE_ABSENT, episodes.first().kind)
        assertEquals(FcmRecoveryOperation.SERVICE_ABSENT_SEND, episodes.first().requestOperation)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, episodes.first().outcome)
        assertEquals(FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED, episodes.last().kind)
        assertNull(episodes.last().requestAtMillis)
    }
    @Test fun `legacy schema two operation remains unknown and unrelated completion cannot change MCS request`() {
        val journal = owner(); journal.observeAt(100); journal.request(request(110)); journal.coverageGap(1000120, 120)
        val bytes = FcmRecoveryEpisodeCodec.encode(journal.snapshot())
        // One-episode schema two is exactly schema three without the appended operation byte.
        val payload = bytes.copyOf(bytes.size - 33).also { ByteBuffer.wrap(it).putInt(4, 2) }
        val decoded = FcmRecoveryEpisodeCodec.decode(signed(payload))
        assertNull(decoded.episodes.single().requestOperation)
        assertEquals(journal.snapshot().episodes.single().copy(requestOperation = null), decoded.episodes.single())
        val mcs = owner(); mcs.observeAt(100, GcmConnectionState.REPORTED_DISCONNECTED)
        mcs.requestStarted(begin(110).copy(operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS), correlation)
        mcs.requestCompleted(request(120), correlation)
        assertTrue(mcs.snapshot().episodes.single().requestPending)
        assertNull(mcs.snapshot().episodes.single().requestStatus)
    }
}
