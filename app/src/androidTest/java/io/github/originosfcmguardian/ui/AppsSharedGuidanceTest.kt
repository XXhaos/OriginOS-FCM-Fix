package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose fixtures only; no service, installed-target, storage or recovery operations. */
class AppsSharedGuidanceTest {
    @get:Rule val compose = createComposeRule()
    private val polling = "约每 30 秒检查，休眠可能延后；不保持目标应用常驻。"
    private val limits = "检测到停止限制时尝试恢复，无固定恢复次数或间隔限制。"
    private val shared = "约每 30 秒检查，被停止时尝试恢复；无固定恢复次数或间隔限制。休眠可能延后，不保证通知送达。"

    @Test fun twoExpandedAppsShareOneHintAndKeepTheirOwnEvidenceAndActions() {
        val first = SelectedTargetUiState(
            SelectedTargetIdentity("example.guidance.first", 10123, 1L, listOf("a".repeat(64))), "First",
            stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10))
        val second = first.copy(identity = first.identity.copy(packageName = "example.guidance.second", uid = 10124),
            label = "Second", stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 20))
        val firstTime = 1_700_000_000_000L
        val secondTime = firstTime + 60_000L
        val state = HomeUiState(selectedTargets = listOf(first, second),
            visibleApps = listOf(first, second).map { VisibleProtectionApp(it.identity.packageName, it.label, it.identity, true) },
            visibleAppsLoadState = HomeSourceLoadState.LOADED, targetSelectionLoadState = HomeSourceLoadState.LOADED,
            recoveryFirmwareSupported = true, stoppedRecovery = StoppedRecoveryUiState(loaded = true),
            shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "Fixture shell ready"),
            automaticProtection = AutomaticProtectionState(targets = listOf(
                AutomaticProtectionTargetState(first.identity, savedAuthorization = true, waitingForPrivilege = true,
                    detail = "First fixture waiting reason", lastCheckedAtMillis = firstTime, lastOutcome = "FLAG_CLEARED"),
                AutomaticProtectionTargetState(second.identity, savedAuthorization = true,
                    detail = "Second fixture paused reason", lastCheckedAtMillis = secondTime, lastOutcome = "STILL_STOPPED"))))
        compose.setContent { MaterialTheme {
            HomeScreen(state = state, onDetect = {}, onShizukuAction = {},
                onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
                onSetTargetSelected = { _, _ -> }, onCheckTarget = {},
                onRunDailyProtection = {}, onClearCorruptTargetSelection = {}, onRequestFcmToken = {},
                onToggleFcmTokenVisibility = {}, onCopyFcmToken = {}, onClearFcmToken = {},
                onRequestNotificationPermission = {}, onSetNotificationFactsEnabled = {},
                onOpenNotificationListenerSettings = {}, onClearNotificationFactsHistory = {},
                onExportNotificationFacts = {})
        } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithText("已选择（2）").assertExists()
        assertOneSharedHint()
        compose.onNodeWithTag("app-details:${first.identity.packageName}:true").performClick()
        compose.onNodeWithText("First fixture waiting reason").assertDoesNotExist()
        compose.onNodeWithText("上次检测：${formatRecordTime(firstTime)}").assertDoesNotExist()
        compose.onNodeWithText("上次自动恢复：停止限制已解除").assertDoesNotExist()
        compose.onNodeWithText("复检结果：可被后台唤醒（未被停止）").assertExists()
        assertDetailActions()
        assertOneSharedHint()

        // Lazy rows may leave the viewport; keep both expansion states and inspect each identity.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithTag("app-details:${second.identity.packageName}:true").performClick()
        compose.onNodeWithText("Second fixture paused reason").assertDoesNotExist()
        compose.onNodeWithText("上次检测：${formatRecordTime(secondTime)}").assertDoesNotExist()
        compose.onNodeWithText("上次恢复未成功：限制仍存在").assertDoesNotExist()
        compose.onNodeWithText("复检结果：后台唤醒受限（已被停止）").assertExists()
        compose.onNodeWithText("撤销已保存授权").assertDoesNotExist()
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        assertDetailActions()
        assertOneSharedHint()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("复检结果：可被后台唤醒（未被停止）").assertExists()
        assertOneSharedHint()
    }

    @Test fun standaloneCardDefaultKeepsBothOriginalExplanations() {
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(), false, false, null, {}, {}, {}, showPreferences = false)
        } }
        compose.onAllNodesWithText(polling).assertCountEquals(1)
        compose.onAllNodesWithText(limits).assertCountEquals(1)
        compose.onNodeWithText(shared).assertDoesNotExist()
    }

    @Test fun consentConfirmationKeepsAllOriginalDisclosures() {
        val disclosure = "应用选择会保存；首页总开关开启时才运行保护。关闭总开关会保留应用选择，取消此应用选择会移除它的授权。"
        compose.setContent { MaterialTheme {
            AutomaticProtectionConfirmation({}, {}, "Fixture", "example.guidance.consent")
        } }
        compose.onNodeWithText(disclosure, substring = true).assertExists()
        compose.onNodeWithText("授权并选择").assertIsEnabled()
        compose.onNodeWithText("取消").assertIsEnabled()
    }

    private fun assertOneSharedHint() {
        compose.onAllNodesWithText(shared).assertCountEquals(1)
        compose.onAllNodesWithText(polling).assertCountEquals(0)
        compose.onAllNodesWithText(limits).assertCountEquals(0)
    }

    private fun assertDetailActions() {
        compose.onAllNodesWithText("复检").onLast().assertIsEnabled()
        compose.onNodeWithText("系统设置").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
    }
}
