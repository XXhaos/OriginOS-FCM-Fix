package io.github.originosfcmguardian.fcmprotection

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryPreparation
import org.junit.Assert.*
import org.junit.Test

class FcmProtectionHostReadinessTest {
    private class Storage : FcmProtectionStorage {
        var value = FcmProtectionConsent(); var halted = false
        override fun read() = value.copy(configured = value.configured && !halted)
        override fun write(value: FcmProtectionConsent) { this.value = value }
        override fun halt() { halted = true }
        override fun releaseHalt() { halted = false }
    }
    private fun sample() = GmsConnectionObservation.Sample(1, 10208, FcmRecoveryPolicy.VERSION, 100, 900,
        FcmRecoveryPolicy.SIGNER, "12345678-1234-1234-1234-123456789abc", 1000, 100,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
        true, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1000, 100, 0))
    @Test fun combinedOFFPollForwardsBothChildReadinessWithoutActionsAndFirstFreshConfirmationCanCommit() {
        val readiness = FcmProtectionHostReadiness(); readiness.attach("actual-host")
        val store = FcmProtectionStore(Storage()); val fence = FcmProtectionCommitFence(Any())
        var recoveryReady = false; var heartbeatReady = false; var actions = 0
        // The exact production poll seam unconditionally forwards readiness BEFORE its ON-only action gate.
        assertTrue(readiness.poll("actual-host", { recoveryReady = true; true }, { heartbeatReady = true; true },
            { store.read().configured }) { actions++ })
        assertTrue(readiness.eligible); assertTrue(recoveryReady); assertTrue(heartbeatReady)
        assertEquals(0, actions); assertFalse(store.read().admits(sample(), true))
        val captured = fence.get()
        assertTrue(fence.commit(captured, { readiness.eligible }) { store.confirm(sample()) })
        assertFalse(store.read().admits(sample(), true)) // Parent commit alone still permits no dispatch.
        assertTrue(recoveryReady) // prepareBundled's host prerequisite is now satisfied on the first confirmation.
        assertTrue(fence.commit(captured, { readiness.eligible && recoveryReady }) {
            store.recoveryPrepared(store.read().epoch!!)
        })
        assertTrue(store.read().admits(sample(), true))
        assertTrue(readiness.poll("actual-host", { true }, { true }, { store.read().configured }) { actions++ })
        assertEquals(1, actions)
        store.disable()
        readiness.poll("actual-host", { true }, { true }, { store.read().configured }) { actions++ }
        assertEquals(1, actions); assertFalse(store.read().admits(sample(), true))
    }
    @Test fun wrongOrEndedHostNeverForwardsReadinessOrStartsActions() {
        val readiness = FcmProtectionHostReadiness(); readiness.attach("current")
        val unexpected: () -> Boolean = { error("must not forward wrong-host readiness") }
        assertFalse(readiness.poll("old", unexpected, unexpected, { true }) { error("no actions") })
        readiness.end("current")
        assertFalse(readiness.poll("current", unexpected, unexpected, { true }) { error("no actions") })
        assertFalse(readiness.eligible)
    }
    @Test fun missingPrerequisiteIsRetryableNotStorageCorruption() {
        assertEquals(FcmProtectionConfirmationFailure.HOST_UNAVAILABLE, fcmPreparationFailure(FcmRecoveryPreparation.HOST_UNAVAILABLE))
        assertEquals(FcmProtectionConfirmationFailure.NETWORK_UNAVAILABLE, fcmPreparationFailure(FcmRecoveryPreparation.NETWORK_UNAVAILABLE))
        assertEquals(FcmProtectionConfirmationFailure.BUSY, fcmPreparationFailure(FcmRecoveryPreparation.READ_IN_PROGRESS))
        assertNull(fcmPreparationFailure(FcmRecoveryPreparation.STORAGE_FAILURE))
        assertNull(fcmPreparationFailure(FcmRecoveryPreparation.READY))
        assertFalse(FcmProtectionUiState(confirmationFailure = FcmProtectionConfirmationFailure.HOST_UNAVAILABLE).storageBlocked)
        assertTrue(FcmProtectionConfirmationFailure.HOST_UNAVAILABLE.detail.contains("稍后重试"))
    }
}
