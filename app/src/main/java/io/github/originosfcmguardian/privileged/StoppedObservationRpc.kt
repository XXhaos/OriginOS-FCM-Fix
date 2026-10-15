package io.github.originosfcmguardian.privileged

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Only the new read endpoint: uninterruptible Binder never holds the coroutine/mutex hostage. */
internal class StoppedObservationRpc(private val budgetMillis: Long = 11_000) {
    init { require(budgetMillis in 1..11_000) }

    private val reader = worker("guardian-telegram-observation-rpc")
    private val cleanup = worker("guardian-telegram-observation-cleanup")

    suspend fun read(
        requestId: Long,
        serviceUid: () -> Int,
        payload: () -> String?,
    ): StoppedTargetObservation {
        fun unknown(reason: StoppedObservationUnknown) = StoppedTargetObservation.Unknown(requestId, reason)
        if (requestId <= 0) return unknown(StoppedObservationUnknown.INVALID_REQUEST)
        return try {
            withTimeout(budgetMillis) {
                suspendCancellableCoroutine { continuation ->
                    val settlement = activity.track()
                    val future = try {
                        reader.submit {
                            if (!settlement.workerEntered()) return@submit
                            try {
                            fun publish(result: StoppedTargetObservation) {
                                if (continuation.isActive) continuation.resume(result)
                            }
                            try {
                                if (!continuation.isActive) return@submit
                                val uid = serviceUid()
                                // A late UID reply after abandonment must never dispatch the next read.
                                if (!continuation.isActive) return@submit
                                if (uid != 2000) {
                                    publish(unknown(StoppedObservationUnknown.SHELL_UNAVAILABLE))
                                    return@submit
                                }
                                val raw = payload()
                                if (!continuation.isActive) return@submit
                                publish(StoppedObservationCodec.decode(raw, requestId))
                            } catch (_: Throwable) {
                                publish(unknown(StoppedObservationUnknown.BRIDGE_UNAVAILABLE))
                            }
                            } finally { settlement.workerEnded(false, null) }
                        }
                    } catch (_: RejectedExecutionException) {
                        settlement.abandonQueued()
                        continuation.resume(unknown(StoppedObservationUnknown.BUSY))
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { future.cancel(true); settlement.abandonQueued() }
                }
            }
        } catch (cancelled: CancellationException) {
            unknown(if (cancelled is TimeoutCancellationException) StoppedObservationUnknown.TIMED_OUT else StoppedObservationUnknown.CANCELLED)
        }
    }

    /** Cleanup may itself make Binder calls: no waiting, no queue, at most one cleanup worker. */
    fun cleanupBestEffort(action: () -> Unit) {
        val settlement = activity.track()
        try { cleanup.execute {
            if (settlement.workerEntered()) try { runCatching(action) }
            finally { settlement.workerEnded(false, null) }
        } } catch (_: RejectedExecutionException) { settlement.abandonQueued() }
    }

    fun close() { reader.shutdownNow(); cleanup.shutdownNow() }
    internal companion object {
        private val activity = PrivilegedWorkerActivity()
        internal fun allQuiescent(): Boolean = activity.isQuiescent()
    }

    private fun worker(name: String) = ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
    )
}
