package io.github.originosfcmguardian.privileged

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Fixed transient dump only; no text leaves the shell read port or reaches persistent logs. */
internal object FcmPackageStateReader {
    internal const val MAX_RAW_BYTES = 8 * 1024 * 1024
    internal const val MAX_PACKAGES_BYTES = 256 * 1024
    internal const val MAX_LINE_BYTES = 8192
    internal const val READ_BUDGET_MILLIS = 2500L
    private val dumpError = Regex("(?i)(permission denial|permission denied|error dumping|exception|dump timeout)")
    private val packageHeader = Regex("  Package \\[com\\.google\\.android\\.gms\\] \\([a-fA-F0-9]+\\):")
    private val userZeroLine = Regex("[ \\t]+User 0:.*")
    private val tokenWhitespace = Regex("\\s+")
    private const val MAIN_HEADER = "Packages:"

    fun parse(bytes: ByteArray): Boolean {
        val collector = Collector()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(4096, bytes.size - offset)
            if (!collector.accept(bytes, offset, count)) return false
            offset += count
        }
        return collector.finish()
    }

    /** UTF-8 cannot contain LF inside a multibyte character. Decode each complete bounded line
     * strictly, including discarded sections; a split character survives arbitrary input chunks.
     * No success is possible before EOF, and an unterminated final line is always rejected. */
    internal class Collector {
        private val line = ByteArray(MAX_LINE_BYTES)
        private var lineSize = 0
        private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        private val decodedLine = CharBuffer.allocate(MAX_LINE_BYTES)
        private val packages = ByteArrayOutputStream()
        private var rawBytes = 0
        private var sections = 0
        private var active = false
        private var rejected = false
        private var finished = false

        fun accept(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): Boolean {
            if (finished || rejected || offset < 0 || count < 0 || offset > bytes.size - count) return false
            if (count > MAX_RAW_BYTES - rawBytes) { rejected = true; return false }
            rawBytes += count
            var index = offset
            val end = offset + count
            while (index < end) {
                var newline = index
                while (newline < end && bytes[newline] != 10.toByte()) newline++
                val span = newline - index
                if (span > MAX_LINE_BYTES - lineSize) { rejected = true; return false }
                // One bulk copy per LF-delimited span, not a synchronized stream write per byte.
                System.arraycopy(bytes, index, line, lineSize, span)
                lineSize += span
                if (newline < end) {
                    if (!completeLine()) { rejected = true; return false }
                    index = newline + 1
                } else {
                    index = end
                }
            }
            return true
        }

        private fun completeLine(): Boolean {
            // Reuse bounded byte/char storage and decoder even for discarded lines. Strict UTF-8
            // decode+flush still validates the entire line, including incomplete final characters.
            val text = try {
                decoder.reset(); decodedLine.clear()
                val decoded = decoder.decode(ByteBuffer.wrap(line, 0, lineSize), decodedLine, true)
                if (decoded.isError || decoded.isOverflow) return false
                val flushed = decoder.flush(decodedLine)
                if (flushed.isError || flushed.isOverflow) return false
                decodedLine.flip()
                decodedLine
            } catch (_: Throwable) { return false }
            if (dumpError.containsMatchIn(text)) return false
            if (text.isNotEmpty() && text[text.length - 1] == '\r') text.limit(text.limit() - 1)
            if (text.length == MAIN_HEADER.length && MAIN_HEADER.indices.all { text[it] == MAIN_HEADER[it] }) {
                if (++sections != 1) return false
                active = true
            } else if (text.isNotEmpty() && !text[0].isWhitespace()) active = false
            if (active) {
                if (lineSize + 1 > MAX_PACKAGES_BYTES - packages.size()) return false
                packages.write(line, 0, lineSize); packages.write(10)
            }
            lineSize = 0
            return true
        }

        fun finish(): Boolean {
            if (finished) return false
            finished = true
            if (rejected) return false
            if (rawBytes <= 0) return false
            if (lineSize != 0) return false
            if (sections != 1) return false
            return parsePackages(packages.toByteArray())
        }
    }

    private fun parsePackages(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_PACKAGES_BYTES || bytes.last() != 10.toByte()) return false
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Throwable) { return false }
        if (dumpError.containsMatchIn(text)) return false
        var active = false; var packages = 0; var user = 0; var qualified = false
        for (line in text.lineSequence()) {
            if (line.length > 8192) return false
            if (line == "Packages:") { active = true; continue }
            if (line.isNotEmpty() && !line[0].isWhitespace()) active = false
            if (!active) continue
            if (line.trimStart().startsWith("Package [")) {
                if (!packageHeader.matches(line)) return false
                packages++
            }
            if (userZeroLine.matches(line)) {
                if (packages != 1 || ++user != 1) return false
                val tokens = line.trim().removePrefix("User 0: ").split(tokenWhitespace)
                // Optional OEM extension: absence is portable; an unread/malformed present value is not.
                if (tokens.any { it.startsWith("vStopped") && it != "vStopped=false" }) return false
                val fields = tokens.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
                if (fields.map { it.first }.distinct().size != fields.size) return false
                val values = fields.toMap()
                qualified = values["installed"] == "true" && values["hidden"] == "false" &&
                    values["suspended"] == "false" && values["stopped"] == "false" &&
                    ("vStopped" !in values || values["vStopped"] == "false") && values["enabled"] in setOf("0", "1")
            }
        }
        return packages == 1 && user == 1 && qualified
    }
    fun read(): Boolean {
        // Do not truncate before the finite complete-read budget. The recovery runner still
        // bounds both fresh qualification reads plus dispatch to 8 seconds in total.
        val child = ProcessBuilder("/system/bin/dumpsys", "-t", "3", "package", GMS_OBSERVATION_PACKAGE).start()
        return readProcess(child)
    }

    internal fun readProcess(child: Process): Boolean {
        val collector = Collector(); val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_BUDGET_MILLIS)
        var stderr = false; var total = 0
        fun stopped() = Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        fun drain(input: InputStream, stdout: Boolean, eof: Boolean = false): Boolean {
            // After process exit its pipe is closed: require actual EOF, not just available()==0.
            while (true) {
                val available = if (eof) buffer.size else input.available()
                if (!eof && available <= 0) return true
                if (stopped()) return false
                val n = input.read(buffer, 0, minOf(buffer.size, available, MAX_RAW_BYTES - total + 1))
                if (n < 0) return true
                if (n == 0) return false
                total += n; if (total > MAX_RAW_BYTES) return false
                if (stdout) { if (!collector.accept(buffer, 0, n)) return false } else stderr = true
            }
        }
        return try {
            while (!stopped()) {
                if (!drain(child.inputStream, true) || !drain(child.errorStream, false)) return false
                if (!child.isAlive) {
                    if (!drain(child.inputStream, true, eof = true) || !drain(child.errorStream, false, eof = true)) return false
                    val qualified = child.exitValue() == 0 && !stderr && collector.finish()
                    return qualified && !stopped()
                }
                // Only this finite qualification read: avoid 10ms pipe starvation between chunks.
                // Still yield/block while the child is alive; no busy spin or recurring timer.
                child.waitFor(1, TimeUnit.MILLISECONDS)
            }
            false
        } finally {
            runCatching { child.destroyForcibly() }; runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }; runCatching { child.outputStream.close() }
        }
    }
}
