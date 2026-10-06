package io.github.originosfcmguardian.doze.cyclebroker

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Exercises the production facade boundary; not Android initialization or device evidence. */
class GmsDozeCycleAutomaticRuntimeFailureGateTest {
    @Test fun ordinaryFacadeFailureBlocksWithoutClosingConfigurationOrCancellingSettlementAndFatalErrorsEscape() {
        val state = MutableStateFlow(GmsDozeCycleAutomaticUiState(loaded = true, configured = true,
            busy = true, eligible = true, nextAttemptAtElapsedMillis = 900000))
        val ownClosures = AtomicInteger()
        val closed = AtomicBoolean()
        val gate = GmsDozeCycleAutomaticRuntimeFailureGate(state) {
            if (closed.compareAndSet(false, true)) ownClosures.incrementAndGet()
        }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val settlement = thread {
            gate.invoke { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
            gate.publish(GmsDozeCycleAutomaticUiState(loaded = true, configured = false, busy = false,
                eligible = true, blocked = false, detail = "Late collector must not replace failure"))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        gate.invoke { throw IllegalStateException("Canonical/store initialization failed") }
        assertEquals(1, ownClosures.get())
        assertTrue(state.value.configured)
        assertTrue(state.value.blocked)
        assertTrue(state.value.busy)
        assertFalse(state.value.eligible)
        assertNull(state.value.confirmationTarget)
        assertEquals("BLOCKED_RUNTIME", state.value.lastOutcome)
        gate.invoke { fail("No restoration/retry after local subsystem failure") }
        finish.countDown(); settlement.join(5000)
        assertFalse(settlement.isAlive)
        assertFalse(state.value.busy)
        assertTrue(state.value.configured)
        assertTrue(state.value.blocked)
        assertFalse(state.value.eligible)
        assertEquals("BLOCKED_RUNTIME", state.value.lastOutcome)
        assertEquals(1, ownClosures.get())

        val absent = MutableStateFlow(GmsDozeCycleAutomaticUiState())
        val brokenClose = GmsDozeCycleAutomaticRuntimeFailureGate(absent) { throw IllegalStateException("Partial owner") }
        brokenClose.invoke { throw IllegalArgumentException("Before owner exists") }
        assertTrue(absent.value.blocked)
        assertFalse(absent.value.configured)
        val fatal = AssertionError("Fatal is not an ordinary initialization failure")
        try {
            GmsDozeCycleAutomaticRuntimeFailureGate(MutableStateFlow(GmsDozeCycleAutomaticUiState())) {}.invoke { throw fatal }
            fail("Fatal error was swallowed")
        } catch (caught: AssertionError) { assertSame(fatal, caught) }
    }
}
