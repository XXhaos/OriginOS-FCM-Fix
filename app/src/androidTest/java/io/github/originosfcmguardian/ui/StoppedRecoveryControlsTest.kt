package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated UI fixtures; never bind Shizuku or mutate the phone. */
@RunWith(AndroidJUnit4::class)
class StoppedRecoveryControlsTest {
    @get:Rule val compose = createComposeRule()
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("org.telegram.messenger", 10434, 100, listOf("a".repeat(64))), "Telegram",
        stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 10),
    )

    @Test fun showingConfirmationDoesNotExecuteAndCancelOnlyDismisses() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent { MaterialTheme {
            StoppedRecoveryConfirmation({ confirmed++ }, { dismissed++ })
        } }
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, dismissed) }
    }

    @Test fun explicitConfirmationIsRequired() {
        var confirmed = 0
        compose.setContent { MaterialTheme { StoppedRecoveryConfirmation({ confirmed++ }, {}) } }
        compose.onNodeWithText("确认恢复").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun unknownReceiptOffersOnlyReadonlyReconciliation() {
        var reads = 0
        compose.setContent { MaterialTheme {
            StoppedRecoveryReceipt(StoppedRecoveryUiState(loaded = true, needsReconcile = true)) { reads++ }
        } }
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        compose.onNodeWithText("核对上次结果（只读）").performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun busyReceiptCannotReenter() {
        compose.setContent { MaterialTheme {
            StoppedRecoveryReceipt(StoppedRecoveryUiState(loaded = true, busy = true, needsReconcile = true)) {
                error("Unexpected callback")
            }
        } }
        compose.onNodeWithText("核对中…").assertIsNotEnabled()
    }

    @Test fun recheckingReceiptTargetDisablesReconciliation() {
        compose.setContent { MaterialTheme {
            StoppedRecoveryReceipt(StoppedRecoveryUiState(loaded = true, needsReconcile = true),
                target, reconcileBusy = true) { error("Read session reentered") }
        } }
        compose.onNodeWithText("核对上次结果（只读）").assertIsNotEnabled()
    }

    @Test fun recoveryShowsDisabledProgressThenTransientCompletionAndKeepsReceipt() {
        val running = StoppedRecoveryUiState(loaded = true, busy = true, phase = StoppedRecoveryPhase.RECOVERING,
            activeIdentity = target.identity, recordIdentity = target.identity)
        val state = mutableStateOf(running)
        val currentTarget = mutableStateOf(target)
        compose.setContent { MaterialTheme { Column {
            StoppedRecoveryAction(stoppedRecoveryActionPresentation(currentTarget.value, state.value, false)) {
                error("Unexpected recovery callback")
            }
            StoppedRecoveryReceipt(state.value, currentTarget.value) {}
        } } }
        compose.onNodeWithText("正在恢复…").assertIsNotEnabled()
        compose.runOnIdle {
            currentTarget.value = target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))
            state.value = running.copy(busy = false, phase = StoppedRecoveryPhase.SUCCESS,
                receiptOutcome = StoppedRecoveryOutcome.FLAG_CLEARED, showSuccessButton = true)
        }
        compose.onNodeWithText("恢复完成").assertIsNotEnabled()
        compose.onNodeWithText("操作成功：已解除停止限制").assertExists()
        compose.runOnIdle { state.value = state.value.copy(showSuccessButton = false) }
        compose.onNodeWithText("恢复完成").assertDoesNotExist()
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        compose.onNodeWithText("操作成功：已解除停止限制").assertExists()
    }

    @Test fun recheckingOneTargetLeavesOtherTargetEnabled() {
        var otherChecks = 0
        val other = target.copy(identity = target.identity.copy(packageName = "example.other"), label = "Other")
        compose.setContent { MaterialTheme { Column {
            SelectedTargetDetails(target, true, StoppedRecoveryUiState(), false,
                onCheck = { error("Busy target reentered") }, onSettings = {}, onRemove = {},
                onRequestRecovery = {}, onReconcile = {})
            SelectedTargetDetails(other, false, StoppedRecoveryUiState(), false,
                onCheck = { otherChecks++ }, onSettings = {}, onRemove = {},
                onRequestRecovery = {}, onReconcile = {})
        } } }
        compose.onNodeWithText("复检中…").assertIsNotEnabled()
        compose.onNodeWithText("复检").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, otherChecks) }
    }

    @Test fun unconfirmedReceiptDoesNotShowSuccessEvenWithUnrelatedNormalReadFalse() {
        compose.setContent { MaterialTheme { Column {
            StoppedRecoveryReceipt(StoppedRecoveryUiState(loaded = true, needsReconcile = true,
                phase = StoppedRecoveryPhase.UNCONFIRMED, recordIdentity = target.identity,
                receiptOutcome = StoppedRecoveryOutcome.UNCONFIRMED),
                target.copy(stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 20))) {}
        } } }
        compose.onNodeWithText("操作成功：已解除停止限制").assertDoesNotExist()
        compose.onNodeWithText("结果未确认：请复检或核对上次结果").assertExists()
        compose.onNodeWithText("核对上次结果（只读）").assertIsEnabled()
    }
}
