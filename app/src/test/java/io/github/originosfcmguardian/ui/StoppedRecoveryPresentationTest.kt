package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import org.junit.Assert.*
import org.junit.Test

class StoppedRecoveryPresentationTest {
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64))), "Telegram",
        stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 10),
    )
    private val ready = StoppedRecoveryUiState(loaded = true)

    @Test fun reconciledObservationDoesNotClaimOriginalOperationSucceededOrFailed() {
        val reconciled = ready.copy(recordIdentity = target.identity, recordStage = StoppedRecoveryStage.RECONCILED,
            receiptOutcome = StoppedRecoveryOutcome.STILL_STOPPED)
        assertEquals("上次复检：仍受限，恢复结果待确认",
            stoppedRecoveryReceiptPresentation(reconciled, target)?.text)
        val cleared = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))
        val clearedReconcile = reconciled.copy(receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED,
            phase = StoppedRecoveryPhase.SUCCESS)
        assertEquals("上次复检：允许后台唤醒，恢复结果待确认",
            stoppedRecoveryReceiptPresentation(clearedReconcile, cleared)?.text)
        assertEquals(TargetStatusTone.NEUTRAL, stoppedRecoveryReceiptPresentation(clearedReconcile, cleared)?.tone)
        assertEquals(RecoveryActionPresentation.HIDDEN,
            stoppedRecoveryActionPresentation(cleared, clearedReconcile.copy(showSuccessButton = true), false))
        assertEquals(TargetStatusTone.NEUTRAL, stoppedRecoveryReceiptPresentation(reconciled,
            target.copy(identity = target.identity.copy(firstInstallTime = 200)))?.tone)
        assertEquals(TargetStatusTone.UNCERTAIN,
            stoppedRecoveryReceiptPresentation(reconciled.copy(needsReconcile = true), target)?.tone)
    }
    private fun eligible(t: SelectedTargetUiState = target, r: StoppedRecoveryUiState = ready) =
        canOfferStoppedRecovery(t, r, true, true, true, false)

    @Test fun knownStoppedRecipientOffersManualActionButExcludedPackagesDoNot() {
        assertTrue(eligible())
        TargetStoppedStatus.entries.filter { it != TargetStoppedStatus.STOPPED }.forEach {
            assertFalse(eligible(target.copy(stoppedSnapshot = TargetStoppedSnapshot(it))))
        }
        assertTrue(eligible(target.copy(identity = target.identity.copy(packageName = "example.other"))))
        listOf("com.google.android.gms", "moe.shizuku.privileged.api", "io.github.originosfcmguardian").forEach {
            assertFalse(eligible(target.copy(identity = target.identity.copy(packageName = it))))
        }
        assertFalse(eligible(target.copy(status = TargetAutostartStatus.IDENTITY_CHANGED)))
    }

    @Test fun unresolvedOrUnavailableJournalNeverOffersAnotherWrite() {
        listOf(ready.copy(loaded = false), ready.copy(busy = true), ready.copy(storageBlocked = true),
            ready.copy(needsReconcile = true)).forEach { assertFalse(eligible(r = it)) }
    }

    @Test fun currentEnvironmentAndSelectionAreRequired() {
        assertFalse(canOfferStoppedRecovery(target, ready, false, true, true, false))
        assertFalse(canOfferStoppedRecovery(target, ready, true, false, true, false))
        assertFalse(canOfferStoppedRecovery(target, ready, true, true, false, false))
        assertFalse(canOfferStoppedRecovery(target, ready, true, true, true, true))
    }

    @Test fun confirmationDoesNotTransferToReplacementOrRemovedSelection() {
        assertTrue(recoveryConfirmationStillValid(target.identity, listOf(target)) { eligible(it) })
        assertFalse(recoveryConfirmationStillValid(target.identity, emptyList()) { eligible(it) })
        val replacement = target.copy(identity = target.identity.copy(firstInstallTime = 200))
        assertFalse(recoveryConfirmationStillValid(target.identity, listOf(replacement)) { eligible(it) })
        assertFalse(recoveryConfirmationStillValid(null, listOf(target)) { eligible(it) })
        assertFalse(recoveryConfirmationStillValid(target.identity, listOf(target)) { false })
    }

    @Test fun recoveryBusyBelongsOnlyToExactActiveIdentity() {
        val running = ready.copy(busy = true, phase = StoppedRecoveryPhase.RECOVERING, activeIdentity = target.identity)
        assertEquals(RecoveryActionPresentation.RECOVERING, stoppedRecoveryActionPresentation(target, running, false))
        val replacement = target.copy(identity = target.identity.copy(firstInstallTime = 200))
        assertEquals(RecoveryActionPresentation.HIDDEN, stoppedRecoveryActionPresentation(replacement, running, false))
    }

    @Test fun completedButtonRequiresFreshKnownFalseForSameInstallationAndDisappears() {
        val cleared = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))
        val success = ready.copy(phase = StoppedRecoveryPhase.SUCCESS, recordIdentity = target.identity,
            receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED, showSuccessButton = true)
        assertEquals(RecoveryActionPresentation.SUCCESS, stoppedRecoveryActionPresentation(cleared, success, false))
        assertEquals(RecoveryActionPresentation.HIDDEN,
            stoppedRecoveryActionPresentation(cleared, success.copy(showSuccessButton = false), false))
        assertEquals(TargetStatusTone.SUCCESS, stoppedRecoveryReceiptPresentation(success, cleared)?.tone)
        listOf(target, cleared.copy(identity = target.identity.copy(firstInstallTime = 200)),
            cleared.copy(status = TargetAutostartStatus.IDENTITY_CHANGED),
            target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN))).forEach {
            assertEquals(RecoveryActionPresentation.HIDDEN, stoppedRecoveryActionPresentation(it, success, false))
            assertEquals(TargetStatusTone.UNCERTAIN, stoppedRecoveryReceiptPresentation(success, it)?.tone)
        }
    }

    @Test fun historicalOrUnconfirmedResultCannotProduceGreenReceipt() {
        val cleared = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))
        val historical = ready.copy(recordIdentity = target.identity, receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED)
        assertEquals(TargetStatusTone.NEUTRAL, stoppedRecoveryReceiptPresentation(historical, cleared)?.tone)
        assertEquals("上次恢复：已解除限制", stoppedRecoveryReceiptPresentation(historical, cleared)?.text)
        val replacement = cleared.copy(identity = target.identity.copy(firstInstallTime = 200))
        assertEquals(TargetStatusTone.NEUTRAL, stoppedRecoveryReceiptPresentation(historical, replacement)?.tone)
        assertEquals("上次恢复：已解除限制", stoppedRecoveryReceiptPresentation(historical, replacement)?.text)
        val uncertain = historical.copy(phase = StoppedRecoveryPhase.UNCONFIRMED, needsReconcile = true)
        assertEquals(TargetStatusTone.UNCERTAIN, stoppedRecoveryReceiptPresentation(uncertain, cleared)?.tone)
        assertEquals(TargetStatusTone.UNCERTAIN, stoppedRecoveryReceiptPresentation(uncertain, null)?.tone)
    }

    @Test fun failedKnownStoppedIsRedButMissingObservationIsAmber() {
        val failed = ready.copy(phase = StoppedRecoveryPhase.FAILED, recordIdentity = target.identity,
            receiptOutcome = StoppedRecoveryOutcome.STILL_STOPPED)
        assertEquals(TargetStatusTone.FAILURE, stoppedRecoveryReceiptPresentation(failed, target)?.tone)
        val unknown = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN))
        assertEquals(TargetStatusTone.UNCERTAIN, stoppedRecoveryReceiptPresentation(failed, unknown)?.tone)
        assertEquals(RecoveryActionPresentation.OFFER, stoppedRecoveryActionPresentation(target, failed, true))
    }

    @Test fun wakeStatusDoesNotTurnReplacementOrUnknownGreen() {
        val cleared = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))
        assertEquals("允许后台唤醒", targetWakePresentation(cleared).text)
        assertEquals(TargetStatusTone.SUCCESS, targetWakePresentation(cleared).tone)
        assertEquals(TargetStatusTone.UNCERTAIN, targetWakePresentation(cleared.copy(status = TargetAutostartStatus.IDENTITY_CHANGED)).tone)
        assertEquals(TargetStatusTone.UNCERTAIN,
            targetWakePresentation(target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN))).tone)
        assertEquals(TargetStatusTone.NEUTRAL, targetWakePresentation(cleared, checking = true).tone)
    }
}
