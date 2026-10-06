package io.github.originosfcmguardian.privilegebridge

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A blocked Binder read occupies one worker; new reads are rejected instead of queued. */
internal class BoundedIdentityRpc : Closeable {
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-priv-identity").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    suspend fun <T> read(operation: () -> T): T = suspendCancellableCoroutine { continuation ->
        val future = try {
            worker.submit {
                try {
                    val value = operation()
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
            null
        }
        continuation.invokeOnCancellation { future?.cancel(true) }
    }

    override fun close() { worker.shutdownNow() }
}
