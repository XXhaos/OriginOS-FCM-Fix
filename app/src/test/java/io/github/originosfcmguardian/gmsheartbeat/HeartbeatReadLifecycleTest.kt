package io.github.originosfcmguardian.gmsheartbeat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HeartbeatReadLifecycleTest {
    @Test fun successfulAndRejectedReadRetireDedicatedReaderExactlyOnceBeforeReturning() = runBlocking {
        for (result in listOf("a".repeat(64), null)) {
            val events = mutableListOf<String>()
            val digest = withHeartbeatReadCleanup({ events.add("retire-reader") }) {
                events.add("read"); result
            }
            events.add("return")
            assertEquals(result, digest)
            assertEquals(listOf("read", "retire-reader", "return"), events)
        }
    }

    @Test fun exceptionRetiresOnlyReaderAndPreservesFailure() = runBlocking {
        var readerRetires = 0
        val original = IllegalStateException("bounded read failed")
        val caught = runCatching {
            withHeartbeatReadCleanup({ readerRetires++ }) { throw original }
        }.exceptionOrNull()
        assertSame(original, caught); assertEquals(1, readerRetires)
    }

    @Test fun cancellationRetiresOnlyReaderAndDoesNotTurnUnknownIntoSuccess() = runBlocking {
        var readerRetires = 0
        val original = CancellationException("host ended")
        val caught = runCatching {
            withHeartbeatReadCleanup({ readerRetires++ }) { throw original }
        }.exceptionOrNull()
        assertSame(original, caught); assertEquals(1, readerRetires)
    }
}
