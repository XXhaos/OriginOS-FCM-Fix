package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class StartupTargetReadFailure { TIMED_OUT, BUSY }
internal sealed interface StartupTargetReadResult<out T> {
    data class Complete<T>(val values: List<T>) : StartupTargetReadResult<T>
    data class Unavailable(val reason: StartupTargetReadFailure) : StartupTargetReadResult<Nothing>
}

internal data class StartupPackageInput(val identity: SelectedTargetIdentity, val requiresQualification: Boolean)
internal data class StartupPackageEvidence(val identity: SelectedTargetIdentity?, val qualified: Boolean)

/** Process-shared, PM-only resource. Recreating a service never replaces stuck Binder workers. */
internal object StartupTargetReader {
    private val reader = BoundedStartupTargetRead()
    fun read(inputs: List<StartupPackageInput>, read: (StartupPackageInput) -> StartupPackageEvidence) =
        reader.readBatch(inputs.map { it.copy(identity = it.identity.copy(
            signerSha256 = it.identity.signerSha256.toList())) }, read)
}

/**
 * One deadline for the whole batch, with no queued work or replacement of occupied workers. A worker may
 * continue in Binder after cancellation; it owns only PM inputs/results, never authority or IO.
 * Each admitted batch reads serially on one of four workers and publishes only a complete list.
 * Core workers remain available between batches; occupied PM reads still retain their slots.
 */
internal class BoundedStartupTargetRead(
    private val timeoutMillis: Long = 5_000,
    maxWorkers: Int = 4,
) : AutoCloseable {
    private val workers = ThreadPoolExecutor(maxWorkers, maxWorkers, 0, TimeUnit.MILLISECONDS,
        SynchronousQueue(), { task -> Thread(task, "guardian-startup-pm-read").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    init { require(timeoutMillis > 0); require(maxWorkers in 1..4) }

    fun <T, R> readBatch(inputs: List<T>, read: (T) -> R): StartupTargetReadResult<R> {
        val captured = inputs.toList()
        if (captured.isEmpty()) return StartupTargetReadResult.Complete(emptyList())
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val abandoned = AtomicBoolean(false)
        val future = try {
            workers.submit<List<R>> {
                Collections.unmodifiableList(captured.map { input ->
                    if (abandoned.get()) throw CancellationException()
                    read(input)
                })
            }
        } catch (_: RejectedExecutionException) {
            return StartupTargetReadResult.Unavailable(StartupTargetReadFailure.BUSY)
        }
        try {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            val values = future.get(remaining, TimeUnit.NANOSECONDS)
            if (System.nanoTime() >= deadline) throw TimeoutException()
            return StartupTargetReadResult.Complete(values)
        } catch (_: TimeoutException) {
            return StartupTargetReadResult.Unavailable(StartupTargetReadFailure.TIMED_OUT)
        } catch (failure: ExecutionException) {
            // Real failures are not local capacity/time uncertainty and retain fail-closed handling.
            throw failure.cause ?: failure
        } finally {
            abandoned.set(true)
            future.cancel(true) // Best effort only; Android Binder need not respond to interruption.
        }
    }

    override fun close() { workers.shutdownNow() }
}

/** Only the master precommit PM check uses this exception; postcommit startup returns a status. */
internal class StartupTargetReadUnavailableException(val reason: StartupTargetReadFailure) : Exception()

internal fun shouldSuspendFailedMasterEnable(failure: Throwable?): Boolean =
    failure != null && failure !is StartupTargetReadUnavailableException

internal fun requireMasterTargetEvidence(
    result: StartupTargetReadResult<StartupPackageEvidence>, expected: List<TargetConsent>,
) {
    when (result) {
        is StartupTargetReadResult.Unavailable -> throw StartupTargetReadUnavailableException(result.reason)
        is StartupTargetReadResult.Complete -> check(result.values.size == expected.size &&
            result.values.zip(expected).all { (evidence, consent) ->
                evidence.identity == consent.identity && evidence.qualified
            })
    }
}
