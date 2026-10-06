package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Dialog fixture only: no system request, service launch, activation or device mutation. */
class NoTouchProtectionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addingRequiresButtonAndDialogExplainsConsentAndManualFallback() {
        var requests = 0
        compose.setContent { MaterialTheme {
            NoTouchProtectionDialog({}, { callback -> requests++; callback("系统添加入口暂不可用") })
        } }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("也可手动添加：控制中心 → 编辑 → 推送保护。").assertExists()
        compose.onNodeWithText("先连接特权服务并开启自动保护。亮起表示检测运行中；点击按钮可打开 Guardian。手动关闭的保护不会自动开启。").assertExists()
        compose.onNodeWithText("添加磁贴").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("系统添加入口暂不可用").assertExists()
    }
}
