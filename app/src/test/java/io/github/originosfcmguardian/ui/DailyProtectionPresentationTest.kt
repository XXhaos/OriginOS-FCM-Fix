package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.protection.*
import org.junit.Assert.*
import org.junit.Test

class DailyProtectionPresentationTest {
    private val target = SelectedTargetUiState(
        SelectedTargetIdentity("example.target", 12345, 100, listOf("a".repeat(64))), "Example")
    private fun report(status: TargetCheckStatus) = DailyProtectionReport(
        10, ProtectionCheckReason.MANUAL, "verified", ProtectionCheckStatus.UNKNOWN, "historical",
        listOf(TargetCheckResult("example.target", status)))

    @Test fun retiredAutostartResultsNeverGenerateCurrentChecksOrSettingsClaims() {
        listOf(TargetCheckStatus.ALLOWED, TargetCheckStatus.NEEDS_USER_ACTION,
            TargetCheckStatus.UNKNOWN, TargetCheckStatus.UNAVAILABLE).forEach {
            val old = report(it)
            val before = old.copy()
            assertTrue(protectionTargetIssues(old, listOf(target), true).isEmpty())
            assertEquals(before, old)
        }
    }

    @Test fun identityMatchDoesNotClaimAutostartPermission() {
        assertTrue(protectionTargetIssues(report(TargetCheckStatus.IDENTITY_MATCH), listOf(target), true).isEmpty())
    }

    @Test fun missingOrChangedIdentityStillRequiresSelection() {
        listOf(TargetCheckStatus.MISSING, TargetCheckStatus.IDENTITY_CHANGED).forEach {
            val issue = protectionTargetIssues(report(it), listOf(target), true).single()
            assertEquals(ProtectionTargetAction.SELECT, issue.action)
            assertFalse(issue.explanation.contains("自启动"))
        }
    }

    @Test fun unavailableSelectionDisablesIdentityActions() {
        val issue = protectionTargetIssues(report(TargetCheckStatus.MISSING), emptyList(), false).single()
        assertEquals(ProtectionTargetAction.NONE, issue.action)
    }
}
