package io.github.originosfcmguardian.core.gms

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Raw dump bytes stay inside the reader/parser; only the enum result leaves this boundary. */
object GcmServiceParser {
    const val COMPONENT = "com.google.android.gms/.gcm.GcmService"
    const val MAX_BYTES = 64 * 1024
    const val MAX_LINES = 2048
    const val MAX_LINE_BYTES = 8192
    private val header = Regex("[ \\t]*SERVICE com\\.google\\.android\\.gms/\\.gcm\\.GcmService [a-fA-F0-9]+ pid=[1-9][0-9]* user=0[ \\t]*")
    private val connected = Regex("[ \\t]+Is client connected: (true|false)[ \\t]*")
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
        }
        return if (headers == 1 && fields == 1) GcmServiceSource(state, SourceCompleteness.COMPLETE)
        else unknown(SourceCompleteness.MALFORMED)
    }
}

/** Poll pipe availability on the existing probe worker: no blocking reader or extra worker accumulates.
 * A child process can be destroyed; an unrelated stuck system Binder call cannot. */
object GcmServiceProcessReader {
    const val BUDGET_MILLIS = 3_000L
    fun read(process: Process, interrupted: () -> Boolean, budgetMillis: Long = BUDGET_MILLIS): GcmServiceSource {
        require(budgetMillis in 1..BUDGET_MILLIS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        val stdout = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        var stderr = false
        fun drain(input: InputStream, output: Boolean): SourceCompleteness? {
            while (true) {
                if (Thread.currentThread().isInterrupted || interrupted()) return SourceCompleteness.CANCELLED
                if (System.nanoTime() >= deadline) return SourceCompleteness.TIMED_OUT
                val available = input.available()
                if (available <= 0) return null
                val count = input.read(chunk, 0, minOf(chunk.size, available, GcmServiceParser.MAX_BYTES - total + 1))
                if (count <= 0) return null
                total += count
                if (total > GcmServiceParser.MAX_BYTES) return SourceCompleteness.TRUNCATED
                if (output) stdout.write(chunk, 0, count) else stderr = true
            }
        }
        return try {
            while (true) {
                if (Thread.currentThread().isInterrupted || interrupted()) return GcmServiceParser.unknown(SourceCompleteness.CANCELLED)
                if (System.nanoTime() >= deadline) return GcmServiceParser.unknown(SourceCompleteness.TIMED_OUT)
                drain(process.inputStream, true)?.let { return GcmServiceParser.unknown(it) }
                drain(process.errorStream, false)?.let { return GcmServiceParser.unknown(it) }
                if (!process.isAlive) {
                    // Termination makes the last pipe bytes available. Drain both before accepting.
                    drain(process.inputStream, true)?.let { return GcmServiceParser.unknown(it) }
                    drain(process.errorStream, false)?.let { return GcmServiceParser.unknown(it) }
                    return if (process.exitValue() != 0 || stderr) GcmServiceParser.unknown(SourceCompleteness.UNAVAILABLE)
                    else GcmServiceParser.parse(stdout.toByteArray())
                }
                process.waitFor(20, TimeUnit.MILLISECONDS)
            }
            @Suppress("UNREACHABLE_CODE") GcmServiceParser.unknown(SourceCompleteness.UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            GcmServiceParser.unknown(SourceCompleteness.CANCELLED)
        } catch (_: SecurityException) { GcmServiceParser.unknown(SourceCompleteness.DENIED) }
        catch (_: Exception) { GcmServiceParser.unknown(SourceCompleteness.UNAVAILABLE) }
        finally {
            // Destroy first: closing a live pipe can otherwise wait on an in-flight read.
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            stdout.reset()
        }
    }
}
