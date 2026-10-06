package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test

class ProtectionConnectionGenerationTest {
    @Test fun deathAndReceiptDuringDelayRequireFreshValidation() {
        val connection = ProtectionConnectionGeneration()
        val validated = connection.current()
        connection.changed() // death
        connection.changed() // replacement receipt before next poll
        assertFalse(connection.matches(validated))
        assertTrue(connection.matches(connection.current()))
    }
    @Test fun generationChangeDuringReadRejectsObservation() {
        val connection = ProtectionConnectionGeneration()
        val read = connection.current()
        assertFalse(connection.authorize(read) { connection.changed(); true })
    }
    @Test fun reservationBeforeDeathCannotAuthorizeNewRpcSession() {
        val connection = ProtectionConnectionGeneration()
        val reserved = connection.current()
        connection.changed()
        var checks = 0
        assertFalse(connection.authorize(reserved) { checks++; true })
        assertEquals(0, checks)
        val fresh = connection.current()
        assertTrue(connection.authorize(fresh) { checks++; true })
        assertEquals(1, checks)
    }
}
