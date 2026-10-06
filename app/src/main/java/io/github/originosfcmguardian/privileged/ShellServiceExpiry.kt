package io.github.originosfcmguardian.privileged

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One nonrenewing shell-service lifetime cap, independent of Binder calls and client cleanup. */
internal class ShellServiceExpiry(
    serviceUid: Int,
    private val onTerminate: () -> Unit,
    scheduler: ShellExpiryScheduler = RealShellExpiryScheduler,
    lifetimeMillis: Long = 65_000L,
) {
    private val shellService = serviceUid == 2_000
    private val terminated = AtomicBoolean(false)
    private val pending = if (shellService) {
        scheduler.schedule(lifetimeMillis) { terminateNow() }
    } else null

    fun terminateNow(): Boolean {
        if (!shellService || !terminated.compareAndSet(false, true)) return false
        pending?.cancel()
        onTerminate()
        return true
    }
}

internal fun interface ShellExpiryCancellation { fun cancel() }

internal fun interface ShellExpiryScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ShellExpiryCancellation
}

private object RealShellExpiryScheduler : ShellExpiryScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): ShellExpiryCancellation {
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "guardian-shell-expiry").apply { isDaemon = true }
        }
        val future = executor.schedule({
            try { action() } finally { executor.shutdownNow() }
        }, delayMillis, TimeUnit.MILLISECONDS)
        return ShellExpiryCancellation {
            future.cancel(false)
            executor.shutdownNow()
        }
    }
}
