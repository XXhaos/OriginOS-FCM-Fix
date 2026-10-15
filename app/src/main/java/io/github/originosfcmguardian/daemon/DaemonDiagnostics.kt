package io.github.originosfcmguardian.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class DaemonDiagnosticsAvailability { NOT_COLLECTED, INITIALIZING, AVAILABLE, UNAVAILABLE, CORRUPT, LOCKED }
enum class DaemonDiagnosticsState { BEGIN, READY, OFF, WAITING, CLOSING, NORMAL_END, UNKNOWN_END }

data class DaemonDiagnosticsSession(
    val sessionId: String, val bootId: String, val processId: Int, val processStartTicks: Long?,
    val versionCode: Long, val startedAtMillis: Long, val startedElapsedMillis: Long,
    val lastCheckpointAtMillis: Long, val lastCheckpointElapsedMillis: Long,
    val state: DaemonDiagnosticsState, val endedAtMillis: Long? = null, val endedElapsedMillis: Long? = null,
    val stoppedCheckedAtMillis: Long? = null, val fcmCheckedAtMillis: Long? = null, val maxPollGapMillis: Long = 0,
)
data class DaemonDiagnosticsEvent(val sessionId: String, val atMillis: Long, val elapsedMillis: Long,
    val state: DaemonDiagnosticsState)
data class DaemonDiagnosticsSample(
    val sessionId: String, val atMillis: Long, val elapsedMillis: Long, val cpuTimeMillis: Long? = null,
    val rssKiB: Long? = null, val heapUsedBytes: Long? = null, val heapMaxBytes: Long? = null,
    val nativeAllocatedBytes: Long? = null, val threads: Long? = null, val batteryPercent: Int? = null,
    val chargingStatus: String? = null, val interactive: Boolean? = null, val deviceIdle: Boolean? = null,
)
data class DaemonDiagnosticsSnapshot(
    val availability: DaemonDiagnosticsAvailability = DaemonDiagnosticsAvailability.NOT_COLLECTED,
    val generatedAtMillis: Long = 0, val generatedElapsedMillis: Long = 0,
    val currentSessionId: String? = null, val generation: Long = 0,
    val sessions: List<DaemonDiagnosticsSession> = emptyList(), val events: List<DaemonDiagnosticsEvent> = emptyList(),
    val samples: List<DaemonDiagnosticsSample> = emptyList(), val evictedSessions: Long = 0,
    val evictedSamples: Long = 0, val evictedEvents: Long = 0, val skippedSamples: Long = 0,
    val failedOperations: Long = 0, val unknownIntervals: Long = 0,
)

/** Independent diagnostic format. Never accepted as authority, reconciliation or CONTROL bytes. */
internal object DaemonDiagnosticsCodec {
    const val MAX_SAMPLES = 2016
    const val MAX_SESSIONS = 32
    const val MAX_EVENTS = 256
    const val MAX_BYTES = 512 * 1024
    fun encode(value: DaemonDiagnosticsSnapshot): ByteArray {
        validate(value)
        val body = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x47444431); out.writeInt(1); out.writeInt(value.availability.ordinal)
            out.writeLong(value.generatedAtMillis); out.writeLong(value.generatedElapsedMillis)
            out.text(value.currentSessionId); out.writeLong(value.generation)
            listOf(value.evictedSessions, value.evictedSamples, value.evictedEvents, value.skippedSamples,
                value.failedOperations, value.unknownIntervals).forEach(out::writeLong)
            out.writeInt(value.sessions.size)
            value.sessions.forEach { s ->
                out.writeUTF(s.sessionId); out.writeUTF(s.bootId); out.writeInt(s.processId); out.number(s.processStartTicks)
                out.writeLong(s.versionCode); out.writeLong(s.startedAtMillis); out.writeLong(s.startedElapsedMillis)
                out.writeLong(s.lastCheckpointAtMillis); out.writeLong(s.lastCheckpointElapsedMillis); out.writeInt(s.state.ordinal)
                out.number(s.endedAtMillis); out.number(s.endedElapsedMillis); out.number(s.stoppedCheckedAtMillis)
                out.number(s.fcmCheckedAtMillis); out.writeLong(s.maxPollGapMillis)
            }
            out.writeInt(value.events.size)
            value.events.forEach { out.writeUTF(it.sessionId); out.writeLong(it.atMillis); out.writeLong(it.elapsedMillis); out.writeInt(it.state.ordinal) }
            out.writeInt(value.samples.size)
            value.samples.forEach { s ->
                out.writeUTF(s.sessionId); out.writeLong(s.atMillis); out.writeLong(s.elapsedMillis)
                listOf(s.cpuTimeMillis, s.rssKiB, s.heapUsedBytes, s.heapMaxBytes, s.nativeAllocatedBytes, s.threads).forEach { out.number(it) }
                out.writeInt(s.batteryPercent ?: -1); out.text(s.chargingStatus); out.flag(s.interactive); out.flag(s.deviceIdle)
            }
        } }.toByteArray()
        require(body.size + 32 <= MAX_BYTES)
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    fun decode(bytes: ByteArray): DaemonDiagnosticsSnapshot {
        require(bytes.size in 40..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == 0x47444431 && input.readInt() == 1)
            val availability = DaemonDiagnosticsAvailability.entries[input.readInt().also { require(it in DaemonDiagnosticsAvailability.entries.indices) }]
            val wall = input.readLong(); val elapsed = input.readLong(); val current = input.text(); val generation = input.readLong()
            val counters = LongArray(6) { input.readLong() }
            val sessions = List(input.count(MAX_SESSIONS)) {
                DaemonDiagnosticsSession(input.boundedText(), input.boundedText(), input.readInt(), input.number(),
                    input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.state(),
                    input.number(), input.number(), input.number(), input.number(), input.readLong())
            }
            val events = List(input.count(MAX_EVENTS)) { DaemonDiagnosticsEvent(input.boundedText(), input.readLong(), input.readLong(), input.state()) }
            val samples = List(input.count(MAX_SAMPLES)) {
                DaemonDiagnosticsSample(input.boundedText(), input.readLong(), input.readLong(), input.number(), input.number(),
                    input.number(), input.number(), input.number(), input.number(), input.readInt().let { if (it == -1) null else it },
                    input.text(), input.flag(), input.flag())
            }
            require(input.available() == 0)
            DaemonDiagnosticsSnapshot(availability, wall, elapsed, current, generation, sessions, events, samples,
                counters[0], counters[1], counters[2], counters[3], counters[4], counters[5]).also(::validate)
        }
    }
    private fun validate(value: DaemonDiagnosticsSnapshot) {
        require(value.sessions.size <= MAX_SESSIONS && value.events.size <= MAX_EVENTS && value.samples.size <= MAX_SAMPLES)
        require(listOf(value.generatedAtMillis, value.generatedElapsedMillis, value.generation, value.evictedSessions,
            value.evictedSamples, value.evictedEvents, value.skippedSamples, value.failedOperations, value.unknownIntervals).all { it >= 0 })
        fun text(s: String) { require(s.isNotBlank() && s.length <= 128 && '\u0000' !in s) }
        value.currentSessionId?.let(::text)
        require(value.sessions.map { it.sessionId }.distinct().size == value.sessions.size)
        value.sessions.forEach { s ->
            text(s.sessionId); text(s.bootId); require(s.processId > 0 && (s.processStartTicks == null || s.processStartTicks >= 0))
            require(s.versionCode > 0 && s.startedAtMillis > 0 && s.startedElapsedMillis >= 0)
            require(s.lastCheckpointAtMillis > 0 && s.lastCheckpointElapsedMillis >= s.startedElapsedMillis && s.maxPollGapMillis >= 0)
            require((s.endedAtMillis == null) == (s.endedElapsedMillis == null))
            require(s.endedElapsedMillis == null || s.endedElapsedMillis >= s.startedElapsedMillis)
            require(s.state != DaemonDiagnosticsState.UNKNOWN_END || s.endedAtMillis == null)
            require(s.state != DaemonDiagnosticsState.NORMAL_END || s.endedAtMillis != null)
            require(listOfNotNull(s.endedAtMillis, s.stoppedCheckedAtMillis, s.fcmCheckedAtMillis).all { it > 0 })
        }
        value.events.forEach { text(it.sessionId); require(it.atMillis > 0 && it.elapsedMillis >= 0) }
        value.samples.forEach { s ->
            text(s.sessionId); require(s.atMillis > 0 && s.elapsedMillis >= 0)
            require(listOfNotNull(s.cpuTimeMillis, s.rssKiB, s.heapUsedBytes, s.heapMaxBytes, s.nativeAllocatedBytes, s.threads).all { it >= 0 })
            require(s.batteryPercent == null || s.batteryPercent in 0..100)
            s.chargingStatus?.let { require(it in setOf("CHARGING", "FULL", "DISCHARGING", "NOT_CHARGING", "UNKNOWN")) }
        }
    }
    private fun DataOutputStream.number(value: Long?) { writeBoolean(value != null); if (value != null) writeLong(value) }
    private fun DataInputStream.number(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.text(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.text(): String? = if (readBoolean()) boundedText() else null
    private fun DataInputStream.boundedText() = readUTF().also { require(it.length <= 128) }
    private fun DataOutputStream.flag(value: Boolean?) = writeByte(if (value == null) 0 else if (value) 2 else 1)
    private fun DataInputStream.flag(): Boolean? = when (readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> error("Invalid flag") }
    private fun DataInputStream.count(maximum: Int) = readInt().also { require(it in 0..maximum) }
    private fun DataInputStream.state() = DaemonDiagnosticsState.entries[readInt().also { require(it in DaemonDiagnosticsState.entries.indices) }]
}
