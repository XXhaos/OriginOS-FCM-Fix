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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.originosfcmguardian.fcm.GMS_DIAGNOSTICS_UNAVAILABLE
import io.github.originosfcmguardian.fcm.attemptGmsDiagnostics
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.monitoring.DeepMonitoringUiState
import io.github.originosfcmguardian.monitoring.MonitorRunPhase
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
        compose.onNodeWithText("FCM 连接与保活").assertExists()
        compose.onNodeWithText("Google Play 服务保护").assertDoesNotExist()
        compose.onNodeWithText("查看连接状态").assertExists()
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

    @Test fun defaultIsCompactAndAdvancedOpensOnlyTheChosenTool() {
        var exports = 0
        compose.setContent { Fixture(HomeUiState(), export = { exports++ }) }
        evidence.checked(SyntheticPhase.INITIAL) {
            compose.onNodeWithText("打开诊断信息").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        }
        compose.waitForIdle()
        evidence.record(SyntheticPhase.DIAGNOSTICS_OPEN)
        compose.onNodeWithText("问题摘要").assertExists()
        compose.onNodeWithText("部分诊断记录尚未载入，暂不能判断").assertDoesNotExist() // Shizuku issue takes precedence.
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
        compose.onNodeWithText("查看工具入口").performScrollTo().performClick()
        compose.onNodeWithText("Guardian 推送自测").performScrollTo().performClick()
        compose.onNodeWithText("主动获取测试令牌").assertExists()
        compose.onNodeWithText("原始状态记录").assertDoesNotExist()
        compose.onNodeWithText("所选应用通知记录").assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("问题摘要").assertExists()
        compose.onNodeWithText("Guardian 推送自测").performScrollTo().performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("诊断").performClick()
        compose.onNodeWithText("问题摘要").assertExists()
        compose.onNodeWithText("主动获取测试令牌").assertDoesNotExist()
        compose.onNodeWithText("收起工具入口").performScrollTo().performClick()
        compose.onNodeWithText("Guardian 推送自测").assertDoesNotExist()
    }

    @Test fun savedCountsAreOnHomeAndAdvancedToolsHaveNoDuplicateDetailsOrRecoveryHistory() {
        val savedAt = java.time.Instant.parse("2026-10-06T15:59:00Z").toEpochMilli()
        val first = SelectedTargetIdentity("fixture.first", 10_001, 100, listOf("a".repeat(64)))
        val second = SelectedTargetIdentity("fixture.second", 10_002, 100, listOf("b".repeat(64)))
        val counts = RecoveryCountsUiState(available = true,
            byIdentity = mapOf(first to TargetRecoveryCounts(2, 5), second to TargetRecoveryCounts(5, 8)),
            calculatedAtMillis = savedAt, zoneId = "Asia/Shanghai")
        compose.setContent { Fixture(HomeUiState(recoveryCounts = counts)) }
        compose.onNodeWithText("解除停止限制").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("今日确认解除").assertExists()
        compose.onNodeWithText("本周确认解除").assertExists()
        compose.onNodeWithText("7").assertExists()
        compose.onNodeWithText("13").assertExists()
        compose.onNodeWithText("统计截至 10-06 23:59（Asia/Shanghai）").assertExists()
        compose.onNodeWithText("仅计解除限制，不代表消息送达。").assertExists()

        compose.onNodeWithText("打开诊断信息").performScrollTo().performClick()
        compose.onNodeWithText("最近操作").assertExists()
        compose.onNodeWithText("查看操作历史").assertDoesNotExist()
        compose.onNodeWithText("查看工具入口").performScrollTo().performClick()
        compose.onNodeWithText("定期巡检").assertExists()
        compose.onNodeWithText("通知记录").assertExists()
        compose.onNodeWithText("恢复操作历史").assertDoesNotExist()
        compose.onNodeWithText("FCM 连接详情").assertDoesNotExist()
        compose.onNodeWithText("自动保护详情").assertDoesNotExist()
        compose.onNodeWithText("解除停止限制").assertDoesNotExist()
    }

    @Test fun activeStopsAndUnresolvedRecoverySurviveCollapsedTools() {
        var monitors = 0
        var recordings = 0
        var reconciles = 0
        val state = mutableStateOf(HomeUiState(deepMonitoring = DeepMonitoringUiState(phase = MonitorRunPhase.RUNNING),
            stoppedRecovery = StoppedRecoveryUiState(loaded = true, needsReconcile = true)))
        state.value = state.value.copy(notificationFacts = state.value.notificationFacts.copy(enabled = true))
        compose.setContent { Fixture(state.value, stopMonitor = { monitors++ }, stopRecording = { recordings++ }, reconcile = { reconciles++ }) }
        compose.onNodeWithText("打开诊断信息").performScrollTo().performClick()
        compose.onNodeWithText("停止监控").assertIsDisplayed().performClick()
        compose.onNodeWithText("停止通知记录").assertIsDisplayed().performClick()
        compose.onNodeWithText("核对上次结果（只读）").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, monitors); assertEquals(1, recordings); assertEquals(1, reconciles)
            state.value = state.value.copy(notificationFacts = state.value.notificationFacts.copy(enabled = false, stopPersistenceUncertain = true))
        }
        compose.onNodeWithText("重试停止记录").assertIsDisplayed()
        compose.onNodeWithText("撤销使用权").assertIsDisplayed()
        compose.onNodeWithText("主动获取测试令牌").assertDoesNotExist()
    }

    @Composable private fun Fixture(state: HomeUiState, launch: () -> Boolean = { false }, export: () -> Unit = {},
        stopMonitor: () -> Unit = {}, stopRecording: () -> Unit = {}, reconcile: () -> Unit = {}) {
        evidence.capture(LocalContext.current)
        MaterialTheme { HomeScreen(state = state, onShizukuAction = {},
            onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
            onSetTargetSelected = { _, _ -> }, onCheckTarget = {},
            onRunDailyProtection = {}, onClearCorruptTargetSelection = {}, onRequestFcmToken = {},
            onToggleFcmTokenVisibility = {}, onCopyFcmToken = {}, onClearFcmToken = {},
            onRequestNotificationPermission = {}, onSetNotificationFactsEnabled = { if (!it) stopRecording() },
            onOpenNotificationListenerSettings = {}, onClearNotificationFactsHistory = {}, onExportNotificationFacts = {},
            onOpenFcmDiagnostics = launch, onExportCombinedDiagnosticReport = { export() },
            onStopDeepMonitoring = stopMonitor, onReconcileStoppedRecovery = reconcile) }
    }
}
