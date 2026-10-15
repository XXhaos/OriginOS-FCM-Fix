package io.github.originosfcmguardian.core.trace

import java.nio.charset.StandardCharsets

/** Narrow IPC payload. A null decode indicates malformed or oversized data. */
object ProcessTraceCodec {
    private const val HEADER = "PT1"
    private const val MAX_EVENTS = 256
    private const val MAX_BYTES = 64 * 1024

    fun encode(events: List<ProcessTraceEvent>): String {
        require(events.size <= MAX_EVENTS) { "Too many trace events" }
        val raw = buildString {
            append(HEADER)
            events.forEach { event ->
                append('\n')
                append(event.timestampEpochMillis)
                append('\t')
                append(event.target.packageName)
                append('\t')
                append(event.target.uid)
                append('\t')
                append(event.kind.name)
                append('\t')
                append(event.pid ?: "-")
            }
        }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "Trace payload too large" }
        return raw
    }

    fun decode(raw: String): List<ProcessTraceEvent>? {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull() != HEADER || lines.size - 1 > MAX_EVENTS) return null
        return runCatching {
            lines.drop(1).map { line ->
                val fields = line.split('\t')
                require(fields.size == 5)
                val timestamp = fields[0].toLongOrNull() ?: error("Invalid timestamp")
                val uid = fields[2].toIntOrNull() ?: error("Invalid UID")
                val kind = ProcessTraceKind.entries.singleOrNull { it.name == fields[3] }
                    ?: error("Invalid kind")
                val pid = if (fields[4] == "-") null else fields[4].toIntOrNull()
                    ?: error("Invalid PID")
                require(fields[4] == "-" || pid != null)
                ProcessTraceEvent(timestamp, ProcessTraceTarget(fields[1], uid), kind, pid)
            }
        }.getOrNull()
    }
}
