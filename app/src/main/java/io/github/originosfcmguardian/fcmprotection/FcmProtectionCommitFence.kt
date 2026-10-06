package io.github.originosfcmguardian.fcmprotection

import java.util.concurrent.atomic.AtomicLong

/** Exact coordinator monitor seam: OFF and both consent commits share this linearization point. */
internal class FcmProtectionCommitFence(private val monitor: Any) {
    private val value = AtomicLong(0)
    fun get() = value.get()
    fun incrementAndGet(): Long = synchronized(monitor) { value.incrementAndGet() }
    fun commit(expected: Long, eligible: () -> Boolean, write: () -> Unit): Boolean = synchronized(monitor) {
        if (value.get() != expected || !eligible()) false else { write(); true }
    }
}
