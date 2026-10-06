package io.github.originosfcmguardian.recovery

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Binding-only port. It has no command, target, consent or journal API. */
internal interface RecoveryBindingPort<T : Any> {
    fun checkReady()
    fun isCurrent(): Boolean = true
    fun start(connected: (T) -> Unit, disconnected: () -> Unit)
    /** Local memory only, before scheduling any potentially blocked cleanup. */
    fun invalidate() {}
    fun disposeEndpoint(endpoint: T)
    fun unbind()
}

/** One immutable binding; cancellation wins before cleanup and late callbacks cannot publish. */
internal class RecoveryBindingLifecycle<T : Any>(
    private val port: RecoveryBindingPort<T>,
    private val localActive: () -> Boolean,
    private val authorityActive: () -> Boolean,
    private val invalidated: () -> Unit,
    private val cleanup: (() -> Unit) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private val connected = CountDownLatch(1)
    private var endpoint: T? = null
    private var started = false
    private var closed = false

    fun isCurrent(): Boolean = port.isCurrent()

    fun bind(): T {
        check(authorityActive())
        port.checkReady()
        check(authorityActive())
        synchronized(lock) { check(!started && !closed); started = true }
        try {
            port.start(::publish) { invalidated(); close() }
            val deadline = nowNanos() + TimeUnit.SECONDS.toNanos(4)
            while (!connected.await(50, TimeUnit.MILLISECONDS)) {
                check(authorityActive() && nowNanos() < deadline)
            }
            check(authorityActive())
            return synchronized(lock) { check(!closed); requireNotNull(endpoint) }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun publish(candidate: T) {
        val accepted = synchronized(lock) {
            when {
                closed || !localActive() -> false
                endpoint === candidate -> true
                endpoint != null -> false
                else -> { endpoint = candidate; true }
            }
        }
        if (!accepted || !localActive()) { invalidated(); close() }
        if (!accepted) enqueue { port.disposeEndpoint(candidate) }
        connected.countDown()
    }

    override fun close() {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            val remote = endpoint; endpoint = null
            remote to started
        }
        connected.countDown()
        port.invalidate()
        if (state.second) enqueue {
            state.first?.let { runCatching { port.disposeEndpoint(it) } }
            runCatching { port.unbind() }
        }
    }

    private fun enqueue(action: () -> Unit) {
        // A stuck cleanup occupies its single worker. Server lifetime still has its 45-second cap.
        runCatching { cleanup(action) }
    }
}

/** A platform registration may return after cancellation; retire that exact registration then. */
internal fun completeRecoveryRegistration(stopped: () -> Boolean, register: () -> Unit, retire: () -> Unit) {
    check(!stopped())
    register()
    if (stopped()) retire()
}
