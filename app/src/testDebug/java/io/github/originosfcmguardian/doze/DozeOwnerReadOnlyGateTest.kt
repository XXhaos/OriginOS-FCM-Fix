package io.github.originosfcmguardian.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerReadOnlyGateTest {
    @Test fun completedActionsAndConfirmedCleanupAllowTheNextRead() {
        val gate = DozeOwnerReadOnlyGate()
        assertTrue(gate.tryEnter())
        assertEquals("ready", gate.bounded(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) { "ready" })
        gate.trackCleanup { }
        gate.finish(true)
        assertTrue(gate.tryEnter())
        gate.finish(true)
    }

    @Test fun stuckAvailabilityReturnsPubliclyWithoutBindingAndKeepsEntranceTerminal() {
        val gate = DozeOwnerReadOnlyGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actuallyExited = CountDownLatch(1)
        val bindings = AtomicInteger()
        assertTrue(gate.tryEnter())
        val public = Executors.newSingleThreadExecutor()
        try {
            val result = public.submit<Boolean> {
                try {
                    gate.bounded(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)) {
                        entered.countDown()
                        awaitIgnoringInterrupt(release)
                        actuallyExited.countDown()
                        true
                    }
                    bindings.incrementAndGet()
                    true
                } catch (_: TimeoutException) { false }
                finally { gate.finish(true) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, bindings.get())
            assertFalse(gate.tryEnter())
            release.countDown()
            assertTrue(actuallyExited.await(2, TimeUnit.SECONDS))
            gate.finish(true)
            assertFalse(gate.tryEnter()) // Late return does not reopen this process's uncertain gate.
        } finally { release.countDown(); public.shutdownNow() }
    }

    @Test fun unconfirmedCleanupKeepsAdmissionClosed() {
        val gate = DozeOwnerReadOnlyGate()
        assertTrue(gate.tryEnter())
        gate.finish(false)
        assertFalse(gate.tryEnter())
        gate.finish(true)
        assertFalse(gate.tryEnter())
    }

    @Test fun realInFlightActionPreventsReleaseEvenWithClaimedCleanupSuccess() {
        val gate = DozeOwnerReadOnlyGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actualExit = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        assertTrue(gate.tryEnter())
        try {
            worker.submit {
                gate.trackCleanup { entered.countDown(); awaitIgnoringInterrupt(release) }
                actualExit.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.finish(true)
            assertFalse(gate.tryEnter())
            release.countDown()
            assertTrue(actualExit.await(2, TimeUnit.SECONDS))
            assertFalse(gate.tryEnter())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun interruptedBoundedCallerKeepsGateTerminalAndRestoresInterrupt() {
        val gate = DozeOwnerReadOnlyGate()
        assertTrue(gate.tryEnter())
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                gate.bounded(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)) { CountDownLatch(1).await() }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            gate.finish(true)
            assertFalse(gate.tryEnter())
        } finally { Thread.interrupted() }
    }

    @Test fun expiredDeadlineDoesNotDispatchAndKeepsAdmissionTerminal() {
        val gate = DozeOwnerReadOnlyGate()
        val calls = AtomicInteger()
        assertTrue(gate.tryEnter())
        assertThrows(TimeoutException::class.java) {
            gate.bounded(System.nanoTime() - 1) { calls.incrementAndGet() }
        }
        gate.finish(true)
        assertEquals(0, calls.get())
        assertFalse(gate.tryEnter())
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { /* Deliberate Binder-like stall. */ }
    }
}
