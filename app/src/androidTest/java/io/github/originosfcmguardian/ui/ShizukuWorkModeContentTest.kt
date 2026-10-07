package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic snapshots/counters only: never installs, requests permission or starts a service. */
class ShizukuWorkModeContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installStartAndAuthorizationAreExplicitUserActions() {
        val snapshot = mutableStateOf(ShizukuStatusSnapshot(ShizukuStatus.NOT_INSTALLED, "fixture"))
        var actions = 0
        compose.setContent {
            MaterialTheme { Column { ShizukuWorkModeContent(snapshot.value, { actions++ }, {}, {}, {}) } }
        }
        compose.onNodeWithText("获取 Shizuku").assertExists()
        compose.runOnIdle {
            assertEquals(0, actions)
            snapshot.value = ShizukuStatusSnapshot(ShizukuStatus.NOT_RUNNING, "fixture")
        }
        compose.onNodeWithText("打开 Shizuku").assertExists()
        compose.runOnIdle { snapshot.value = ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "请授权 Guardian") }
        compose.onNodeWithText("Shizuku 授权").performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        compose.onNodeWithText("配置内置服务").assertDoesNotExist()
        compose.onNodeWithText("开始通知配对").assertDoesNotExist()
    }

    @Test fun blockedAuthorizationUsesManagerAndReadyDoesNotEnableProtection() {
        val snapshot = mutableStateOf(ShizukuStatusSnapshot(ShizukuStatus.PERMISSION_REQUIRED, "fixture", true))
        var actions = 0
        var manager = 0
        compose.setContent {
            MaterialTheme { Column { ShizukuWorkModeContent(snapshot.value, { actions++ }, {}, { manager++ }, {}) } }
        }
        compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
        compose.onNodeWithText("请在 Shizuku 中允许 Guardian。").assertExists()
        compose.onNodeWithText("打开 Shizuku").performClick()
        compose.runOnIdle {
            assertEquals(1, actions)
            snapshot.value = ShizukuStatusSnapshot(ShizukuStatus.READY_SHELL, "synthetic shell UID 2000")
        }
        compose.onNodeWithText("已授权 · shell 就绪").assertExists()
        compose.onNodeWithText("保护仍需单独开启。").assertExists()
        compose.onNodeWithText("Shizuku 授权").assertDoesNotExist()
        compose.onNodeWithText("synthetic shell UID 2000").assertDoesNotExist()
        compose.onNodeWithText("连接详情").assertDoesNotExist()
        compose.onNodeWithText("收起连接详情").assertDoesNotExist()
        compose.onNodeWithText("打开 Shizuku").performClick()
        compose.runOnIdle { assertEquals(1, manager) }
    }

    @Test fun rootAndErrorRemainActionableAndNoTouchHelpIsPreserved() {
        val snapshot = mutableStateOf(ShizukuStatusSnapshot(ShizukuStatus.READY_ROOT, "fixture"))
        var actions = 0
        var refreshes = 0
        var help = 0
        compose.setContent {
            MaterialTheme { Column { ShizukuWorkModeContent(snapshot.value, { actions++ }, { refreshes++ }, {}, { help++ }) } }
        }
        compose.onNodeWithText("Root 模式（不支持）").assertExists()
        compose.onNodeWithText("打开 Shizuku").performClick()
        compose.runOnIdle { snapshot.value = ShizukuStatusSnapshot(ShizukuStatus.ERROR, "fixture") }
        compose.onNodeWithText("状态异常").assertExists()
        compose.onNodeWithText("刷新状态").performClick()
        compose.onNodeWithText("无感保活").performClick()
        compose.runOnIdle {
            assertEquals(1, actions)
            assertEquals(1, refreshes)
            assertEquals(1, help)
        }
    }
}
