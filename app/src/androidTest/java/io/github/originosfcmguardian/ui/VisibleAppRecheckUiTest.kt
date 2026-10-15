package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic callbacks only: never reads an installed app, enrolls, authorizes or recovers it. */
class VisibleAppRecheckUiTest {
    @get:Rule val compose = createComposeRule()
    private val identity = SelectedTargetIdentity("example.visible.fixture", 10123, 1, listOf("a".repeat(64)))
    private val app = VisibleProtectionApp(identity.packageName, "Fixture", identity, supported = false)

    @Test fun unsupportedUnselectedRowRechecksWithoutShizukuAndKeepsProtectionOff() {
        val state = mutableStateOf(HomeUiState(visibleApps = listOf(app), visibleAppsLoadState = HomeSourceLoadState.LOADED))
        val checked = mutableListOf<SelectedTargetIdentity>()
        compose.setContent { Fixture(state.value) { target ->
            checked += target
            state.value = applyVisibleAppStoppedSnapshot(state.value, target, 1, 1,
                TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 20))
        } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("Fixture 自动保护选择").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("app-details:${identity.packageName}:true").performClick()
        compose.onNodeWithText("复检结果：尚未检查").assertExists()
        compose.onNodeWithText("复检").assertIsEnabled().performClick()
        compose.onNodeWithText("复检结果：后台唤醒受限").assertExists()
        compose.onNodeWithContentDescription("Fixture 自动保护选择").assertIsOff()
        compose.onNodeWithText("系统设置").assertDoesNotExist()
        compose.onNodeWithText("恢复接收").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(identity), checked)
            assertTrue(state.value.selectedTargets.isEmpty())
            assertTrue(state.value.automaticProtection.authorizedIdentities.isEmpty())
        }
    }

    @Test fun oldInstallationCannotDisplayHealthySelectedEvidenceOrRecheckReplacement() {
        val replacement = identity.copy(firstInstallTime = 2)
        val state = HomeUiState(visibleApps = listOf(app.copy(label = "NewFixture", identity = replacement)),
            visibleAppsLoadState = HomeSourceLoadState.LOADED,
            selectedTargets = listOf(SelectedTargetUiState(identity, "OldFixture",
                stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10))))
        compose.setContent { Fixture(state) { error("An old installation must never invoke a check callback") } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("搜索名称或包名").performTextInput("OldFixture")
        compose.onNodeWithTag("app-details:${identity.packageName}:false").performClick()
        compose.onNodeWithText("复检").assertIsNotEnabled()
        compose.onNodeWithText("复检结果：应用已变化，请重新选择").assertExists()
        compose.onNodeWithText("复检结果：允许后台唤醒").assertDoesNotExist()
    }

    @Test fun failedRefreshSuppressesPriorClearResultAndBlocksRead() {
        val state = HomeUiState(visibleApps = listOf(app.copy(
            stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 10))),
            visibleAppsLoadState = HomeSourceLoadState.FAILED)
        compose.setContent { Fixture(state) { error("A failed list must not invoke a check") } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithTag("app-details:${identity.packageName}:true").performClick()
        compose.onNodeWithText("复检").assertIsNotEnabled()
        compose.onNodeWithText("复检结果：状态未知，请复检").assertExists()
        compose.onNodeWithText("复检结果：允许后台唤醒").assertDoesNotExist()
    }

    @Test fun absentIdentityIsUnavailableAndCannotOfferRecheckOrProtection() {
        val state = HomeUiState(visibleApps = listOf(app.copy(identity = null)), visibleAppsLoadState = HomeSourceLoadState.LOADED)
        compose.setContent { Fixture(state) { error("An absent identity must not invoke a check") } }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithText("无法读取应用，请刷新").assertExists()
        compose.onNodeWithTag("app-details:${identity.packageName}:true").assertDoesNotExist()
        compose.onNodeWithText("复检").assertDoesNotExist()
        compose.onNodeWithContentDescription("Fixture 自动保护选择").assertIsOff().assertIsNotEnabled()
    }

    @Composable private fun Fixture(state: HomeUiState, check: (SelectedTargetIdentity) -> Unit) {
        MaterialTheme { HomeScreen(state = state, onShizukuAction = {},
            onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
            onSetTargetSelected = { _, _ -> }, onCheckTarget = { error("No package-only check expected") },
            onClearCorruptTargetSelection = {},

             onCheckVisibleApp = check) }
    }
}
