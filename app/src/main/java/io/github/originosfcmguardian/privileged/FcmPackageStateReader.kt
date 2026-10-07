package io.github.originosfcmguardian.privileged

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Fixed transient dump only; no text leaves the shell read port or reaches persistent logs. */
internal object FcmPackageStateReader {
    fun parse(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 1024 * 1024 || bytes.last() != 10.toByte()) return false
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Throwable) { return false }
        if (Regex("(?i)(permission denial|permission denied|error dumping|exception|dump timeout)").containsMatchIn(text)) return false
        var active = false; var packages = 0; var user = 0; var qualified = false
        for (line in text.lineSequence()) {
            if (line.length > 8192) return false
            if (line == "Packages:") { active = true; continue }
            if (line.isNotEmpty() && !line[0].isWhitespace()) active = false
            if (!active) continue
            if (line.trimStart().startsWith("Package [")) {
                if (!Regex("  Package \\[com\\.google\\.android\\.gms\\] \\([a-fA-F0-9]+\\):").matches(line)) return false
                packages++
            }
            if (Regex("[ \\t]+User 0:.*").matches(line)) {
                if (packages != 1 || ++user != 1) return false
                val tokens = line.trim().removePrefix("User 0: ").split(Regex("\\s+"))
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
        val child = ProcessBuilder("/system/bin/dumpsys", "-t", "1", "package", GMS_OBSERVATION_PACKAGE).start()
        val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        var stderr = false; var total = 0
        fun stopped() = Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        fun drain(input: InputStream, stdout: Boolean): Boolean {
            while (input.available() > 0) {
                if (stopped()) return false
                val n = input.read(buffer, 0, minOf(buffer.size, input.available(), 1024 * 1024 - total + 1))
                if (n <= 0) break
                total += n; if (total > 1024 * 1024) return false
                if (stdout) out.write(buffer, 0, n) else stderr = true
            }
            return true
        }
        return try {
            while (!stopped()) {
                if (!drain(child.inputStream, true) || !drain(child.errorStream, false)) return false
                if (!child.isAlive) {
                    if (!drain(child.inputStream, true) || !drain(child.errorStream, false)) return false
                    return child.exitValue() == 0 && !stderr && parse(out.toByteArray())
                }
                child.waitFor(10, TimeUnit.MILLISECONDS)
            }
            false
        } finally {
            runCatching { child.destroyForcibly() }; runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }; runCatching { child.outputStream.close() }; out.reset()
        }
    }
}
