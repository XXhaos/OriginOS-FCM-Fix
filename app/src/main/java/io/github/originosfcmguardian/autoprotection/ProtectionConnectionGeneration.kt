package io.github.originosfcmguardian.autoprotection

import java.util.concurrent.atomic.AtomicLong

/** Old reads/reservations can never acquire write authority from a replacement Binder. */
internal class ProtectionConnectionGeneration {
    private val value = AtomicLong(0)
    fun current(): Long = value.get()
    fun changed() { value.incrementAndGet() }
    fun matches(captured: Long): Boolean = current() == captured
    fun authorize(captured: Long, check: () -> Boolean): Boolean =
        matches(captured) && check() && matches(captured)
}
