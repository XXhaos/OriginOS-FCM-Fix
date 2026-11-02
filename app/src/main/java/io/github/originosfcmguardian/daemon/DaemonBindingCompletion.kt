package io.github.originosfcmguardian.daemon

import java.util.concurrent.atomic.AtomicBoolean

/** Exactly one pending-bind completion; late connected/dead/null callbacks never resume twice.
 * Cancellation retires only the waiter. The caller separately retains/detaches a real daemon;
 * this gate owns no service-removal operation and cannot release remote execution authority. */
internal class DaemonBindingCompletion<T>(private val deliver: (Result<T>) -> Unit) {
    private val settled = AtomicBoolean()
    fun connected(value: T) = finish(Result.success(value))
    fun failed(failure: Throwable) = finish(Result.failure(failure))
    fun cancel() { settled.set(true) }
    private fun finish(value: Result<T>) {
        if (settled.compareAndSet(false, true)) deliver(value)
    }
}
