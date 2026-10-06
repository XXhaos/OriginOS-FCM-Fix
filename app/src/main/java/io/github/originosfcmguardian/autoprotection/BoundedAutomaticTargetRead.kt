package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.RECIPIENT_READ_PARALLELISM
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal enum class AutomaticTargetReadFailure { TIMED_OUT, BUSY, FAILED }

/** PM evidence only. This result never carries journal, selection or write authority. */
internal data class AutomaticTargetObservation(
    val qualified: Boolean? = null,
    val stopped: TargetStoppedSnapshot? = null,
    val failure: AutomaticTargetReadFailure? = null,
) {
    val locallyUnavailable: Boolean get() =
        failure == AutomaticTargetReadFailure.TIMED_OUT || failure == AutomaticTargetReadFailure.BUSY
}

/** Shared across service/session recreation; stuck Binder calls must not spawn replacement pools. */
internal object AutomaticTargetReader {
    private val reader = BoundedAutomaticTargetRead()

    suspend fun <T> readChunk(targets: List<T>, read: (T) -> AutomaticTargetObservation): List<AutomaticTargetObservation> =
        reader.readChunk(targets, read)
}

/** Production uses the measured bounded limit; debug measurements use a separate bounded pool. */
internal class BoundedAutomaticTargetRead(
    private val timeoutMillis: Long = 5_000,
    maxParallel: Int = CHUNK_SIZE,
) : AutoCloseable {
    private val parallelLimit = maxParallel.also { require(it in 1..8) }
    private val workers = ThreadPoolExecutor(0, parallelLimit, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-auto-target-read").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    init { require(timeoutMillis > 0) }

    suspend fun <T> readChunk(targets: List<T>, read: (T) -> AutomaticTargetObservation): List<AutomaticTargetObservation> {
        require(targets.size <= parallelLimit)
        return coroutineScope { targets.map { target -> async { readOne { read(target) } } }.awaitAll() }
    }

    private suspend fun readOne(read: () -> AutomaticTargetObservation): AutomaticTargetObservation =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val future = try {
                    workers.submit {
                        if (!continuation.isActive) return@submit
                        val result = try { read() } catch (_: Exception) {
                            AutomaticTargetObservation(failure = AutomaticTargetReadFailure.FAILED)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (_: RejectedExecutionException) {
                    continuation.resume(AutomaticTargetObservation(failure = AutomaticTargetReadFailure.BUSY))
                    null
                }
                continuation.invokeOnCancellation { future?.cancel(true) }
            }
        } ?: AutomaticTargetObservation(failure = AutomaticTargetReadFailure.TIMED_OUT)

    override fun close() { workers.shutdownNow() }

    companion object { const val CHUNK_SIZE = RECIPIENT_READ_PARALLELISM }
}

/** Recheck proves current durable/shared gates passed; PM uncertainty never authorizes recovery. */
internal fun maySkipLocallyUnavailableTarget(
    observation: AutomaticTargetObservation,
    decision: AutomaticCheckDecision,
): Boolean = observation.locallyUnavailable && decision is AutomaticCheckDecision.Recheck
