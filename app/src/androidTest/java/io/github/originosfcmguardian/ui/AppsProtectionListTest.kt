package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI callbacks only; these fixtures never authorize or operate an installed application. */
class AppsProtectionListTest {
    @Test fun bulkRecheckButtonUsesSavedChoicesEvenWhenSearchHidesThem() {
        var checks = 0
        val state = ready.copy(automaticProtection = AutomaticProtectionState(targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = true))))
        compose.setContent { MaterialTheme { AppsProtectionList(state, "NO_MATCH", {}, {}, {}, {}, {}, {},
            Modifier.fillMaxSize(), onRecheckSelected = { checks++ }) } }
        compose.onNodeWithText("复检已选应用").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @get:Rule val compose = createComposeRule()
    private val first = SelectedTargetIdentity("example.first", 10123, 1, listOf("a".repeat(64)))
    private val second = first.copy(packageName = "example.second", uid = 10124)
    private val ready = HomeUiState(visibleApps = listOf(
        VisibleProtectionApp(first.packageName, "First", first, true),
        VisibleProtectionApp(second.packageName, "Second", second, true)),
        visibleAppsLoadState = HomeSourceLoadState.LOADED, targetSelectionLoadState = HomeSourceLoadState.LOADED,
        recoveryFirmwareSupported = true, stoppedRecovery = StoppedRecoveryUiState(loaded = true),
        shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready"))

    @Test fun systemDisplaySwitchOnlyChangesVisibilityAndKeepsSavedSystemChoice() {
        val core = VisibleProtectionApp("android", "Android")
        val state = mutableStateOf(ready.copy(visibleApps = ready.visibleApps + core,
            hiddenPreinstalledPackages = setOf(first.packageName, core.packageName),
            automaticProtection = AutomaticProtectionState(targets = listOf(
                AutomaticProtectionTargetState(first, savedAuthorization = true)))))
        var displayChanges = 0
        var grants = 0
        var revocations = 0
        compose.setContent { MaterialTheme { AppsProtectionList(state.value, "", {}, {},
            { grants++ }, { revocations++ }, {}, {}, Modifier.fillMaxSize(),
            onShowSystemAppsChanged = { displayChanges++; state.value = state.value.copy(showSystemApps = it) }) } }
        compose.onNodeWithContentDescription("显示系统应用").assertIsOff().performClick()
        compose.onNodeWithContentDescription("显示系统应用").assertIsOn()
        compose.onNodeWithContentDescription("已保护（1）").assertExists()
        compose.onNodeWithContentDescription("所有应用（2）").assertExists()
        compose.onNodeWithContentDescription("Android 自动保护选择").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Second 自动保护选择").assertExists()
        compose.onNodeWithContentDescription("显示系统应用").performClick()
        compose.onNodeWithContentDescription("显示系统应用").assertIsOff()
        compose.onNodeWithContentDescription("Android 自动保护选择").assertDoesNotExist()
        compose.onNodeWithContentDescription("First 自动保护选择").assertIsOn().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(2, displayChanges)
            assertEquals(0, grants)
            assertEquals(0, revocations)
            assertEquals(listOf(first), state.value.automaticProtection.configuredIdentities)
        }
    }

    @Test fun changingSystemDisplayDoesNotTreatOpenedAppAsUninstalled() {
        val state = mutableStateOf(ready.copy(showSystemApps = true,
            hiddenPreinstalledPackages = setOf(first.packageName)))
        compose.setContent { MaterialTheme { AppsProtectionList(state.value, "", {}, {}, {}, {}, {},
            { Text("${it.currentInstallation} installation fixture") }, Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("app-details:${first.packageName}:true").performClick()
        compose.onNodeWithText("true installation fixture").assertExists()
        compose.runOnIdle { state.value = state.value.copy(showSystemApps = false) }
        compose.onNodeWithText("true installation fixture").assertExists()
        compose.onNodeWithText("应用已卸载或更新，请刷新").assertDoesNotExist()
        compose.onNodeWithContentDescription("First 详情内 自动保护选择").assertIsOff().assertIsEnabled()
    }

    @Test fun listShowsSavedAuthorizationFirstAndSearchesAllUnselectedApps() {
        val query = mutableStateOf("")
        var stopped: SelectedTargetIdentity? = null
        val state = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true, targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = true, enabled = false))))
        compose.setContent { MaterialTheme { AppsProtectionList(state, query.value, { query.value = it }, {},
            { error("Unexpected enable") }, { stopped = it }, {}, {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription("已保护（1）").assertExists()
        compose.onNodeWithText("等待恢复").assertDoesNotExist()
        compose.onNodeWithContentDescription("First 自动保护选择").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(first, stopped) }
        compose.onNodeWithContentDescription("搜索名称或包名").performTextInput("EXAMPLE.SEC")
        compose.onNodeWithContentDescription("已保护（0）").assertExists()
        compose.onNodeWithContentDescription("所有应用（1）").assertExists()
        compose.onNodeWithText("example.second").assertExists()
        compose.onNodeWithContentDescription("Second 自动保护选择").assertIsOff().assertIsEnabled()
        compose.onNodeWithText("选择应用").assertDoesNotExist()
        compose.onNodeWithText("关注应用").assertDoesNotExist()
    }

    @Test fun unreadableSelectionDoesNotPresentVerifiedEmptyConfiguration() {
        val query = mutableStateOf("")
        val state = ready.copy(automaticProtection = AutomaticProtectionState(configurationAvailable = false))
        compose.setContent { MaterialTheme { AppsProtectionList(state, query.value, { query.value = it }, {},
            { error("Unexpected selection") }, { error("Unexpected removal") }, {}, {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription("上次已知选择（0）").assertExists()
        compose.onNodeWithText("已保护").assertDoesNotExist()
        compose.onNodeWithText("应用选择记录暂不可用").assertExists()
        compose.onNodeWithText("尚未选择保护应用").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索名称或包名").performTextInput("Telegram")
        compose.onNodeWithText("应用选择记录暂不可用").assertExists()
        compose.onNodeWithText("没有匹配的已选择应用").assertDoesNotExist()
    }

    @Test fun directSwitchOpensConsentAndCancelDoesNotCheckOrEnrollApp() {
        val state = mutableStateOf(ready)
        var grants = 0
        compose.setContent { MaterialTheme {
            AppsProtectionList(state.value, "", {}, {},
                { state.value = state.value.copy(automaticConfirmationIdentity = second) }, {}, {}, {}, Modifier.fillMaxSize())
            if (state.value.automaticConfirmationIdentity != null) AutomaticProtectionConfirmation(
                { grants++ }, { state.value = state.value.copy(automaticConfirmationIdentity = null) }, "Second", second.packageName)
        } }
        compose.onNodeWithContentDescription("Second 自动保护选择").performClick()
        compose.onNodeWithText("选择 Second 自动保护？").assertExists()
        compose.runOnIdle { assertEquals(0, grants); assertEquals(0, state.value.selectedTargets.size) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("Second 自动保护选择").assertIsOff().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, grants); assertEquals(0, state.value.selectedTargets.size) }
    }

    @Test fun unsupportedRowsAndFailedLoadingRemainClearAndRetryable() {
        val state = mutableStateOf(ready.copy(visibleApps = listOf(ready.visibleApps.first().copy(supported = false)),
            visibleAppsLoadState = HomeSourceLoadState.FAILED, appsMessage = "读取失败"))
        var retries = 0
        compose.setContent { MaterialTheme { AppsProtectionList(state.value, "", {}, { retries++ }, {}, {}, {}, {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("此应用不支持自动保护").assertDoesNotExist()
        compose.onNodeWithContentDescription("First 自动保护选择").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("app-details:${first.packageName}:true").performClick()
        compose.onNodeWithText("此应用不支持自动保护").assertExists()
        compose.onNodeWithContentDescription("First 详情内 自动保护选择").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals(1, retries); state.value = state.value.copy(visibleAppsLoadState = HomeSourceLoadState.LOADED, visibleApps = emptyList()) }
        compose.onNodeWithText("没有其他可见应用").assertExists()
    }

    @Test fun applicationHeaderOpensOnlyItsSheetAndCloseReturnsToCompactList() {
        val state = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true, targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = true),
            AutomaticProtectionTargetState(second, savedAuthorization = true))))
        compose.setContent { MaterialTheme { AppsProtectionList(state, "", {}, {}, {}, {}, {},
            { Text("${it.app.label} detail fixture") }, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("详情").assertDoesNotExist()
        compose.onNodeWithText("收起").assertDoesNotExist()
        compose.onNodeWithText("First detail fixture").assertDoesNotExist()
        compose.onNodeWithTag("app-details:${first.packageName}:true").performClick()
        compose.onNodeWithTag("app-sheet:${first.packageName}").assertExists()
        compose.onNodeWithText("First detail fixture").assertExists()
        compose.onNodeWithText("Second detail fixture").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("app-sheet:${first.packageName}").assertDoesNotExist()
        compose.onNodeWithText("First detail fixture").assertDoesNotExist()
        compose.onNodeWithTag("app-details:${second.packageName}:true").performClick()
        compose.onNodeWithText("Second detail fixture").assertExists()
        compose.onNodeWithText("First detail fixture").assertDoesNotExist()
    }

    @Test fun rowSwitchAndSheetSwitchUseCallbacksWithoutOpeningOtherApp() {
        var stops = 0
        val state = ready.copy(automaticProtection = AutomaticProtectionState(masterEnabled = true, targets = listOf(
            AutomaticProtectionTargetState(first, savedAuthorization = true))))
        compose.setContent { MaterialTheme { AppsProtectionList(state, "", {}, {}, {}, { stops++ }, {},
            { Text("${it.app.label} detail fixture") }, Modifier.fillMaxSize()) } }
        compose.onNodeWithContentDescription("First 自动保护选择").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("First detail fixture").assertDoesNotExist()
        compose.onNodeWithTag("app-details:${first.packageName}:true").performClick()
        compose.onNodeWithText("First detail fixture").assertExists()
        compose.onNodeWithContentDescription("First 详情内 自动保护选择").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(2, stops) }
        compose.onNodeWithText("First detail fixture").assertExists()
        compose.onNodeWithText("Second detail fixture").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("app-details:${second.packageName}:true").performClick()
        compose.onNodeWithText("Second detail fixture").assertExists()
        compose.onNodeWithContentDescription("Second 详情内 自动保护选择").assertIsOff()
    }

    @Test fun sheetKeepsOpenedIdentityWhenInstallationChanges() {
        val state = mutableStateOf(ready)
        var enables = 0
        compose.setContent { MaterialTheme { AppsProtectionList(state.value, "", {}, {}, { enables++ }, {}, {},
            { Text("${it.currentInstallation} installation fixture") }, Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("app-details:${first.packageName}:true").performClick()
        compose.onNodeWithText("true installation fixture").assertExists()
        compose.runOnIdle {
            state.value = ready.copy(visibleApps = listOf(ready.visibleApps.first().copy(identity = first.copy(uid = 10223))))
        }
        compose.onNodeWithText("应用已卸载或更新，请刷新").assertExists()
        compose.onNodeWithText("false installation fixture").assertExists()
        compose.onNodeWithContentDescription("First 详情内 自动保护选择").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, enables) }
    }

    @Test fun selectingInSheetClosesSheetBeforeConfirmationAppears() {
        val state = mutableStateOf(ready)
        var grants = 0
        compose.setContent { MaterialTheme {
            AppsProtectionList(state.value, "", {}, {},
                { state.value = state.value.copy(automaticConfirmationIdentity = second) }, {}, {}, {}, Modifier.fillMaxSize())
            if (state.value.automaticConfirmationIdentity != null) AutomaticProtectionConfirmation(
                { grants++ }, { state.value = state.value.copy(automaticConfirmationIdentity = null) }, "Second", second.packageName)
        } }
        compose.onNodeWithTag("app-details:${second.packageName}:true").performClick()
        compose.onNodeWithContentDescription("Second 详情内 自动保护选择").assertIsOff().performClick()
        compose.onNodeWithTag("app-sheet:${second.packageName}").assertDoesNotExist()
        compose.onNodeWithText("选择 Second 自动保护？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("Second 自动保护选择").assertIsOff()
        compose.runOnIdle { assertEquals(0, grants); assertEquals(0, state.value.selectedTargets.size) }
    }
}
