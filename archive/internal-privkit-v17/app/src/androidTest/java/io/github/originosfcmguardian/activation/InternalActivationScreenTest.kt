package io.github.originosfcmguardian.activation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic input and callbacks only; never starts activation or opens system settings. */
class InternalActivationScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        status: InternalActivationStatus = InternalActivationStatus(),
        onStart: () -> Unit = {},
        onCancel: () -> Unit = {},
        onCleanup: () -> Unit = {},
        notificationsAvailable: Boolean = true,
        pairingActive: Boolean = false,
        onNotificationSettings: () -> Unit = {},
    ) {
        compose.setContent { MaterialTheme {
            InternalActivationScreen(status, pairingActive, "", notificationsAvailable, "", {},
                onPair = {}, onStart = onStart, onCancel = onCancel,
                onNotificationSettings = onNotificationSettings,
                onManualCleanup = onCleanup, onOpenSettings = {}, onReturn = {})
        } }
    }

    @Test fun tutorialsStartCollapsedAndReadyRemainsSeparateFromProtection() {
        var starts = 0
        show(InternalActivationStatus(ready = true, message = "synthetic ready"), onStart = { starts++ })
        compose.onNodeWithText("内置服务（试用）").assertIsDisplayed()
        compose.onNodeWithText("当前自动保护仍使用外部 Shizuku").assertIsDisplayed()
        compose.onNodeWithText("配对和启动会保留原本开启的无线调试；仅关闭本次临时开启的无线调试，期间请勿切换开关。").assertIsDisplayed()
        compose.onNodeWithText("adb shell pm grant io.github.originosfcmguardian android.permission.WRITE_SECURE_SETTINGS").assertDoesNotExist()
        compose.onNodeWithText("本页仅由你手动启动自有特权服务，启动同意独立于推送保护。尚未接入自动无线启动或第三方恢复。").assertDoesNotExist()
        compose.onNodeWithText("开始通知配对").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("六位配对码").assertDoesNotExist()
        compose.onNodeWithText("配对端口（可留空自动发现）").assertDoesNotExist()
        compose.onNodeWithText("启动并核验内置服务").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithText("一次性电脑授权 · 展开教程").performScrollTo().performClick()
        compose.onNodeWithText("adb shell pm grant io.github.originosfcmguardian android.permission.WRITE_SECURE_SETTINGS").assertExists()
    }

    @Test fun busyCancelStaysReachableAndSubmissionIsDisabled() {
        var cancels = 0
        show(InternalActivationStatus(busy = true, message = "synthetic busy"), onCancel = { cancels++ })
        compose.onNodeWithText("取消并清理").assertIsDisplayed().performClick()
        compose.onNodeWithText("开始通知配对").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("启动并核验内置服务").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun cleanupActionStaysReachableAndNormalActionsAreBlocked() {
        var cleanups = 0
        show(InternalActivationStatus(needsManualCleanup = true, message = "synthetic cleanup"),
            onCleanup = { cleanups++ })
        compose.onNodeWithText("确认已手动关闭无线调试").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("开始通知配对").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("启动并核验内置服务").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, cleanups) }
    }

    @Test fun hiddenNotificationsBlockPairingAndOfferSettingsWithoutStarting() {
        var settings = 0
        show(notificationsAvailable = false, onNotificationSettings = { settings++ })
        compose.onNodeWithText("开始通知配对").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("开启配对通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun notificationSessionDisablesSecondPairingAndActivationButKeepsCancel() {
        var cancels = 0
        show(pairingActive = true, onCancel = { cancels++ })
        compose.onNodeWithText("开始通知配对").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("启动并核验内置服务").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("取消并清理").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }
}
