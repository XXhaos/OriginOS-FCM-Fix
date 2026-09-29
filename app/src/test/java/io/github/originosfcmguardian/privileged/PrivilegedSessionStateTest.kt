package io.github.originosfcmguardian.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedSessionStateTest {
    @Test
    fun independentExecutorNamespacesCannotShareServiceIdentity() {
        val first = userServiceIdentity("0123456789abcdef", 2)
        val second = userServiceIdentity("fedcba9876543210", 2)

        assertNotEquals(first.tag, second.tag)
        assertNotEquals(first.processSuffix, second.processSuffix)
    }

    @Test
    fun closeDuringBindRejectsLateConnection() {
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        val ticket = requireNotNull(state.beginBind())
        val connection = Any()
        assertTrue(state.attachConnection(ticket, connection))

        val teardown = state.close()

        assertEquals(connection, teardown.pendingConnection)
        assertFalse(state.acceptBind(ticket, connection, "late remote"))
        assertFalse(state.isOpen())
    }

    @Test
    fun timeoutRetiresGenerationAndRejectsLateConnection() {
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        val ticket = requireNotNull(state.beginBind())
        val connection = Any()
        assertTrue(state.attachConnection(ticket, connection))

        assertEquals(connection, state.retireBind(ticket))

        assertFalse(state.acceptBind(ticket, connection, "late remote"))
        assertNull(state.currentBound())
        assertTrue(state.isOpen())
    }

    @Test
    fun closeBetweenCommandsPreventsAnotherCallOrBind() {
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        val ticket = requireNotNull(state.beginBind())
        val connection = Any()
        assertTrue(state.attachConnection(ticket, connection))
        assertTrue(state.acceptBind(ticket, connection, "remote"))
        val bound = requireNotNull(state.currentBound())
        assertTrue(state.beginCall(bound, 1))
        state.finishCall(1)

        state.close()

        assertFalse(state.beginCall(bound, 2))
        assertNull(state.beginBind())
    }

    @Test
    fun ordinaryCleanupAllowsFreshDetectionBind() {
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        val firstTicket = requireNotNull(state.beginBind())
        val firstConnection = Any()
        assertTrue(state.attachConnection(firstTicket, firstConnection))
        assertTrue(state.acceptBind(firstTicket, firstConnection, "first"))

        val teardown = state.cleanup()

        assertEquals("first", teardown.bound?.remote)
        assertTrue(state.isOpen())
        val secondTicket = requireNotNull(state.beginBind())
        val secondConnection = Any()
        assertTrue(state.attachConnection(secondTicket, secondConnection))
        assertTrue(state.acceptBind(secondTicket, secondConnection, "second"))
        assertEquals("second", state.currentBound()?.remote)
    }
}
