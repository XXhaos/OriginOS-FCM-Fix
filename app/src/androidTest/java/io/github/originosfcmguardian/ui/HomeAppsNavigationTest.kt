package io.github.originosfcmguardian.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionTargetState
import io.github.originosfcmguardian.autoprotection.masterOffAutomaticProtectionState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Offline UI fixture: all callbacks are counters/no-ops; no service or device operation. */
class HomeAppsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val telegram = SelectedTargetUiState(
        SelectedTargetIdentity("org.telegram.messenger", 10123, 1L, listOf("a".repeat(64))), "Telegram")
    private val other = telegram.copy(identity = telegram.identity.copy(packageName = "example.other"), label = "Other")

    @Test fun homeConnectionSettingsIsVisibleInTopCardAndNavigationHasNoProtectionSideEffects() {
        var actions = 0
        var starts = 0
        var stops = 0
        var checks = 0
        var connectionReads = 0
        val state = mutableStateOf(HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready")))
        compose.setContent { Fixture(state.value, shizukuAction = { actions++ },
            startProtection = { starts++ }, stop = { stops++ }, check = { checks++ },
            readConnection = { connectionReads++ }) }
        listOf(ShizukuStatus.READY_SHELL, ShizukuStatus.NOT_RUNNING).forEach { status ->
            compose.runOnIdle { state.value = state.value.copy(shizuku = ShizukuStatusSnapshot(status, "fixture")) }
            // No scroll: the shortcut belongs to the first card, not a separate bottom card.
            compose.onNodeWithContentDescription("连接与后台设置").assertIsDisplayed().performClick()
            compose.onNodeWithText("工作模式").assertIsDisplayed()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithContentDescription("连接与后台设置").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertTrue("Visible Home refreshes connection evidence", connectionReads > 0)
            assertEquals(0, actions); assertEquals(0, starts); assertEquals(0, stops); assertEquals(0, checks)
        }
    }

    @Test fun homeMissingOrStoppedManagerUsesExistingExplicitActionWithoutAutomaticRequests() {
        var actions = 0
        val state = mutableStateOf(HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.NOT_INSTALLED, "missing")))
        compose.setContent { Fixture(state.value, shizukuAction = { actions++ }) }
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithText("获取 Shizuku").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, actions)
            state.value = state.value.copy(shizuku = ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "stopped"))
        }
        compose.onNodeWithText("获取 Shizuku").assertDoesNotExist()
        compose.onNodeWithText("打开 Shizuku").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, actions) }
        compose.onNodeWithText("管理应用（0）").assertDoesNotExist()
        compose.onNodeWithContentDescription("连接与后台设置").assertExists()
    }

    @Test fun homePermissionActionOnlyInvokesShizukuCallbackWithoutProtectionStopOrChecks() {
        var actions = 0
        var starts = 0
        var stops = 0
        var checks = 0
        val state = HomeUiState(shizuku = ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "permission fixture"),
            automaticProtection = AutomaticProtectionState(savedAuthorization = true))
        compose.setContent { Fixture(state, stop = { stops++ }, shizukuAction = { actions++ },
            check = { checks++ }, startProtection = { starts++ }) }
        compose.runOnIdle { assertEquals(0, actions); assertEquals(0, starts); assertEquals(0, stops); assertEquals(0, checks) }
        compose.onNodeWithText("Shizuku 授权").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions); assertEquals(0, starts); assertEquals(0, stops); assertEquals(0, checks) }
        compose.onNodeWithText("Shizuku").assertExists()
        compose.onNodeWithText("返回").assertDoesNotExist()
    }

    @Test fun homeBlockedPermissionRootAndErrorOpenManagerUsingExistingMapping() {
        var actions = 0
        val state = mutableStateOf(HomeUiState(shizuku = ShizukuStatusSnapshot(
            ShizukuStatus.PERMISSION_REQUIRED, "blocked fixture", authorizationBlocked = true)))
        compose.setContent { Fixture(state.value, shizukuAction = { actions++ }) }
        compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
        compose.onNodeWithText("打开 Shizuku").assertIsDisplayed().performClick()
        listOf(ShizukuStatus.READY_ROOT, ShizukuStatus.ERROR).forEachIndexed { index, status ->
            compose.runOnIdle {
                assertEquals(index + 1, actions)
                state.value = state.value.copy(shizuku = ShizukuStatusSnapshot(status, "fixture"))
            }
            compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
            compose.onNodeWithText("打开 Shizuku").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(3, actions) }
    }

    @Test fun homeShellReadyHidesDirectActionIncludingAfterStatusUpdate() {
        var actions = 0
        val ready = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "ready fixture")
        val state = mutableStateOf(HomeUiState(shizuku = ready))
        compose.setContent { Fixture(state.value, shizukuAction = { actions++ }) }
        compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
        compose.onNodeWithText("打开 Shizuku").assertDoesNotExist()
        compose.onNodeWithText("获取 Shizuku").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(shizuku = ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "fixture")) }
        compose.onNodeWithText("Shizuku 授权").assertExists()
        compose.runOnIdle { state.value = state.value.copy(shizuku = ready) }
        compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
        compose.onNodeWithText("打开 Shizuku").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun settingsShowsGeneratedBuildVersionWithoutAddingPrimaryPageContentOrStoppingProtection() {
        var stops = 0
        val version = BuildConfig.VERSION_NAME
        compose.setContent { Fixture(HomeUiState(), stop = { stops++ }) }
        compose.onNodeWithText("关于").assertDoesNotExist()
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithText(version).assertDoesNotExist()
        openSettings()
        compose.onNodeWithText("关于").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OriginOS FCM Guardian").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(version).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, stops) }
        compose.onNodeWithText("后台设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("通知显示设置").assertExists()
        compose.onNodeWithText("打开自启动设置").assertExists()
        compose.onNodeWithContentDescription("最近任务隐藏开关").assertExists()
        compose.onNodeWithText("后台与通知").assertDoesNotExist()
        compose.onNodeWithText("自启动设置").assertDoesNotExist()
        compose.onNodeWithText("诊断").assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("关于").assertDoesNotExist()
        compose.onNodeWithText(version).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, stops) }
    }

    @Test fun homeIsCompactAndSecondaryReturnPreservesAppsSearch() {
        compose.setContent { Fixture(HomeUiState(selectedTargets = listOf(telegram, other))) }
        compose.onNodeWithText("Shizuku").assertExists()
        compose.onNodeWithText("原始状态记录").assertDoesNotExist()
        compose.onNodeWithText("定期巡检").assertDoesNotExist()
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("搜索名称或包名").performTextInput("Other")
        compose.onNodeWithTag("app-details:${other.identity.packageName}:true").assertExists()
        compose.onNodeWithText("Telegram").assertDoesNotExist()
        openSettings()
        compose.onNodeWithText("工作模式").assertExists()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("应用标签").performClick()
        try {
            // Discriminate navigation/focus from search filtering before changing production.
            compose.onNodeWithText("返回").assertDoesNotExist()
            compose.onNodeWithContentDescription("已保护（0）").assertExists()
            compose.onNodeWithContentDescription("应用标签").assertIsSelected()
            compose.onNodeWithContentDescription("搜索名称或包名").assertTextContains("Other")
            compose.onNodeWithTag("app-details:${other.identity.packageName}:true").assertExists()
        } catch (failure: AssertionError) {
            // Synthetic fixture only: contains no installed-app data or pairing secrets.
            compose.onRoot().printToLog("GuardianSyntheticNavigation")
            compose.onRoot(useUnmergedTree = true).printToLog("GuardianSyntheticNavigationRaw")
            throw failure
        }
        compose.onNodeWithText("Telegram").assertDoesNotExist()
    }

    @Test fun eachRecipientHasOwnAutomaticToggleAndRowDetailsOnlyShowRecheck() {
        compose.setContent { Fixture(HomeUiState(selectedTargets = listOf(telegram, other))) }
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("Telegram 自动保护选择").assertExists()
        compose.onNodeWithContentDescription("Other 自动保护选择").assertExists()
        compose.onNodeWithText("example.other").assertExists()
        compose.onNodeWithTag("app-details:${other.identity.packageName}:true").performClick()
        compose.onNodeWithText("系统设置").assertDoesNotExist()
        compose.onNodeWithText("移除").assertDoesNotExist()
        compose.onNodeWithText("详情").assertDoesNotExist()
        compose.onNodeWithText("复检").assertExists()
        compose.onNodeWithText("复检结果：尚未检查").assertExists()
    }

    @Test fun platformBackReturnsFromSecondaryThenAppsToHome() {
        var activity: ComponentActivity? = null
        compose.setContent {
            activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<ComponentActivity>().first()
            Fixture(HomeUiState())
        }
        compose.onNodeWithContentDescription("应用标签").performClick()
        openSettings()
        compose.runOnIdle { checkNotNull(activity).onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Shizuku").assertExists()
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("已保护（0）").assertExists()
        compose.runOnIdle { checkNotNull(activity).onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Shizuku").assertExists()
    }

    @Test fun homeMasterOffRetainsAppChoicesAndNavigationDoesNotStopAgain() {
        var stops = 0
        val state = mutableStateOf(HomeUiState(selectedTargets = listOf(telegram), automaticProtection =
            AutomaticProtectionState(masterEnabled = true, savedAuthorization = true, targets = listOf(
                AutomaticProtectionTargetState(telegram.identity, savedAuthorization = true)))))
        compose.setContent { Fixture(state.value, stop = {
            stops++
            state.value = state.value.copy(automaticProtection = masterOffAutomaticProtectionState(
                state.value.automaticProtection, "已关闭；应用选择保留"))
        }) }
        compose.onNodeWithContentDescription("FCM推送保护开关").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithContentDescription("FCM推送保护开关").assertIsOff()
        compose.onNodeWithContentDescription("应用标签").performClick()
        compose.onNodeWithContentDescription("已保护（1）").assertExists()
        compose.onNodeWithContentDescription("Telegram 自动保护选择").assertIsOn()
        openSettings()
        compose.onNodeWithText("后台设置").assertExists()
        compose.onNodeWithText("诊断").assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("FCM推送保护开关").assertIsOff()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(listOf(telegram.identity), state.value.automaticProtection.configuredIdentities) }
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("主页标签").performClick()
        compose.onNodeWithContentDescription("连接与后台设置").performScrollTo().performClick()
    }

    @Composable private fun Fixture(state: HomeUiState, stop: () -> Unit = {}, stopMonitor: () -> Unit = {},
        shizukuAction: () -> Unit = {}, check: () -> Unit = {}, startProtection: () -> Unit = {},
        readConnection: () -> Unit = {}) {
        val visibleState = state.copy(visibleApps = state.selectedTargets.map {
            VisibleProtectionApp(it.identity.packageName, it.label, it.identity, supported = true)
        }, visibleAppsLoadState = HomeSourceLoadState.LOADED)
        MaterialTheme { HomeScreen(state = visibleState, onShizukuAction = shizukuAction,
            onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
            onSetTargetSelected = { _, _ -> }, onCheckTarget = { check() },
            onClearCorruptTargetSelection = {},

             onStopAutomaticProtection = stop,
            onRequestAutomaticProtection = startProtection, onConfirmAutomaticProtection = startProtection,
            onRequestAppAutomaticProtection = { startProtection() }, onReadGmsConnection = readConnection) }
    }
}
