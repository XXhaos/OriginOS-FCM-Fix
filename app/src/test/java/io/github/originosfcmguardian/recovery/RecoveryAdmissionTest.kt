package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production controller admission and service wait policy; no Android/Binder execution is claimed. */
class RecoveryAdmissionTest {
    private val identity = SelectedTargetIdentity("example.recipient", 10435, 101, listOf("a".repeat(64)))
    private fun unavailable() = StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "unavailable or busy")
    private fun successful() = StoppedRecoveryResult(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "no write")
    private fun pending() = StoppedRecoveryRecord(1, identity, 1_000, 900,
        VERIFIED_AUTOSTART_FINGERPRINT, 0, true, true, true, StoppedRecoveryStage.PREPARED)

    private suspend fun whileManualOwns(gate: RecoveryAdmission, check: suspend () -> Unit) = coroutineScope {
        val release = CompletableDeferred<Unit>()
        val manual = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withAdmission({ false }, ::unavailable) { release.await(); successful() }
        }
        try {
            assertTrue(gate.isBusy())
            check()
        } finally {
            release.complete(Unit)
            manual.join()
        }
    }

    @Test fun staleIdleObservationRejectsAutomaticWithoutSessionOrRpc() = runBlocking {
        val gate = RecoveryAdmission()
        // Automatic polling saw idle; a manual request wins admission before automatic dispatch.
        assertFalse(gate.isBusy())
        var sessions = 0
        var rpcCalls = 0
        whileManualOwns(gate) {
            val automatic = gate.withAdmission({ false }, ::unavailable) {
                sessions++; rpcCalls++; successful()
            }
            assertEquals(0, sessions)
            assertEquals(0, rpcCalls)
            assertTrue("CAS refusal must retain local busy provenance", automatic.admissionBusy)
            assertEquals(StoppedRecoveryOutcome.BLOCKED, automatic.outcome)
            assertTrue("The refused caller must not release the manual owner's slot", gate.isBusy())
        }
        assertFalse(gate.isBusy())
    }

    @Test fun busyAdmissionPreservesSavedConsentAndAlreadyReservedBudget() = runBlocking {
        val storage = object : ProtectionAuthorityStorage {
            var bytes: ByteArray? = null
            override fun <T> exclusive(action: () -> T): T = action()
            override fun read() = bytes?.let(ProtectionAuthorityCodec::decode)
            override fun write(value: ProtectionAuthoritySnapshot) { bytes = ProtectionAuthorityCodec.encode(value) }
        }
        val store = ProtectionConsentStore(storage)
        val clock = BudgetClock(14, 600_000, 1_000_000)
        store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        assertTrue(store.enableMaster(store.readSnapshot(), clock))
        val lease = store.desiredLeases().single()
        assertEquals(AutomaticCheckDecision.Recover, store.take(lease, clock))
        val reservedBytes = storage.bytes!!.clone()
        val gate = RecoveryAdmission()
        whileManualOwns(gate) {
            val result = gate.withAdmission({ false }, ::unavailable) { error("No automatic session") }
            // The service uses this production policy after its live authorization/connection gates.
            if (!mayWaitAfterRecoveryAdmission(result, Result.success(null))) {
                store.suspendLease(lease, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
            }
            assertArrayEquals("Busy must not retire consent or refund a reserved attempt", reservedBytes, storage.bytes)
            assertTrue(store.verifyAuthorized(lease))
            assertEquals(listOf(clock.elapsed), store.readSnapshot()!!.budget.attempts)
        }
    }

    @Test fun closedNeverCarriesBusyEvidence() = runBlocking {
        val gate = RecoveryAdmission()
        suspend fun closedCheck() {
            val result = gate.withAdmission({ true }, ::unavailable) { error("Closed controller created session") }
            assertFalse(result.admissionBusy)
            assertFalse(mayWaitAfterRecoveryAdmission(result, Result.success(null)))
        }
        closedCheck()
        assertFalse(gate.isBusy())
        whileManualOwns(gate) { closedCheck(); assertTrue(gate.isBusy()) }
    }

    @Test fun busyCannotInheritAnotherRequestsTransientPendingReceipt() = runBlocking {
        val gate = RecoveryAdmission()
        var fallbackReads = 0
        whileManualOwns(gate) {
            val result = gate.withAdmission({ false }, {
                fallbackReads++
                StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "manual pending", pending())
            }) { error("No automatic session") }
            assertEquals("An unadmitted caller must not borrow another request's result", 0, fallbackReads)
            assertNull(result.record)
            assertTrue(result.admissionBusy)
            assertFalse(mayWaitAfterRecoveryAdmission(result, Result.success(pending())))
            val completedManual = pending().copy(stage = StoppedRecoveryStage.FINISHED,
                outcome = StoppedRecoveryOutcome.FLAG_CLEARED)
            assertTrue(mayWaitAfterRecoveryAdmission(result, Result.success(completedManual)))
        }
    }

    @Test fun admittedFailureAndCancellationReleaseGate() = runBlocking {
        val gate = RecoveryAdmission()
        for (failure in listOf(IllegalStateException("construction failed"), CancellationException("cancelled"))) {
            try {
                gate.withAdmission({ false }, ::unavailable) { throw failure }
                fail("Failure must propagate")
            } catch (actual: Exception) {
                assertEquals(failure.javaClass, actual.javaClass)
            }
            assertFalse(gate.isBusy())
            assertEquals(successful(), gate.withAdmission({ false }, ::unavailable) { successful() })
        }
    }

    @Test fun busyEvidenceCannotCrossRpcCodec() {
        val local = unavailable().copy(admissionBusy = true)
        val decoded = RecoveryResultCodec.decode(RecoveryResultCodec.encode(local))
        assertFalse(decoded.admissionBusy)
        assertFalse(mayWaitAfterRecoveryAdmission(decoded, Result.success(null)))
        assertFalse(RecoveryResultCodec.decode("BLOCKED|busy|0|true").admissionBusy)
    }

    @Test fun pendingStorageFailureAndOrdinaryBlockedNeverBecomeBusyWait() {
        val busy = unavailable().copy(admissionBusy = true)
        assertTrue(mayWaitAfterRecoveryAdmission(busy, Result.success(null)))
        assertFalse(mayWaitAfterRecoveryAdmission(busy, Result.failure(IllegalStateException("journal unreadable"))))
        assertFalse(mayWaitAfterRecoveryAdmission(busy, Result.success(pending())))
        assertFalse(mayWaitAfterRecoveryAdmission(busy.copy(record = pending()), Result.success(null)))
        assertFalse(mayWaitAfterRecoveryAdmission(busy.copy(outcome = StoppedRecoveryOutcome.UNCONFIRMED), Result.success(null)))
        assertFalse(mayWaitAfterRecoveryAdmission(busy.copy(commandSucceeded = true), Result.success(null)))
        assertFalse(mayWaitAfterRecoveryAdmission(unavailable(), Result.success(null)))
    }
}
