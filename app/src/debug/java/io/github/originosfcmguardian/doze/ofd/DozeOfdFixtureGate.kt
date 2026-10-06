package io.github.originosfcmguardian.doze.ofd

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** UNKNOWN is sticky in this process, including a late successful RPC or cleanup. */
internal class DozeOfdFixtureGate {
    private val occupied = AtomicBoolean(false)
    private val unknown = AtomicBoolean(false)
    private val actions = AtomicInteger()
    fun enter() = !unknown.get() && occupied.compareAndSet(false, true)
    fun markUnknown() { unknown.set(true) }
    fun isUnknown() = unknown.get()
    fun finish(cleanup: Boolean): Boolean {
        val confirmed = cleanup && actions.get() == 0
        if (!confirmed || unknown.get()) unknown.set(true) else occupied.set(false)
        return confirmed
    }
    fun <T> observe(action: () -> T): T {
        actions.incrementAndGet()
        try { return action() } finally { actions.decrementAndGet() }
    }
    fun <T> rpc(deadline: Long, action: () -> T): T {
        check(occupied.get() && !unknown.get())
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "guardian-ofd-rpc").apply { isDaemon = true } }
        try {
            val left = minOf(6_000L, remaining(deadline))
            check(left > 0)
            // Reserve before scheduling: a timed-out action that has not entered yet is not exited.
            actions.incrementAndGet()
            return worker.submit(Callable {
                try { check(!unknown.get()); action() }
                finally { actions.decrementAndGet() }
            }).get(left, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            markUnknown()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        } finally {
            // Interrupt is a request only; observe's real finally determines completion.
            worker.shutdownNow()
        }
    }
    companion object {
        fun remaining(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
    }
}
