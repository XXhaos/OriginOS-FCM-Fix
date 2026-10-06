package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class GuardianTileControllerTest {
    private val token = DurableConsentToken("12345678-1234-1234-1234-123456789abc", 1, 1000,
        SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64))),
        VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2)

    private class Fixture(val throttle: GuardianTileThrottle = GuardianTileThrottle()) {
        var now = 0L
        var session = false
        val probes = mutableListOf<(StartupProbeResult) -> Unit>()
        val launches = mutableListOf<Pair<() -> Boolean, () -> Unit>>()
        val publications = mutableListOf<StartupEligibilityStatus?>()
        val controller = GuardianTileController(throttle, { now }, { session },
            { probes += it }, { permitted, complete -> launches += permitted to complete }, { publications += it })
    }

    private fun confirmedInvalidTargetWithSurvivor(): StartupProbeResult {
        val survivor = RecipientConsentToken("12345678-1234-1234-1234-123456789abd", 2, 1001,
            SelectedTargetIdentity("example.recipient", 10124, 1235, listOf("b".repeat(64))),
            VERIFIED_AUTOSTART_FINGERPRINT)
        val consents = listOf(
            TargetConsent.Recipient(RecipientConsentToken(token.id, 1, token.consentedAtMillis,
                token.identity, token.fingerprint)), TargetConsent.Recipient(survivor),
        ).sortedBy { it.identity.packageName }
        val root = ProtectionAuthoritySnapshot(2, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 10_000, 100_000), emptyList(), null), consents, schemaVersion = 2)
        val leases = consents.map { TargetConsentLease(root.generation, it) }
        val input = StartupEligibilityInputs(authorityReadSuccessful = true, authority = root,
            userUnlocked = true, exitHistory = StartupExitHistoryStatus.CLEAR,
            fingerprint = VERIFIED_AUTOSTART_FINGERPRINT, currentUser = 0,
            journal = RestartJournalStatus.CLEAR, budget = AutomaticCheckDecision.Recover,
            privilege = RestartPrivilegeEvidence(true, true, 2000, 0, true),
            targets = leases.map { lease -> StartupTargetEvidence(lease, lease.consent.identity,
                lease.consent.identity.takeUnless { it == token.identity }, qualified = true) })
        return StartupProbeResult(leases, startupEligibility(input), confirmedInvalidTargetLeases(input, true))
    }

    @Test fun `confirmed invalid protected target does not block tile restore for healthy survivor`() {
        val f = Fixture()
        val result = confirmedInvalidTargetWithSurvivor()
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY, result.status)
        assertEquals(1, result.invalidTargets.size)
        f.controller.startListening()
        f.probes.single()(result)
        assertEquals(1, f.launches.size)
        assertTrue(f.launches.single().first())
        // Tile observation only asks the existing restore path; it never asserts active protection.
        assertFalse(guardianTilePresentation(AutomaticProtectionState(savedAuthorization = true),
            actuallyProtecting = false, startupStatus = result.status).active)
    }

    @Test fun `unknown all invalid duplicate and foreign target evidence cannot request isolation restore`() {
        val result = confirmedInvalidTargetWithSurvivor()
        for (rejected in listOf(
            result.copy(invalidTargets = emptyList()), result.copy(invalidTargets = result.leases),
            result.copy(invalidTargets = result.invalidTargets + result.invalidTargets),
            result.copy(invalidTargets = listOf(result.invalidTargets.single().copy(rootGeneration = 3))),
            result.copy(status = StartupEligibilityStatus.BLOCKED_STORAGE),
            result.copy(status = StartupEligibilityStatus.BLOCKED_RECONCILIATION),
            result.copy(status = StartupEligibilityStatus.STOPPED_BY_USER),
        )) {
            val f = Fixture()
            f.controller.startListening()
            f.probes.single()(rejected)
            assertTrue(f.launches.isEmpty())
        }
    }

    @Test fun `confirmed isolation restore still obeys session listening and launch generation gates`() {
        val result = confirmedInvalidTargetWithSurvivor()
        val stopped = Fixture()
        stopped.controller.startListening()
        stopped.controller.stopListening()
        stopped.probes.single()(result)
        assertTrue(stopped.launches.isEmpty())
        val running = Fixture()
        running.controller.startListening()
        running.session = true
        running.probes.single()(result)
        assertTrue(running.launches.isEmpty())
        val queued = Fixture()
        queued.controller.startListening()
        queued.probes.single()(result)
        val mayLaunch = queued.launches.single().first
        assertTrue(mayLaunch())
        queued.controller.stopListening()
        assertFalse(mayLaunch())
        queued.now = 3_000
        queued.controller.startListening()
        assertFalse(mayLaunch())
    }

    @Test fun `only validated policy2 may restore including waiting sessions`() {
        val eligible = setOf(StartupEligibilityStatus.READY_TO_START,
            StartupEligibilityStatus.WAITING_PRIVILEGE, StartupEligibilityStatus.WAITING_BUDGET,
            StartupEligibilityStatus.WAITING_RECONCILIATION, StartupEligibilityStatus.WAITING_TARGET_READ)
        for (status in StartupEligibilityStatus.entries) {
            val f = Fixture()
            f.controller.startListening()
            f.probes.single()(StartupProbeResult(token, status))
            assertEquals(status.name, status in eligible, f.launches.isNotEmpty())
        }
        for (unconsented in listOf(null, token.copy(policyVersion = 1))) {
            val f = Fixture()
            f.controller.startListening()
            f.probes.single()(StartupProbeResult(unconsented, StartupEligibilityStatus.READY_TO_START))
            assertTrue(f.launches.isEmpty())
        }
    }

    @Test fun `disabled and suspended authority do not launch on listening`() {
        for (status in listOf(StartupEligibilityStatus.OFF, StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT)) {
            val f = Fixture()
            f.controller.startListening()
            f.probes.single()(StartupProbeResult(null, status))
            assertTrue(f.launches.isEmpty())
            assertEquals(status, f.publications.last())
        }
    }

    @Test fun `throttle spans stop start and a new service instance`() {
        val shared = GuardianTileThrottle()
        val first = Fixture(shared)
        first.controller.startListening()
        first.controller.stopListening()
        first.now = 2_999
        first.controller.startListening()
        assertEquals(1, first.probes.size)
        val recreated = Fixture(shared)
        recreated.now = 2_999
        recreated.controller.startListening()
        assertTrue(recreated.probes.isEmpty())
        recreated.now = 3_000
        recreated.controller.startListening()
        assertEquals(1, recreated.probes.size)
        assertFalse(shared.acquire(2_000))
    }

    @Test fun `stop and newer listening generation discard late probe callbacks`() {
        val f = Fixture()
        f.controller.startListening()
        val stale = f.probes.single()
        f.controller.stopListening()
        val publishedAtStop = f.publications.size
        stale(StartupProbeResult(token, StartupEligibilityStatus.READY_TO_START))
        f.controller.refresh()
        assertEquals(publishedAtStop, f.publications.size)
        assertTrue(f.launches.isEmpty())
        f.now = 3_000
        f.controller.startListening()
        stale(StartupProbeResult(token, StartupEligibilityStatus.READY_TO_START))
        assertTrue(f.launches.isEmpty())
        f.probes.last()(StartupProbeResult(token, StartupEligibilityStatus.READY_TO_START))
        assertEquals(1, f.launches.size)
    }

    @Test fun `queued restore is cancelled by stop remove or destruction and cannot revive in new generation`() {
        val f = Fixture()
        f.controller.startListening()
        f.probes.single()(StartupProbeResult(token, StartupEligibilityStatus.READY_TO_START))
        val (mayLaunch, finished) = f.launches.single()
        assertTrue(mayLaunch())
        f.controller.stopListening() // Service routes stop, removal and destruction through this gate.
        assertFalse(mayLaunch())
        val count = f.publications.size
        finished()
        assertEquals(count, f.publications.size)
        f.now = 3_000
        f.controller.startListening()
        assertFalse(mayLaunch())
    }

    @Test fun `existing session avoids probe and session started during probe avoids duplicate restore`() {
        val f = Fixture()
        f.session = true
        f.controller.startListening()
        assertTrue(f.probes.isEmpty())
        f.session = false
        f.controller.startListening()
        f.session = true
        f.probes.single()(StartupProbeResult(token, StartupEligibilityStatus.READY_TO_START))
        assertTrue(f.launches.isEmpty())
    }

    @Test fun `tile lights only for a live protecting session never saved busy or waiting state`() {
        for (runtime in listOf(AutomaticProtectionState(), AutomaticProtectionState(savedAuthorization = true),
            AutomaticProtectionState(busy = true, savedAuthorization = true),
            AutomaticProtectionState(waitingForPrivilege = true, savedAuthorization = true))) {
            assertFalse(guardianTilePresentation(runtime, false, StartupEligibilityStatus.READY_TO_START).active)
            assertFalse(guardianTilePresentation(runtime, true).active)
        }
        assertFalse(guardianTilePresentation(AutomaticProtectionState(enabled = true), false).active)
        assertTrue(guardianTilePresentation(AutomaticProtectionState(enabled = true), true).active)
        assertFalse(guardianTilePresentation(AutomaticProtectionState(enabled = true, waitingForPrivilege = true), true).active)
        assertFalse(guardianTilePresentation(AutomaticProtectionState(enabled = true, revocationSaveFailed = true), true).active)
        assertFalse(guardianTilePresentation(AutomaticProtectionState(enabled = true, savedAuthorization = true,
            waitingForReconciliation = true), true).active)
    }
}
