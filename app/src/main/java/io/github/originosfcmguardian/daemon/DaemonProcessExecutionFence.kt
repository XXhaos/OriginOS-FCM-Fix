package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.privileged.PrivilegedExecutionFence
import io.github.originosfcmguardian.privileged.PrivilegedExecutionSettlement
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Real runner/owner adapter, not an App Binder callback. Domain code settles its journal/result
 * before calling completeWhenSafe. A timed-out caller cannot release an actually running worker.
 * UNKNOWN with a retained child may be reaped on the EXISTING host poll, without a new timer.
 */
internal class DaemonProcessExecutionFence(private val owner: DaemonOwnerControl,
    private val ticket: DaemonWriteTicket) : PrivilegedExecutionFence {
    private val permit = AtomicReference<DaemonPreparedWrite?>()
    private val child = AtomicReference<Process?>()
    private val settlement = AtomicReference<PrivilegedExecutionSettlement?>()
    // 0 open, 1 releasing the kernel ticket, 2 release completed.
    private val completion = AtomicInteger(0)

    override fun prepare(): Boolean {
        if (completion.get() != 0 || settlement.get() != null) return false
        val fresh = owner.prepareWrite(ticket) ?: return false
        return permit.compareAndSet(null, fresh)
    }

    override fun start(launch: () -> Process): Process? {
        if (completion.get() != 0 || settlement.get() != null) return null
        val fresh = permit.getAndSet(null) ?: return null
        var process: Process? = null
        val started = owner.startPrepared(fresh) {
            process = launch().also { child.set(it) }
        }
        return if (started) process else null
    }

    override fun workerSettled(value: PrivilegedExecutionSettlement) {
        // Only the actual worker (or a never-started task CAS) calls this, exactly once.
        settlement.compareAndSet(null, value)
    }

    /** False retains global single-flight admission, including no-handle ambiguous launches. */
    fun completeWhenSafe(): Boolean {
        if (completion.get() == 2) return true
        if (completion.get() == 1) return false
        val actual = settlement.get() ?: return false
        if (actual == PrivilegedExecutionSettlement.UNKNOWN &&
            child.get()?.let { runCatching { !it.isAlive }.getOrDefault(false) } != true) return false
        if (!completion.compareAndSet(0, 1)) return completion.get() == 2
        return try {
            owner.complete(ticket) // A stale/OFF completion may release itself, never re-enable it.
            permit.set(null)
            completion.set(2)
            true
        } catch (_: Exception) { completion.set(0); false }
    }

    fun isQuiescent(): Boolean = completion.get() == 2
}
