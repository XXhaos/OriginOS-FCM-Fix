package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.TargetStoppedReadFailure
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.RECIPIENT_READ_PARALLELISM
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Process-wide bound: recreating an Activity must not allocate more workers behind stuck Binder calls. */
internal object AppRecheckReader {
    private val reader = BoundedAppRecheck(parallelism = RECIPIENT_READ_PARALLELISM)
    suspend fun read(probe: () -> TargetStoppedSnapshot): TargetStoppedSnapshot = reader.read(probe)
}

/** Read-only PM calls, no queue. Cancellation abandons a reply, not a guarantee of Binder interruption. */
internal class BoundedAppRecheck(private val timeoutMillis: Long = 5_000, parallelism: Int = 2) : AutoCloseable {
    private val parallelLimit = parallelism.also { require(it in 1..8) }
    private val workers = ThreadPoolExecutor(0, parallelLimit, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-app-recheck").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    init { require(timeoutMillis > 0) }

    suspend fun read(probe: () -> TargetStoppedSnapshot): TargetStoppedSnapshot =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val future = try {
                    workers.submit {
                        if (!continuation.isActive) return@submit
                        val result = try { probe() } catch (_: Exception) {
                            unknown(TargetStoppedReadFailure.FAILED)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (_: RejectedExecutionException) {
                    continuation.resume(unknown(TargetStoppedReadFailure.BUSY))
                    null
                }
                continuation.invokeOnCancellation { future?.cancel(true) }
            }
        } ?: unknown(TargetStoppedReadFailure.TIMED_OUT)

    private fun unknown(failure: TargetStoppedReadFailure) =
        TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN, System.currentTimeMillis(), failure)

    override fun close() { workers.shutdownNow() }
}
