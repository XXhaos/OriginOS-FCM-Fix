package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.fcmprotection.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only; never starts actual protection or privileged actions. */
class FcmProtectionControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unifiedControlNeedsExplicitConfirmationAndKeepsBranchesInternal() {
        var requests = 0
        compose.setContent { MaterialTheme { FcmProtectionControls(FcmProtectionUiState(), true, { requests++ }, {}) } }
        compose.onNodeWithText("FCM 保护").assertExists()
        compose.onNodeWithText("FCM 保护（试用）").assertDoesNotExist()
        compose.onNodeWithText("低频心跳").assertDoesNotExist()
        compose.onNodeWithText("FCM 恢复").assertDoesNotExist()
        compose.onNodeWithText("低频心跳（试用）").assertDoesNotExist()
        compose.onNodeWithText("FCM 恢复（试用）").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithContentDescription("FCM 保护开关").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
    @Test fun combinedDialogDoesNotConfirmUntilUserClicks() {
        var confirms = 0
        compose.setContent { MaterialTheme { FcmProtectionConfirmation({ confirms++ }, {}) } }
        compose.onNodeWithText("开启 FCM 保护？").assertExists()
        compose.onNodeWithText("开启 FCM 保护试用？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("确认开启 FCM 保护").performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }
}
