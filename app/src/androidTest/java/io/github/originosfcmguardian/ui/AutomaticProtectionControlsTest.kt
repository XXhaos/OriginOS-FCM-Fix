package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.ProtectionNotificationVisibility
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI fixtures only. No service launch, permission grant or recovery operation. */
class AutomaticProtectionControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notificationSettingsRemainReachableAsSystemVisibilityChanges() {
        val visibility = mutableStateOf(ProtectionNotificationVisibility.ALLOWED)
        var settingsOpened = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(masterEnabled = true, savedAuthorization = true), false, false, null,
                { error("Unexpected enable") }, { error("Unexpected revoke") }, {},
                onOpenNotificationSettings = { settingsOpened++; true }, showProtectionControls = false,
                notificationVisibility = visibility.value)
        } }
        compose.onNodeWithText("保护通知：允许显示").assertExists()
        compose.onNodeWithText("通知显示设置").assertIsEnabled().performClick()
        compose.runOnIdle { visibility.value = ProtectionNotificationVisibility.BLOCKED }
        compose.onNodeWithText("保护通知：系统已关闭").assertExists()
        compose.onNodeWithText("保护通知：允许显示").assertDoesNotExist()
        compose.onNodeWithText("通知显示设置").assertIsEnabled().performClick()
        compose.runOnIdle { visibility.value = ProtectionNotificationVisibility.UNKNOWN }
        compose.onNodeWithText("保护通知：状态未知").assertExists()
        compose.onNodeWithText("保护通知：系统已关闭").assertDoesNotExist()
        compose.onNodeWithText("通知显示设置").assertIsEnabled().performClick()
        compose.onNodeWithText("关闭此类通知不关闭保护。").assertExists()
        compose.runOnIdle { assertEquals(3, settingsOpened) }
    }

    @Test fun failedNotificationSettingsLaunchReportsFailureAndAllowsRetry() {
        var settingsOpened = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(), false, false, null,
                { error("Unexpected enable") }, { error("Unexpected revoke") }, {},
                onOpenNotificationSettings = { ++settingsOpened > 1 }, showProtectionControls = false)
        } }
        val failure = "无法打开设置，请在系统通知管理中调整 Guardian 保护通知。"
        compose.onNodeWithText("通知显示设置").performClick()
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText("通知显示设置").assertIsEnabled().performClick()
        compose.onNodeWithText(failure).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, settingsOpened) }
    }

    @Test fun enablingRequiresConfirmationAndCancelDoesNotGrant() {
        var grants = 0
        var cancels = 0
        compose.setContent { MaterialTheme { AutomaticProtectionConfirmation({ grants++ }, { cancels++ }) } }
        compose.runOnIdle { assertEquals(0, grants) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, grants); assertEquals(1, cancels) }
        compose.onNodeWithText("授权并选择").performClick()
        compose.runOnIdle { assertEquals(1, grants) }
    }
    @Test fun activeSwitchCanAlwaysStopEvenWhenEnablePrerequisitesLost() {
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(masterEnabled = true, enabled = true), false, false, null,
                { error("Unexpected enable") }, { stops++ }, {})
        } }
        compose.onNodeWithContentDescription("自动保护总开关").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun hiddenTaskChoiceRequiresConfirmationAndDoesNotStartProtection() {
        var hidden = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(), false, false, null,
                { error("Unexpected enable") }, {}, { if (it) hidden++ })
        } }
        compose.onNodeWithContentDescription("自动保护总开关").assertIsNotEnabled()
        compose.onNodeWithContentDescription("最近任务隐藏开关").performClick()
        compose.runOnIdle { assertEquals(0, hidden) }
        compose.onNodeWithText("确认隐藏").performClick()
        compose.runOnIdle { assertEquals(1, hidden) }
    }
    @Test fun startingSessionCanBeStoppedBeforeEnabled() {
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(masterEnabled = true, busy = true), false, false, null,
                { error("Unexpected second enable") }, { stops++ }, {})
        } }
        compose.onNodeWithContentDescription("自动保护总开关").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun savedGrantCanBeRevokedWithoutShellOrLiveService() {
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(masterEnabled = true, savedAuthorization = true), false, false, null,
                { error("Unexpected enable") }, { stops++ }, {})
        } }
        compose.onNodeWithContentDescription("自动保护总开关").assertIsEnabled()
        compose.onNodeWithContentDescription("自动保护总开关").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun failedStopPersistenceAlwaysExposesRetry() {
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(revocationSaveFailed = true), false, false, null,
                { error("Unexpected enable") }, { stops++ }, {})
        } }
        compose.onNodeWithContentDescription("自动保护总开关").assertIsNotEnabled()
        compose.onNodeWithText("重试保存关闭状态").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun pendingMasterOnCannotBeClickedIntoAnOffRequest() {
        val state = mutableStateOf(AutomaticProtectionState())
        var starts = 0
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionToggle(state.value, canEnable = true,
                onEnable = { starts++; state.value = state.value.copy(busy = true) },
                onDisable = { stops++ })
        } }
        compose.onNodeWithContentDescription("自动保护总开关").performClick()
        compose.onNodeWithContentDescription("自动保护总开关").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
    }

    @Test fun waitingForPrivilegeKeepsStopReachableWithoutClaimingProtection() {
        var stops = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionCard(AutomaticProtectionState(masterEnabled = true, waitingForPrivilege = true, savedAuthorization = true),
                false, false, null, { error("Unexpected enable") }, { stops++ }, {})
        } }
        compose.onNodeWithText("等待 Shizuku").assertExists()
        compose.onNodeWithText("检测已开启").assertDoesNotExist()
        compose.onNodeWithContentDescription("自动保护总开关").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
}
