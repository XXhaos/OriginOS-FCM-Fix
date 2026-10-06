package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import io.github.originosfcmguardian.recovery.TargetRecoveryCounts
import org.junit.Rule
import org.junit.Test

/** Synthetic identities and no-op callbacks only; never operates an installed application. */
class SelectedTargetDetailsExpansionTest {
    @get:Rule val compose = createComposeRule()
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("example.details.fixture", 10123, 1L, listOf("a".repeat(64))), "Fixture",
        stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10),
    )
    private val snapshotMillis = 1_700_000_000_000L
    private val countsText = "已保存确认：今日 2 · 本周 4（可能少计）\n" +
        "统计截至 2023-11-15 06:13（Asia/Shanghai）；回到应用刷新"

    @Test fun appsRowExpansionKeepsOnlyRecheckAndResult() {
        val state = HomeUiState(
            selectedTargets = listOf(target),
            visibleApps = listOf(VisibleProtectionApp(target.identity.packageName, target.label, target.identity, true)),
            visibleAppsLoadState = HomeSourceLoadState.LOADED,
            automaticProtection = AutomaticProtectionState(enabled = true, savedAuthorization = true,
                targets = listOf(AutomaticProtectionTargetState(target.identity,
                    savedAuthorization = true, enabled = true, detail = "Fixture protection details"))),
            recoveryCounts = RecoveryCountsUiState(available = true,
                byIdentity = mapOf(target.identity to TargetRecoveryCounts(today = 2, week = 4)),
                calculatedAtMillis = snapshotMillis, zoneId = "Asia/Shanghai"),
        )
        compose.setContent { MaterialTheme {
            HomeScreen(state = state, onShizukuAction = {},
                onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
                onSetTargetSelected = { _, _ -> }, onCheckTarget = {},
                onRunDailyProtection = {}, onClearCorruptTargetSelection = {}, onRequestFcmToken = {},
                onToggleFcmTokenVisibility = {}, onCopyFcmToken = {}, onClearFcmToken = {},
                onRequestNotificationPermission = {}, onSetNotificationFactsEnabled = {},
                onOpenNotificationListenerSettings = {}, onClearNotificationFactsHistory = {},
                onExportNotificationFacts = {})
        } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithText(countsText).assertDoesNotExist()
        compose.onAllNodesWithTag("app-details:${target.identity.packageName}:true").assertCountEquals(1)
        compose.onNodeWithTag("app-details:${target.identity.packageName}:true").performClick()
        compose.onAllNodesWithTag("app-details:${target.identity.packageName}:true").assertCountEquals(1)
        compose.onAllNodesWithText("Fixture").assertCountEquals(1)
        compose.onNodeWithText("今日 2 · 本周 4 次确认解除").assertExists()
        compose.onNodeWithText(countsText).assertDoesNotExist()
        compose.onNodeWithText("复检结果：可被后台唤醒（未被停止）").assertExists()
        compose.onNodeWithText("自启动权限：尚未检查").assertDoesNotExist()
        compose.onNodeWithText("Fixture protection details").assertDoesNotExist()
        compose.onNodeWithText("复检").assertIsEnabled()
        compose.onNodeWithText("系统设置").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        compose.onNodeWithText("详情").assertDoesNotExist()
        compose.onNodeWithText("收起").assertDoesNotExist()
        compose.onNodeWithTag("app-details:${target.identity.packageName}:true").performClick()
        compose.onNodeWithText(countsText).assertDoesNotExist()
        compose.onNodeWithText("复检结果：可被后台唤醒（未被停止）").assertDoesNotExist()
        compose.onNodeWithText("Fixture protection details").assertDoesNotExist()
        compose.onNodeWithText("复检").assertDoesNotExist()
        compose.onAllNodesWithTag("app-details:${target.identity.packageName}:true").assertCountEquals(1)
        compose.onAllNodesWithText("Fixture").assertCountEquals(1)
    }

    @Test fun embeddedBodyIgnoresInnerCollapsedStateAndPreservesBusyAndRecoveryControls() {
        compose.setContent { MaterialTheme {
            SelectedTargetDetails(target, checking = false,
                recovery = StoppedRecoveryUiState(loaded = true, needsReconcile = true,
                    recordIdentity = target.identity), recoveryEligible = false, detectionBusy = true,
                onCheck = {}, onSettings = {}, onRemove = {}, onRequestRecovery = {}, onReconcile = {},
                automaticControls = { Text("Fixture automatic controls") },
                initiallyExpanded = false, confirmedCounts = countsText, showHeader = false)
        } }
        compose.onNodeWithContentDescription("Fixture详情").assertDoesNotExist()
        compose.onNodeWithText("Fixture").assertDoesNotExist()
        compose.onNodeWithText(countsText).assertExists()
        compose.onNodeWithText("可被后台唤醒（未被停止）").assertExists()
        compose.onNodeWithText("Fixture automatic controls").assertExists()
        compose.onNodeWithText("复检").assertIsNotEnabled()
        compose.onNodeWithText("系统设置").assertIsEnabled()
        compose.onNodeWithText("移除").assertIsEnabled()
        compose.onNodeWithText("核对上次结果（只读）").assertIsNotEnabled()
    }

    @Test fun standaloneDefaultRetainsItsOwnExpansionHeader() {
        compose.setContent { MaterialTheme {
            SelectedTargetDetails(target, false, StoppedRecoveryUiState(), false,
                onCheck = {}, onSettings = {}, onRemove = {}, onRequestRecovery = {}, onReconcile = {})
        } }
        compose.onAllNodesWithText("Fixture").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Fixture详情").assertCountEquals(1)
        compose.onNodeWithText("复检").assertExists()
        compose.onNodeWithContentDescription("Fixture详情").performClick()
        compose.onNodeWithText("复检").assertDoesNotExist()
        compose.onNodeWithText("可被后台唤醒（未被停止）").assertExists()
        compose.onNodeWithContentDescription("Fixture详情").performClick()
        compose.onNodeWithText("复检").assertExists()
    }
}
