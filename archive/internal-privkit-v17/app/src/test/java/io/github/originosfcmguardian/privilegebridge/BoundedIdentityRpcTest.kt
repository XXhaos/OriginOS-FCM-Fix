package io.github.originosfcmguardian.privilegebridge

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BoundedIdentityRpcTest {
    @Test fun cancellationDropsLateResultAndDoesNotQueueAnotherRead() = runBlocking {
        BoundedIdentityRpc().use { rpc ->
            val started = CountDownLatch(1); val release = CountDownLatch(1); val returned = AtomicBoolean(false)
            val read = launch(Dispatchers.Default) {
                rpc.read {
                    started.countDown()
                    // A platform Binder transaction can ignore Thread interruption.
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    42
                }
                returned.set(true)
            }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                withTimeout(1_000) { read.cancelAndJoin() }
                try { rpc.read { 7 }; fail("A blocked worker must not queue a second read") }
                catch (_: RejectedExecutionException) { }
                assertFalse(returned.get())
            } finally { release.countDown() }
        }
    }

    @Test fun timeoutReturnsWhilePlatformReadStillBlocked() = runBlocking {
        BoundedIdentityRpc().use { rpc ->
            val release = CountDownLatch(1); val started = CountDownLatch(1)
            try {
                try {
                    withTimeout(200) { rpc.read {
                        started.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                        1
                    } }
                    fail("Expected timeout")
                } catch (_: TimeoutCancellationException) { }
                assertEquals(0L, started.count)
            } finally { release.countDown() }
        }
    }

    @Test fun readExceptionIsReturnedAndCloseRejectsNewRequests() = runBlocking {
        val rpc = BoundedIdentityRpc()
        try {
            try { rpc.read<Int> { throw IllegalStateException("read failed") }; fail("Expected failure") }
            catch (failure: IllegalStateException) { assertEquals("read failed", failure.message) }
        } finally { rpc.close() }
        try { rpc.read { 1 }; fail("Closed worker must reject requests") }
        catch (_: RejectedExecutionException) { }
    }
}
