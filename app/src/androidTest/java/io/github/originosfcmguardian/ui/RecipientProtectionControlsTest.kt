package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic counters only: never invokes Runtime, Shizuku or a recovery command. */
class RecipientProtectionControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneTargetCanStopWhileAnotherRemainsUnconsented() {
        var firstStops = 0
        var secondRequests = 0
        compose.setContent { MaterialTheme { Column {
            AppProtectionSelection(true, false, { error("Unexpected first grant") }, { firstStops++ }, "First")
            AppProtectionSelection(false, true,
                { secondRequests++ }, { error("Unexpected second stop") }, "Second")
        } } }
        compose.onNodeWithContentDescription("First 自动保护选择").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, firstStops); assertEquals(0, secondRequests) }
        compose.onNodeWithContentDescription("Second 自动保护选择").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(1, firstStops); assertEquals(1, secondRequests) }
    }

    @Test fun confirmationNamesExactRecipientAndCancelGrantsNothing() {
        var grants = 0
        var cancels = 0
        compose.setContent { MaterialTheme {
            AutomaticProtectionConfirmation({ grants++ }, { cancels++ }, "Second", "example.second")
        } }
        compose.onNodeWithText("选择 Second 自动保护？").assertExists()
        compose.onNodeWithText("example.second", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, grants); assertEquals(1, cancels) }
    }
}
