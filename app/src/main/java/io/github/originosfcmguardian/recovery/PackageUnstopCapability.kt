package io.github.originosfcmguardian.recovery

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Only checks the existing fixed command; never probes support by changing a package. */
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
        if (!active()) return false
        val child = ProcessBuilder("/system/bin/cmd", "package", "help").start()
        children.add(child)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        fun current() = active() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline
        fun drain(): Boolean {
            if (child.errorStream.available() > 0) return false
            while (child.inputStream.available() > 0) {
                if (!current()) return false
                val count = child.inputStream.read(buffer, 0, minOf(buffer.size, child.inputStream.available()))
                if (count <= 0) break
                if (output.size() + count > 65_536) return false
                output.write(buffer, 0, count)
            }
            return true
        }
        return try {
            while (current()) {
                if (!drain()) return false
                if (!child.isAlive) return drain() && current() && child.exitValue() == 0 && advertised(output.toByteArray())
                child.waitFor(10, TimeUnit.MILLISECONDS)
            }
            false
        } finally {
            runCatching { child.destroyForcibly() }
            runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
            runCatching { child.outputStream.close() }; output.reset()
            if (runCatching { !child.isAlive }.getOrDefault(false)) children.remove(child)
        }
    }
}
