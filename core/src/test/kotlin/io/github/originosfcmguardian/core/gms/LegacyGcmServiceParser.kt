package io.github.originosfcmguardian.core.gms

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Frozen 2026-10-29 parser for differential representation-optimization tests only. */
internal object LegacyGcmServiceParser {
    const val COMPONENT = "com.google.android.gms/.gcm.GcmService"
    const val MAX_BYTES = 64 * 1024
    const val MAX_LINES = 2048
    const val MAX_LINE_BYTES = 8192
    private val header = Regex("[ \\t]*SERVICE com\\.google\\.android\\.gms/\\.gcm\\.GcmService [a-fA-F0-9]+ pid=[1-9][0-9]* user=0[ \\t]*")
    private val connected = Regex("[ \\t]+Is client connected: (true|false)[ \\t]*")
    // Pinned GMS263635035: left is the same current-session clock as Diagnostics Time connected.
    // Right is the previous duration. Non-ASCII/localized or unfamiliar output stays unknown.
    private val connectionTime = Regex("[ \\t]+connectTime=([0-9]{1,16}:[0-9]{2}(?::[0-9]{2})?)/([0-9]{1,16}:[0-9]{2}(?::[0-9]{2})?)[ \\t]*")
    private val failure = Regex("(?i)(permission denial|permission denied|securityexception|remoteexception|error dumping|timed out|timeout while|exception while)")
    private val failureBanner = Regex("(?i)^[ \\t]*(?:\\*{3}[ \\t]*)?(?:(?:SERVICE[ \\t]+)?DUMP[ \\t]+TIMEOUT\\b|ERROR\\b|FAILURE[ \\t]+DUMPING\\b)")
    fun unknown(status: SourceCompleteness) = GcmServiceSource(GcmConnectionState.UNKNOWN, status)

    fun parse(bytes: ByteArray): GcmServiceSource {
        if (bytes.size > MAX_BYTES) return unknown(SourceCompleteness.TRUNCATED)
        if (bytes.isNotEmpty() && bytes.last() != 10.toByte()) return unknown(SourceCompleteness.TRUNCATED)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return unknown(SourceCompleteness.MALFORMED) }
        // Only the complete, exact dumpsys response is negative evidence. Extra lines,
        // failed child exit and stderr are rejected by the parser/reader boundaries.
        val absence = "No services match: $COMPONENT"
        if (text == "$absence\n" || text == "$absence\r\n" ||
            text == "$absence\nUse -h for help.\n" || text == "$absence\r\nUse -h for help.\r\n")
            return GcmServiceSource(GcmConnectionState.SERVICE_ABSENT, SourceCompleteness.COMPLETE)
        var headers = 0
        var fields = 0
        var state = GcmConnectionState.UNKNOWN
        var durationFields = 0
        var durationMillis: Long? = null
        var lines = 0
        for (line in text.lineSequence()) {
            if (++lines > MAX_LINES || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return unknown(SourceCompleteness.TRUNCATED)
            if (failure.containsMatchIn(line) || failureBanner.containsMatchIn(line)) return unknown(if (line.contains("permission", true)) SourceCompleteness.DENIED else SourceCompleteness.UNAVAILABLE)
            val trimmed = line.trim()
            if (trimmed.startsWith("SERVICE ")) {
                if (!header.matches(line) || ++headers != 1) return unknown(SourceCompleteness.MALFORMED)
            }
            if (trimmed.startsWith("Is client connected:")) {
                val match = connected.matchEntire(line)
                if (headers != 1 || match == null || ++fields != 1) return unknown(SourceCompleteness.MALFORMED)
                state = if (match.groupValues[1] == "true") GcmConnectionState.REPORTED_CONNECTED else GcmConnectionState.REPORTED_DISCONNECTED
            }
            if (trimmed.startsWith("connectTime=")) {
                durationFields++
                val match = connectionTime.matchEntire(line)
                durationMillis = if (headers == 1 && match != null && parseElapsedDuration(match.groupValues[2]) != null)
                    parseElapsedDuration(match.groupValues[1]) else null
            }
        }
        return if (headers == 1 && fields == 1) GcmServiceSource(state, SourceCompleteness.COMPLETE,
            connectedDurationMillis = durationMillis.takeIf { durationFields == 1 && state == GcmConnectionState.REPORTED_CONNECTED })
        else unknown(SourceCompleteness.MALFORMED)
    }

    private fun parseElapsedDuration(text: String): Long? = runCatching {
        val parts = text.split(':')
        require(parts.size in 2..3)
        val seconds = parts.last().toLong(); require(seconds in 0..59)
        val minutes = parts[parts.size - 2].toLong()
        require(parts[parts.size - 2].length == 2 && minutes in 0..59)
        val hours = if (parts.size == 3) parts[0].toLong().also { require(it > 0) } else 0L
        Math.multiplyExact(Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600),
            Math.multiplyExact(minutes, 60)), seconds), 1000)
    }.getOrNull()
}
