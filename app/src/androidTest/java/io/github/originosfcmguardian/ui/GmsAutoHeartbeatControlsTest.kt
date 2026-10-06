package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only. Compile in this package; never start the real background protection. */
class GmsAutoHeartbeatControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preferenceOrRecompositionDoesNotConfirmNewSession() {
        var confirms = 0
        val value = mutableStateOf(GmsAutoHeartbeatUiState(configured = true, requiresConfirmation = true))
        compose.setContent { MaterialTheme { GmsAutoHeartbeatControls(value.value, true, { confirms++ }, {}) } }
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("偏好已保存 · 心跳已暂停").assertExists()
        compose.onNodeWithText("重新确认心跳").performClick()
        compose.runOnIdle { assertEquals(1, confirms); value.value = value.value.copy(detail = "更新显示") }
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test fun stopPendingBlocksReEnable() {
        var stops = 0
        compose.setContent { MaterialTheme { GmsAutoHeartbeatControls(
            GmsAutoHeartbeatUiState(stopPending = true), true, {}, { stops++ }) } }
        compose.onNodeWithText("停止待确认").assertExists()
        compose.onNodeWithContentDescription("低频心跳开关").assertIsNotEnabled()
        compose.onNodeWithText("重试确认停止").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun dialogRequiresExplicitConfirmation() {
        var confirms = 0
        compose.setContent { MaterialTheme { GmsAutoHeartbeatConfirmation({ confirms++ }, {}) } }
        compose.runOnIdle { assertEquals(0, confirms) }
        compose.onNodeWithText("确认开启心跳").performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
        compose.onNodeWithText("允许 Guardian 保存新的心跳授权，同次开机内服务或 App 进程恢复后安全核验并接续。每次新会话首次等待至少 15 分钟，不保证连接修复或通知送达。重启手机后需确认；结果不明或未结算的旧操作阻断接续与手动心跳，不重发。").assertExists()
    }
}
