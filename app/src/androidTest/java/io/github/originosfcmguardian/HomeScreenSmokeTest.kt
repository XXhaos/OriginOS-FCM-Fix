package io.github.originosfcmguardian

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeExplainsReadOnlyScopeAndDisablesWrites() {
        composeRule.onNodeWithText("只读检测").assertIsDisplayed()
        composeRule.onNodeWithText("一键修复").assertIsNotEnabled()
        composeRule.onNodeWithText("恢复原始配置").assertIsNotEnabled()
    }
}
