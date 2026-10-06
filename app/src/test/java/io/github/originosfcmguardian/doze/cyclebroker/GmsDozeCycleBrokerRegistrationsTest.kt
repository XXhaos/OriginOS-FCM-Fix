package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.GmsDozeBrokerKey
import io.github.originosfcmguardian.core.doze.GmsDozeCycleAttemptRef
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeOperation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real production registration helper/host executors; death booleans are explicit host inputs,
 * not claimed Android Binder, command execution or device qualification evidence. */
class GmsDozeCycleBrokerRegistrationsTest {
    private fun ref(generation: Long) = GmsDozeCycleAttemptRef(
        "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000003", 1,
        "00000000-0000-0000-0000-000000000004", GmsGlobalDozeOperation.ADD,
        GmsDozeBrokerKey("host-registration-$generation", generation))
    private fun terminatedLanes(): List<ExecutorService> = List(5) {
        Executors.newSingleThreadExecutor().also { lane ->
            lane.submit {}.get(2, TimeUnit.SECONDS)
            lane.shutdown()
            check(lane.awaitTermination(2, TimeUnit.SECONDS) && lane.isTerminated)
        }
    }

    @Test fun moreThan129ActualRegistrationsAndCleanInertRetirementsNeverExhaustOrDeleteGrantedTombstones() {
        val registrations = GmsDozeCycleBrokerRegistrations()
        val lanes = terminatedLanes()
        val tombstone = registrations.register()
        registrations.reserveGrantPreparation(tombstone)
        registrations.pin(tombstone, ref(1))
        registrations.beginRetirement(tombstone)
        assertFalse(registrations.retireInert(tombstone, true, true, lanes))
        repeat(260) {
            val inert = registrations.register()
            registrations.beginCaller(inert)
            registrations.beginRpc(inert) // A real helper registration/completion, never a write.
            registrations.endRpc(inert)
            registrations.endCaller(inert)
            registrations.beginRetirement(inert)
            assertTrue(registrations.retireInert(inert, true, true, lanes))
            assertFalse(registrations.contains(inert))
            assertEquals(1, registrations.size())
            assertTrue(registrations.contains(tombstone))
        }
        // The 128 actual grant/nonce tombstones stay bounded and permanent; only INERT consumes
        // the final temporary slot, which can be reclaimed repeatedly without clearing authority.
        repeat(127) { index ->
            val granted = registrations.register()
            registrations.reserveGrantPreparation(granted)
            registrations.pin(granted, ref(index + 2L))
            registrations.beginRetirement(granted)
            assertFalse(registrations.retireInert(granted, true, true, lanes))
        }
        repeat(130) {
            val inert = registrations.register()
            assertEquals(129, registrations.size())
            assertThrows(Exception::class.java) { registrations.register() }
            registrations.beginRetirement(inert)
            assertTrue(registrations.retireInert(inert, true, true, lanes))
            assertEquals(128, registrations.size())
        }
        assertTrue(registrations.contains(tombstone))
    }

    @Test fun casToPinUnknownActiveRpcAndActualLaneTerminationAreDistinctFromReclaimableInertRetirement() {
        val registrations = GmsDozeCycleBrokerRegistrations()
        val terminated = terminatedLanes()
        val casToPin = registrations.register()
        registrations.reserveGrantPreparation(casToPin) // BEFORE the possibly committed grant CAS.
        registrations.beginRetirement(casToPin)
        assertFalse(registrations.retireInert(casToPin, true, true, terminated)) // ref still null.
        registrations.pin(casToPin, ref(1)) // The existing reserved CAS may return after own death.
        assertTrue(registrations.contains(casToPin))
        assertFalse(registrations.retireInert(casToPin, true, true, terminated))
        assertThrows(Exception::class.java) { registrations.pin(casToPin, ref(2)) }
        val ambiguousCas = registrations.register()
        registrations.reserveGrantPreparation(ambiguousCas)
        registrations.markUnknown(ambiguousCas)
        registrations.beginRetirement(ambiguousCas)
        assertFalse(registrations.retireInert(ambiguousCas, true, true, terminated))
        val unknownInert = registrations.register()
        registrations.markUnknown(unknownInert)
        registrations.beginRetirement(unknownInert)
        assertFalse(registrations.retireInert(unknownInert, true, true, terminated))
        val granted = registrations.register()
        registrations.reserveGrantPreparation(granted)
        registrations.pin(granted, ref(3))
        registrations.markActivation(granted)
        registrations.markStart(granted)
        registrations.beginRetirement(granted)
        assertFalse(registrations.retireInert(granted, true, true, terminated))
        val lateCaller = registrations.register()
        registrations.beginCaller(lateCaller)
        registrations.beginRpc(lateCaller)
        registrations.endRpc(lateCaller)
        registrations.beginRetirement(lateCaller)
        assertFalse(registrations.retireInert(lateCaller, true, true, terminated)) // Task/lanes done, caller postcheck pending.
        registrations.markUnknown(lateCaller) // A late caller error is recorded before its finally.
        registrations.endCaller(lateCaller)
        assertFalse(registrations.retireInert(lateCaller, true, true, terminated))

        val active = registrations.register()
        val realLanes = List(5) { Executors.newSingleThreadExecutor() }
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        registrations.beginCaller(active)
        registrations.beginRpc(active)
        val future = realLanes.first().submit {
            try { entered.countDown(); check(proceed.await(2, TimeUnit.SECONDS)) }
            finally { registrations.endRpc(active) }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            registrations.beginRetirement(active)
            assertFalse(registrations.retireInert(active, true, true, terminated)) // Active task alone blocks.
            assertFalse(registrations.retireInert(active, true, true, realLanes))
            proceed.countDown()
            future.get(2, TimeUnit.SECONDS)
            assertFalse(registrations.retireInert(active, true, true, realLanes)) // Returned != lanes terminated.
            realLanes.forEach { it.shutdown() }
            realLanes.forEach { assertTrue(it.awaitTermination(2, TimeUnit.SECONDS)) }
            assertFalse(registrations.retireInert(active, true, true, realLanes)) // Caller still owns its final checks.
            registrations.endCaller(active)
            assertFalse(registrations.retireInert(active, false, true, realLanes))
            assertFalse(registrations.retireInert(active, true, false, realLanes))
            assertTrue(registrations.retireInert(active, true, true, realLanes))
            assertThrows(Exception::class.java) { registrations.pin(active, ref(4)) }
            assertThrows(Exception::class.java) { registrations.reserveGrantPreparation(active) }
            assertThrows(Exception::class.java) { registrations.beginRpc(active) }
            assertThrows(Exception::class.java) { registrations.beginCaller(active) }
            assertThrows(Exception::class.java) { registrations.markActivation(active) }
            assertThrows(Exception::class.java) { registrations.markStart(active) }
            assertTrue(registrations.contains(casToPin))
            assertTrue(registrations.contains(ambiguousCas))
            assertTrue(registrations.contains(unknownInert))
            assertTrue(registrations.contains(granted))
            assertTrue(registrations.contains(lateCaller))
        } finally {
            proceed.countDown()
            realLanes.forEach { it.shutdownNow() }
            realLanes.forEach { it.awaitTermination(2, TimeUnit.SECONDS) }
        }
    }
}
