package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Read RPCs share a finite admission wait; control/write operations have their own executor.
 * One queue slot is only the completion handoff after busy clears, never a backlog behind a
 * stuck Binder. Cancellation releases the caller without admitting a second actual worker.
 */
internal class DaemonReadRpc(private val budgetMillis: Long = 30_000,
    private val worker: ThreadPoolExecutor = newWorker()) : AutoCloseable {
    init { require(budgetMillis in 1..30_000) }
    private val admission = Mutex()
    private val busy = AtomicBoolean()

    internal fun isQuiescent(): Boolean = !busy.get()

    /** The action may check cancellation between individual Binder calls, without logging inputs. */
    suspend fun <T> call(action: (checkActive: () -> Unit) -> T): T {
        val completed = withTimeoutOrNull(budgetMillis) { admission.withLock {
            val value = suspendCancellableCoroutine<T> { continuation ->
                if (!busy.compareAndSet(false, true)) {
                    continuation.resumeWith(Result.failure(RejectedExecutionException()))
                    return@suspendCancellableCoroutine
                }
                try {
                    worker.execute {
                        fun checkActive() {
                            if (!continuation.isActive) throw CancellationException("Read no longer active")
                        }
                        val result = runCatching { checkActive(); action(::checkActive) }
                        // A resumed sequential caller can submit before this Runnable has returned.
                        busy.set(false)
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                } catch (failure: RejectedExecutionException) {
                    busy.set(false)
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
                // Do not interrupt/release an in-flight Binder on caller timeout. An admitted
                // handoff remains bounded and runs only its inactive guard if the caller expired.
            }
            Completed(value)
        } } ?: throw TimeoutException()
        return completed.value
    }

    override fun close() { worker.shutdownNow() }

    companion object {
        private fun newWorker() = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "guardian-daemon-read").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }

    private data class Completed<T>(val value: T)
}
