package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GmsDozeBrokerChild
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Actual child facts only. Command success and matching durable result remain separate. */
internal data class GmsDozeBrokerProcessEvidence(
    val childExited: Boolean,
    val stdoutEofAndDrained: Boolean,
    val stderrEofAndDrained: Boolean,
    val handlesClosed: Boolean,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
    val exitCode: Int? = null,
)

/**
 * Wrap immediately after genuine fixed ProcessBuilder.start, inside broker admission's monitor.
 * Construction only retains the Process: no wait, stream access, close, RPC or storage operation.
 * awaitEvidence must run OUTSIDE that monitor. This helper launches no command and cannot release
 * broker busy/intent. Timeout, cancellation, destroy and process death are not completion facts.
 */
internal class GmsDozeBrokerProcessChild(private val process: Process) : GmsDozeBrokerChild {
    private val awaitLock = Any()
    private val stdout = Drain(STDOUT_BYTES)
    private val stderr = Drain(STDERR_BYTES)
    private var started = false
    private var stdinWorker: Thread? = null
    @Volatile private var stdinClosed = false
    private var childExited = false
    private var exitCode: Int? = null

    /** Repeated calls wait for the same collectors; failed initialization/close is not retried. */
    fun awaitEvidence(timeoutMillis: Long): GmsDozeBrokerProcessEvidence = synchronized(awaitLock) {
        require(timeoutMillis in 0..MAX_WAIT_MILLIS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (!started) {
            started = true
            try {
                stdinWorker = Thread({
                    try { process.outputStream.close(); stdinClosed = true } catch (_: Exception) { }
                }, "guardian-gms-broker-stdin").apply { isDaemon = true; start() }
            } catch (_: Exception) { }
            stdout.start { process.inputStream }
            stderr.start { process.errorStream }
        }
        try {
            if (!childExited && process.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                // A successful real wait and exitValue, not isAlive=false or a cancelled Future.
                exitCode = process.exitValue()
                childExited = true
            }
            stdout.joinUntil(deadline)
            stderr.joinUntil(deadline)
            stdinWorker?.let { joinUntil(it, deadline) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Retain the actual handle. No finally path clears ownership or invents EOF.
        }
        val outFinished = stdout.finished()
        val errFinished = stderr.finished()
        GmsDozeBrokerProcessEvidence(childExited, outFinished && stdout.eof,
            errFinished && stderr.eof,
            stdinClosed && stdinWorker?.let(::finished) == true && outFinished && errFinished &&
                stdout.closed && stderr.closed,
            stdout.text(), stderr.text(), stdout.truncated || stderr.truncated, exitCode)
    }

    private class Drain(private val cap: Int) {
        private val retained = ByteArrayOutputStream()
        private var worker: Thread? = null
        @Volatile var eof = false
            private set
        @Volatile var closed = false
            private set
        @Volatile var truncated = false
            private set

        fun start(getStream: () -> InputStream) {
            // Stream acquisition or thread startup failure cannot lose the enclosing Process handle.
            try {
                val thread = Thread({
                    var stream: InputStream? = null
                    try {
                        stream = getStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) { eof = true; break }
                            if (count > 0) synchronized(retained) {
                                val keep = minOf(count, cap - retained.size())
                                if (keep > 0) retained.write(buffer, 0, keep)
                                if (keep < count) truncated = true
                            }
                            // Output overflow is discarded, but the stream still drains to real EOF.
                        }
                    } catch (_: Exception) {
                        // Read failure/interruption is not EOF, even if close subsequently succeeds.
                    } finally {
                        if (stream != null) try { stream.close(); closed = true } catch (_: Exception) { }
                    }
                }, "guardian-gms-broker-drain").apply { isDaemon = true }
                worker = thread
                thread.start()
            } catch (_: Exception) {
                // No collector means no completion evidence. No restart/takeover in this helper.
            }
        }

        fun joinUntil(deadline: Long) {
            val thread = worker ?: return
            joinUntil(thread, deadline)
        }

        fun finished(): Boolean = worker?.let(::finished) == true
        fun text(): String = synchronized(retained) { retained.toString(Charsets.UTF_8.name()) }
    }

    companion object {
        const val STDOUT_BYTES = 16 * 1024
        const val STDERR_BYTES = 4 * 1024
        const val MAX_WAIT_MILLIS = 60_000L
        private fun remainingNanos(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)
        private fun finished(thread: Thread): Boolean = thread.state == Thread.State.TERMINATED && !thread.isAlive
        private fun joinUntil(thread: Thread, deadline: Long) {
            val remaining = remainingNanos(deadline)
            if (remaining > 0) thread.join(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }
}
