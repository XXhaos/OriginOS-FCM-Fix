package io.github.originosfcmguardian.fcmprotection

import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.privileged.GmsConnectionReadStage
import io.github.originosfcmguardian.protection.InteractiveGmsReadCoordinator
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import io.github.originosfcmguardian.ui.readDirectGmsConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FcmInitialConnectionReadTest {
    private fun sample() = GmsConnectionObservation.Sample(1, 10208, FcmRecoveryPolicy.VERSION, 100, 900,
        FcmRecoveryPolicy.SIGNER, "12345678-1234-1234-1234-123456789abc", 1000, 100,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
        true, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1000, 100, 0))

    @Test fun initialConfirmationWaitsForExistingHomeReadAndCoalescesRefreshesWithoutRpcBusy() = runBlocking {
        val coordinator = InteractiveGmsReadCoordinator()
        val releaseHome = CompletableDeferred<Unit>()
        val releaseConfirmation = CompletableDeferred<Unit>()
        val confirmationEntered = CompletableDeferred<Unit>()
        val expected = sample()
        var active = 0; var reads = 0
        val failures = mutableListOf<FcmInitialReadFailure>()
        suspend fun rpc(release: CompletableDeferred<Unit>): GmsConnectionObservation {
            check(active++ == 0) { "interactive RPC overlap would return BUSY" }
            reads++
            return try { release.await(); expected } finally { active-- }
        }
        val home = async(start = CoroutineStart.UNDISPATCHED) {
            readDirectGmsConnection(1, coordinator = coordinator) { rpc(releaseHome) }
        }
        val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
            readInitialFcmConnection({ 100 }, { failures.add(it) }, coordinator) {
                confirmationEntered.complete(Unit)
                rpc(releaseConfirmation)
            }
        }
        try {
            assertEquals(1, reads)
            assertNull(readDirectGmsConnection(2, coordinator = coordinator) { error("refresh must not jump the confirmation") })
            releaseHome.complete(Unit)
            confirmationEntered.await()
            assertSame(expected, home.await())
            assertNull(readDirectGmsConnection(3, coordinator = coordinator) { error("confirmation still owns the lane") })
            releaseConfirmation.complete(Unit)
            assertSame(expected, confirmation.await())
            assertEquals(2, reads); assertEquals(0, active); assertTrue(failures.isEmpty())
            assertSame(expected, readDirectGmsConnection(4, coordinator = coordinator) { expected })
        } finally {
            home.cancelAndJoin(); confirmation.cancelAndJoin()
        }
    }

    @Test fun occupiedInteractiveLaneHasFiniteConfirmationDeadlineAndDoesNotStartLateRead() = runBlocking {
        val coordinator = InteractiveGmsReadCoordinator()
        var failure: FcmInitialReadFailure? = null
        val home = async(start = CoroutineStart.UNDISPATCHED) {
            readDirectGmsConnection(1, coordinator = coordinator) { awaitCancellation() }
        }
        try {
            assertNull(readInitialFcmConnection({ 456 }, { failure = it }, coordinator,
                timeoutMillis = 20, wallMillis = { 1234 }) { error("expired admission must not read") })
            assertEquals(FcmInitialReadFailure(GmsConnectionReadStage.INTERACTIVE_ADMISSION,
                GmsUnknown.TIMED_OUT, 1234, 456), failure)
        } finally { home.cancelAndJoin() }
        assertNotNull(coordinator.foreground { sample() })
    }

    @Test fun readSessionWaitIsAlsoBoundedAndNeverEscapesIntoAnUncoordinatedRead() = runBlocking {
        val coordinator = InteractiveGmsReadCoordinator()
        var failure: FcmInitialReadFailure? = null
        val background = launch(start = CoroutineStart.UNDISPATCHED) {
            ReadSessionCoordinator.withSession { awaitCancellation() }
        }
        try {
            assertNull(readInitialFcmConnection({ 456 }, { failure = it }, coordinator,
                timeoutMillis = 20, wallMillis = { 1234 }) { error("must not bypass the existing session") })
            assertEquals(GmsConnectionReadStage.READ_SESSION, failure?.stage)
            assertEquals(GmsUnknown.TIMED_OUT, failure?.reason)
        } finally { background.cancelAndJoin() }
        assertNotNull(coordinator.foreground { sample() })
    }

    @Test fun unknownReasonAndLastReadStageAreRetainedWithoutRetryOrRawException() = runBlocking {
        var failure: FcmInitialReadFailure? = null
        val coordinator = InteractiveGmsReadCoordinator()
        var reads = 0
        val unknown = GmsConnectionObservation.Unknown(9, GmsUnknown.IDENTITY_UNKNOWN)
        assertSame(unknown, readInitialFcmConnection({ 456 }, { failure = it }, coordinator,
            wallMillis = { 1234 }) { stage -> reads++; stage(GmsConnectionReadStage.OBSERVATION); unknown })
        assertEquals(FcmInitialReadFailure(GmsConnectionReadStage.OBSERVATION,
            GmsUnknown.IDENTITY_UNKNOWN, 1234, 456), failure)
        assertNull(readInitialFcmConnection({ 457 }, { failure = it }, coordinator,
            wallMillis = { 1235 }) { stage -> reads++; stage(GmsConnectionReadStage.BIND); error("private diagnostic text") })
        assertEquals(FcmInitialReadFailure(GmsConnectionReadStage.BIND,
            GmsUnknown.BRIDGE_UNAVAILABLE, 1235, 457), failure)
        assertEquals(2, reads)
        assertFalse(failure.toString().contains("private diagnostic"))
    }

    @Test fun cancellingConfirmationDoesNotLeavePriorityOrReadSessionAdmissionOccupied() = runBlocking {
        val coordinator = InteractiveGmsReadCoordinator()
        val entered = CompletableDeferred<Unit>()
        var failure: FcmInitialReadFailure? = null
        val confirmation = launch(start = CoroutineStart.UNDISPATCHED) {
            readInitialFcmConnection({ 100 }, { failure = it }, coordinator) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await(); confirmation.cancelAndJoin()
        assertEquals(GmsUnknown.CANCELLED, failure?.reason)
        assertNotNull(readDirectGmsConnection(2, coordinator = coordinator) { sample() })
        val freshFailures = mutableListOf<FcmInitialReadFailure>()
        assertNotNull(readInitialFcmConnection({ 100 }, { freshFailures.add(it) }, coordinator) { sample() })
        assertTrue(freshFailures.isEmpty())
    }
}
