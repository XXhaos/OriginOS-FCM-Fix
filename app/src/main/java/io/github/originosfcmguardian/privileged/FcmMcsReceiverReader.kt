package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.FcmRecoveryPolicy
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** Transient complete bounded receiver read. No raw data crosses IPC or reaches the journal. */
internal object FcmMcsReceiverReader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_SECTION = 2 * 1024 * 1024
    private val actionLine = Regex("Action: \"([^\"]+)\"")
    // Diagnostic lines only: legitimate receiver actions/history can contain EXCEPTION.
    private val dumpError = Regex("(?i)^\\s*(?:\\*+\\s*)?(?:permission denial\\b|permission denied\\b|error (?:while )?dumping\\b|[A-Za-z0-9_.$]*Exception(?::\\s+\\S|\\s+\\S|$)|dump timeout\\b)")
    private val exportMetadata = Regex("exported=(true|false)")
    internal class Collector(private val uid: Int, private val allowReviewedMissingExported: Boolean = false) {
        private val line = ByteArray(8192)
        private var lineSize = 0
        private var ignoredLongLine = false
        private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        private val decoded = CharBuffer.allocate(8192)
        private var total = 0
        private var failed = false
        private var owner = false
        private var gmsOwner = false
        private var ownerConfirmed = false
        private var filter = false
        private var actions = mutableListOf<String>()
        private var permission = false
        private var permissionSeen = false
        private var exported: Boolean? = null
        private var matches = 0
        private var sectionBytes = 0
        private var headers = 0
        private var registered = false
        private val receiver = Regex("\\s*\\* ReceiverList\\{[a-fA-F0-9]+ ([0-9]+) com\\.google\\.android\\.gms/$uid/u0 remote:[a-fA-F0-9]+\\}")
        private val app = Regex("app=([0-9]+):com\\.google\\.android\\.gms/u0a${uid - 10000} pid=([0-9]+) uid=$uid user=0 #receivers=[0-9]+")
        private var pid: String? = null
        private fun finishFilter() {
            if (gmsOwner && FcmRecoveryPolicy.MCS_ACTION in actions) {
                val reviewedLegacyFilter = allowReviewedMissingExported && exported == null &&
                    actions.toSet() == setOf(FcmRecoveryPolicy.MCS_ACTION,
                        "com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW") && actions.size == 2
                if (!owner || !ownerConfirmed || permission || actions.distinct().size != actions.size ||
                    (exported != true && !reviewedLegacyFilter)) failed = true
                else matches++
            }
            actions.clear(); permission = false; permissionSeen = false; exported = null; filter = false
        }
        private fun completeLine() {
            if (ignoredLongLine) {
                // Historical broadcasts are irrelevant and can contain large extras lines.
                // Retain only their bounded prefix for dump-error detection; never parse them.
                if (dumpError.containsMatchIn(String(line, 0, minOf(lineSize, 256), Charsets.UTF_8))) failed = true
                lineSize = 0; ignoredLongLine = false; return
            }
            val lineBytes = lineSize + 1
            val text = try {
                decoder.reset(); decoded.clear()
                val read = decoder.decode(ByteBuffer.wrap(line, 0, lineSize), decoded, true)
                if (read.isError || read.isOverflow) { failed = true; return }
                val flushed = decoder.flush(decoded)
                if (flushed.isError || flushed.isOverflow) { failed = true; return }
                decoded.flip(); decoded.toString().trimEnd('\r')
            }
                catch (_: Exception) { failed = true; return }
            lineSize = 0
            if (dumpError.containsMatchIn(text)) failed = true
            val trim = text.trim()
            if (trim == "Registered Receivers:") { finishFilter(); registered = true; headers++; return }
            // Registered receiver section ends at the next two-space section heading.
            if (registered && text.startsWith("  ") && !text.startsWith("    ") && trim.endsWith(":")) {
                finishFilter(); registered = false
            }
            if (!registered) return
            sectionBytes += lineBytes
            if (sectionBytes > MAX_SECTION) failed = true
            if (trim.startsWith("* ReceiverList{")) {
                finishFilter(); val match = receiver.matchEntire(text)
                // A second MCS filter in another process of this UID is still a second
                // package-targeted recipient. Unsupported owner shapes cannot hide it.
                gmsOwner = trim.contains(" com.google.android.gms/") || trim.contains("/$uid/")
                owner = match != null; pid = match?.groupValues?.get(1); ownerConfirmed = false
            } else if (trim.startsWith("app=")) {
                val match = app.matchEntire(trim)
                ownerConfirmed = owner && match != null && match.groupValues[1] == pid && match.groupValues[2] == pid
            } else if (trim.startsWith("Filter #")) {
                finishFilter(); filter = true
            } else if (filter && trim.startsWith("Action:")) {
                val match = actionLine.matchEntire(trim)
                if (match == null) failed = true else if (gmsOwner) actions.add(match.groupValues[1])
            } else if (filter && trim.startsWith("requiredPermission")) {
                // Absence is qualified only within the exact complete filter, never inferred
                // from adjacent filters (HEARTBEAT_ALARM has INTERNAL_BROADCAST permission).
                if (permissionSeen || trim != "requiredPermission=null") permission = true
                permissionSeen = true
            }
            if (filter && gmsOwner && "exported" in trim) {
                val metadata = exportMetadata.matchEntire(trim)
                if (metadata == null || exported != null) permission = true
                else exported = metadata.groupValues[1] == "true"
            }
        }
        fun accept(bytes: ByteArray, count: Int = bytes.size): Boolean {
            if (failed || count > MAX_BYTES - total) return false
            total += count
            var index = 0
            while (index < count) {
                var newline = index
                while (newline < count && bytes[newline] != 10.toByte()) newline++
                val span = newline - index
                if (!ignoredLongLine) {
                    if (span > line.size - lineSize) {
                        if (registered) { failed = true; return false }
                        val retained = line.size - lineSize
                        System.arraycopy(bytes, index, line, lineSize, retained); lineSize += retained
                        ignoredLongLine = true
                    } else {
                        System.arraycopy(bytes, index, line, lineSize, span); lineSize += span
                    }
                }
                if (newline < count) { completeLine(); index = newline + 1 } else index = count
                if (failed) return false
            }
            return true
        }
        fun finish(): Boolean { finishFilter(); return !failed && total > 0 && lineSize == 0 && !ignoredLongLine && headers == 1 && !registered && matches == 1 }
    }
    internal fun parse(bytes: ByteArray, uid: Int, allowReviewedMissingExported: Boolean = false): Boolean =
        Collector(uid, allowReviewedMissingExported).let { it.accept(bytes) && it.finish() }
    fun read(uid: Int, allowReviewedMissingExported: Boolean = false): Boolean {
        // Parser construction can throw on Android ICU: do not start a child before it succeeds.
        val collector = Collector(uid, allowReviewedMissingExported)
        val child = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "activity", "broadcasts").start()
        val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500)
        var stderr = false; var total = 0
        fun stopped() = Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        fun drain(stdout: Boolean, eof: Boolean = false): Boolean {
            val input = if (stdout) child.inputStream else child.errorStream
            while (!stopped()) {
                val available = if (eof) buffer.size else input.available()
                if (!eof && available <= 0) return true
                val count = input.read(buffer, 0, minOf(buffer.size, available, MAX_BYTES - total + 1))
                if (count < 0) return true
                if (count == 0 || count > MAX_BYTES - total) return false
                total += count
                if (stdout) { if (!collector.accept(buffer, count)) return false } else stderr = true
            }
            return false
        }
        return try {
            while (!stopped()) {
                if (!drain(true) || !drain(false)) return false
                if (!child.isAlive) return drain(true, true) && drain(false, true) && !stderr &&
                    child.exitValue() == 0 && collector.finish() && !stopped()
                child.waitFor(1, TimeUnit.MILLISECONDS)
            }
            false
        } finally {
            runCatching { child.destroyForcibly() }; runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }; runCatching { child.outputStream.close() }
        }
    }
}
