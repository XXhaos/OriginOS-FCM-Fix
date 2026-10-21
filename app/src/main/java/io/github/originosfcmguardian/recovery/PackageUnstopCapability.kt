package io.github.originosfcmguardian.recovery

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Fixed read-only capability children only; no target is changed to discover support. */
internal object PackageUnstopCapability {
    private val children = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    fun isQuiescent(): Boolean {
        children.removeIf { runCatching { !it.isAlive }.getOrDefault(false) }
        return children.isEmpty()
    }
    fun advertised(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 65_536 || bytes.last() != 10.toByte() ||
            bytes.any { it.toInt() !in 9..13 && it.toInt() !in 32..126 }) return false
        val lines = bytes.toString(Charsets.US_ASCII).lineSequence().toList()
        return lines.any { it.trim() == "Package manager (package) commands:" } &&
            lines.count { it.trim() == "unstop [--user USER_ID] PACKAGE" } == 1
    }

    fun read(active: () -> Boolean): Boolean {
        val bytes = fixedRead(listOf("/system/bin/cmd", "package", "help"), active) ?: return false
        return advertised(bytes)
    }

    /** PackageManager's fixed Guardian sourceDir, never a caller-provided classpath. */
    fun guardianApk(active: () -> Boolean): String? {
        val bytes = fixedRead(listOf("/system/bin/cmd", "package", "path", "--user", "0",
            "io.github.originosfcmguardian"), active) ?: return null
        val path = guardianApkOutput(bytes) ?: return null
        val file = File(path)
        return path.takeIf { file.isAbsolute && file.isFile && file.canonicalPath == path && active() }
    }

    internal fun guardianApkOutput(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > 4096 || bytes.last() != 10.toByte() ||
            bytes.any { it.toInt() !in 10..13 && it.toInt() !in 32..126 }) return null
        val lines = bytes.toString(Charsets.US_ASCII).lineSequence().toList()
        if (lines.size != 2 || lines.last().isNotEmpty() || !lines.first().startsWith("package:/")) return null
        return lines.first().removePrefix("package:").takeIf {
            it.substringAfterLast('/') == "base.apk" && it.none { char -> char.isWhitespace() || char == ':' || char == '\\' }
        }
    }

    fun binderProbe(apk: String, active: () -> Boolean): Boolean = fixedRead(
        // A cold app_process includes VM/class loading; this is only preclaim fallback readiness.
        listOf("/system/bin/app_process", "/system/bin", PACKAGE_STOPPED_STATE_MAIN, "probe"), active, apk,
        budgetMillis = 3_000)
        ?.contentEquals("$PACKAGE_STOPPED_STATE_PROBE\n".toByteArray(Charsets.US_ASCII)) == true

    private fun fixedRead(argv: List<String>, active: () -> Boolean, classpath: String? = null,
        budgetMillis: Long = 1_500): ByteArray? {
        if (!active()) return null
        val builder = ProcessBuilder(argv)
        if (classpath != null) builder.environment()["CLASSPATH"] = classpath
        val child = builder.start()
        children.add(child)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun current() = active() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline
        fun drain(eof: Boolean = false): Boolean {
            if (child.errorStream.available() > 0) return false
            while (eof || child.inputStream.available() > 0) {
                if (!current()) return false
                val available = if (eof) buffer.size else child.inputStream.available()
                val count = child.inputStream.read(buffer, 0, minOf(buffer.size, available))
                if (count < 0) break
                if (count == 0) return false
                if (output.size() + count > 65_536) return false
                output.write(buffer, 0, count)
            }
            return true
        }
        return try {
            while (current()) {
                if (!drain()) return null
                if (!child.isAlive) return if (drain(true) && current() && child.exitValue() == 0 &&
                    child.errorStream.read() == -1) output.toByteArray() else null
                child.waitFor(10, TimeUnit.MILLISECONDS)
            }
            null
        } finally {
            runCatching { child.destroyForcibly() }
            runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
            runCatching { child.outputStream.close() }; output.reset()
            if (runCatching { !child.isAlive }.getOrDefault(false)) children.remove(child)
        }
    }
}
