package io.github.originosfcmguardian.fcm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmRegistrationRequestGateTest {
    @Test
    fun onlyOneRequestCanBeActive() {
        val gate = FcmRegistrationRequestGate()
        val first = requireNotNull(gate.tryStart())

        assertNull(gate.tryStart())
        assertTrue(gate.completeIfCurrent(first))
        assertTrue(gate.tryStart() != null)
    }

    @Test
    fun invalidationRejectsLateCompletionAndAllowsFreshRequest() {
        val gate = FcmRegistrationRequestGate()
        val cancelled = requireNotNull(gate.tryStart())

        gate.invalidate()
        val fresh = requireNotNull(gate.tryStart())

        assertNotEquals(cancelled, fresh)
        assertFalse(gate.completeIfCurrent(cancelled))
        assertTrue(gate.completeIfCurrent(fresh))
    }
}
