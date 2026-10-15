package io.github.originosfcmguardian.ui

import android.content.ActivityNotFoundException
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.originosfcmguardian.fcm.GMS_DIAGNOSTICS_UNAVAILABLE
import io.github.originosfcmguardian.fcm.attemptGmsDiagnostics
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import io.github.originosfcmguardian.recovery.TargetRecoveryCounts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline render/counters only. No external Activity, service, shell, permission or system write. */
class CompactDiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val evidence by lazy { SyntheticFixtureEvidence {
        compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size
    } }

    @Test fun homeSeparatesPendingProtectionAndExternalPageFailure() {
        var available = false
        var launches = 0
        compose.setContent { Fixture(HomeUiState(), launch = { launches++; available }) }
        compose.onNodeWithText("FCM推送保护").assertExists()
        compose.onNodeWithContentDescription("FCM推送保护开关").assertExists()
        compose.onNodeWithText("FCM 连接与保活").assertDoesNotExist()
        compose.onNodeWithText("Google Play 服务保护").assertDoesNotExist()
        compose.onNodeWithText("刷新").assertDoesNotExist()
        compose.onNodeWithTag("fcm-inline-read").assertDoesNotExist()
        compose.onNodeWithText("FCM Diagnostics").assertIsDisplayed().performClick()
        compose.onNodeWithText(GMS_DIAGNOSTICS_UNAVAILABLE).assertExists()
        compose.runOnIdle {
            assertEquals(1, launches)
            assertFalse(attemptGmsDiagnostics { _, _ -> throw ActivityNotFoundException("private fixture") })
            assertFalse(attemptGmsDiagnostics { _, _ -> throw SecurityException("private fixture") })
            available = true
        }
        compose.onNodeWithText("FCM Diagnostics").performClick()
        compose.onNodeWithText(GMS_DIAGNOSTICS_UNAVAILABLE).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, launches) }
    }

    @Test fun homeExportsDirectlyWithoutDiagnosticOrRetiredToolNavigation() {
        var exports = 0
        compose.setContent { Fixture(HomeUiState(), export = { exports++ }) }
        evidence.checked(SyntheticPhase.INITIAL) {
            compose.onNodeWithText("打开诊断信息").assertDoesNotExist()
            compose.onNodeWithText("诊断信息").assertDoesNotExist()
            compose.onNodeWithText("导出报告").performScrollTo().assertIsDisplayed()
        }
        compose.waitForIdle()
        compose.onNodeWithText("问题摘要").assertDoesNotExist()
        compose.onNodeWithText("最近操作").assertDoesNotExist()
        compose.onNodeWithText("报告含设备与应用信息，请私下分享。").assertExists()
        compose.onNodeWithText("原始状态记录").assertDoesNotExist()
        compose.onNodeWithText("主动获取测试令牌").assertDoesNotExist()
        compose.onNodeWithText("所选应用通知记录").assertDoesNotExist()
        compose.onNodeWithText("一键修复").assertDoesNotExist()
        evidence.checked(SyntheticPhase.EXPORT_SCROLLED) { compose.onNodeWithText("导出脱敏报告").performScrollTo() }
        compose.waitForIdle()
        evidence.checked(SyntheticPhase.EXPORT_READY) {
            compose.onNodeWithText("导出脱敏报告").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, exports) }
        }
        compose.onNodeWithText("导出脱敏报告").performClick()
        compose.runOnIdle { assertEquals(0, exports) }
        compose.onNodeWithText("JSON（结构化）").performClick()
        compose.waitForIdle()
        evidence.record(SyntheticPhase.EXPORT_TOUCHED, exports)
        compose.runOnIdle { assertEquals("Exactly one touch must dispatch the export callback", 1, exports) }
        compose.onNodeWithText("高级排查").assertDoesNotExist()
        compose.onNodeWithText("查看工具入口").assertDoesNotExist()
        compose.onNodeWithText("定期巡检").assertDoesNotExist()
        compose.onNodeWithText("立即巡检").assertDoesNotExist()
        compose.onNodeWithContentDescription("连接与后台设置").performScrollTo().performClick()
        compose.onNodeWithText("后台设置").assertExists()
        compose.onNodeWithText("诊断").assertDoesNotExist()
        compose.onNodeWithText("导出报告").assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("Guardian").assertExists()
        compose.onNodeWithText("导出脱敏报告").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, exports) }
    }

    @Test fun savedCountsAndReportAreOnHomeWithoutRetiredToolsOrDuplicateHistory() {
        val savedAt = java.time.Instant.parse("2026-10-06T15:59:00Z").toEpochMilli()
        val first = SelectedTargetIdentity("fixture.first", 10_001, 100, listOf("a".repeat(64)))
        val second = SelectedTargetIdentity("fixture.second", 10_002, 100, listOf("b".repeat(64)))
        val counts = RecoveryCountsUiState(available = true,
            byIdentity = mapOf(first to TargetRecoveryCounts(2, 5), second to TargetRecoveryCounts(5, 8)),
            calculatedAtMillis = savedAt, zoneId = "Asia/Shanghai")
        compose.setContent { Fixture(HomeUiState(recoveryCounts = counts)) }
        compose.onNodeWithText("解除停止限制").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("今日").assertExists()
        compose.onNodeWithText("本周").assertExists()
        compose.onNodeWithText("7").assertExists()
        compose.onNodeWithText("13").assertExists()
        compose.onNodeWithText("更新于 10-06 23:59").assertExists()
        compose.onNodeWithText("仅计解除限制，不代表消息送达。").assertDoesNotExist()

        compose.onNodeWithText("导出报告").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开诊断信息").assertDoesNotExist()
        compose.onNodeWithText("最近操作").assertDoesNotExist()
        compose.onNodeWithText("查看操作历史").assertDoesNotExist()
        compose.onNodeWithText("查看工具入口").assertDoesNotExist()
        compose.onNodeWithText("定期巡检").assertDoesNotExist()
        compose.onNodeWithText("通知记录").assertDoesNotExist()
        compose.onNodeWithText("恢复操作历史").assertDoesNotExist()
        compose.onNodeWithText("FCM 连接详情").assertDoesNotExist()
        compose.onNodeWithText("自动保护详情").assertDoesNotExist()
        compose.onNodeWithText("解除停止限制").assertExists()
    }

    @Composable private fun Fixture(state: HomeUiState, launch: () -> Boolean = { false }, export: () -> Unit = {},
        stopMonitor: () -> Unit = {}, stopRecording: () -> Unit = {}, reconcile: () -> Unit = {}) {
        evidence.capture(LocalContext.current)
        MaterialTheme { HomeScreen(state = state, onShizukuAction = {},
            onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
            onSetTargetSelected = { _, _ -> }, onCheckTarget = {},
            onClearCorruptTargetSelection = {},

            onOpenFcmDiagnostics = launch, onExportCombinedDiagnosticReport = { export() },
             onReconcileStoppedRecovery = reconcile) }
    }
}
