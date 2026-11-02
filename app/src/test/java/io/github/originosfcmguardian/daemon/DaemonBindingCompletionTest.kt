package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class DaemonBindingCompletionTest {
    @Test fun coldDisconnectNullBindingOrFailureCompletesWithoutWaitingForTimeout() {
        for (reason in listOf(DaemonAdoptionReason.BIND_DISCONNECTED, DaemonAdoptionReason.BIND_FAILED,
            DaemonAdoptionReason.DEAD_BINDER)) {
            val results = mutableListOf<Result<String>>()
            val completion = DaemonBindingCompletion<String> { results += it }
            val failure = DaemonAdoptionException(DaemonAdoptionStage.BIND, reason)
            completion.failed(failure)
            completion.connected("late endpoint")
            completion.failed(failure)
            assertEquals(1, results.size)
            assertSame(failure, results.single().exceptionOrNull())
        }
    }

    @Test fun timeoutOrParentCancellationRetiresOnlyThePendingWaiter() {
        val results = mutableListOf<Result<String>>()
        val completion = DaemonBindingCompletion<String> { results += it }
        completion.cancel()
        completion.connected("daemon remains independently alive")
        completion.failed(IllegalStateException())
        assertTrue(results.isEmpty())
    }

    @Test fun connectedAndDisconnectRacingPublishOnlyOneOutcome() {
        repeat(100) {
            val results = Collections.synchronizedList(mutableListOf<Result<String>>())
            val completion = DaemonBindingCompletion<String> { results += it }
            val start = CountDownLatch(1)
            val connected = Thread { start.await(); completion.connected("endpoint") }
            val disconnected = Thread { start.await(); completion.failed(IllegalStateException()) }
            connected.start(); disconnected.start(); start.countDown()
            connected.join(); disconnected.join()
            assertEquals(1, results.size)
        }
    }
}
