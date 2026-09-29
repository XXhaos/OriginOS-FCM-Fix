package io.github.originosfcmguardian.doze

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.github.originosfcmguardian.core.doze.DozeTarget

/** A bounded direct-process runner local to the Doze transaction service. */
internal class DozeCommandRunner(
    private val mayContinue: () -> Boolean,
    private val remainingMillis: () -> Long,
    private val onProcess: (Process?) -> Unit,
) {
    fun small(argv: List<String>): String = run(argv, projectPackage = false)
    fun packageProjection(target: DozeTarget): String = run(
        listOf("/system/bin/dumpsys", "package", target.packageName),
        projectPackage = true,
    )

    private fun run(argv: List<String>, projectPackage: Boolean): String {
        check(mayContinue()) { "Doze transaction session is no longer active" }
        val budget = minOf(PER_COMMAND_MILLIS, remainingMillis())
        check(budget > 0) { "Doze transaction deadline exhausted" }
        val process = ProcessBuilder(argv).start()
        val drains = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "guardian-doze-drain").apply { isDaemon = true }
        }
        try {
            onProcess(process)
            process.outputStream.close()
            val stdout = drains.submit<String> {
                if (projectPackage) projectPackageLines(process.inputStream)
                else process.inputStream.use { it.readBounded(SMALL_OUTPUT_BYTES) }
            }
            val stderr = drains.submit<String> {
                process.errorStream.use { it.readBounded(STDERR_BYTES) }
            }
            if (!process.waitFor(budget, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Doze command deadline exceeded")
            }
            val remaining = remainingMillis().coerceAtLeast(1)
            val out = stdout.get(remaining, TimeUnit.MILLISECONDS)
            val err = stderr.get(remaining, TimeUnit.MILLISECONDS)
            check(process.exitValue() == 0 && err.isBlank() && mayContinue()) {
                "Doze command failed, produced stderr, or session closed"
            }
            return out
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
            onProcess(null)
            drains.shutdownNow()
        }
    }

    internal fun projectPackageLines(input: InputStream): String = input.use { stream ->
        val selected = StringBuilder()
        val line = ByteArrayOutputStream()
        val rawCount = AtomicInteger()
        var lineTooLong = false
        fun acceptLine() {
            if (lineTooLong) {
                // A selected identity line must be complete. Oversized irrelevant lines are discarded.
                if (String(line.toByteArray(), StandardCharsets.UTF_8).trimStart().let(::isSelectedLine)) {
                    throw IllegalStateException("Oversized package identity line")
                }
            } else {
                val value = String(line.toByteArray(), StandardCharsets.UTF_8).trim()
                if (isSelectedLine(value)) {
                    check(selected.length + value.length + 1 <= PROJECTED_BYTES) {
                        "Package identity projection exceeds bound"
                    }
                    selected.append(value).append('\n')
                }
            }
            line.reset()
            lineTooLong = false
        }
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(rawCount.addAndGet(count) <= RAW_PACKAGE_BYTES) { "Package dump exceeds raw bound" }
            for (index in 0 until count) {
                val value = buffer[index]
                if (value == '\n'.code.toByte()) {
                    acceptLine()
                } else if (!lineTooLong) {
                    if (line.size() < LINE_BYTES) line.write(value.toInt())
                    else lineTooLong = true
                }
            }
        }
        if (line.size() > 0 || lineTooLong) acceptLine()
        selected.toString()
    }

    private fun isSelectedLine(value: String): Boolean =
        value.startsWith("Package [") || value.startsWith("appId=") ||
            value.startsWith("pccId=") ||
            (Regex("^User \\d+:").containsMatchIn(value) &&
                !Regex("^User \\d+:$").matches(value))

    private fun InputStream.readBounded(maxBytes: Int): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(result.size() + count <= maxBytes) { "Doze command output exceeds bound" }
            result.write(buffer, 0, count)
        }
        return String(result.toByteArray(), StandardCharsets.UTF_8)
    }

    private companion object {
        const val PER_COMMAND_MILLIS = 6_000L
        const val RAW_PACKAGE_BYTES = 4 * 1024 * 1024
        const val PROJECTED_BYTES = 16 * 1024
        const val LINE_BYTES = 16 * 1024
        const val SMALL_OUTPUT_BYTES = 128 * 1024
        const val STDERR_BYTES = 4 * 1024
    }
}
