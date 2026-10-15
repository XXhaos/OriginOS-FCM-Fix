package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.ProtectionCheckReason
import io.github.originosfcmguardian.protection.ProtectionCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic counters/state only; never binds Shizuku, reads a device, or dispatches protection. */
class FcmInlineConnectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entireStripReadsAndCanRefreshAfterCompletionWithoutOpeningADialog() {
        var reads = 0
        val state = mutableStateOf(GmsConnectionUiState())
        compose.setContent { MaterialTheme {
            FcmInlineConnection(state.value, null) { reads++ }
        } }
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithTag("fcm-inline-read").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, reads)
            state.value = state.value.copy(readAttempted = true, observation = sample(),
                networkTrackingAvailable = true, networkKey = "fixture")
        }
        compose.onNode(hasContentDescription("FCM 已连接，取样时连接时长01:01:01", substring = true)).assertExists()
        compose.onNodeWithTag("fcm-inline-read").performClick()
        compose.runOnIdle { assertEquals(2, reads) }
        compose.onNodeWithText("读取一次").assertDoesNotExist()
        compose.onNodeWithText("触发一次心跳").assertDoesNotExist()
    }

    @Test fun readingDisablesTheWholeStripAndDoesNotShowOldConnectedState() {
        var reads = 0
        compose.setContent { MaterialTheme {
            FcmInlineConnection(GmsConnectionUiState(busy = true, observation = sample()), sample()) { reads++ }
        } }
        compose.onNodeWithTag("fcm-inline-read").assertIsNotEnabled()
        compose.onNode(hasContentDescription("FCM 读取中", substring = true)).assertExists()
        compose.onNode(hasContentDescription("FCM 已连接", substring = true)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads) }
    }

    @Test fun productionHomeUsesDirectReadAndNeverRendersLegacyModal() {
        var reads = 0
        compose.setContent { HomeFixture(HomeUiState(gmsConnection = GmsConnectionUiState(visible = true))) { reads++ } }
        compose.onNodeWithTag("fcm-inline-read").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reads) }
        compose.onNodeWithText("FCM 连接").assertDoesNotExist()
        compose.onNodeWithText("读取一次").assertDoesNotExist()
        compose.onNodeWithText("触发一次心跳").assertDoesNotExist()
    }

    @Test fun historyIsShownOnlyInDiagnosticsSummaryNotInADuplicateHomeCard() {
        val savedAt = 1_700_000_000_000L
        val state = HomeUiState(dailyProtectionReport = DailyProtectionReport(savedAt,
            ProtectionCheckReason.MANUAL, "fixture", ProtectionCheckStatus.HEALTHY_READ_ONLY, "fixture history"))
        compose.setContent { HomeFixture(state) {} }
        compose.onNodeWithText("上次检查").assertDoesNotExist()
        val history = "已保存检查：${formatRecordTime(savedAt)}（历史记录）"
        compose.onNodeWithText(history).assertDoesNotExist()
        compose.onNodeWithText("打开诊断信息").performScrollTo().performClick()
        compose.onNodeWithText("问题摘要").assertExists()
        compose.onNodeWithText(history).assertExists()
    }

    @Composable private fun HomeFixture(state: HomeUiState, onRead: () -> Unit) {
        MaterialTheme { HomeScreen(state = state, onShizukuAction = {},
            onOpenAutostartSettings = {}, onShowTargetSelection = {}, onDismissTargetSelection = {},
            onSetTargetSelected = { _, _ -> }, onCheckTarget = {}, onRunDailyProtection = {},
            onClearCorruptTargetSelection = {}, onRequestFcmToken = {}, onToggleFcmTokenVisibility = {},
            onCopyFcmToken = {}, onClearFcmToken = {}, onRequestNotificationPermission = {},
            onSetNotificationFactsEnabled = {}, onOpenNotificationListenerSettings = {},
            onClearNotificationFactsHistory = {}, onExportNotificationFacts = {}, onReadGmsConnection = onRead) }
    }

    private fun sample(): GmsConnectionObservation.Sample {
        val now = SystemClock.elapsedRealtime()
        return GmsConnectionObservation.Sample(1, 10_208, 1, 1, 1, "a".repeat(64),
            "11111111-2222-3333-4444-555555555555", 1_000, now,
            SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
            false, emptyList(), GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED,
                SourceCompleteness.COMPLETE, 1_000, now, 0, 3_661_000))
    }
}
