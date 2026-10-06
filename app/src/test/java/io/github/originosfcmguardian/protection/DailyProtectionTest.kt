package io.github.originosfcmguardian.protection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import org.junit.Assert.*
import org.junit.Test

class DailyProtectionTest {
    private val identity = SelectedTargetIdentity("example.target", 12345, 100, listOf("a".repeat(64)))
    private fun check(selected: List<SelectedTargetIdentity> = listOf(identity),
        probe: (String) -> SelectedTargetIdentity? = { identity }) =
        executeCheck(1000, ProtectionCheckReason.MANUAL, "any-build", selected, probe)

    @Test fun matchingInstallationIsIdentityOnlyEvidence() {
        val report = check()
        assertEquals(ProtectionCheckStatus.HEALTHY_READ_ONLY, report.status)
        assertEquals(TargetCheckStatus.IDENTITY_MATCH, report.targets.single().status)
        assertTrue(report.summary.contains("不代表停止限制、FCM 连接或通知送达状态"))
        assertFalse(report.containsRetiredAutostartEvidence)
    }
    @Test fun missingAndReinstalledTargetsRequireReselection() {
        assertEquals(TargetCheckStatus.MISSING, check(probe = { null }).targets.single().status)
        val changed = check(probe = { identity.copy(firstInstallTime = 101) })
        assertEquals(TargetCheckStatus.IDENTITY_CHANGED, changed.targets.single().status)
        assertEquals(ProtectionCheckStatus.NEEDS_USER_ACTION, changed.status)
    }
    @Test fun emptySelectionIsNotHealthy() {
        assertEquals(ProtectionCheckStatus.UNKNOWN, check(emptyList()).status)
    }
    @Test fun gmsVersionChangesDoNotTriggerIndependentPolicyInspection() {
        val old = ProtectionEventStamps("old", 1, 2, false)
        assertTrue(decideEvents(old, old.copy(gmsVersion = 3)).isEmpty())
        assertEquals(listOf(ProtectionCheckReason.BUILD_CHANGED, ProtectionCheckReason.GUARDIAN_VERSION_CHANGED),
            decideEvents(old, ProtectionEventStamps("new", 2, 3, false)))
        assertTrue(isShizukuAvailableTransition(false, true))
        assertFalse(isShizukuAvailableTransition(true, true))
    }
    @Test fun historicalGmsPolicyReportsCannotSupplyCurrentHealth() {
        listOf("Google Play 服务：已启用", "GMS=存在", "后台权限符合预期", "待机白名单符合预期").forEach {
            assertTrue(check().copy(summary = it).containsRetiredAutostartEvidence)
        }
    }
}
