package io.github.originosfcmguardian.core.trace

import java.nio.charset.StandardCharsets

/** Parses only Android EventLog's direct-UID process-start/UID-state events. */
object ProcessTraceParser {
    private const val MAX_RAW_BYTES = 128 * 1024
    // `logcat` may add a small number of buffer-banner lines around the requested 256 records.
    private const val MAX_RAW_LINES = 260
    private val prefix = Regex(
        "^\\s*(\\d{10})\\.(\\d{3,9})\\s+\\d+\\s+\\d+\\s+I\\s+" +
            "(am_proc_start|am_uid_active|am_uid_idle|am_uid_running|am_uid_stopped):\\s*(.*?)\\s*$",
    )
    private val positiveInt = Regex("[1-9][0-9]*")
    private val nonnegativeInt = Regex("0|[1-9][0-9]*")
    private val processSuffix = Regex("[A-Za-z0-9_.]+")

    fun parse(raw: String, target: ProcessTraceTarget): List<ProcessTraceEvent> {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_RAW_BYTES) return emptyList()
        val lines = raw.lineSequence().toList()
        if (lines.size > MAX_RAW_LINES) return emptyList()
        val events = ArrayList<ProcessTraceEvent>()
        lines.forEach { line ->
            val match = prefix.matchEntire(line) ?: return@forEach
            val seconds = match.groupValues[1].toLongOrNull() ?: return@forEach
            val fraction = match.groupValues[2].take(3).toLongOrNull() ?: return@forEach
            val timestamp = seconds.takeIf { it <= (Long.MAX_VALUE - fraction) / 1_000 }
                ?.let { it * 1_000 + fraction } ?: return@forEach
            val tag = match.groupValues[3]
            val data = match.groupValues[4]
            when (tag) {
                "am_proc_start" -> {
                    if (!data.startsWith('[') || !data.endsWith(']')) return@forEach
                    val fields = data.substring(1, data.length - 1).split(',').map(String::trim)
                    if (fields.size != 6 || !nonnegativeInt.matches(fields[0]) || fields[0] != "0") {
                        return@forEach
                    }
                    val pid = fields[1].takeIf(positiveInt::matches)?.toIntOrNull() ?: return@forEach
                    val uid = fields[2].takeIf(positiveInt::matches)?.toIntOrNull() ?: return@forEach
                    if (uid != target.uid) return@forEach
                    val name = fields[3]
                    if (name != target.packageName && !(
                            name.startsWith("${target.packageName}:") &&
                                name.substring(target.packageName.length + 1).let {
                                    it.length in 1..255 && processSuffix.matches(it)
                                }
                            )) return@forEach
                    events += ProcessTraceEvent(timestamp, target, ProcessTraceKind.PROCESS_START, pid)
                }
                "am_uid_active", "am_uid_idle", "am_uid_running", "am_uid_stopped" -> {
                    val uidText = if (data.startsWith('[') && data.endsWith(']')) {
                        data.substring(1, data.length - 1)
                    } else data
                    val uid = uidText.takeIf(positiveInt::matches)?.toIntOrNull()
                    if (uid != target.uid) return@forEach
                    val kind = when (tag) {
                        "am_uid_active" -> ProcessTraceKind.UID_ACTIVE
                        "am_uid_idle" -> ProcessTraceKind.UID_IDLE
                        "am_uid_running" -> ProcessTraceKind.UID_RUNNING
                        else -> ProcessTraceKind.UID_STOPPED
                    }
                    events += ProcessTraceEvent(timestamp, target, kind)
                }
            }
        }
        return events
    }
}
