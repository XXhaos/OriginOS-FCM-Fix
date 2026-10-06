package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
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
        compose.onNodeWithText("尚未取样；需要已授权的 Shizuku shell。").assertExists()
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
        compose.onNodeWithText("GMS 连接").assertDoesNotExist()
    }

    @Test fun samplePartialAndUnknownShowCoverageWithoutDeliveryClaim() {
        val state = mutableStateOf(GmsConnectionUiState(visible = true, observation = sample(),
            networkBackground = "合成网络背景", networkTrackingAvailable = true))
        compose.setContent { MaterialTheme { GmsConnectionDialog(state.value, {}, {}, {}) } }
        compose.onNodeWithText("本次观察到匹配 TCP 迹象").assertExists()
        compose.onNodeWithText("shell /proc/net/tcp：COMPLETE；tcp6：COMPLETE").assertExists()
        compose.onNodeWithText("未查询到 manifest 接收者；不排除动态注册。").assertExists()
        compose.onNodeWithText("接收者元数据不证明 shell 可分发，也不证明重连有效。").assertExists()
        compose.runOnIdle { state.value = state.value.copy(observation = sample(partial = true)) }
        compose.onNodeWithText("本次来源覆盖不足，无法判断").assertExists()
        compose.onNodeWithText("shell /proc/net/tcp：PARTIAL；tcp6：DENIED").assertExists()
        compose.onNodeWithText("GCM_RECONNECT manifest 接收者查询不可用。").assertExists()
        compose.runOnIdle { state.value = state.value.copy(observation = GmsConnectionObservation.Unknown(1, GmsUnknown.CAPABILITY_UNAVAILABLE)) }
        compose.onNodeWithText("无法判断：CAPABILITY_UNAVAILABLE").assertExists()
    }

    @Test fun expiredAndNetworkChangedSamplesAreHistorical() {
        // Device uptime is the component's production clock; no 30-second wait or invalid future timestamp.
        val now = SystemClock.elapsedRealtime()
        assumeTrue("Historical fixture requires at least 31 seconds of uptime", now >= 31_000)
        val state = mutableStateOf(GmsConnectionUiState(visible = true,
            observation = sample(elapsed = now - 31_000), networkTrackingAvailable = true))
        compose.setContent { MaterialTheme { GmsConnectionDialog(state.value, {}, {}, {}) } }
        compose.onNodeWithText("历史样本已超过30秒；当前连接未知").assertExists()
        compose.runOnIdle { state.value = state.value.copy(observation = sample(), networkChanged = true) }
        compose.onNodeWithText("网络背景已变化；该历史样本无法判断当前连接").assertExists()
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

    private fun sample(partial: Boolean = false, elapsed: Long = SystemClock.elapsedRealtime()) = GmsConnectionObservation.Sample(
        requestId = 1, uid = 10_208, versionCode = 1, firstInstallTime = 1, lastUpdateTime = 1,
        signerSha256 = "a".repeat(64), bootId = "11111111-2222-3333-4444-555555555555",
        observedAtMillis = 1_000, elapsedRealtimeMillis = elapsed,
        tcp = SocketSource(if (partial) SourceCompleteness.PARTIAL else SourceCompleteness.COMPLETE, !partial),
        tcp6 = SocketSource(if (partial) SourceCompleteness.DENIED else SourceCompleteness.COMPLETE, false),
        receiverInspectionComplete = !partial, receivers = emptyList(),
    )
}
