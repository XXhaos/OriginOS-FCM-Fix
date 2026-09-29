package io.github.originosfcmguardian.privileged

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class RemoteRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
)

/** Runs an already validated argv directly. It never invokes a shell. */
internal class RemoteCommandRunner(
    private val timeoutMillis: Long = 8_000,
    private val maxCombinedOutputBytes: Int = 128 * 1024,
) {
    fun run(
        argv: List<String>,
        timeoutMillis: Long = this.timeoutMillis,
        wasCancelled: () -> Boolean,
        onProcessStarted: (Process) -> Unit,
    ): RemoteRunResult {
        require(argv.isNotEmpty())
        require(timeoutMillis > 0)
        val process = ProcessBuilder(argv).start()
        onProcessStarted(process)
        process.outputStream.close()

        val remaining = AtomicInteger(maxCombinedOutputBytes)
        val truncated = AtomicBoolean(false)
        val drainPool = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "guardian-output-drain").apply { isDaemon = true }
        }
        val stdout = drainPool.submit<ByteArray> {
            drain(process.inputStream, remaining, truncated)
        }
        val stderr = drainPool.submit<ByteArray> {
            drain(process.errorStream, remaining, truncated)
        }

        var timedOut = false
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut = true
                terminateProcess(process, forcibly = false)
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    terminateProcess(process, forcibly = true)
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                }
            }

            val outBytes = stdout.get(1, TimeUnit.SECONDS)
            val errBytes = stderr.get(1, TimeUnit.SECONDS)
            val cancelled = wasCancelled()
            return RemoteRunResult(
                exitCode = when {
                    cancelled -> IpcContract.EXIT_CANCELLED
                    timedOut -> IpcContract.EXIT_TIMED_OUT
                    else -> process.exitValue()
                },
                stdout = outBytes.toString(StandardCharsets.UTF_8),
                stderr = when {
                    cancelled -> "Command cancelled"
                    timedOut -> "Command timed out"
                    else -> errBytes.toString(StandardCharsets.UTF_8)
                },
                timedOut = timedOut,
                outputTruncated = truncated.get(),
            )
        } finally {
            if (process.isAlive) terminateProcess(process, forcibly = true)
            drainPool.shutdownNow()
        }
    }

    private fun drain(
        input: InputStream,
        remaining: AtomicInteger,
        truncated: AtomicBoolean,
    ): ByteArray {
        val captured = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val allowed = reserve(remaining, count)
                if (allowed > 0) captured.write(buffer, 0, allowed)
                if (allowed < count) truncated.set(true)
            }
        }
        return captured.toByteArray()
    }

    private fun reserve(remaining: AtomicInteger, requested: Int): Int {
        while (true) {
            val available = remaining.get()
            if (available <= 0) return 0
            val granted = minOf(available, requested)
            if (remaining.compareAndSet(available, available - granted)) return granted
        }
    }
}

/** Terminates the directly launched fixed executable; commands never use an intermediary shell. */
internal fun terminateProcess(process: Process, forcibly: Boolean = true) {
    runCatching { if (forcibly) process.destroyForcibly() else process.destroy() }
}
