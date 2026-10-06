package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.TargetCheckStatus

internal enum class ProtectionTargetAction { RECHECK, SELECT, NONE }

internal data class ProtectionTargetIssue(
    val packageName: String,
    val explanation: String,
    val action: ProtectionTargetAction,
)

/** Historical package-only results must never become current installation evidence. */
internal fun protectionTargetIssues(
    report: DailyProtectionReport,
    selectedTargets: List<SelectedTargetUiState>,
    selectionReady: Boolean,
): List<ProtectionTargetIssue> = report.targets.mapNotNull { target ->
    val explanation = when (target.status) {
        TargetCheckStatus.IDENTITY_MATCH, TargetCheckStatus.ALLOWED, TargetCheckStatus.NEEDS_USER_ACTION,
        TargetCheckStatus.UNKNOWN, TargetCheckStatus.UNAVAILABLE -> return@mapNotNull null
        TargetCheckStatus.IDENTITY_CHANGED -> "上次巡检：应用身份已变化；请重新选择并检查"
        TargetCheckStatus.MISSING -> "上次巡检：应用未安装或不可见；请检查关注应用选择"
    }
    val current = selectedTargets.singleOrNull { it.identity.packageName == target.packageName }
    val action = when {
        !selectionReady -> ProtectionTargetAction.NONE
        target.status == TargetCheckStatus.IDENTITY_CHANGED || target.status == TargetCheckStatus.MISSING ||
            current == null || current.status == TargetAutostartStatus.IDENTITY_CHANGED -> ProtectionTargetAction.SELECT
        else -> ProtectionTargetAction.RECHECK
    }
    ProtectionTargetIssue(target.packageName, explanation, action)
}
