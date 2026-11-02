package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.core.gms.GmsUnknown
import org.junit.Assert.*
import org.junit.Test

class DaemonFcmPreparationTest {
    @Test fun authenticatedTypedNotStagedCarriesOnlyFixedPreparationAndReadReasons() {
        val decoded = requireNotNull(decodeDaemonFcmNotStaged("FCM_NOT_STAGED", "READ_UNAVAILABLE", "TIMED_OUT"))
        assertEquals(DaemonFcmPreparationReason.READ_UNAVAILABLE, decoded.reason)
        assertEquals(GmsUnknown.TIMED_OUT, decoded.readUnknown)
        assertEquals(DaemonFcmPreparationReason.FCM_BUSY,
            decodeDaemonFcmNotStaged("FCM_NOT_STAGED", "FCM_BUSY", null)?.reason)
    }

    @Test fun legacyGenericMalformedAndWrongStageReasonsNeverBecomeNoStageProof() {
        for (error in listOf(null, "NOT_READY", "BUSY", "UNAVAILABLE"))
            assertNull(decodeDaemonFcmNotStaged(error, "READ_UNAVAILABLE", "TIMED_OUT"))
        for (reason in listOf(null, "private arbitrary payload", "CLIENT_BUSY", "PREPARATION_UNAVAILABLE", "NOT_REMOTE"))
            assertNull(decodeDaemonFcmNotStaged("FCM_NOT_STAGED", reason, null))
        assertNull(decodeDaemonFcmNotStaged("FCM_NOT_STAGED", "FCM_BUSY", "TIMED_OUT"))
        assertNull(decodeDaemonFcmNotStaged("FCM_NOT_STAGED", "READ_UNAVAILABLE", "private arbitrary payload"))
    }
}
