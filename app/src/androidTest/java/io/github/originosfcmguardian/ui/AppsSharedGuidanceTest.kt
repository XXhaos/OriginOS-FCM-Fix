package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose fixtures only; no service, installed-target, storage or recovery operations. */
class AppsSharedGuidanceTest {
    @get:Rule val compose = createComposeRule()
    private val polling = "约每 30 秒检查，休眠可能延后；不让应用常驻。"
    private val limits = "被停止时尝试恢复，无固定次数或间隔限制；不保证送达。"
    private val shared = "约每 30 秒检查；休眠可能延后，不保证送达。"

    @Test fun appSheetsKeepTheirOwnStatusAndActionsWithoutRepeatedGuidance() {
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
            HomeScreen(state = state, onShizukuAction = {},
                onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
                onSetTargetSelected = { _, _ -> }, onCheckTarget = {},
                onClearCorruptTargetSelection = {})
        } }
        compose.onNodeWithText("已选 2 个应用").assertExists()
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("已保护（2）").assertExists()
        assertNoSharedHints()
        compose.onNodeWithTag("app-details:${first.identity.packageName}:true").performClick()
        compose.onNodeWithText("First fixture waiting reason").assertDoesNotExist()
        compose.onNodeWithText("上次检测：${formatRecordTime(firstTime)}").assertDoesNotExist()
        compose.onNodeWithText("上次自动恢复：停止限制已解除").assertDoesNotExist()
        compose.onNodeWithText("复检结果：允许后台唤醒").assertExists()
        assertDetailActions()
        assertNoSharedHints()

        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("app-details:${second.identity.packageName}:true").performClick()
        compose.onNodeWithText("Second fixture paused reason").assertDoesNotExist()
        compose.onNodeWithText("上次检测：${formatRecordTime(secondTime)}").assertDoesNotExist()
        compose.onNodeWithText("上次恢复未成功：限制仍存在").assertDoesNotExist()
        compose.onNodeWithText("复检结果：后台唤醒受限").assertExists()
        compose.onNodeWithText("撤销已保存授权").assertDoesNotExist()
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        assertDetailActions()
        assertNoSharedHints()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("app-details:${first.identity.packageName}:true").performClick()
        compose.onNodeWithText("复检结果：允许后台唤醒").assertExists()
        assertNoSharedHints()
    }

    @Test fun standaloneCardShowsStateWithoutTimingAndRecoveryDisclosures() {
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(), false, false, null, {}, {}, {}, showPreferences = false)
        } }
        compose.onNodeWithText("保护未开启").assertExists()
        assertNoSharedHints()
    }

    @Test fun consentConfirmationNamesScopeAndKeepsExplicitActions() {
        compose.setContent { MaterialTheme {
            AutomaticProtectionConfirmation({}, {}, "Fixture", "example.guidance.consent")
        } }
        compose.onNodeWithText("example.guidance.consent").assertExists()
        compose.onNodeWithText("允许 Guardian 在此应用被停止时自动恢复后台唤醒。").assertExists()
        compose.onNodeWithText("授权并选择").assertIsEnabled()
        compose.onNodeWithText("取消").assertIsEnabled()
    }

    private fun assertNoSharedHints() {
        compose.onAllNodesWithText(shared).assertCountEquals(0)
        compose.onAllNodesWithText(polling).assertCountEquals(0)
        compose.onAllNodesWithText(limits).assertCountEquals(0)
    }

    private fun assertDetailActions() {
        compose.onAllNodesWithText("复检").onLast().assertIsEnabled()
        compose.onNodeWithText("系统设置").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
    }
}
