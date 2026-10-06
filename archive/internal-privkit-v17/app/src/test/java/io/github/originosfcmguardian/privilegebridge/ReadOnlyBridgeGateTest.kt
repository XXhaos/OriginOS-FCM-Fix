package io.github.originosfcmguardian.privilegebridge

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyBridgeGateTest {
    @Test fun shellAndUserZeroOwnerOnly() {
        for (uid in listOf(0, 1000, 2001)) assertNull(ReadOnlyBridgeGate().begin(Any(), uid, 10362))
        for (owner in listOf(0, 2000, 9999, 110362)) assertNull(ReadOnlyBridgeGate().begin(Any(), 2000, owner))
    }

    @Test fun responseRequiresExactServerGenerationAndUser() {
        val gate = ReadOnlyBridgeGate(); val server = Any(); val ticket = gate.begin(server, 2000, 10362)!!
        assertTrue(gate.accepts(ticket, server, 2000, 0, ticket.generation))
        assertFalse(gate.accepts(ticket, Any(), 2000, 0, ticket.generation))
        assertFalse(gate.accepts(ticket, server, 0, 0, ticket.generation))
        assertFalse(gate.accepts(ticket, server, 2000, 1, ticket.generation))
        assertFalse(gate.accepts(ticket, server, 2000, 0, ticket.generation + 1))
    }

    @Test fun deathOrCancellationDropsResultAndRetainsBusyUntilCleanup() {
        val gate = ReadOnlyBridgeGate(); val server = Any(); val ticket = gate.begin(server, 2000, 10362)!!
        gate.invalidate(ticket)
        assertFalse(gate.accepts(ticket, server, 2000, 0, ticket.generation))
        assertTrue(gate.isBusy()); assertNull(gate.begin(server, 2000, 10362))
        gate.finish(ticket); assertFalse(gate.isBusy())
    }

    @Test fun oldCleanupCannotInvalidateReplacementConnection() {
        val gate = ReadOnlyBridgeGate(); val server = Any(); val old = gate.begin(server, 2000, 10362)!!
        gate.finish(old)
        val fresh = gate.begin(server, 2000, 10362)!!
        gate.invalidate(old); gate.finish(old)
        assertTrue(gate.isBusy()); assertTrue(gate.accepts(fresh, server, 2000, 0, fresh.generation))
        assertFalse(gate.accepts(old, server, 2000, 0, old.generation))
    }

    @Test fun revokePermanentlyRejectsInFlightAndNewRead() {
        val gate = ReadOnlyBridgeGate(); val server = Any(); val ticket = gate.begin(server, 2000, 10362)!!
        gate.revoke()
        assertFalse(gate.accepts(ticket, server, 2000, 0, ticket.generation))
        assertNull(gate.begin(server, 2000, 10362)); assertTrue(gate.isRevoked())
    }

    @Test fun callerMustOwnOnlyGuardianAndBeUserZeroApp() {
        assertTrue(authenticatedGuardianCaller(10362, listOf("io.github.originosfcmguardian")))
        for (owners in listOf(null, emptyList(), listOf("other.app"),
            listOf("io.github.originosfcmguardian", "other.app"))) {
            assertFalse(authenticatedGuardianCaller(10362, owners))
        }
        assertFalse(authenticatedGuardianCaller(110362, listOf("io.github.originosfcmguardian")))
        assertFalse(authenticatedGuardianCaller(2000, listOf("io.github.originosfcmguardian")))
    }

    @Test fun explicitExternalChoiceNeverRequiresAppOwnedServer() = kotlinx.coroutines.runBlocking {
        PrivKitReadOnlyIdentityAdapter().use { adapter ->
            assertEquals(PrivilegeIdentityResult.ExternalFallbackSelected,
                adapter.readIdentity(ReadOnlyTransportChoice.EXTERNAL_SHIZUKU))
        }
    }
}
