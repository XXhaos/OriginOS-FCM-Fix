package io.github.originosfcmguardian.fcmprotection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FcmProtectionCommitFenceTest {
    @Test fun OFFAfterReadBeforeFirstCommitCannotResurrectConsent() {
        val fence = FcmProtectionCommitFence(Any()); val readRevision = fence.get(); var writes = 0
        fence.incrementAndGet()
        assertFalse(fence.commit(readRevision, { true }) { writes++ })
        assertEquals(0, writes)
    }
    @Test fun OFFAfterParentCommitBeforeChildReadinessRejectsFinalONAndCrashReplay() {
        val monitor = Any(); val fence = FcmProtectionCommitFence(monitor); val readRevision = fence.get()
        var parent = FcmProtectionConsent()
        assertTrue(fence.commit(readRevision, { true }) {
            parent = FcmProtectionConsent(true, "12345678-1234-1234-1234-123456789abc", "a".repeat(64), "b".repeat(64))
        })
        val crash = FcmProtectionCodec.decode(FcmProtectionCodec.encode(parent))
        assertFalse(crash.admits(null, false)); assertFalse(crash.admits(null, true))
        synchronized(monitor) { fence.incrementAndGet(); parent = parent.copy(configured = false) }
        assertFalse(fence.commit(readRevision, { true }) { parent = parent.copy(configured = true, recoveryPrepared = true) })
        assertFalse(FcmProtectionCodec.decode(FcmProtectionCodec.encode(parent)).configured)
    }
    @Test fun OFFConcurrentWithFinalCommitWinsDurableLastStateWithoutBlockingOnBinder() {
        val monitor = Any(); val fence = FcmProtectionCommitFence(monitor); val readRevision = fence.get()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val revoked = CountDownLatch(1)
        var configured = false
        val commit = Thread { fence.commit(readRevision, { true }) {
            entered.countDown(); assertTrue(release.await(1, TimeUnit.SECONDS)); configured = true
        } }
        val off = Thread { synchronized(monitor) { fence.incrementAndGet(); configured = false }; revoked.countDown() }
        commit.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); off.start(); release.countDown()
        assertTrue(revoked.await(1, TimeUnit.SECONDS)); commit.join(1000); off.join(1000)
        assertFalse(configured)
        assertFalse(fence.commit(readRevision, { true }) { configured = true })
    }
}
