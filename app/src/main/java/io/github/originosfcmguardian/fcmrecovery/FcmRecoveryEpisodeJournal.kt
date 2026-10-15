package io.github.originosfcmguardian.fcmrecovery

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import io.github.originosfcmguardian.core.gms.*
import java.io.*
import java.security.MessageDigest
import java.util.Collections

enum class FcmEpisodeAvailability { NOT_LOADED, AVAILABLE, CORRUPT, UNAVAILABLE }
enum class FcmRecoveryEpisodeKind { SERVICE_ABSENT, PRESENT_DISCONNECTED }
enum class FcmRecoveryEpisodeOutcome { OBSERVING, CONNECTION_AFTER_REQUEST, CONNECTION_WITHOUT_REQUEST, REQUEST_NOT_SENT, REQUEST_UNKNOWN, COVERAGE_GAP }
enum class FcmRecoveryEpisodeBlock { NONE, PRESENT_DISCONNECTED_NOT_ADMITTED, NOT_QUALIFIED, INELIGIBLE, OBSERVATION_UNKNOWN }
private val episodeCorrelationPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Temporal association only: a connection after a request does not prove its cause or delivery. */
data class FcmRecoveryEpisode(
    val sequence: Long, val processId: Int, val processStartedElapsedMillis: Long,
    val startedAtMillis: Long, val startedElapsedMillis: Long, val kind: FcmRecoveryEpisodeKind,
    val lastObservedAtMillis: Long, val lastObservedElapsedMillis: Long,
    val requestAtMillis: Long? = null, val requestElapsedMillis: Long? = null,
    val requestStatus: FcmRecoveryStatus? = null, val reason: FcmRecoveryReason? = null,
    val endedAtMillis: Long? = null, val endedElapsedMillis: Long? = null,
    val outcome: FcmRecoveryEpisodeOutcome = FcmRecoveryEpisodeOutcome.OBSERVING,
    val timeoutObserved: Boolean = false, val lastBlock: FcmRecoveryEpisodeBlock = FcmRecoveryEpisodeBlock.NONE,
    /** Diagnostic nonce only; never a grant, binding, or privileged request identifier. */
    val requestCorrelationId: String? = null, val requestPending: Boolean = false,
    val resultAtMillis: Long? = null, val resultElapsedMillis: Long? = null,
    val requestOperation: FcmRecoveryOperation? = null,
) {
    init {
        require(sequence > 0 && processId > 0 && processStartedElapsedMillis >= 0)
        require(startedAtMillis > 0 && startedElapsedMillis >= processStartedElapsedMillis)
        require(lastObservedAtMillis > 0 && lastObservedElapsedMillis >= startedElapsedMillis)
        require((requestAtMillis == null) == (requestElapsedMillis == null) && (requestStatus == null) == (reason == null))
        require(requestCorrelationId == null || episodeCorrelationPattern.matches(requestCorrelationId))
        require(requestOperation == null || requestAtMillis != null &&
            (requestOperation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) == (kind == FcmRecoveryEpisodeKind.SERVICE_ABSENT))
        if (requestAtMillis == null) require(requestStatus == null && requestCorrelationId == null && !requestPending)
        else require(if (requestPending) requestStatus == null && requestCorrelationId != null else requestStatus != null)
        require((resultAtMillis == null) == (resultElapsedMillis == null))
        require(resultAtMillis == null || !requestPending && requestStatus != null && resultAtMillis > 0 &&
            resultElapsedMillis!! >= requestElapsedMillis!!)
        require(requestAtMillis == null || requestAtMillis > 0 && requestElapsedMillis!! >= startedElapsedMillis)
        require((endedAtMillis == null) == (endedElapsedMillis == null))
        require((outcome == FcmRecoveryEpisodeOutcome.OBSERVING) == (endedAtMillis == null))
        require(endedAtMillis == null || endedAtMillis > 0 && endedElapsedMillis!! >= lastObservedElapsedMillis &&
            (outcome == FcmRecoveryEpisodeOutcome.COVERAGE_GAP || endedElapsedMillis >= (requestElapsedMillis ?: startedElapsedMillis)))
        require(requestStatus == null || (requestStatus == FcmRecoveryStatus.REQUESTED) == (reason == FcmRecoveryReason.COMMAND_ACCEPTED))
        require(outcome != FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST || requestStatus == FcmRecoveryStatus.REQUESTED)
        require(outcome != FcmRecoveryEpisodeOutcome.CONNECTION_WITHOUT_REQUEST || requestAtMillis == null)
        require(outcome != FcmRecoveryEpisodeOutcome.REQUEST_NOT_SENT || requestStatus == FcmRecoveryStatus.NOT_SENT)
        require(outcome != FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN || requestStatus == FcmRecoveryStatus.UNKNOWN)
    }
}

data class FcmRecoveryEpisodeSnapshot(
    val availability: FcmEpisodeAvailability = FcmEpisodeAvailability.NOT_LOADED,
    val episodes: List<FcmRecoveryEpisode> = emptyList(), val evictedEpisodes: Long = 0, val writeFailures: Long = 0,
    /** Runtime queue loss for this process; not persisted or mistaken for empty coverage. */
    val droppedUpdates: Long = 0,
) {
    init {
        require(episodes.size <= FcmRecoveryEpisodeCodec.MAX_EPISODES && evictedEpisodes >= 0 && writeFailures >= 0 && droppedUpdates >= 0)
        require(episodes.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(episodes.count { it.outcome == FcmRecoveryEpisodeOutcome.OBSERVING } <= 1)
        require(episodes.none { it.outcome == FcmRecoveryEpisodeOutcome.OBSERVING && it != episodes.last() })
        require(episodes.mapNotNull { it.requestCorrelationId }.let { it.size == it.distinct().size })
    }
}

internal interface FcmRecoveryEpisodeStorage {
    fun read(): FcmRecoveryEpisodeSnapshot
    fun write(value: FcmRecoveryEpisodeSnapshot)
}
internal class FcmEpisodeCorruption(cause: Exception) : IOException(cause)

/** Separate diagnostic owner; no authority, timers, privileged reads or callbacks under its lock. */
internal class FcmRecoveryEpisodeJournal(
    private val storage: FcmRecoveryEpisodeStorage, private val processId: Int,
    private val processStartedElapsedMillis: Long,
    private val epochClock: () -> Long, private val elapsedClock: () -> Long,
) {
    private var loaded = false
    private var value = FcmRecoveryEpisodeSnapshot()
    // Latest actual observation is memory-only until a meaningful transition is committed.
    private var latestAt = 0L
    private var latestElapsed = 0L
    // A consume-to-begin OFF race can enqueue the gap first. Only a gap observed by
    // this owner may receive that late begin; reconstructed disk gaps are not candidates.
    private var recentGapSequence: Long? = null
    companion object {
        fun create(context: Context): FcmRecoveryEpisodeJournal = FcmRecoveryEpisodeJournal(
            AtomicFcmRecoveryEpisodeStorage(context.applicationContext.noBackupFilesDir), Process.myPid(),
            Process.getStartElapsedRealtime(), System::currentTimeMillis, SystemClock::elapsedRealtime).also { it.initialize() }
    }
    @Synchronized fun initialize() {
        if (loaded) return
        loaded = true
        value = try { frozen(storage.read().copy(availability = FcmEpisodeAvailability.AVAILABLE)) }
        catch (_: FcmEpisodeCorruption) { value = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.CORRUPT); return }
        catch (_: Exception) { value = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.UNAVAILABLE); return }
        // Every durable unfinished episode has lost continuity, even if PID/start values
        // collide after reboot. No additional boot source or recovered request is trusted.
        val reconstructed = value.episodes.map { episode ->
            val closed = if (episode.outcome == FcmRecoveryEpisodeOutcome.OBSERVING) episode.copy(
                endedAtMillis = maxOf(episode.lastObservedAtMillis, episode.requestAtMillis ?: 0),
                endedElapsedMillis = maxOf(episode.lastObservedElapsedMillis, episode.requestElapsedMillis ?: 0),
                outcome = FcmRecoveryEpisodeOutcome.COVERAGE_GAP, lastBlock = FcmRecoveryEpisodeBlock.OBSERVATION_UNKNOWN) else episode
            if (closed.requestPending) closed.copy(requestPending = false, requestStatus = FcmRecoveryStatus.UNKNOWN,
                reason = FcmRecoveryReason.BRIDGE_UNAVAILABLE) else closed
        }
        if (reconstructed != value.episodes) commit(value.copy(episodes = reconstructed))
    }
    @Synchronized fun snapshot(): FcmRecoveryEpisodeSnapshot = value
    @Synchronized fun observe(event: FcmRecoveryEvent, qualified: Boolean, connected: Boolean) = safely {
        if (event.action !in listOf(FcmRecoveryEventAction.OBSERVATION, FcmRecoveryEventAction.READBACK)) return@safely
        if (event.elapsedMillis < processStartedElapsedMillis) return@safely
        var active = active()
        if (active != null && event.elapsedMillis < maxOf(latestElapsed, active.startedElapsedMillis)) {
            closeGap(active, latestAt.coerceAtLeast(active.lastObservedAtMillis), maxOf(latestElapsed, active.lastObservedElapsedMillis)); return@safely
        }
        if (!qualified || !event.eligible) {
            if (active != null) closeGap(active, event.atMillis, event.elapsedMillis,
                if (!qualified) FcmRecoveryEpisodeBlock.NOT_QUALIFIED else FcmRecoveryEpisodeBlock.INELIGIBLE)
            return@safely
        }
        val known = event.completeness == SourceCompleteness.COMPLETE && event.service != GcmConnectionState.UNKNOWN
        if (!known || (connected && (!qualified || !event.eligible))) {
            if (active != null) closeGap(active, event.atMillis, event.elapsedMillis)
            return@safely
        }
        val acceptedRequest = active?.takeIf { !it.requestPending &&
            it.requestStatus == FcmRecoveryStatus.REQUESTED && it.reason == FcmRecoveryReason.COMMAND_ACCEPTED }
        // Request acceptance is not indefinite connection credit. Bound the existing observation
        // opportunity from request start, not from result return or an intermediate startup read.
        if (acceptedRequest != null && event.elapsedMillis - acceptedRequest.requestElapsedMillis!! > 120000) {
            latestAt = event.atMillis; latestElapsed = event.elapsedMillis
            closeGap(acceptedRequest.copy(timeoutObserved = true), event.atMillis, event.elapsedMillis)
            return@safely
        }
        // A known connected service can precede its TCP socket during accepted cold startup.
        // Preserve only this narrow live episode; NO_MATCH is never a connection confirmation.
        // Keep the latest actual read in RAM, with no per-poll healthy/startup disk writes.
        if (acceptedRequest != null &&
            event.service == GcmConnectionState.REPORTED_CONNECTED && event.socket == SocketObservation.NO_MATCH) {
            if (event.elapsedMillis < maxOf(acceptedRequest.requestElapsedMillis!!,
                    acceptedRequest.resultElapsedMillis ?: 0) ||
                event.atMillis < maxOf(latestAt, acceptedRequest.lastObservedAtMillis,
                    acceptedRequest.requestAtMillis!!, acceptedRequest.resultAtMillis ?: 0)) {
                closeGap(acceptedRequest, event.atMillis, event.elapsedMillis)
                return@safely
            }
            latestAt = event.atMillis; latestElapsed = event.elapsedMillis
            return@safely
        }
        if (connected && qualified && event.service == GcmConnectionState.REPORTED_CONNECTED && event.socket == SocketObservation.OBSERVED) {
            if (active?.requestElapsedMillis?.let { event.elapsedMillis < it } == true) return@safely
            if (active?.requestPending == true) { closeGap(active, event.atMillis, event.elapsedMillis); return@safely }
            if (active != null) commit(replace(active.copy(lastObservedAtMillis = event.atMillis,
                lastObservedElapsedMillis = event.elapsedMillis, endedAtMillis = event.atMillis,
                endedElapsedMillis = event.elapsedMillis, outcome = if (active.requestStatus == FcmRecoveryStatus.REQUESTED)
                    FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST else FcmRecoveryEpisodeOutcome.CONNECTION_WITHOUT_REQUEST)))
            return@safely
        }
        val kind = when (event.service) {
            GcmConnectionState.SERVICE_ABSENT -> FcmRecoveryEpisodeKind.SERVICE_ABSENT
            GcmConnectionState.REPORTED_DISCONNECTED -> FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED
            else -> { if (active != null) closeGap(active, event.atMillis, event.elapsedMillis); return@safely }
        }
        val block = when {
            kind == FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED && active?.requestAtMillis == null -> FcmRecoveryEpisodeBlock.PRESENT_DISCONNECTED_NOT_ADMITTED
            !qualified -> FcmRecoveryEpisodeBlock.NOT_QUALIFIED
            !event.eligible -> FcmRecoveryEpisodeBlock.INELIGIBLE
            else -> FcmRecoveryEpisodeBlock.NONE
        }
        latestAt = event.atMillis; latestElapsed = event.elapsedMillis
        if (active != null && active.kind != kind) {
            // Never rewrite a SEND request's kind when state oscillates into disconnection.
            closeGap(active, event.atMillis, event.elapsedMillis)
            active = null
        }
        if (active == null) {
            val sequence = value.episodes.lastOrNull()?.sequence?.let { Math.addExact(it, 1) } ?: 1
            val all = value.episodes + FcmRecoveryEpisode(sequence, processId, processStartedElapsedMillis,
                event.atMillis, event.elapsedMillis, kind, event.atMillis, event.elapsedMillis, lastBlock = block)
            commit(value.copy(episodes = all.takeLast(FcmRecoveryEpisodeCodec.MAX_EPISODES),
                evictedEpisodes = add(value.evictedEpisodes, (all.size - FcmRecoveryEpisodeCodec.MAX_EPISODES).coerceAtLeast(0).toLong())))
        } else {
            val timeout = active.timeoutObserved || event.elapsedMillis - (active.requestElapsedMillis ?: active.startedElapsedMillis) >= 120000
            if (active.kind != kind || active.lastBlock != block || active.timeoutObserved != timeout) commit(replace(active.copy(
                kind = kind, lastBlock = block, timeoutObserved = timeout,
                lastObservedAtMillis = event.atMillis, lastObservedElapsedMillis = event.elapsedMillis)))
        }
    }
    @Synchronized fun requestStarted(event: FcmRecoveryEvent, correlationId: String) = safely {
        if (event.action !in listOf(FcmRecoveryEventAction.ATTEMPT_CONSUMED, FcmRecoveryEventAction.REQUEST_RESULT) ||
            event.status != null || !episodeCorrelationPattern.matches(correlationId)) return@safely
        val active = active()
        val index = if (active != null) value.episodes.lastIndex else value.episodes.indexOfLast {
            it.sequence == recentGapSequence && it.outcome == FcmRecoveryEpisodeOutcome.COVERAGE_GAP
        }
        if (index < 0) return@safely
        val episode = value.episodes[index]
        if (!matchesOperation(episode, event) || episode.requestAtMillis != null ||
            episode.processId != processId || episode.processStartedElapsedMillis != processStartedElapsedMillis ||
            event.elapsedMillis < maxOf(episode.startedElapsedMillis, episode.lastObservedElapsedMillis, latestElapsed) ||
            value.episodes.any { it.requestCorrelationId == correlationId }) return@safely
        // The request begin and the gap endpoint are independent facts. A begin after
        // OFF cannot extend coverage, move the endpoint or reopen the episode.
        val begun = episode.copy(requestAtMillis = event.atMillis, requestElapsedMillis = event.elapsedMillis,
            requestCorrelationId = correlationId, requestPending = true, requestOperation = operation(event), lastBlock = FcmRecoveryEpisodeBlock.NONE,
            lastObservedAtMillis = latestAt.coerceAtLeast(episode.lastObservedAtMillis),
            lastObservedElapsedMillis = maxOf(latestElapsed, episode.lastObservedElapsedMillis))
        commit(value.copy(episodes = value.episodes.mapIndexed { i, item -> if (i == index) begun else item }))
    }
    @Synchronized fun requestCompleted(event: FcmRecoveryEvent, correlationId: String) = safely {
        if (event.action != FcmRecoveryEventAction.REQUEST_RESULT || event.status == null || event.reason == null ||
            !episodeCorrelationPattern.matches(correlationId)) return@safely
        val index = value.episodes.indexOfFirst { it.requestPending && it.requestCorrelationId == correlationId &&
            it.processId == processId && it.processStartedElapsedMillis == processStartedElapsedMillis }
        if (index < 0) return@safely
        val episode = value.episodes[index]
        if (!matchesOperation(episode, event) || episode.requestOperation != operation(event)) return@safely
        if (event.elapsedMillis < episode.requestElapsedMillis!!) return@safely
        val outcome = if (episode.outcome != FcmRecoveryEpisodeOutcome.OBSERVING) episode.outcome else when (event.status) {
            FcmRecoveryStatus.REQUESTED -> FcmRecoveryEpisodeOutcome.OBSERVING
            FcmRecoveryStatus.NOT_SENT -> FcmRecoveryEpisodeOutcome.REQUEST_NOT_SENT
            FcmRecoveryStatus.UNKNOWN -> FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN
        }
        val newlyEnded = episode.outcome == FcmRecoveryEpisodeOutcome.OBSERVING && outcome != FcmRecoveryEpisodeOutcome.OBSERVING
        val completed = episode.copy(requestPending = false, requestStatus = event.status, reason = event.reason,
            resultAtMillis = event.atMillis, resultElapsedMillis = event.elapsedMillis,
            outcome = outcome, endedAtMillis = if (newlyEnded) event.atMillis else episode.endedAtMillis,
            endedElapsedMillis = if (newlyEnded) maxOf(event.elapsedMillis, episode.lastObservedElapsedMillis) else episode.endedElapsedMillis)
        commit(value.copy(episodes = value.episodes.mapIndexed { i, item -> if (i == index) completed else item }))
    }
    @Synchronized fun request(event: FcmRecoveryEvent) = safely {
        if (event.action != FcmRecoveryEventAction.REQUEST_RESULT || event.status == null || event.reason == null) return@safely
        val active = active() ?: return@safely
        if (!matchesOperation(active, event) || active.requestAtMillis != null ||
            event.elapsedMillis < active.startedElapsedMillis || event.elapsedMillis < latestElapsed) return@safely
        val end = when (event.status) {
            FcmRecoveryStatus.REQUESTED -> FcmRecoveryEpisodeOutcome.OBSERVING
            FcmRecoveryStatus.NOT_SENT -> FcmRecoveryEpisodeOutcome.REQUEST_NOT_SENT
            FcmRecoveryStatus.UNKNOWN -> FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN
        }
        commit(replace(active.copy(requestAtMillis = event.atMillis, requestElapsedMillis = event.elapsedMillis,
            requestStatus = event.status, reason = event.reason, requestOperation = operation(event),
            resultAtMillis = event.atMillis, resultElapsedMillis = event.elapsedMillis,
            lastObservedAtMillis = latestAt.coerceAtLeast(active.lastObservedAtMillis),
            lastObservedElapsedMillis = maxOf(latestElapsed, active.lastObservedElapsedMillis),
            endedAtMillis = event.atMillis.takeIf { end != FcmRecoveryEpisodeOutcome.OBSERVING },
            endedElapsedMillis = event.elapsedMillis.takeIf { end != FcmRecoveryEpisodeOutcome.OBSERVING }, outcome = end)))
    }
    @Synchronized fun coverageGap(atMillis: Long, elapsedMillis: Long) = safely {
        val active = active() ?: return@safely
        closeGap(active, atMillis, elapsedMillis)
    }
    private fun active() = value.episodes.lastOrNull()?.takeIf { it.outcome == FcmRecoveryEpisodeOutcome.OBSERVING }
    private fun operation(event: FcmRecoveryEvent) = event.operation ?: FcmRecoveryOperation.SERVICE_ABSENT_SEND
    private fun matchesOperation(episode: FcmRecoveryEpisode, event: FcmRecoveryEvent) =
        (operation(event) == FcmRecoveryOperation.SERVICE_ABSENT_SEND) == (episode.kind == FcmRecoveryEpisodeKind.SERVICE_ABSENT)
    private fun replace(episode: FcmRecoveryEpisode) = value.copy(episodes = value.episodes.dropLast(1) + episode)
    private fun closeGap(active: FcmRecoveryEpisode, at: Long, elapsed: Long,
        block: FcmRecoveryEpisodeBlock = FcmRecoveryEpisodeBlock.OBSERVATION_UNKNOWN) {
        commit(replace(active.copy(lastObservedAtMillis = latestAt.coerceAtLeast(active.lastObservedAtMillis),
            lastObservedElapsedMillis = maxOf(latestElapsed, active.lastObservedElapsedMillis), endedAtMillis = at.coerceAtLeast(1),
            endedElapsedMillis = maxOf(elapsed, latestElapsed, active.lastObservedElapsedMillis, active.requestElapsedMillis ?: 0),
            outcome = FcmRecoveryEpisodeOutcome.COVERAGE_GAP, lastBlock = block)))
        recentGapSequence = active.sequence
    }
    private fun safely(action: () -> Unit) {
        initialize()
        if (value.availability != FcmEpisodeAvailability.AVAILABLE) return
        try { action() } catch (_: Exception) { value = value.copy(availability = FcmEpisodeAvailability.UNAVAILABLE,
            writeFailures = add(value.writeFailures, 1)) }
    }
    private fun commit(next: FcmRecoveryEpisodeSnapshot) {
        val immutable = frozen(next.copy(availability = FcmEpisodeAvailability.AVAILABLE))
        try { storage.write(immutable); value = immutable }
        catch (_: Exception) { value = value.copy(availability = FcmEpisodeAvailability.UNAVAILABLE,
            writeFailures = add(value.writeFailures, 1)) }
    }
    private fun frozen(snapshot: FcmRecoveryEpisodeSnapshot) = snapshot.copy(episodes = Collections.unmodifiableList(snapshot.episodes.toList()))
    private fun add(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

internal class AtomicFcmRecoveryEpisodeStorage(directory: File) : FcmRecoveryEpisodeStorage {
    private val file = AtomicFile(File(directory, "fcm-recovery-episodes-v1.bin"))
    override fun read(): FcmRecoveryEpisodeSnapshot {
        val input = try { file.openRead() } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()) throw missing
            return FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE)
        }
        val bytes = input.use {
            val out = ByteArrayOutputStream(1024); val chunk = ByteArray(1024)
            while (true) {
                val request = minOf(chunk.size, FcmRecoveryEpisodeCodec.MAX_BYTES + 1 - out.size())
                val count = it.read(chunk, 0, request)
                if (count < 0) break
                check(count > 0 && count <= request)
                out.write(chunk, 0, count)
                if (out.size() > FcmRecoveryEpisodeCodec.MAX_BYTES) throw FcmEpisodeCorruption(IllegalArgumentException("Episode bound"))
            }
            out.toByteArray()
        }
        return try { FcmRecoveryEpisodeCodec.decode(bytes) } catch (error: Exception) { throw FcmEpisodeCorruption(error) }
    }
    override fun write(value: FcmRecoveryEpisodeSnapshot) {
        val bytes = FcmRecoveryEpisodeCodec.encode(value)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}

internal object FcmRecoveryEpisodeCodec {
    const val MAX_EPISODES = 32
    const val MAX_BYTES = 16384
    fun encode(value: FcmRecoveryEpisodeSnapshot): ByteArray {
        require(value.availability == FcmEpisodeAvailability.AVAILABLE)
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x46455031); out.writeInt(3); out.writeLong(value.evictedEpisodes); out.writeLong(value.writeFailures)
            out.writeInt(value.episodes.size)
            value.episodes.forEach { e ->
                out.writeLong(e.sequence); out.writeInt(e.processId); out.writeLong(e.processStartedElapsedMillis)
                out.writeLong(e.startedAtMillis); out.writeLong(e.startedElapsedMillis); out.writeByte(e.kind.ordinal)
                out.writeLong(e.lastObservedAtMillis); out.writeLong(e.lastObservedElapsedMillis)
                out.writeBoolean(e.requestAtMillis != null)
                if (e.requestAtMillis != null) { out.writeLong(e.requestAtMillis); out.writeLong(e.requestElapsedMillis!!)
                    out.writeBoolean(e.requestStatus != null)
                    if (e.requestStatus != null) { out.writeByte(e.requestStatus.ordinal); out.writeByte(e.reason!!.ordinal) } }
                out.writeBoolean(e.endedAtMillis != null)
                if (e.endedAtMillis != null) { out.writeLong(e.endedAtMillis); out.writeLong(e.endedElapsedMillis!!) }
                out.writeByte(e.outcome.ordinal); out.writeBoolean(e.timeoutObserved); out.writeByte(e.lastBlock.ordinal)
                out.writeBoolean(e.requestCorrelationId != null)
                e.requestCorrelationId?.let { out.write(it.toByteArray(Charsets.US_ASCII)) }
                out.writeBoolean(e.requestPending)
                out.writeBoolean(e.resultAtMillis != null)
                if (e.resultAtMillis != null) { out.writeLong(e.resultAtMillis); out.writeLong(e.resultElapsedMillis!!) }
                out.writeByte(e.requestOperation?.ordinal ?: 255)
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): FcmRecoveryEpisodeSnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            fun flag(): Boolean = input.readUnsignedByte().also { require(it in 0..1) } == 1
            fun <T> readEnum(values: List<T>): T = values.getOrNull(input.readUnsignedByte()) ?: error("Episode enum")
            require(input.readInt() == 0x46455031)
            val schema = input.readInt().also { require(it in 1..3) }
            val evicted = input.readLong(); val failed = input.readLong(); val count = input.readInt().also { require(it in 0..MAX_EPISODES) }
            val episodes = List(count) {
                val seq = input.readLong(); val pid = input.readInt(); val processStart = input.readLong()
                val at = input.readLong(); val elapsed = input.readLong(); val kind = readEnum(FcmRecoveryEpisodeKind.entries)
                val lastAt = input.readLong(); val lastElapsed = input.readLong()
                val request = flag(); val requestAt = if (request) input.readLong() else null
                val requestElapsed = if (request) input.readLong() else null
                val resolved = request && (schema == 1 || flag())
                val status = if (resolved) readEnum(FcmRecoveryStatus.entries) else null
                val reason = if (resolved) readEnum(FcmRecoveryReason.entries) else null
                val ended = flag(); val endAt = if (ended) input.readLong() else null; val endElapsed = if (ended) input.readLong() else null
                val outcome = readEnum(FcmRecoveryEpisodeOutcome.entries); val timeout = flag(); val block = readEnum(FcmRecoveryEpisodeBlock.entries)
                val correlation = if (schema >= 2 && flag()) ByteArray(36).also(input::readFully)
                    .let { bytes -> require(bytes.all { it.toInt() in 0..127 }); String(bytes, Charsets.US_ASCII) } else null
                val pending = schema >= 2 && flag()
                val result = schema >= 2 && flag()
                val resultAt = if (result) input.readLong() else null; val resultElapsed = if (result) input.readLong() else null
                val operation = if (schema >= 3) input.readUnsignedByte().let { if (it == 255) null else FcmRecoveryOperation.entries.getOrNull(it) ?: error("Operation enum") }
                    else null
                FcmRecoveryEpisode(seq, pid, processStart, at, elapsed, kind, lastAt, lastElapsed,
                    requestAt, requestElapsed, status, reason, endAt, endElapsed, outcome, timeout, block,
                    correlation, pending, resultAt, resultElapsed, operation)
            }
            require(input.available() == 0)
            FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE, Collections.unmodifiableList(episodes), evicted, failed)
        }
    }
}
