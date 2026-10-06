package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.*
import org.junit.Assert.*
import org.junit.Test

class TransientAutomaticCheckTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64)))
    private val token = DurableConsentToken("b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 7, 90,
        identity, VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
    private val leases = listOf(TargetConsentLease(7, TargetConsent.Legacy(token)))
    private val record = StoppedRecoveryRecord(1, identity, 100, 99, VERIFIED_AUTOSTART_FINGERPRINT, 0,
        true, true, true, StoppedRecoveryStage.FINISHED, StoppedRecoveryOutcome.FLAG_CLEARED)
    private val marked = StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "bind failed", record,
        bindingFailedBeforeAction = true)

    @Test fun `full startup must be ready or budget waiting with exact leases and no invalid targets`() {
        for (status in StartupEligibilityStatus.entries) {
            assertEquals(status in setOf(StartupEligibilityStatus.READY_TO_START, StartupEligibilityStatus.WAITING_BUDGET),
                mayRetainAfterFreshAutomaticCheck(StartupProbeResult(leases, status), leases))
        }
        assertFalse(mayRetainAfterFreshAutomaticCheck(StartupProbeResult(emptyList(), StartupEligibilityStatus.READY_TO_START), leases))
        assertFalse(mayRetainAfterFreshAutomaticCheck(StartupProbeResult(leases, StartupEligibilityStatus.READY_TO_START, leases), leases))
        assertFalse(mayRetainAfterFreshAutomaticCheck(StartupProbeResult(leases.map { it.copy(rootGeneration = 8) },
            StartupEligibilityStatus.READY_TO_START), leases))
    }

    @Test fun `binding wait requires unchanged clear journal and exact local provenance`() {
        assertTrue(mayWaitAfterRecoveryBinding(marked, Result.success(record), Result.success(record)))
        assertTrue(mayWaitAfterRecoveryBinding(marked.copy(record = null), Result.success(null), Result.success(null)))
        val pending = record.copy(stage = StoppedRecoveryStage.PREPARED, outcome = null)
        for (result in listOf(marked.copy(bindingFailedBeforeAction = false), marked.copy(commandSucceeded = true),
            marked.copy(admissionBusy = true), marked.copy(outcome = StoppedRecoveryOutcome.UNCONFIRMED),
            marked.copy(record = null), marked.copy(record = pending))) {
            assertFalse(mayWaitAfterRecoveryBinding(result, Result.success(record), Result.success(record)))
        }
        assertFalse(mayWaitAfterRecoveryBinding(marked, Result.success(record), Result.success(record.copy(requestId = 2))))
        assertFalse(mayWaitAfterRecoveryBinding(marked.copy(record = pending), Result.success(pending), Result.success(pending)))
        assertFalse(mayWaitAfterRecoveryBinding(marked, Result.failure(Exception()), Result.success(record)))
        assertFalse(mayWaitAfterRecoveryBinding(marked, Result.success(record), Result.failure(Exception())))
    }

    @Test fun `retained wait closes real write gate and preserves durable bytes tokens and reserved attempts`() {
        val storage = object : ProtectionAuthorityStorage {
            var bytes: ByteArray? = null
            var writes = 0
            override fun <T> exclusive(action: () -> T): T = action()
            override fun read() = bytes?.let(ProtectionAuthorityCodec::decode)
            override fun write(value: ProtectionAuthoritySnapshot) {
                writes++; bytes = ProtectionAuthorityCodec.encode(value)
            }
        }
        val store = ProtectionConsentStore(storage)
        val clock = BudgetClock(14, 1000, 1000000)
        val firstLease = store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        val other = identity.copy(packageName = "example.recipient", uid = 10435)
        store.grantRecipientExplicit(other, VERIFIED_AUTOSTART_FINGERPRINT, clock)
        assertTrue(store.enableMaster(store.readSnapshot(), clock))
        val currentLeases = store.desiredLeases()
        assertFalse(store.verifyAuthorized(firstLease))
        assertEquals(AutomaticCheckDecision.Recover, store.take(currentLeases.first(), clock))
        val authority = store.readSnapshot()!!
        val beforeBytes = storage.bytes!!.clone()
        val beforeWrites = storage.writes
        val decision = automaticCheckDecision(currentLeases.all(store::verifyAuthorized), true, false, false,
            io.github.originosfcmguardian.autostart.TargetStoppedStatus.UNKNOWN)
        assertTrue(decision is AutomaticCheckDecision.Recheck)
        val freshProbe = StartupProbeResult(store.desiredLeases(), StartupEligibilityStatus.WAITING_BUDGET)
        assertTrue(mayRetainAfterFreshAutomaticCheck(freshProbe, currentLeases))
        val previous = AutomaticProtectionState(enabled = true, busy = true, savedAuthorization = true,
            waitingForPrivilege = true, waitingForReconciliation = true, lastCheckedAtMillis = 1000,
            lastOutcome = "FLAG_CLEARED", targets = currentLeases.map { AutomaticProtectionTargetState(it.consent.identity,
                savedAuthorization = true, enabled = true, busy = true, waitingForPrivilege = true,
                lastCheckedAtMillis = 999, lastOutcome = "STILL_STOPPED") })
        val gate = AutomaticWriteGate(); gate.open(); val oldTicket = gate.ticket()
        val waiting = waitForNextAutomaticCheck(gate, previous, "waiting")
        assertEquals(waitingForNextAutomaticCheckState(previous, "waiting"), waiting)
        assertFalse(waiting.enabled); assertFalse(waiting.busy)
        assertFalse(waiting.waitingForPrivilege); assertFalse(waiting.waitingForReconciliation)
        assertEquals(previous.authorizedIdentities, waiting.authorizedIdentities)
        assertEquals(previous.lastCheckedAtMillis, waiting.lastCheckedAtMillis)
        assertEquals(previous.lastOutcome, waiting.lastOutcome)
        assertEquals(previous.targets.map { it.lastCheckedAtMillis }, waiting.targets.map { it.lastCheckedAtMillis })
        assertEquals(previous.targets.map { it.lastOutcome }, waiting.targets.map { it.lastOutcome })
        assertTrue(waiting.targets.all { !it.enabled && !it.busy && !it.waitingForPrivilege })
        assertFalse(gate.permits(oldTicket)); assertNull(gate.ticket())
        gate.open(); assertFalse(gate.permits(oldTicket)); assertTrue(gate.permits(gate.ticket()))
        assertEquals(currentLeases, store.desiredLeases())
        assertEquals(authority, store.readSnapshot())
        assertEquals(authority.budget, store.readSnapshot()!!.budget)
        assertEquals(listOf(1000L), store.readSnapshot()!!.budget.attempts)
        assertArrayEquals(beforeBytes, storage.bytes)
        assertEquals(beforeWrites, storage.writes)
    }

    @Test fun `empty off and ungranted targets never gain consent from waiting transform`() {
        val off = AutomaticProtectionState(targets = listOf(AutomaticProtectionTargetState(identity)))
        val gate = AutomaticWriteGate(); gate.open(); val oldTicket = gate.ticket()
        val waiting = waitForNextAutomaticCheck(gate, off, "waiting")
        assertFalse(gate.permits(oldTicket)); assertNull(gate.ticket())
        assertFalse(waiting.savedAuthorization); assertFalse(waiting.targets.single().savedAuthorization)
        assertTrue(waiting.authorizedIdentities.isEmpty())
        assertTrue(waitingForNextAutomaticCheckState(AutomaticProtectionState(), "waiting").targets.isEmpty())
    }
}
