package io.github.originosfcmguardian.privileged

import android.os.IBinder
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class GmsAutoRemoteOwnerTest {
    private class Endpoint {
        var death: IBinder.DeathRecipient? = null
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, args ->
            when (method.name) {
                "linkToDeath" -> { death = args!![0] as IBinder.DeathRecipient; null }
                "unlinkToDeath" -> { death = null; true }
                "pingBinder", "isBinderAlive" -> false // Neither is used as a stop acknowledgement.
                else -> null
            }
        } as IBinder
        val remote = Proxy.newProxyInstance(IGuardianUserService::class.java.classLoader,
            arrayOf(IGuardianUserService::class.java)) { _, method, _ ->
            if (method.name == "asBinder") binder else error("Unexpected remote call")
        } as IGuardianUserService
    }
    @Test fun uncertainArmAndFailedRevokeRetainOriginalEndpointAndBlockReplacement() {
        val endpoint = Endpoint()
        val owner = GmsAutoRemoteOwner(endpoint.remote, 1)
        owner.invoked.set(true) // Arm may have minted a capability, but its response was lost.
        assertSame(endpoint.remote, owner.remote)
        assertFalse(mayArmGmsHeartbeat(listOf(owner)))
        assertFalse(mayBindManualGmsHeartbeat(listOf(owner)))
        assertFalse(endpoint.binder.pingBinder())
        assertFalse(owner.dead.get())
        assertFalse(owner.confirmRevoked(false))
        assertFalse(mayArmGmsHeartbeat(listOf(owner)))
        assertFalse(mayBindManualGmsHeartbeat(listOf(owner)))
        endpoint.death!!.binderDied()
        assertTrue(owner.dead.get())
        assertTrue(mayArmGmsHeartbeat(listOf(owner)))
        assertTrue(mayBindManualGmsHeartbeat(listOf(owner)))
    }
    @Test fun acknowledgedGateClosureAllowsReplacementWithoutClaimingProcessDeath() {
        val owner = GmsAutoRemoteOwner(Endpoint().remote, 2)
        assertTrue(owner.confirmRevoked(true))
        assertFalse(owner.dead.get())
        assertTrue(mayArmGmsHeartbeat(listOf(owner)))
        assertTrue(mayBindManualGmsHeartbeat(listOf(owner)))
    }
    @Test fun disablingLocalAdmissionIsNotAcknowledgedRemoteRevocation() {
        val owner = GmsAutoRemoteOwner(Endpoint().remote, 3)
        assertFalse(owner.unavailable())
        owner.disabled.set(true) // OFF / UNKNOWN while the original endpoint still owns the gate.
        assertTrue(owner.unavailable())
        assertFalse(mayBindManualGmsHeartbeat(listOf(owner)))
        assertFalse(mayArmGmsHeartbeat(listOf(owner)))
        assertTrue(owner.confirmRevoked(true))
        assertTrue(mayBindManualGmsHeartbeat(listOf(owner)))
    }
    @Test fun manualUnknownSignalDoesNotReplayOrCarryResultPayload() {
        val baseline = GmsManualHeartbeatEvents.unknownSerial.value
        GmsManualHeartbeatEvents.happened()
        assertEquals(baseline, GmsManualHeartbeatEvents.unknownSerial.value)
        GmsManualHeartbeatEvents.unknown()
        assertEquals(baseline + 1, GmsManualHeartbeatEvents.unknownSerial.value)
    }
}
