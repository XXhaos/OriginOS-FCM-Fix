package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only; no document provider, phone privilege or protection controller is invoked. */
class DiagnosticReportExportButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneClickExportsOnceWithoutFormatChoice() {
        var exports = 0
        compose.setContent { MaterialTheme { DiagnosticReportExportButton(false) { exports++ } } }
        compose.runOnIdle { assertEquals(0, exports) }
        compose.onNodeWithText("导出日志").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
        compose.onNodeWithText("包含软件信息").assertDoesNotExist()
        compose.onNodeWithText("导出脱敏日志").assertDoesNotExist()
        compose.onNodeWithText("JSON（结构化）").assertDoesNotExist()
        compose.onNodeWithText("Markdown（便于阅读）").assertDoesNotExist()
    }

    @Test fun busyDisablesExportThenAllowsAnotherExplicitExportWhenFinished() {
        var exports = 0
        val busy = mutableStateOf(true)
        compose.setContent { MaterialTheme { DiagnosticReportExportButton(busy.value) { exports++ } } }
        compose.onNodeWithText("正在准备…").assertIsNotEnabled()
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exports); busy.value = false }
        compose.onNodeWithText("导出日志").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, exports) }
    }
}
