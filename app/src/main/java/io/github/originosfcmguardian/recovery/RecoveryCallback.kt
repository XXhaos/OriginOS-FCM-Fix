package io.github.originosfcmguardian.recovery

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One callback may wait for the completed worker's handoff; Binder stalls remain bounded. */
internal fun recoveryCallbackWorker(name: String) = ThreadPoolExecutor(
    0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
    { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy(),
)

/** Polls only local cancellation here; full authority is checked by the execution owner. */
internal fun <T> boundedRecoveryCallback(
    worker: ThreadPoolExecutor,
    localActive: () -> Boolean,
    cancel: () -> Unit,
    timeoutMillis: Long = 2_000,
    activity: io.github.originosfcmguardian.privileged.PrivilegedWorkerActivity? = null,
    block: () -> T,
): T {
    var future: Future<T>? = null
    val settlement = activity?.track()
    return try {
        check(localActive())
        val submitted = worker.submit<T> {
            check(settlement?.workerEntered() != false)
            try {
            // A queued callback must not enter Binder after cancellation or deadline expiry.
            check(localActive())
            block()
            } finally { settlement?.workerEnded(false, null) }
        }
        future = submitted
        submitted.get(timeoutMillis, TimeUnit.MILLISECONDS).also { check(localActive()) }
    } catch (failure: Throwable) {
        cancel()
        throw failure
    } finally {
        future?.cancel(true)
        settlement?.abandonQueued()
    }
}

/** Capability subprocess polling never calls Binder; authority still brackets its result. */
internal fun readRecoveryCapability(
    localActive: () -> Boolean,
    sessionActive: () -> Boolean,
    read: (() -> Boolean) -> Boolean = PackageUnstopCapability::read,
): Boolean {
    check(sessionActive())
    val advertised = read(localActive)
    check(sessionActive())
    return advertised
}
