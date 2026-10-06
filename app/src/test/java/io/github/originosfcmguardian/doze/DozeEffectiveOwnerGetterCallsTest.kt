package io.github.originosfcmguardian.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DozeEffectiveOwnerGetterCallsTest {
    @Test fun returnedGettersAllowNextSerialRead() {
        DozeEffectiveOwnerGetterCalls({}, { 2_000 }, { error("unexpected uncertain exit") }).use { lane ->
            assertEquals(1, lane.read { 1 })
            assertEquals(2, lane.read { 2 })
            assertFalse(lane.uncertainExit)
        }
    }
    @Test fun stalledBinderCannotReleaseAdmissionOrAdmitAnotherGetterAfterLateReturn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val calls = AtomicInteger()
        val lane = DozeEffectiveOwnerGetterCalls({}, { 100 }, { cancelled.incrementAndGet() })
        try {
            assertThrows(TimeoutException::class.java) {
                lane.read { calls.incrementAndGet(); entered.countDown(); awaitIgnoringInterrupt(release); exited.countDown() }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(1, cancelled.get())
            assertTrue(lane.uncertainExit)
            assertThrows(IllegalStateException::class.java) { lane.read { calls.incrementAndGet() } }
            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { lane.read { calls.incrementAndGet() } }
            assertEquals(1, calls.get())
        } finally { release.countDown(); lane.close() }
    }
    @Test fun expiredBudgetNeverDispatchesAndClosesSession() {
        val cancelled = AtomicInteger()
        DozeEffectiveOwnerGetterCalls({}, { 0 }, { cancelled.incrementAndGet() }).use { lane ->
            assertThrows(TimeoutException::class.java) { lane.read { error("must not dispatch") } }
            assertEquals(1, cancelled.get())
            assertTrue(lane.uncertainExit)
        }
    }
    @Test fun completedExceptionalGetterHasNoUnreturnedBinderWork() {
        DozeEffectiveOwnerGetterCalls({}, { 2_000 }, { error("unexpected uncertain exit") }).use { lane ->
            assertThrows(ExecutionException::class.java) { lane.read { error("private exception") } }
            assertFalse(lane.uncertainExit)
        }
    }
    @Test fun interruptionMakesAdmissionTerminalAndRestoresFlag() {
        val cancelled = AtomicInteger()
        DozeEffectiveOwnerGetterCalls({}, { 2_000 }, { cancelled.incrementAndGet() }).use { lane ->
            Thread.currentThread().interrupt()
            try {
                assertThrows(InterruptedException::class.java) { lane.read { CountDownLatch(1).await() } }
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(lane.uncertainExit)
                assertEquals(1, cancelled.get())
            } finally { Thread.interrupted() }
        }
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { }
    }
}
