package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DirectGmsConnectionReadTest {
    @Test fun completedReadRetainsTheExecutorResultIncludingUnknown() = runBlocking {
        val unknown = GmsConnectionObservation.Unknown(123, GmsUnknown.SHELL_UNAVAILABLE)
        assertSame(unknown, readDirectGmsConnection(1) { unknown })
    }

    @Test fun bridgeExceptionFinishesWithTypedUnknown() = runBlocking {
        val result = readDirectGmsConnection(7) { error("bridge failed") }
        assertEquals(GmsConnectionObservation.Unknown(7, GmsUnknown.BRIDGE_UNAVAILABLE), result)
    }

    @Test fun suspendedReadHasFiniteDeadline() = runBlocking {
        val result = readDirectGmsConnection(8, timeoutMillis = 20) { awaitCancellation() }
        assertEquals(GmsConnectionObservation.Unknown(8, GmsUnknown.TIMED_OUT), result)
    }

    @Test fun cancellationNeverTurnsIntoACompletedObservation() = runBlocking {
        var published = false
        var finished = false
        val job = async {
            try {
                readDirectGmsConnection(9) { awaitCancellation() }
                published = true
            } finally { finished = true }
        }
        delay(10)
        job.cancelAndJoin()
        assertFalse(published)
        assertTrue(finished)
    }

    @Test fun identicalInitialNetworkCallbacksPreserveTheSample() {
        val key = "41:true:false:false:true"
        assertFalse(gmsReadNetworkInvalidated(key, 41))
        assertFalse(gmsReadNetworkInvalidated(key, 41, eventKey = key))
        assertFalse(gmsReadNetworkInvalidated(null, 41, eventKey = key))
    }

    @Test fun networkLossAndCapabilityChangesInvalidateEvenWithoutModalVisibility() {
        val key = "41:true:false:false:true"
        assertTrue(gmsReadNetworkInvalidated(key, 41, lost = true))
        assertTrue(gmsReadNetworkInvalidated(key, 42))
        assertTrue(gmsReadNetworkInvalidated(key, 41, eventKey = "41:true:false:true:true"))
        assertTrue(gmsReadNetworkInvalidated(key, 41, eventKey = "41:true:false:false:false"))
        assertTrue(gmsReadNetworkInvalidated("unknown", 41))
    }

    @Test fun oldNetworkLossCannotInvalidateANewNetworkRead() {
        assertFalse(gmsReadNetworkInvalidated("42:false:true:false:true", 41, lost = true))
    }
}
