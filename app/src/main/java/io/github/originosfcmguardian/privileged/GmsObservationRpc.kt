package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

internal class GmsObservationRpc(private val budgetMillis: Long = 11_000) {
    init { require(budgetMillis in 1..11_000) }
    // Capacity one is only a completion handoff: busy stays set through stuck actions, so
    // abandoned system calls cannot accumulate queued work. Release before continuation resume.
    private val reader = worker("guardian-gms-observation-rpc", handoff = true)
    private val readerBusy = AtomicBoolean(false)
    private val cleanup = worker("guardian-gms-observation-cleanup")
    /** Also bounds synchronous availability and binding Binder calls before the read endpoint. */
    suspend fun <T> bounded(action: () -> T): T = withTimeout(budgetMillis) {
        suspendCancellableCoroutine { continuation ->
            if (!readerBusy.compareAndSet(false, true)) {
                continuation.resumeWith(Result.failure(RejectedExecutionException())); return@suspendCancellableCoroutine
            }
            try { reader.submit {
                if (!continuation.isActive) { readerBusy.set(false); return@submit }
                try {
                    val value = action()
                    readerBusy.set(false)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Throwable) {
                    readerBusy.set(false)
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            } } catch (error: RejectedExecutionException) {
                readerBusy.set(false)
                continuation.resumeWith(Result.failure(error)); return@suspendCancellableCoroutine
            }
            // Leave an admitted handoff to run its inactive guard and release admission.
            // A stuck Binder keeps admission occupied; the cancelled caller returns promptly.
        }
    }
    suspend fun read(requestId: Long, serviceUid: () -> Int, payload: () -> String?): GmsConnectionObservation {
        fun unknown(reason: GmsUnknown) = GmsConnectionObservation.Unknown(requestId, reason)
        if (requestId <= 0) return unknown(GmsUnknown.INVALID_REQUEST)
        return try { withTimeout(budgetMillis) {
            suspendCancellableCoroutine { continuation ->
                if (!readerBusy.compareAndSet(false, true)) {
                    continuation.resume(unknown(GmsUnknown.BUSY)); return@suspendCancellableCoroutine
                }
                try { reader.submit {
                    fun publish(result: GmsConnectionObservation) { readerBusy.set(false); if (continuation.isActive) continuation.resume(result) }
                    try {
                        if (!continuation.isActive) { readerBusy.set(false); return@submit }
                        val uid = serviceUid()
                        if (!continuation.isActive) { readerBusy.set(false); return@submit }
                        if (uid != 2000) { publish(unknown(GmsUnknown.SHELL_UNAVAILABLE)); return@submit }
                        val raw = payload()
                        publish(GmsObservationCodec.decode(raw, requestId))
                    } catch (_: Throwable) { publish(unknown(GmsUnknown.BRIDGE_UNAVAILABLE)) }
                } } catch (_: RejectedExecutionException) {
                    readerBusy.set(false)
                    continuation.resume(unknown(GmsUnknown.BUSY)); return@suspendCancellableCoroutine
                }
            }
        } } catch (cancelled: CancellationException) {
            unknown(if (cancelled is TimeoutCancellationException) GmsUnknown.TIMED_OUT else GmsUnknown.CANCELLED)
        }
    }
    fun cleanupBestEffort(action: () -> Unit) {
        try { cleanup.execute { runCatching(action) } } catch (_: RejectedExecutionException) { }
    }
    fun close() { reader.shutdownNow(); cleanup.shutdown() }
    private fun worker(name: String, handoff: Boolean = false) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        if (handoff) ArrayBlockingQueue(1) else SynchronousQueue(),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
}
