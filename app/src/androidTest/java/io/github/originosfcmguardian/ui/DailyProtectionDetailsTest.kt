package io.github.originosfcmguardian.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.ProtectionCheckReason
import io.github.originosfcmguardian.protection.ProtectionCheckStatus
import io.github.originosfcmguardian.protection.TargetCheckResult
import io.github.originosfcmguardian.protection.TargetCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure Compose fixtures: no production ViewModel, Shizuku, settings launch or Worker. */
@RunWith(AndroidJUnit4::class)
class DailyProtectionDetailsTest {
    @get:Rule val compose = createComposeRule()
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("example.target", 12345, 100, listOf("a".repeat(64))), "Example",
    )
    private fun report(status: TargetCheckStatus) = DailyProtectionReport(
        10, ProtectionCheckReason.MANUAL, "fixture", ProtectionCheckStatus.UNKNOWN, "partial",
        listOf(TargetCheckResult("example.target", status)),
    )

    @Test fun retiredAutostartIssueDoesNotOfferPermissionCheck() {
        var checked: String? = null
        compose.setContent {
            MaterialTheme {
                DailyProtectionDetails(report(TargetCheckStatus.NEEDS_USER_ACTION), listOf(target), true, false,
                    onCheckTarget = { checked = it }, onShowTargetSelection = { error("Unexpected selection") })
            }
        }
        compose.onNodeWithText("example.target").assertDoesNotExist()
        compose.onNodeWithText("不检测自启动权限", substring = true).assertIsDisplayed()
        compose.onNodeWithText("复检该应用").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, checked) }
    }

    @Test fun missingTargetRoutesToSelection() {
        var selections = 0
        compose.setContent {
            MaterialTheme {
                DailyProtectionDetails(report(TargetCheckStatus.MISSING), emptyList(), true, false,
                    onCheckTarget = { error("Unexpected read") }, onShowTargetSelection = { selections++ })
            }
        }
        compose.onNodeWithText("检查应用选择").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun retiredUnknownPermissionDoesNotOfferRecheck() {
        compose.setContent {
            MaterialTheme {
                DailyProtectionDetails(report(TargetCheckStatus.UNKNOWN), listOf(target), true, true,
                    onCheckTarget = { error("Unexpected read") }, onShowTargetSelection = {})
            }
        }
        compose.onNodeWithText("复检该应用").assertDoesNotExist()
    }
}
