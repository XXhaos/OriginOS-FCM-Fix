package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Current core session only; saved ON intent is never a running clock. */
private fun automaticProtectionUptimeMillis(state: AutomaticProtectionState, nowElapsedMillis: Long): Long? {
    if (!state.masterEnabled || !state.enabled || state.waitingForPrivilege ||
        state.waitingForReconciliation || state.revocationSaveFailed) return null
    val started = state.coreSessionStartedAtElapsedMillis ?: return null
    if (started < 0 || nowElapsedMillis < started) return null
    return nowElapsedMillis - started
}

internal fun automaticProtectionUptimeText(state: AutomaticProtectionState, nowElapsedMillis: Long): String? =
    automaticProtectionUptimeMillis(state, nowElapsedMillis)?.let { "已开启时长 ${fcmDurationClock(it)}" }

/** Only visible UI ticks. No RPC, disk write, background timer or wake lock. */
@Composable
internal fun AutomaticProtectionUptime(state: AutomaticProtectionState) {
    val owner = LocalLifecycleOwner.current
    val started = state.coreSessionStartedAtElapsedMillis
    val visible = automaticProtectionUptimeMillis(state, SystemClock.elapsedRealtime()) != null
    var now by remember(started) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(owner, started, visible) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    automaticProtectionUptimeText(state, maxOf(now, SystemClock.elapsedRealtime()))?.let { text ->
        Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
