package io.github.originosfcmguardian.doze

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One serial getter lane. An uncertain exit permanently closes this lane and its owning session. */
internal class DozeEffectiveOwnerGetterCalls(
    private val requireActive: () -> Unit,
    private val remainingMillis: () -> Long,
    private val onUncertainExit: () -> Unit,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "guardian-effective-owner-getter").apply { isDaemon = true }
    }
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val occupied = AtomicBoolean(false)
    private val inFlight = AtomicInteger()
    val uncertainExit: Boolean get() = terminal.get() || inFlight.get() != 0

    fun <T> read(action: () -> T): T {
        requireActive()
        check(!closed.get() && !terminal.get() && occupied.compareAndSet(false, true))
        try {
            val budget = minOf(6_000L, remainingMillis())
            if (budget <= 0) throw TimeoutException()
            val result = worker.submit(Callable {
                inFlight.incrementAndGet()
                try { requireActive(); action() } finally { inFlight.decrementAndGet() }
            }).get(budget, TimeUnit.MILLISECONDS)
            requireActive()
            return result
        } catch (failure: TimeoutException) {
            terminal.set(true)
            onUncertainExit()
            throw failure
        } catch (failure: InterruptedException) {
            terminal.set(true)
            onUncertainExit()
            Thread.currentThread().interrupt()
            throw failure
        } finally {
            // Real action return is required; timeout/cancellation is never a release signal.
            if (!terminal.get() && inFlight.get() == 0) occupied.set(false)
        }
    }
    override fun close() { closed.set(true); worker.shutdownNow() }
}
