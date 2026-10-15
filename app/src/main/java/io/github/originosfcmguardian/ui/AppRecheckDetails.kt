package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetStoppedReadFailure
import io.github.originosfcmguardian.autostart.TargetStoppedStatus

/** A read snapshot only; a cleared flag is not confirmation of notification delivery. */
internal fun appRecheckResult(target: SelectedTargetUiState?, checking: Boolean): TargetStatusPresentation = when {
    target == null -> TargetStatusPresentation("应用状态不可用", TargetStatusTone.UNCERTAIN)
    checking -> TargetStatusPresentation("正在复检…", TargetStatusTone.NEUTRAL)
    target.stoppedSnapshot.status == TargetStoppedStatus.UNKNOWN && target.stoppedSnapshot.readFailure != null ->
        TargetStatusPresentation(when (target.stoppedSnapshot.readFailure) {
            TargetStoppedReadFailure.TIMED_OUT -> "读取超时，请重试"
            TargetStoppedReadFailure.BUSY -> "读取繁忙，请稍后重试"
            else -> "读取失败，请重试"
        }, TargetStatusTone.UNCERTAIN)
    else -> targetWakePresentation(target).let { it.copy(text = "复检结果：${it.text}") }
}

@Composable
internal fun AppRecheckDetails(target: SelectedTargetUiState?, checking: Boolean, blocked: Boolean,
    onCheck: () -> Unit) {
    val result = appRecheckResult(target, checking)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onCheck, enabled = target != null && !checking && !blocked) {
            Text(if (checking) "复检中…" else "复检")
        }
        Text(result.text, color = result.tone.color(), style = MaterialTheme.typography.bodySmall)
    }
}
