package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.protection.DailyProtectionReport

@Composable
internal fun DailyProtectionDetails(
    report: DailyProtectionReport,
    selectedTargets: List<SelectedTargetUiState>,
    selectionReady: Boolean,
    busy: Boolean,
    onCheckTarget: (String) -> Unit,
    onShowTargetSelection: () -> Unit,
) {
    Text(
        "这是上次巡检的历史快照，不代表当前状态或推送已修复。不检测自启动权限。",
        style = MaterialTheme.typography.bodySmall,
    )
    protectionTargetIssues(report, selectedTargets, selectionReady).forEach { issue ->
        Column {
            Text(issue.packageName, style = MaterialTheme.typography.bodyMedium)
            Text(issue.explanation, style = MaterialTheme.typography.bodySmall)
            when (issue.action) {
                ProtectionTargetAction.RECHECK -> TextButton(
                    onClick = { onCheckTarget(issue.packageName) },
                    enabled = !busy,
                ) { Text("复检该应用") }
                ProtectionTargetAction.SELECT -> TextButton(
                    onClick = onShowTargetSelection,
                    enabled = !busy,
                ) { Text("检查应用选择") }
                ProtectionTargetAction.NONE -> Text(
                    "关注应用列表尚不可用；暂不提供该记录的操作入口",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
