package io.github.originosfcmguardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsUnifiedProtectionAndDirectReportWithoutPlaceholderWrites() {
        composeRule.onNodeWithText("Guardian").assertIsDisplayed()
        composeRule.onNodeWithText("Shizuku").assertIsDisplayed()
        composeRule.onNodeWithText("FCM Diagnostics").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("应用标签").assertIsDisplayed()
        composeRule.onNodeWithText("FCM推送保护").assertExists()
        composeRule.onNodeWithContentDescription("FCM推送保护开关").assertExists()
        composeRule.onNodeWithText("打开诊断信息").assertDoesNotExist()
        composeRule.onNodeWithText("问题摘要").assertDoesNotExist()
        composeRule.onNodeWithText("一键修复").assertDoesNotExist()
        composeRule.onNodeWithText("恢复原始配置").assertDoesNotExist()
        composeRule.onNodeWithText("原始状态记录").assertDoesNotExist()
        composeRule.onNodeWithText("查看工具入口").assertDoesNotExist()
        composeRule.onNodeWithText("定期巡检").assertDoesNotExist()
        composeRule.onNodeWithText("导出报告").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("导出脱敏报告").performScrollTo().assertIsDisplayed()
    }
}
