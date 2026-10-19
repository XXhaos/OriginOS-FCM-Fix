package io.github.originosfcmguardian.autoprotection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

enum class ProtectionResourceAvailability { AVAILABLE, EMPTY, CORRUPT, UNAVAILABLE }

private val resourceProcessInstancePattern = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
private val resourceStatePattern = Regex("[A-Z][A-Z0-9_]{0,31}")

/** Diagnostic process identity is never a consent/session identity. Null means unavailable. */
data class ProtectionResourceSample(
    val processInstance: String, val processId: Int,
    val processStartedAtMillis: Long, val processStartedElapsedMillis: Long,
    val atMillis: Long, val elapsedMillis: Long, val versionCode: Long? = null,
    val rssKiB: Long? = null, val threads: Long? = null,
    val heapUsedBytes: Long? = null, val heapMaxBytes: Long? = null,
    val cpuTimeMillis: Long? = null, val nativeAllocatedBytes: Long? = null,
    val systemAvailableBytes: Long? = null, val systemThresholdBytes: Long? = null,
    val systemLowMemory: Boolean? = null, val interactive: Boolean? = null,
    val selectedCount: Int? = null, val automaticBusy: Boolean? = null,
    val automaticLastCheckedAtMillis: Long? = null, val pollIterations: Long = 0,
    val readerActive: Int = 0, val readerPoolSize: Int = 0,
    val readerTimeouts: Long = 0, val readerRejections: Long = 0,
    val fcmObservationAtMillis: Long? = null, val fcmObservationElapsedMillis: Long? = null,
    val fcmService: String? = null, val fcmCompleteness: String? = null, val fcmSocket: String? = null,
    val fcmConfigured: Boolean? = null, val recoveryReady: Boolean? = null,
    val maintenance: String? = null, val heartbeatConfigured: Boolean? = null,
    val heartbeatActive: Boolean? = null, val heartbeatPaused: Boolean? = null,
    val heartbeatStopPending: Boolean? = null, val heartbeatResumeBlocked: Boolean? = null,
    val heartbeatRequiresConfirmation: Boolean? = null, val heartbeatLastStatus: String? = null,
    val recoveryAction: String? = null, val recoveryActionAtMillis: Long? = null,
) {
    init {
        require(resourceProcessInstancePattern.matches(processInstance))
        require(processId > 0 && processStartedAtMillis > 0 && processStartedElapsedMillis >= 0)
        require(atMillis > 0 && elapsedMillis >= processStartedElapsedMillis)
        require(longs().all { it == null || it >= 0 })
        require(pollIterations >= 0 && readerTimeouts >= 0 && readerRejections >= 0)
        require(readerActive in 0..8 && readerPoolSize in 0..8 && (selectedCount == null || selectedCount in 0..1024))
        require(strings().all { it == null || resourceStatePattern.matches(it) })
        require((fcmObservationAtMillis == null) == (fcmObservationElapsedMillis == null))
        require((recoveryAction == null) == (recoveryActionAtMillis == null))
    }
    internal fun longs() = listOf(versionCode, rssKiB, threads, heapUsedBytes, heapMaxBytes, cpuTimeMillis,
        nativeAllocatedBytes, systemAvailableBytes, systemThresholdBytes, automaticLastCheckedAtMillis,
        fcmObservationAtMillis, fcmObservationElapsedMillis, recoveryActionAtMillis)
    internal fun flags() = listOf(systemLowMemory, interactive, automaticBusy, fcmConfigured, recoveryReady,
        heartbeatConfigured, heartbeatActive, heartbeatPaused, heartbeatStopPending, heartbeatResumeBlocked,
        heartbeatRequiresConfirmation)
    internal fun strings() = listOf(fcmService, fcmCompleteness, fcmSocket, maintenance, heartbeatLastStatus, recoveryAction)
}

/** RSS/PSS are Android's LAST sampled values, not precise measurements at process death. */
data class ProtectionResourceExit(val processId: Int, val atMillis: Long, val reason: Int, val status: Int,
    val importance: Int, val rssKiB: Long? = null, val pssKiB: Long? = null) {
    init {
        require(processId > 0 && atMillis > 0 && reason in 0..100 && importance >= 0)
        require(rssKiB == null || rssKiB > 0); require(pssKiB == null || pssKiB > 0)
    }
}

/** Bounded watermark equality dedup, including exits evicted from the public 32-entry ring. */
data class ProtectionResourceExitKey(val processId: Int, val reason: Int, val status: Int) {
    init { require(processId > 0 && reason in 0..100) }
}
private fun ProtectionResourceExit.key() = ProtectionResourceExitKey(processId, reason, status)

data class ProtectionResourceSnapshot(
    val availability: ProtectionResourceAvailability = ProtectionResourceAvailability.EMPTY,
    val samples: List<ProtectionResourceSample> = emptyList(), val exits: List<ProtectionResourceExit> = emptyList(),
    val evictedSamples: Long = 0, val evictedExits: Long = 0, val skipped: Long = 0, val failed: Long = 0,
    val exitHistoryAvailable: Boolean = false,
    val exitHistoryThroughMillis: Long = 0,
    val exitHistoryBoundary: List<ProtectionResourceExitKey> = emptyList(),
) {
    init {
        require(samples.size <= ProtectionResourceCodec.MAX_SAMPLES && exits.size <= ProtectionResourceCodec.MAX_EXITS)
        require(listOf(evictedSamples, evictedExits, skipped, failed).all { it >= 0 })
        require(exitHistoryThroughMillis >= 0)
        require(exitHistoryBoundary.size <= 64 && exitHistoryBoundary.distinct().size == exitHistoryBoundary.size)
    }
}

internal fun saturatedDiagnosticAdd(value: Long, extra: Long): Long {
    require(value >= 0 && extra >= 0)
    return if (Long.MAX_VALUE - value < extra) Long.MAX_VALUE else value + extra
}
internal fun AtomicLong.incrementDiagnostic() = updateAndGet { saturatedDiagnosticAdd(it, 1) }

internal fun appendProtectionResource(prior: ProtectionResourceSnapshot, sample: ProtectionResourceSample): ProtectionResourceSnapshot {
    val all = prior.samples + sample
    return prior.copy(availability = ProtectionResourceAvailability.AVAILABLE,
        samples = all.takeLast(ProtectionResourceCodec.MAX_SAMPLES),
        evictedSamples = saturatedDiagnosticAdd(prior.evictedSamples, (all.size - ProtectionResourceCodec.MAX_SAMPLES).coerceAtLeast(0).toLong()))
}

/** Always validates current bytes before collecting or replacing any diagnostic evidence. */
internal fun updateProtectionResourceStorage(read: () -> ProtectionResourceSnapshot,
    write: (ProtectionResourceSnapshot) -> Unit, capture: () -> ProtectionResourceSample,
    skipped: Long, failed: Long): ProtectionResourceSnapshot {
    val prior = read()
    val next = appendProtectionResource(prior, capture()).copy(skipped = skipped, failed = failed)
    write(next)
    return next
}

internal fun mergeProtectionResourceExits(prior: ProtectionResourceSnapshot, exits: List<ProtectionResourceExit>?): ProtectionResourceSnapshot {
    if (exits == null) return prior.copy(exitHistoryAvailable = false)
    require(exits.size <= 64)
    val all = (prior.exits + exits.filter { it.atMillis > prior.exitHistoryThroughMillis ||
        (it.atMillis == prior.exitHistoryThroughMillis && it.key() !in prior.exitHistoryBoundary) })
        .distinctBy { listOf(it.processId.toLong(), it.atMillis, it.reason.toLong(), it.status.toLong()) }
        .sortedWith(compareBy<ProtectionResourceExit> { it.atMillis }.thenBy { it.processId })
    val watermark = maxOf(prior.exitHistoryThroughMillis, exits.maxOfOrNull { it.atMillis } ?: 0)
    val boundary = ((if (watermark == prior.exitHistoryThroughMillis) prior.exitHistoryBoundary else emptyList()) +
        exits.filter { it.atMillis == watermark }.map { it.key() }).distinct().takeLast(64)
    return prior.copy(availability = if (all.isEmpty() && prior.samples.isEmpty()) ProtectionResourceAvailability.EMPTY else ProtectionResourceAvailability.AVAILABLE,
        exits = all.takeLast(ProtectionResourceCodec.MAX_EXITS), exitHistoryAvailable = true,
        exitHistoryThroughMillis = watermark, exitHistoryBoundary = boundary,
        evictedExits = saturatedDiagnosticAdd(prior.evictedExits, (all.size - ProtectionResourceCodec.MAX_EXITS).coerceAtLeast(0).toLong()))
}

/** Startup enrichment must keep new exit/coverage evidence, without rewriting unchanged history. */
internal fun persistChangedProtectionResources(prior: ProtectionResourceSnapshot, next: ProtectionResourceSnapshot,
    write: (ProtectionResourceSnapshot) -> Unit) {
    if (next != prior) write(next)
}

/** All admission is memory-only. Denied attempts never advance the monotonic cadence. */
internal class ProtectionResourceAdmission(private val intervalMillis: Long = 300_000) {
    private var last: Long? = null
    private var pending = false
    init { require(intervalMillis > 0) }
    @Synchronized fun isDue(nowElapsed: Long): Boolean = nowElapsed >= 0 &&
        (last == null || (nowElapsed >= last!! && nowElapsed - last!! >= intervalMillis))
    @Synchronized fun offer(nowElapsed: Long, backlog: Boolean): Boolean {
        if (nowElapsed < 0 || backlog || pending) return false
        val previous = last
        if (previous != null && (nowElapsed < previous || nowElapsed - previous < intervalMillis)) return false
        last = nowElapsed; pending = true; return true
    }
    @Synchronized fun finished() { pending = false }
}

internal object ProtectionResourceCodec {
    const val MAX_SAMPLES = 1024
    const val MAX_EXITS = 32
    const val MAX_BYTES = 524_288
    fun encode(value: ProtectionResourceSnapshot): ByteArray {
        require(value.availability in setOf(ProtectionResourceAvailability.EMPTY, ProtectionResourceAvailability.AVAILABLE))
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x47524A31); out.writeInt(1)
            listOf(value.evictedSamples, value.evictedExits, value.skipped, value.failed).forEach(out::writeLong)
            out.writeBoolean(value.exitHistoryAvailable); out.writeLong(value.exitHistoryThroughMillis)
            out.writeInt(value.exitHistoryBoundary.size)
            value.exitHistoryBoundary.forEach { out.writeInt(it.processId); out.writeInt(it.reason); out.writeInt(it.status) }
            out.writeInt(value.samples.size)
            value.samples.forEach { s ->
                out.writeUTF(s.processInstance); out.writeInt(s.processId)
                listOf(s.processStartedAtMillis, s.processStartedElapsedMillis, s.atMillis, s.elapsedMillis,
                    s.pollIterations, s.readerTimeouts, s.readerRejections).forEach(out::writeLong)
                out.writeInt(s.readerActive); out.writeInt(s.readerPoolSize); out.writeInt(s.selectedCount ?: -1)
                s.longs().forEach { out.writeBoolean(it != null); if (it != null) out.writeLong(it) }
                s.flags().forEach { out.writeByte(if (it == null) 0 else if (it) 2 else 1) }
                s.strings().forEach { out.writeBoolean(it != null); if (it != null) out.writeUTF(it) }
            }
            out.writeInt(value.exits.size)
            value.exits.forEach { e ->
                out.writeInt(e.processId); out.writeLong(e.atMillis); out.writeInt(e.reason); out.writeInt(e.status); out.writeInt(e.importance)
                out.writeLong(e.rssKiB ?: -1); out.writeLong(e.pssKiB ?: -1)
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): ProtectionResourceSnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x47524A31 && input.readInt() == 1)
            val counters = List(4) { input.readLong() }; val history = input.readBoolean(); val watermark = input.readLong()
            val boundaryCount = input.readInt().also { require(it in 0..64) }
            val boundary = List(boundaryCount) { ProtectionResourceExitKey(input.readInt(), input.readInt(), input.readInt()) }
            val count = input.readInt().also { require(it in 0..MAX_SAMPLES) }
            val samples = List(count) {
                val instance = input.readUTF(); val pid = input.readInt(); val base = List(7) { input.readLong() }
                val active = input.readInt(); val pool = input.readInt(); val selected = input.readInt().also { require(it >= -1) }
                val longs = List(13) { if (input.readBoolean()) input.readLong() else null }
                val flags = List(11) { when (input.readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> error("Invalid flag") } }
                val strings = List(6) { if (input.readBoolean()) input.readUTF() else null }
                ProtectionResourceSample(instance, pid, base[0], base[1], base[2], base[3],
                    longs[0], longs[1], longs[2], longs[3], longs[4], longs[5], longs[6], longs[7], longs[8],
                    flags[0], flags[1], selected.takeIf { it >= 0 }, flags[2], longs[9], base[4], active, pool, base[5], base[6],
                    longs[10], longs[11], strings[0], strings[1], strings[2], flags[3], flags[4], strings[3],
                    flags[5], flags[6], flags[7], flags[8], flags[9], flags[10], strings[4], strings[5], longs[12])
            }
            val exitCount = input.readInt().also { require(it in 0..MAX_EXITS) }
            val exits = List(exitCount) {
                ProtectionResourceExit(input.readInt(), input.readLong(), input.readInt(), input.readInt(), input.readInt(),
                    input.readLong().let { require(it == -1L || it > 0); it.takeIf { it > 0 } },
                    input.readLong().let { require(it == -1L || it > 0); it.takeIf { it > 0 } })
            }
            require(input.available() == 0)
            ProtectionResourceSnapshot(if (samples.isEmpty() && exits.isEmpty()) ProtectionResourceAvailability.EMPTY else ProtectionResourceAvailability.AVAILABLE,
                samples, exits, counters[0], counters[1], counters[2], counters[3], history, watermark, boundary)
        }
    }
}
