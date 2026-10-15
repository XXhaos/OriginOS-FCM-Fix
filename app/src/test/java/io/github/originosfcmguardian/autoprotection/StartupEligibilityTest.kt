package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class StartupEligibilityTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private val token = DurableConsentToken("12345678-1234-1234-1234-123456789abc", 1, 1000,
        identity, VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)
    private val ready = StartupEligibilityInputs(
        authorityReadSuccessful = true,
        authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, token, null,
            BudgetSnapshot(BudgetClock(1, 0, 1000), emptyList(), null)),
        userUnlocked = true, exitHistory = StartupExitHistoryStatus.CLEAR,
        fingerprint = VERIFIED_AUTOSTART_FINGERPRINT, currentUser = 0,
        freshIdentity = identity, selectedIdentity = identity, journal = RestartJournalStatus.CLEAR,
        budget = AutomaticCheckDecision.Recover,
        privilege = RestartPrivilegeEvidence(true, true, 2000, 0, true),
    )

    @Test fun `historical policy never restarts and new explicit policy can become ready`() {
        assertEquals(StartupEligibilityStatus.READY_TO_START, startupEligibility(ready))
        val historical = ready.authority!!.copy(token = token.copy(policyVersion = 1))
        val decoded = ProtectionAuthorityCodec.decode(ProtectionAuthorityCodec.encode(historical))
        assertEquals(StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
            startupEligibility(ready.copy(authority = decoded)))
        assertEquals(StartupEligibilityStatus.OFF, startupEligibility(ready.copy(authority = null)))
        assertEquals(StartupEligibilityStatus.BLOCKED_STORAGE,
            startupEligibility(ready.copy(authorityReadSuccessful = false)))
        assertEquals(StartupEligibilityStatus.WAITING_UNLOCK,
            startupEligibility(ready.copy(userUnlocked = false)))
    }

    @Test fun `user stop crash loop and unreadable exit history deny restart`() {
        for ((history, status) in listOf(
            StartupExitHistoryStatus.USER_STOPPED to StartupEligibilityStatus.STOPPED_BY_USER,
            StartupExitHistoryStatus.CRASH_LOOP to StartupEligibilityStatus.BLOCKED_CRASH_LOOP,
            StartupExitHistoryStatus.UNKNOWN to StartupEligibilityStatus.BLOCKED_STORAGE,
        )) assertEquals(status, startupEligibility(ready.copy(exitHistory = history)))
    }

    @Test fun `identity firmware user and current selection must all freshly match`() {
        val invalid = listOf(
            ready.copy(fingerprint = "other"), ready.copy(currentUser = 10),
            ready.copy(freshIdentity = null), ready.copy(selectedIdentity = null),
            ready.copy(freshIdentity = identity.copy(firstInstallTime = 1235)),
            ready.copy(selectedIdentity = identity.copy(signerSha256 = listOf("b".repeat(64)))),
        )
        invalid.forEach { assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY, startupEligibility(it)) }
    }

    @Test fun `legacy policy2 can start on matching firmware but remains bound across OTA`() {
        val firmware = "another/product/device:17/BUILD/123:user/release-keys"
        val differentFirmware = ready.copy(fingerprint = firmware,
            authority = ready.authority!!.copy(token = token.copy(fingerprint = firmware)))
        assertEquals(StartupEligibilityStatus.READY_TO_START, startupEligibility(differentFirmware))
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY,
            startupEligibility(differentFirmware.copy(fingerprint = "$firmware-new")))
        assertEquals(listOf(identity), differentFirmware.authority!!.configuredSchema3().targetConsents.map { it.identity })
    }

    @Test fun `unresolved journal and unavailable budget block startup while rate limits wait`() {
        for (journal in RestartJournalStatus.entries.filter { it !in setOf(RestartJournalStatus.CLEAR, RestartJournalStatus.PENDING) }) {
            assertEquals(StartupEligibilityStatus.BLOCKED_RECONCILIATION, startupEligibility(ready.copy(journal = journal)))
        }
        for (budget in listOf(null, AutomaticCheckDecision.Wait, AutomaticCheckDecision.Pause("unavailable"))) {
            assertEquals(StartupEligibilityStatus.BLOCKED_BUDGET, startupEligibility(ready.copy(budget = budget)))
        }
        assertEquals(StartupEligibilityStatus.WAITING_BUDGET,
            startupEligibility(ready.copy(budget = AutomaticCheckDecision.Cooldown(300_000))))
    }

    @Test fun `hour limit wait retains exact active authority but never revives retired consent`() {
        val hourlyWait = ready.copy(budget = AutomaticCheckDecision.Cooldown(2_700_000))
        assertEquals(StartupEligibilityStatus.WAITING_BUDGET, startupEligibility(hourlyWait))
        assertEquals(ready.authority, hourlyWait.authority)
        val retired = ready.authority!!.copy(mode = ProtectionConsentMode.SUSPENDED, token = null,
            suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
        assertEquals(StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
            startupEligibility(hourlyWait.copy(authority = retired)))
        assertEquals(StartupEligibilityStatus.WAITING_RECONCILIATION,
            startupEligibility(hourlyWait.copy(journal = RestartJournalStatus.PENDING)))
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY,
            startupEligibility(hourlyWait.copy(freshIdentity = identity.copy(firstInstallTime = 1235))))
        assertEquals(StartupEligibilityStatus.STOPPED_BY_USER,
            startupEligibility(hourlyWait.copy(exitHistory = StartupExitHistoryStatus.USER_STOPPED)))
    }

    @Test fun `pending startup creates only readonly waiting after all remaining guards pass`() {
        val pending = ready.copy(journal = RestartJournalStatus.PENDING)
        assertEquals(StartupEligibilityStatus.WAITING_RECONCILIATION, startupEligibility(pending))
        assertTrue(StartupEligibilityStatus.WAITING_RECONCILIATION.mayCreateWaitingSession())
        assertEquals(StartupEligibilityStatus.BLOCKED_BUDGET,
            startupEligibility(pending.copy(budget = AutomaticCheckDecision.Pause("corrupt budget"))))
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY,
            startupEligibility(pending.copy(freshIdentity = identity.copy(uid = 10124))))
        assertEquals(StartupEligibilityStatus.STOPPED_BY_USER,
            startupEligibility(pending.copy(exitHistory = StartupExitHistoryStatus.USER_STOPPED)))
        assertEquals(StartupEligibilityStatus.BLOCKED_STORAGE,
            startupEligibility(pending.copy(authorityReadSuccessful = false)))
        assertEquals(StartupEligibilityStatus.WAITING_PRIVILEGE,
            startupEligibility(pending.copy(privilege = RestartPrivilegeEvidence())))
        val cleared = pending.copy(journal = RestartJournalStatus.CLEAR, budget = AutomaticCheckDecision.Cooldown(299_999))
        assertEquals(StartupEligibilityStatus.WAITING_BUDGET, startupEligibility(cleared))
        assertEquals(StartupEligibilityStatus.READY_TO_START,
            startupEligibility(cleared.copy(budget = AutomaticCheckDecision.Recover)))
    }

    @Test fun `fresh shell identity and authorization are mandatory even when saved consent exists`() {
        val absent = listOf(
            RestartPrivilegeEvidence(), ready.privilege.copy(binderAlive = false),
            ready.privilege.copy(permissionGranted = false), ready.privilege.copy(uid = 0),
            ready.privilege.copy(currentUser = 10), ready.privilege.copy(freshForCurrentConnection = false),
        )
        absent.forEach { assertEquals(StartupEligibilityStatus.WAITING_PRIVILEGE, startupEligibility(ready.copy(privilege = it))) }
    }

    @Test fun `local PM unavailable waits only after every shared safety gate and never retires targets`() {
        val waiting = ready.copy(targetReadUnavailable = true, freshIdentity = null)
        assertEquals(StartupEligibilityStatus.WAITING_TARGET_READ, startupEligibility(waiting))
        assertTrue(StartupEligibilityStatus.WAITING_TARGET_READ.mayCreateWaitingSession())
        assertEquals(StartupEligibilityStatus.WAITING_TARGET_READ,
            startupEligibility(waiting.copy(journal = RestartJournalStatus.PENDING)))
        assertEquals(StartupEligibilityStatus.WAITING_TARGET_READ,
            startupEligibility(waiting.copy(budget = AutomaticCheckDecision.Cooldown(30_000))))
        val checks = listOf(
            waiting.copy(userUnlocked = false) to StartupEligibilityStatus.WAITING_UNLOCK,
            waiting.copy(authorityReadSuccessful = false) to StartupEligibilityStatus.BLOCKED_STORAGE,
            waiting.copy(authority = ready.authority!!.copy(storageBlocked = true)) to StartupEligibilityStatus.BLOCKED_STORAGE,
            waiting.copy(authority = null) to StartupEligibilityStatus.OFF,
            waiting.copy(authority = ready.authority!!.configuredSchema3().copy(mode = ProtectionConsentMode.USER_PAUSED)) to StartupEligibilityStatus.OFF,
            waiting.copy(authority = ready.authority!!.configuredSchema3().copy(mode = ProtectionConsentMode.SUSPENDED,
                suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE)) to StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
            waiting.copy(exitHistory = StartupExitHistoryStatus.USER_STOPPED) to StartupEligibilityStatus.STOPPED_BY_USER,
            waiting.copy(exitHistory = StartupExitHistoryStatus.CRASH_LOOP) to StartupEligibilityStatus.BLOCKED_CRASH_LOOP,
            waiting.copy(exitHistory = StartupExitHistoryStatus.UNKNOWN) to StartupEligibilityStatus.BLOCKED_STORAGE,
            waiting.copy(fingerprint = "changed") to StartupEligibilityStatus.BLOCKED_IDENTITY,
            waiting.copy(currentUser = 10) to StartupEligibilityStatus.BLOCKED_IDENTITY,
            waiting.copy(selectedIdentity = null) to StartupEligibilityStatus.BLOCKED_IDENTITY,
            waiting.copy(journal = RestartJournalStatus.CORRUPT) to StartupEligibilityStatus.BLOCKED_RECONCILIATION,
            waiting.copy(journal = RestartJournalStatus.UNKNOWN) to StartupEligibilityStatus.BLOCKED_RECONCILIATION,
            waiting.copy(budget = null) to StartupEligibilityStatus.BLOCKED_BUDGET,
            waiting.copy(budget = AutomaticCheckDecision.Pause("unavailable")) to StartupEligibilityStatus.BLOCKED_BUDGET,
            waiting.copy(privilege = RestartPrivilegeEvidence()) to StartupEligibilityStatus.WAITING_PRIVILEGE,
        )
        checks.forEach { (input, status) -> assertEquals(status, startupEligibility(input)) }
        val lease = TargetConsentLease(ready.authority!!.generation, TargetConsent.Legacy(token))
        val partialNegative = waiting.copy(targets = listOf(StartupTargetEvidence(lease,
            identity.copy(firstInstallTime = 5678), null, false)))
        assertTrue(confirmedInvalidTargetLeases(partialNegative, true).isEmpty())
        assertTrue(confirmedInvalidTargetLeases(partialNegative.copy(targetReadUnavailable = false), true).isNotEmpty())
        assertEquals(StartupEligibilityStatus.BLOCKED_STORAGE,
            startupEligibility(waiting.copy(targets = listOf(StartupTargetEvidence(lease.copy(rootGeneration = 99),
                null, identity, false)))))
        assertEquals(StartupEligibilityStatus.BLOCKED_STORAGE, startupEligibility(waiting.copy(targets = emptyList())))
        assertFalse(mayRetainAfterFreshAutomaticCheck(StartupProbeResult(listOf(lease),
            StartupEligibilityStatus.WAITING_TARGET_READ), listOf(lease)))
        // A completed PM negative is never reclassified as a local timeout or busy result.
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY, startupEligibility(waiting.copy(targetReadUnavailable = false)))
    }

    @Test fun `only a successfully read bounded history with valid timestamps can be clear`() {
        assertEquals(StartupExitHistoryStatus.CLEAR, startupExitHistoryDecision(1000, 2000, emptyList()))
        assertEquals(StartupExitHistoryStatus.UNKNOWN, startupExitHistoryDecision(1000, 2000, null))
        assertEquals(StartupExitHistoryStatus.UNKNOWN, startupExitHistoryDecision(2001, 2000, emptyList()))
        for (timestamp in listOf(0L, -1L, 2001L)) {
            assertEquals(StartupExitHistoryStatus.UNKNOWN,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(StartupExitReason.OTHER, timestamp))))
        }
        val full = List(STARTUP_EXIT_HISTORY_LIMIT) { StartupExitRecord(StartupExitReason.OTHER, 1500) }
        assertEquals(StartupExitHistoryStatus.UNKNOWN, startupExitHistoryDecision(1000, 2000, full))
        assertEquals(StartupExitHistoryStatus.CLEAR,
            startupExitHistoryDecision(1000, 2000, full.drop(1) + StartupExitRecord(StartupExitReason.OTHER, 999)))
        assertEquals(StartupExitHistoryStatus.UNKNOWN, startupExitHistoryDecision(1000, 2000, full + full.first()))
        assertEquals(StartupExitHistoryStatus.UNKNOWN,
            startupExitHistoryDecision(1000, 2000, emptyList(), historySaturated = true))
        assertEquals(StartupExitHistoryStatus.CLEAR,
            startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(StartupExitReason.OTHER, 999)), historySaturated = true))
    }

    @Test fun `user requested or user stopped since consent wins regardless of crash reasons`() {
        for (reason in listOf(StartupExitReason.USER_REQUESTED, StartupExitReason.USER_STOPPED)) {
            assertEquals(StartupExitHistoryStatus.USER_STOPPED,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 1000))))
            assertEquals(StartupExitHistoryStatus.CLEAR,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 999))))
            assertEquals(StartupExitHistoryStatus.USER_STOPPED,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 1100),
                    StartupExitRecord(StartupExitReason.CRASH, 1200), StartupExitRecord(StartupExitReason.ANR, 1300))))
        }
    }

    @Test fun `two main process crash family exits since consent blocks while older or other exits do not`() {
        for (reason in listOf(StartupExitReason.CRASH, StartupExitReason.CRASH_NATIVE,
            StartupExitReason.ANR, StartupExitReason.INITIALIZATION_FAILURE)) {
            assertEquals(StartupExitHistoryStatus.CLEAR,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 1500))))
            assertEquals(StartupExitHistoryStatus.CRASH_LOOP,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 1000),
                    StartupExitRecord(StartupExitReason.CRASH, 1500))))
            assertEquals(StartupExitHistoryStatus.CLEAR,
                startupExitHistoryDecision(1000, 2000, listOf(StartupExitRecord(reason, 999),
                    StartupExitRecord(StartupExitReason.OTHER, 1500))))
        }
    }
}
