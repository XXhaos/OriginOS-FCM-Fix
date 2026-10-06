package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

/** Pure runtime projection only; no Android service, permission or target mutation. */
class MasterProtectionRuntimeStateTest {
    private val identity = SelectedTargetIdentity("example.app", 10123, 10, listOf("a".repeat(64)))
    private val consent = TargetConsent.Recipient(RecipientConsentToken(
        "12345678-1234-1234-1234-123456789abc", 1, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val active = ProtectionAuthoritySnapshot(2, ProtectionConsentMode.ACTIVE, null, null,
        BudgetSnapshot(BudgetClock(1, 0, 1000), emptyList(), null), listOf(consent), 3, masterEnabledAtMillis = 2000)

    @Test fun `master OFF retains exact configured choices while clearing all live and saved capability`() {
        val prior = configuredAutomaticProtectionState(AutomaticProtectionState(), active, "ready").copy(
            enabled = true, busy = true, waitingForPrivilege = true, waitingForReconciliation = true)
        val off = masterOffAutomaticProtectionState(prior, "OFF")
        assertFalse(off.masterEnabled)
        assertFalse(off.enabled)
        assertFalse(off.busy)
        assertFalse(off.savedAuthorization)
        assertFalse(off.waitingForPrivilege)
        assertFalse(off.waitingForReconciliation)
        assertEquals(listOf(identity), off.configuredIdentities)
        assertTrue(off.authorizedIdentities.isEmpty())
    }

    @Test fun `paused suspended and quarantined roots retain choices without authorizing writes`() {
        val paused = active.copy(mode = ProtectionConsentMode.USER_PAUSED)
        val suspended = active.copy(mode = ProtectionConsentMode.SUSPENDED,
            suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
        for (root in listOf(paused, suspended, active.copy(invalidatedConsentIds = listOf(consent.id)))) {
            val state = configuredAutomaticProtectionState(AutomaticProtectionState(), root, "retained")
            assertEquals(listOf(identity), state.configuredIdentities)
            assertTrue(state.authorizedIdentities.isEmpty())
            assertFalse(state.enabled)
        }
        val quarantined = configuredAutomaticProtectionState(AutomaticProtectionState(),
            active.copy(invalidatedConsentIds = listOf(consent.id)), "retained")
        assertTrue(quarantined.targets.single().needsReconfirmation)
        val replacement = identity.copy(firstInstallTime = 20)
        assertFalse(replacement in quarantined.configuredIdentities)
    }

    @Test fun `paused intent is directly OFF and ambiguous storage always blocks it`() {
        val paused = active.copy(mode = ProtectionConsentMode.USER_PAUSED)
        val inputs = StartupEligibilityInputs(authorityReadSuccessful = true, authority = paused, userUnlocked = true)
        assertEquals(StartupEligibilityStatus.OFF, startupEligibility(inputs))
        assertEquals(StartupEligibilityStatus.BLOCKED_STORAGE,
            startupEligibility(inputs.copy(authority = paused.copy(storageBlocked = true))))
        assertFalse(StartupEligibilityStatus.OFF.mayCreateWaitingSession())
    }

    @Test fun `explicit master acknowledgement is independent of retained application consent history`() {
        val stop = listOf(StartupExitRecord(StartupExitReason.USER_REQUESTED, 1500))
        assertEquals(StartupExitHistoryStatus.USER_STOPPED, startupExitHistoryDecision(consent.consentedAtMillis, 2500, stop))
        assertEquals(StartupExitHistoryStatus.CLEAR, startupExitHistoryDecision(active.masterEnabledAtMillis!!, 2500, stop))
        assertEquals(1000L, consent.consentedAtMillis)
        assertEquals(StartupExitHistoryStatus.UNKNOWN,
            startupExitHistoryDecision(active.masterEnabledAtMillis!!, 2500,
                listOf(StartupExitRecord(StartupExitReason.OTHER, 2501))))
    }

    @Test fun `delayed master preflight does not acknowledge stop or crash events after user action`() {
        val actionClock = BudgetClock(1, 100, 2000)
        val delayedCommitMillis = 3000L
        val stopAfterAction = listOf(StartupExitRecord(StartupExitReason.USER_STOPPED, 2500))
        assertEquals(StartupExitHistoryStatus.USER_STOPPED,
            startupExitHistoryDecision(actionClock.epoch, delayedCommitMillis, stopAfterAction))
        val crashAfterAction = listOf(StartupExitRecord(StartupExitReason.CRASH, 2400),
            StartupExitRecord(StartupExitReason.ANR, 2500))
        assertEquals(StartupExitHistoryStatus.CRASH_LOOP,
            startupExitHistoryDecision(actionClock.epoch, delayedCommitMillis, crashAfterAction))
        // This demonstrates why substituting commit time for the captured action is unsafe.
        assertEquals(StartupExitHistoryStatus.CLEAR,
            startupExitHistoryDecision(delayedCommitMillis, delayedCommitMillis, stopAfterAction))
    }

    @Test fun `read failure retains known choices but cannot claim master or live capability`() {
        val previous = configuredAutomaticProtectionState(AutomaticProtectionState(), active, "known").copy(enabled = true)
        val failed = configuredAutomaticProtectionReadState(previous, Result.failure(IllegalStateException("unreadable")), "unused")
        assertEquals(previous.configuredIdentities, failed.configuredIdentities)
        assertFalse(failed.configurationAvailable)
        assertFalse(failed.masterEnabled)
        assertFalse(failed.enabled)
        assertFalse(failed.savedAuthorization)
        assertTrue(failed.authorizedIdentities.isEmpty())
        assertTrue(failed.detail.contains("暂不可用"))
    }

    @Test fun `initial unknown read failure stays unknown while successful absence proves empty choices`() {
        val unknown = AutomaticProtectionState(configurationAvailable = false)
        val failed = configuredAutomaticProtectionReadState(unknown, Result.failure(IllegalStateException("unreadable")), "unused")
        assertFalse(failed.configurationAvailable)
        assertTrue(failed.detail.contains("暂不可用"))
        val known = configuredAutomaticProtectionState(AutomaticProtectionState(), active, "known")
        val absent = configuredAutomaticProtectionReadState(known, Result.success(null), "OFF")
        assertTrue(absent.configurationAvailable)
        assertTrue(absent.configuredIdentities.isEmpty())
        assertFalse(absent.masterEnabled)
        assertFalse(absent.savedAuthorization)
    }
}
