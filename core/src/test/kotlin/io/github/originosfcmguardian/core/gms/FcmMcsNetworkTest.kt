package io.github.originosfcmguardian.core.gms

import kotlin.test.*

class FcmMcsNetworkTest {
    private val physical = FcmMcsNetwork(true, true, true, false)
    private val vpn = physical.copy(vpn = true)
    @Test fun residualVpnAndImsOnlyCannotQualifyButRealPhysicalReadinessCan() {
        assertFalse(usableFcmMcsNetwork(null, listOf(vpn, physical)))
        assertFalse(usableFcmMcsNetwork(vpn, listOf(vpn, physical.copy(internet = false))))
        assertFalse(usableFcmMcsNetwork(vpn, listOf(physical.copy(notSuspended = false))))
        assertFalse(usableFcmMcsNetwork(vpn, listOf(physical.copy(validated = false))))
        assertFalse(usableFcmMcsNetwork(physical.copy(notSuspended = false), listOf(physical)))
        assertTrue(usableFcmMcsNetwork(vpn, listOf(vpn, physical)))
        assertTrue(usableFcmMcsNetwork(physical, emptyList()))
    }
    @Test fun operationWireRejectsCrossBranchResultsAndKeepsLegacySenderBytes() {
        val send = FcmRecoveryResult(5, FcmRecoveryStatus.REQUESTED, FcmRecoveryReason.COMMAND_ACCEPTED)
        assertEquals("1|5|REQUESTED|COMMAND_ACCEPTED", FcmRecoveryCodec.encode(send))
        val mcs = send.copy(operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS)
        assertEquals(mcs, FcmRecoveryCodec.decode(FcmRecoveryCodec.encode(mcs), 5, mcs.operation))
        assertEquals(FcmRecoveryStatus.UNKNOWN, FcmRecoveryCodec.decode(FcmRecoveryCodec.encode(send), 5, mcs.operation).status)
        assertEquals(FcmRecoveryStatus.UNKNOWN, FcmRecoveryCodec.decode(FcmRecoveryCodec.encode(mcs), 5).status)
    }
}
