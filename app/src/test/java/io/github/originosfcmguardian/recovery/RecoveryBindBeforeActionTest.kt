package io.github.originosfcmguardian.recovery

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RecoveryBindBeforeActionTest {
    private val blocked = StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "unavailable")

    @Test fun `bind exception has local marker and never enters action`() {
        var actions = 0
        val result = bindBeforeRecoveryAction<Int>(
            bind = { throw IllegalStateException("bind failed") },
            fallback = { blocked }, action = { actions++; blocked })
        assertTrue(result.bindingFailedBeforeAction)
        assertEquals(0, actions)
        assertFalse(result.commandSucceeded)
        assertFalse(result.admissionBusy)
        assertFalse(RecoveryResultCodec.decode(RecoveryResultCodec.encode(result)).bindingFailedBeforeAction)
    }

    @Test fun `successful bind runs action exactly once and propagates its result without marker`() {
        var actions = 0
        val result = bindBeforeRecoveryAction(bind = { 2000 }, fallback = { blocked }) {
            actions++; assertEquals(2000, it); blocked
        }
        assertEquals(1, actions)
        assertFalse(result.bindingFailedBeforeAction)
    }

    @Test fun `uid validation and action exceptions remain outside bind classification`() {
        var fallbacks = 0
        for (uid in listOf(0, 2000)) {
            assertThrows(IllegalStateException::class.java) {
                bindBeforeRecoveryAction(bind = { uid }, fallback = { fallbacks++; blocked }) {
                    check(it == 2000); error("action failure")
                }
            }
        }
        assertEquals(0, fallbacks)
    }

    @Test fun `bind cancellation and fatal failure do not produce marked fallback`() {
        var fallbacks = 0
        assertThrows(CancellationException::class.java) {
            bindBeforeRecoveryAction<Int>(bind = { throw CancellationException("timeout or stop") },
                fallback = { fallbacks++; blocked }, action = { blocked })
        }
        assertThrows(AssertionError::class.java) {
            bindBeforeRecoveryAction<Int>(bind = { throw AssertionError("fatal") },
                fallback = { fallbacks++; blocked }, action = { blocked })
        }
        assertEquals(0, fallbacks)
    }
}
