package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.core.gms.SocketSource
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic component fixtures: no service binding and no real read or protection callback. */
class GmsConnectionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingDoesNotReadAndExplicitClickRequestsExactlyOnce() {
        var reads = 0
        compose.setContent { MaterialTheme { GmsConnectionDialog(GmsConnectionUiState(visible = true), { reads++ }, {}, {}) } }
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("FCM 连接").assertExists()
        assertStatus("FCM 尚未读取")
        compose.onNodeWithText("读取一次").performClick()
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun busyDisablesReadAndCancelDismissesFixture() {
        var reads = 0
        var dismisses = 0
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible.value) GmsConnectionDialog(GmsConnectionUiState(visible = true, busy = true),
                { reads++ }, { dismisses++; visible.value = false }, {})
        } }
        compose.onNodeWithText("读取中…").assertExists()
        compose.onNodeWithText("读取一次").assertIsNotEnabled()
        compose.onNodeWithText("触发一次心跳").assertIsNotEnabled()
        compose.onNodeWithText("取消并关闭").performClick()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(1, dismisses) }
        compose.onNodeWithText("FCM 连接").assertDoesNotExist()
    }

    @Test fun samplePartialAndUnknownKeepStatusCompactWithoutExposingDebugData() {
        val state = mutableStateOf(GmsConnectionUiState(visible = true, observation = sample(),
            networkBackground = "合成网络背景", networkTrackingAvailable = true))
        compose.setContent { MaterialTheme { GmsConnectionDialog(state.value, {}, {}, {}) } }
        assertStatus("FCM 状态未知，取样更新于")
        compose.onNodeWithText("状态仅供参考，不代表消息送达。").assertExists()
        assertDebugDataHidden()
        compose.runOnIdle { state.value = state.value.copy(observation = sample(partial = true)) }
        assertStatus("FCM 状态未知，取样更新于")
        assertDebugDataHidden()
        compose.runOnIdle { state.value = state.value.copy(observation = GmsConnectionObservation.Unknown(1, GmsUnknown.CAPABILITY_UNAVAILABLE)) }
        assertStatus("FCM 当前状态无法读取")
        assertDebugDataHidden()
    }

    @Test fun expiredAndNetworkChangedSamplesAreHistorical() {
        // Device uptime is the component's production clock; no 30-second wait or invalid future timestamp.
        val now = SystemClock.elapsedRealtime()
        assumeTrue("Historical fixture requires at least 31 seconds of uptime", now >= 31_000)
        val state = mutableStateOf(GmsConnectionUiState(visible = true,
            observation = sample(elapsed = now - 31_000), networkTrackingAvailable = true))
        compose.setContent { MaterialTheme { GmsConnectionDialog(state.value, {}, {}, {}) } }
        assertStatus("FCM 状态已过期，取样更新于")
        compose.runOnIdle { state.value = state.value.copy(observation = sample(), networkChanged = true) }
        assertStatus("FCM 网络已变化，取样更新于")
        compose.runOnIdle { state.value = state.value.copy(networkChanged = false, networkTrackingAvailable = false) }
        assertStatus("FCM 网络待确认，取样更新于")
    }

    @Test fun onlyExplicitHeartbeatClickRequestsAndRecompositionNeverReplays() {
        var heartbeats = 0
        val state = mutableStateOf(GmsConnectionUiState(visible = true))
        compose.setContent { MaterialTheme { GmsConnectionDialog(state.value, {}, {}, { heartbeats++ }) } }
        compose.runOnIdle { assertEquals(0, heartbeats) }
        compose.onNodeWithText("触发一次心跳").performClick()
        compose.runOnIdle { state.value = state.value.copy(heartbeatStatus = GmsHeartbeatStatus.REQUESTED) }
        compose.onNodeWithText("已请求心跳；不代表连接已修复或通知送达。").assertExists()
        compose.runOnIdle { assertEquals(1, heartbeats) }
    }

    private fun assertStatus(description: String) {
        compose.onNode(hasContentDescription(description, substring = true)).assertExists()
    }

    private fun assertDebugDataHidden() {
        listOf("TCP", "/proc/", "tcp6", "manifest", "GCM_RECONNECT", "COMPLETE",
            "PARTIAL", "DENIED", "CAPABILITY_UNAVAILABLE", "合成网络背景").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
    }

    private fun sample(partial: Boolean = false, elapsed: Long = SystemClock.elapsedRealtime()) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10_208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 1_000, elapsedRealtimeMillis = elapsed,
        tcp = SocketSource(if (partial) SourceCompleteness.PARTIAL else SourceCompleteness.COMPLETE, !partial),
        tcp6 = SocketSource(if (partial) SourceCompleteness.DENIED else SourceCompleteness.COMPLETE, false),
        receiverInspectionComplete = !partial, receivers = emptyList(),
    )
}
