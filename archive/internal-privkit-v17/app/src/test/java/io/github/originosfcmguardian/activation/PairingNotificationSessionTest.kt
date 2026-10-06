package io.github.originosfcmguardian.activation

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Production helper tests, not a claim of Android service or real RemoteInput acceptance. */
class PairingNotificationSessionTest {
    private fun waiting(identity: String = "current", started: Long = 100): PairingNotificationSession =
        PairingNotificationSession(identity, started).also {
            assertTrue(it.start(identity, started))
            assertTrue(it.discovered(12345, started + 1))
        }

    @Test fun explicitReservationIsExclusiveAndOldRetirementCannotReleaseReplacement() {
        val gate = PairingNotificationGate()
        val first = requireNotNull(gate.reserve("first", 100))
        assertNull(gate.reserve("second", 101))
        assertSame(first, gate.find("first"))
        assertTrue(gate.release(first))
        val second = requireNotNull(gate.reserve("second", 102))
        assertFalse(gate.release(first))
        assertSame(second, gate.find("second"))
    }

    @Test fun discoveryAndStartAreOneShotAndCannotExtendOriginalDeadline() {
        val session = PairingNotificationSession("current", 100)
        assertTrue(session.start("current", 101))
        assertFalse(session.start("current", 200))
        assertFalse(session.discovered(0, 201))
        assertTrue(session.discovered(12345, 202))
        assertFalse(session.discovered(54321, 203))
        assertEquals(PAIRING_INPUT_MILLIS - 103, session.remainingInput(203))
        assertEquals(PAIRING_SESSION_MILLIS - 103, session.remainingTotal(203))
    }

    @Test fun atomicReplyClaimRejectsStaleDuplicateAndRevokedReplies() {
        val session = waiting()
        assertNull(session.claimReply("old", 102))
        assertEquals(PairingNotificationPhase.WAITING, session.phase)
        val claim = requireNotNull(session.claimReply("current", 102))
        assertEquals(12345, claim.port)
        assertEquals(40_000L, claim.operationBudgetMillis)
        assertNull(session.claimReply("current", 103))
        session.close()
        assertNull(session.claimReply("current", 104))
        assertFalse(session.matches("current"))
        assertNull(PairingNotificationGate().find("current")) // Recreated process has no reservation.
    }

    @Test fun inputAndTotalDeadlinesAndBackwardsClockFailClosed() {
        val inputExpired = waiting()
        assertNull(inputExpired.claimReply("current", 100 + PAIRING_INPUT_MILLIS))
        assertEquals(0L, inputExpired.remainingTotal(100 + PAIRING_SESSION_MILLIS))
        assertNull(waiting().claimReply("current", 99))
        assertFalse(PairingNotificationSession("new", 100).start("new", 100 + PAIRING_INPUT_MILLIS))
    }

    @Test fun invalidCodeConsumesClaimWithoutRetryAndOnlyAsciiSixDigitsAreAccepted() {
        val session = waiting()
        assertNotNull(session.claimReply("current", 102))
        for (input in listOf(null, "", "12345", "1234567", "１２３４５６", "12;345", " 12345"))
            assertFalse(validNotificationPairingCode(input))
        assertTrue(validNotificationPairingCode("012345"))
        assertNull(session.claimReply("current", 103))
    }

    @Test fun cancelledControllerReservationCannotAdmitLateOldOwner() {
        assertTrue(notificationPairingOwnerAllows(null, null))
        assertTrue(notificationPairingOwnerAllows("current", "current"))
        assertFalse(notificationPairingOwnerAllows("current", null))
        assertFalse(notificationPairingOwnerAllows("current", "old"))
        assertFalse(notificationPairingOwnerAllows(null, "old")) // Reservation revoked between claim and dispatch.
    }

    @Test fun revocationDoesNotWaitForBlockedPreflightAndItsLateReadCannotDispatch() {
        val token = PairingOperationToken()
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val dispatches = AtomicInteger()
        val worker = thread {
            try {
                token.readWhileLive {
                    reading.countDown()
                    assertTrue(resume.await(2, TimeUnit.SECONDS))
                }
                dispatches.incrementAndGet()
            } catch (_: CancellationException) {
                // The production post-read checkpoint rejects the cancelled admission.
            } finally { finished.countDown() }
        }
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            token.revoke() // Returns while the preflight thread is still blocked.
            assertFalse(token.isLive)
            assertEquals(1L, finished.count)
        } finally { resume.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        assertEquals(0, dispatches.get())
    }

    @Test fun revokedQueuedDispatchCannotPassTransactionCheckpointOrReadAgain() {
        val token = PairingOperationToken()
        assertEquals("preflight", token.readWhileLive { "preflight" })
        token.revoke() // Revoked after admission but before the controller coroutine is dispatched.
        assertThrows(CancellationException::class.java) { token.ensureLive() }
        var read = false
        assertThrows(CancellationException::class.java) { token.readWhileLive { read = true } }
        assertFalse(read)
        token.revoke() // Repeated retirement remains harmless.
        assertFalse(token.isLive)
    }

    @Test fun workingAndTerminalNotificationPlansRemoveReplyAndResultIsDismissible() {
        for (phase in PairingNotificationPhase.entries) {
            val plan = pairingNotificationPlan(phase)
            assertEquals(phase != PairingNotificationPhase.TERMINAL, plan.ongoing)
            assertEquals(phase == PairingNotificationPhase.TERMINAL, plan.autoCancel)
            assertEquals(phase == PairingNotificationPhase.WAITING, plan.allowReply)
        }
    }

    @Test fun typedPairResultDoesNotUseMessageAndPreservesFailurePlusCleanup() {
        val secret = "012345 PRIVATE key protocol-output"
        val misleading = InternalActivationStatus(message = "配对成功 $secret")
        assertFalse(pairingNotificationResult(misleading).contains("配对成功"))
        assertFalse(pairingNotificationResult(misleading).contains(secret))
        assertTrue(pairingNotificationResult(InternalActivationStatus(paired = true)).contains("配对成功"))
        val failed = InternalActivationStatus(paired = true, needsManualCleanup = true, message = secret,
            failure = ActivationDiagnostic(ActivationStage.PAIRING_REQUEST, ActivationFailureKind.TLS),
            cleanupFailure = ActivationDiagnostic(ActivationStage.CLEANUP, ActivationFailureKind.SECURITY))
        val result = pairingNotificationResult(failed)
        assertFalse(result.contains("配对成功"))
        assertFalse(result.contains(secret))
        assertTrue(result.contains("PAIRING_REQUEST/TLS"))
        assertTrue(result.contains("CLEANUP/SECURITY"))
        assertTrue(result.contains("手动关闭无线调试"))
    }
}
