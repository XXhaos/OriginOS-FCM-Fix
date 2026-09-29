package io.github.originosfcmguardian.doze

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds each outbound Binder callback. A timed-out Binder thread may linger, but cannot admit a write. */
internal class BoundedDozeCallback(
    private val cancelled: AtomicBoolean,
    private val remainingMillis: () -> Long,
    private val onCancel: () -> Unit,
) {
    fun isLocallyActive(): Boolean = !cancelled.get() && remainingMillis() > 0

    fun <T> call(block: () -> T): T {
        check(isLocallyActive()) { "Doze callback session is closed" }
        val budget = minOf(PER_CALLBACK_MILLIS, remainingMillis())
        check(budget > 0) { "Doze callback deadline exhausted" }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "guardian-doze-callback").apply { isDaemon = true }
        }
        try {
            val result = executor.submit(Callable(block)).get(budget, TimeUnit.MILLISECONDS)
            check(isLocallyActive()) { "Doze callback returned after session closed" }
            return result
        } catch (failure: Throwable) {
            cancelled.set(true)
            onCancel()
            throw failure
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val PER_CALLBACK_MILLIS = 3_000L
    }
}
