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
        compose.onNodeWithText("将“推送保护”添加到控制中心，下拉显示时可恢复意外中断的保护。").assertExists()
        compose.onNodeWithText("请保持 Shizuku 和自动保护开启。手动关闭保护后不会自动开启。").assertExists()
        compose.onNodeWithText("添加磁贴").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("系统添加入口暂不可用").assertExists()
    }
}
