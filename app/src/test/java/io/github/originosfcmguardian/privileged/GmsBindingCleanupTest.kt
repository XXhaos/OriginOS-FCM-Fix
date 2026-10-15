package io.github.originosfcmguardian.privileged

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GmsBindingCleanupTest {
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    private fun eventuallyAcquire(cleanup: GmsBindingCleanup): GmsBindingCleanup.Lease {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            cleanup.acquire()?.let { return it }
            Thread.yield()
        }
        error("cleanup credit was never returned")
    }

    @Test fun twoConcurrentRetirementsBothUnbindAndDuplicateCallbacksCoalesce() {
        val cleanup = GmsBindingCleanup(2)
        val first = requireNotNull(cleanup.acquire())
        val second = requireNotNull(cleanup.acquire())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        try {
            first.cleanup {
                entered.countDown(); awaitUninterruptibly(release); calls.incrementAndGet(); completed.countDown()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { first.cleanup { error("same handle must not be queued twice") } }
            second.cleanup { calls.incrementAndGet(); completed.countDown() }
            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            val returned = eventuallyAcquire(cleanup)
            returned.unused()
            first.cleanup { error("retired handle must not recreate cleanup work") }
            assertEquals(2, calls.get())
        } finally { release.countDown(); cleanup.close() }
    }

    @Test fun stuckCleanupExhaustsEightReservedBindingsWithoutAdmittingMoreAndRecoversAfterReturn() {
        val cleanup = GmsBindingCleanup()
        val leases = List(8) { requireNotNull(cleanup.acquire()) }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(8)
        val actualBindings = AtomicInteger(8)
        try {
            leases.first().cleanup { entered.countDown(); awaitUninterruptibly(release); completed.countDown() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            leases.drop(1).forEach { it.cleanup { completed.countDown() } }
            repeat(100) {
                cleanup.acquire()?.let { actualBindings.incrementAndGet(); it.unused() }
            }
            assertEquals(8, actualBindings.get())
            assertNull(cleanup.acquire())
            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            eventuallyAcquire(cleanup).unused()
        } finally { release.countDown(); cleanup.close() }
    }

    @Test fun failedUnbindRetainsCreditAndRetriesOnlyOnLaterAcquisition() {
        val cleanup = GmsBindingCleanup(1)
        val lease = requireNotNull(cleanup.acquire())
        val failed = CountDownLatch(1)
        val attempts = AtomicInteger()
        try {
            lease.cleanup {
                if (attempts.incrementAndGet() == 1) { failed.countDown(); error("unbind failed") }
            }
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertEquals(1, attempts.get())
            eventuallyAcquire(cleanup).unused()
            assertEquals(2, attempts.get())
        } finally { cleanup.close() }
    }

    @Test fun ordinaryCleanupCannotBlockRealRevokeOrLocalOff() = runBlocking {
        val owner = GmsRpcLanes(100)
        val lease = requireNotNull(owner.cleanup.acquire())
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val state = PrivilegedSessionState<String, Any>()
        state.open()
        try {
            lease.cleanup { entered.countDown(); awaitUninterruptibly(release) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            state.close()
            assertFalse(state.isOpen())
            assertEquals("revoked", owner.lane(GmsRpcLane.REVOKE).bounded { "revoked" })
            assertTrue(owner.lane(GmsRpcLane.INTERACTIVE_OBSERVATION).bounded { true })
        } finally { release.countDown(); owner.close() }
    }
}
