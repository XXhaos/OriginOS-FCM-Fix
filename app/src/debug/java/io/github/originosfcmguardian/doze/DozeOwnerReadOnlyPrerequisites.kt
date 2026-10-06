package io.github.originosfcmguardian.doze

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Only two fixed debug identity/roster reads, each capped at 6s through exit and both EOFs. */
internal class DozeOwnerReadOnlyPrerequisites(
    private val scope: DozeOwnerReadOnlySession.Scope,
    private val startProcess: (Kind) -> Process = { ProcessBuilder(it.argv).start() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun readCallerPackages(): String = read(Kind.CALLER_PACKAGES)
    fun readReportedUsers(): String = read(Kind.REPORTED_USERS)

    private fun read(kind: Kind): String {
        val started = nanoTime()
        fun remaining(): Long {
            scope.requireActive()
            val left = minOf(6_000L - TimeUnit.NANOSECONDS.toMillis(nanoTime() - started), scope.remainingMillis())
            check(left > 0) { "Debug prerequisite deadline exceeded" }
            return left
        }
        remaining()
        val process = startProcess(kind)
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "guardian-owner-prerequisite").apply { isDaemon = true }
        }
        try {
            scope.onProcess(process)
            remaining()
            process.outputStream.close()
            val out = executor.submit<ByteArray> {
                process.inputStream.use { drain(it, 128 * 1024, ::remaining) }
            }
            val err = executor.submit<ByteArray> {
                process.errorStream.use { drain(it, 4 * 1024, ::remaining) }
            }
            while (!process.waitFor(minOf(50L, remaining()), TimeUnit.MILLISECONDS)) {
                if (out.isDone) out.get()
                if (err.isDone) err.get()
            }
            check(process.exitValue() == 0)
            val bytes = await(out, ::remaining)
            check(await(err, ::remaining).isEmpty())
            remaining()
            return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } finally {
            runCatching { if (process.isAlive) process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            executor.shutdownNow()
            scope.onProcess(null)
        }
    }

    private fun drain(input: InputStream, limit: Int, remaining: () -> Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (true) {
            remaining()
            val count = input.read(buffer)
            if (count < 0) break
            check(bytes.size() + count <= limit)
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun <T> await(future: Future<T>, remaining: () -> Long): T {
        while (true) try {
            return future.get(minOf(50L, remaining()), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) { /* Keep the 6s/request budget in force through EOF. */ }
    }

    internal enum class Kind(argv: List<String>) {
        CALLER_PACKAGES(listOf("/system/bin/pm", "list", "packages", "-U", "--user", "0")),
        REPORTED_USERS(listOf("/system/bin/pm", "list", "users"));
        val argv: List<String> = java.util.Collections.unmodifiableList(ArrayList(argv))
    }
}
