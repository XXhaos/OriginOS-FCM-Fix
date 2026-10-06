package io.github.originosfcmguardian.privileged

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity

/**
 * Binder reads cannot necessarily be interrupted. One daemon, no queue: a stuck system read
 * consumes at most one worker and subsequent reads return BUSY, never accumulate or publish late.
 */
internal class StoppedIdentityProbeRunner(
    private val portFactory: () -> StoppedIdentityReadPort,
    private val budgetMillis: Long = 8_000,
) {
    init { require(budgetMillis in 1..8_000) }

    private val worker = ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, "guardian-telegram-identity-read").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun read(requestId: Long, wasCancelled: () -> Boolean): StoppedTargetObservation = readInternal(requestId, null, wasCancelled)

    fun readTarget(requestId: Long, expected: SelectedTargetIdentity, wasCancelled: () -> Boolean): StoppedTargetObservation =
        readInternal(requestId, expected, wasCancelled)

    private fun readInternal(requestId: Long, expected: SelectedTargetIdentity?, wasCancelled: () -> Boolean): StoppedTargetObservation {
        fun unknown(reason: StoppedObservationUnknown) = StoppedTargetObservation.Unknown(requestId, reason)
        if (requestId <= 0) return unknown(StoppedObservationUnknown.INVALID_REQUEST)
        val abandoned = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun interruption(): StoppedObservationUnknown? = when {
            abandoned.get() || wasCancelled() -> StoppedObservationUnknown.CANCELLED
            System.nanoTime() >= deadline -> StoppedObservationUnknown.TIMED_OUT
            else -> null
        }
        interruption()?.let { return unknown(it) }
        val future = try {
            worker.submit<StoppedTargetObservation> {
                try {
                    if (expected == null) observeStoppedTelegram(requestId, portFactory(), ::interruption)
                    else observeStoppedTarget(requestId, expected, portFactory(), ::interruption)
                } catch (_: Throwable) {
                    unknown(interruption() ?: StoppedObservationUnknown.CAPABILITY_UNAVAILABLE)
                }
            }
        } catch (_: RejectedExecutionException) {
            return unknown(StoppedObservationUnknown.BUSY)
        }
        return try {
            while (true) {
                interruption()?.let { return unknown(it) }
                val remaining = deadline - System.nanoTime()
                try {
                    val observation = future.get(minOf(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)
                    return interruption()?.let(::unknown) ?: observation
                } catch (_: TimeoutException) {
                    // Cancellation is sampled within 50ms even while system Binder is blocked.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            unknown(StoppedObservationUnknown.CAPABILITY_UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unknown(StoppedObservationUnknown.CANCELLED)
        } catch (_: Throwable) {
            unknown(interruption() ?: StoppedObservationUnknown.CAPABILITY_UNAVAILABLE)
        } finally {
            abandoned.set(true)
            future.cancel(true)
        }
    }

    fun close() { worker.shutdownNow() }
}
