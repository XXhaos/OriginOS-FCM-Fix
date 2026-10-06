package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only; no document provider, phone privilege or protection controller is invoked. */
class DiagnosticReportExportButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choiceAndCancellationDoNotStartAnExport() {
        var exports = 0
        compose.setContent { MaterialTheme { DiagnosticReportExportButton(false) { exports++ } } }
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
        compose.onNodeWithText("导出脱敏报告").performClick()
        compose.onNodeWithText("选择报告格式").assertExists()
        compose.runOnIdle { assertEquals(0, exports) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exports) }
    }

    @Test fun jsonChoiceDispatchesOnceAndClosesChoice() = checkFormat("JSON（结构化）", DiagnosticReportFormat.JSON)

    @Test fun markdownChoiceDispatchesOnceAndClosesChoice() = checkFormat("Markdown（便于阅读）", DiagnosticReportFormat.MARKDOWN)

    @Test fun busyCannotOpenChoiceOrDispatch() {
        compose.setContent { MaterialTheme { DiagnosticReportExportButton(true) { error("Busy export dispatched") } } }
        compose.onNodeWithText("正在准备…").assertIsNotEnabled()
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
    }

    private fun checkFormat(label: String, expected: DiagnosticReportFormat) {
        val exports = mutableListOf<DiagnosticReportFormat>()
        compose.setContent { MaterialTheme { DiagnosticReportExportButton(false) { exports += it } } }
        compose.onNodeWithText("导出脱敏报告").performClick()
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText("选择报告格式").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(expected), exports) }
    }
}
