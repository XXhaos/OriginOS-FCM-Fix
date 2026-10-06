package io.github.originosfcmguardian.doze

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated read prerequisite; intentionally has no production caller, journal or device policy.
 * Only the fixed global settings command can be started. Returned structure is never inventory proof.
 */
internal class DozeOwnerCandidateReader(
    private val mayContinue: () -> Boolean = { true },
    private val remainingMillis: () -> Long = { REQUEST_MILLIS },
    private val onProcess: (Process?) -> Unit = {},
    private val startFixedProcess: () -> Process = { ProcessBuilder(FIXED_ARGV).start() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val reading = AtomicBoolean(false)

    fun read(expectedUserIds: Set<Int>): DozeOwnerCandidates {
        check(reading.compareAndSet(false, true)) { "Owner read is already running" }
        try {
            return readOnce(expectedUserIds)
        } finally {
            // Release only after readOnce has destroyed/closed the process and cleared its registration.
            reading.set(false)
        }
    }

    private fun readOnce(expectedUserIds: Set<Int>): DozeOwnerCandidates {
        val start = nanoTime()
        val initialBudget = minOf(COMMAND_MILLIS, REQUEST_MILLIS, remainingMillis())
        fun remaining(): Long {
            check(mayContinue() && !Thread.currentThread().isInterrupted) { "Owner read cancelled" }
            val left = minOf(initialBudget - TimeUnit.NANOSECONDS.toMillis(nanoTime() - start), remainingMillis())
            check(left > 0) { "Owner read deadline exceeded" }
            return left
        }
        remaining()
        val process = startFixedProcess()
        val drains = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "guardian-doze-owner-read").apply { isDaemon = true }
        }
        try {
            onProcess(process)
            remaining()
            process.outputStream.close()
            val stdout = drains.submit<DozeOwnerCandidates> {
                process.inputStream.use { input ->
                    DozeOwnerCandidateParser.parse(input, expectedUserIds) { remaining(); true }
                }
            }
            val stderr = drains.submit<Int> {
                process.errorStream.use { input ->
                    val bytes = ByteArray(512)
                    var total = 0
                    while (true) {
                        remaining()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        check(total <= STDERR_BYTES) { "Owner read stderr capacity exceeded" }
                    }
                    total
                }
            }
            while (!process.waitFor(minOf(POLL_MILLIS, remaining()), TimeUnit.MILLISECONDS)) {
                // Surface drain errors promptly instead of keeping a blocked writer until timeout.
                if (stdout.isDone) stdout.get()
                if (stderr.isDone) stderr.get()
            }
            check(process.exitValue() == 0) { "Owner read process failed" }
            val result = awaitDrain(stdout, ::remaining)
            check(awaitDrain(stderr, ::remaining) == 0) { "Owner read produced stderr" }
            remaining()
            return result
        } finally {
            // No unbounded waitFor/join during cancellation or callback failure.
            runCatching { if (process.isAlive) process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            drains.shutdownNow()
            runCatching { onProcess(null) }
        }
    }

    private fun <T> awaitDrain(future: Future<T>, remaining: () -> Long): T {
        while (true) {
            try {
                return future.get(minOf(POLL_MILLIS, remaining()), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Polling keeps cancellation and the command/request deadlines in force through EOF.
            }
        }
    }

    internal companion object {
        val FIXED_ARGV: List<String> = java.util.Collections.unmodifiableList(
            listOf("/system/bin/dumpsys", "package", "--include-apex", "packages"),
        )
        const val COMMAND_MILLIS = 6_000L
        const val REQUEST_MILLIS = 40_000L
        const val STDERR_BYTES = 4 * 1024
        private const val POLL_MILLIS = 50L
    }
}
