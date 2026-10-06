package io.github.originosfcmguardian.doze

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide debug admission. An uncertain Binder exit/cleanup permanently closes this process's
 * entrance, even if the ignored-interrupt action returns later; a new process is required to retry.
 */
internal class DozeOwnerReadOnlyGate(private val nanoTime: () -> Long = System::nanoTime) {
    private val occupied = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val dispatchedActions = AtomicInteger()

    fun tryEnter(): Boolean = !terminal.get() && occupied.compareAndSet(false, true)

    fun markTerminal() { terminal.set(true) }

    fun finish(cleanupConfirmed: Boolean) {
        if (!cleanupConfirmed || dispatchedActions.get() != 0 || terminal.get()) {
            terminal.set(true)
        } else occupied.set(false)
    }

    /** Counts real action finally exits. Future cancellation/completion flags are not used as proof. */
    fun <T> trackCleanup(action: () -> T): T {
        dispatchedActions.incrementAndGet()
        try { return action() }
        finally { dispatchedActions.decrementAndGet() }
    }

    fun <T> bounded(deadline: Long, action: () -> T): T {
        check(occupied.get() && !terminal.get()) { "Debug read admission is closed" }
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "guardian-owner-read-call").apply { isDaemon = true }
        }
        try {
            val remaining = (deadline - nanoTime()) / 1_000_000L
            if (remaining <= 0) throw TimeoutException()
            return worker.submit(Callable {
                trackCleanup {
                    check(!terminal.get()) { "Debug read dispatch is closed" }
                    action()
                }
            }).get(remaining, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            terminal.set(true)
            throw failure
        } catch (failure: InterruptedException) {
            terminal.set(true)
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: ExecutionException) {
            if (failure.cause is InterruptedException) terminal.set(true)
            throw failure
        } finally { worker.shutdownNow() }
    }
}
