package io.github.originosfcmguardian.daemon

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Independent control lane. Admission/timeout never retries a write or frees its actual worker. */
internal class DaemonControlRpc(budgetMillis: Long = 30_000,
    worker: ThreadPoolExecutor = newWorker()) : AutoCloseable {
    private val rpc = DaemonReadRpc(budgetMillis, worker)

    suspend fun <T> call(action: (checkActive: () -> Unit) -> T): T = rpc.call(action)
    internal fun isQuiescent(): Boolean = rpc.isQuiescent()
    override fun close() = rpc.close()

    companion object {
        private fun newWorker() = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "guardian-daemon-control").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
