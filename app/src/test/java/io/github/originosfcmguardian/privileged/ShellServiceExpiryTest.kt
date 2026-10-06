package io.github.originosfcmguardian.privileged

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Shared stopped-recovery lifetime guard retained after standalone Doze removal. */
class ShellServiceExpiryTest {
    @Test fun shellServiceExpiryIsOneShotAndExplicitDestroyCancelsTimer() {
        val scheduler = FakeExpiryScheduler()
        val exits = AtomicInteger()
        val expiry = ShellServiceExpiry(2_000, { exits.incrementAndGet() }, scheduler)
        assertEquals(1, scheduler.scheduled)
        assertEquals(65_000L, scheduler.delay)
        scheduler.fire()
        scheduler.fire()
        assertEquals(1, exits.get())
        assertEquals(1, scheduler.cancelled)
        assertFalse(expiry.terminateNow())

        val second = FakeExpiryScheduler()
        val secondExits = AtomicInteger()
        val explicit = ShellServiceExpiry(2_000, { secondExits.incrementAndGet() }, second)
        assertTrue(explicit.terminateNow())
        second.fire()
        assertEquals(1, secondExits.get())
        assertEquals(1, second.cancelled)

        val nonShell = FakeExpiryScheduler()
        val rejected = ShellServiceExpiry(10_000, { error("App process must never exit") }, nonShell)
        assertFalse(rejected.terminateNow())
        assertEquals(0, nonShell.scheduled)
    }

    private class FakeExpiryScheduler : ShellExpiryScheduler {
        var scheduled = 0
        var cancelled = 0
        var delay = 0L
        private var action: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, action: () -> Unit): ShellExpiryCancellation {
            scheduled++
            delay = delayMillis
            this.action = action
            return ShellExpiryCancellation { cancelled++ }
        }
        fun fire() { requireNotNull(action).invoke() }
    }
}
