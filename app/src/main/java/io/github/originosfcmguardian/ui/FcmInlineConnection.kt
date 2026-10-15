package io.github.originosfcmguardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation

internal data class FcmInlineSelection(
    val observation: GmsConnectionObservation?, val busy: Boolean,
    val networkInvalid: Boolean = false, val networkTrackingUnavailable: Boolean = false,
)

/** Presentation only. A failed explicit read cannot fall back to an older automatic positive. */
internal fun selectFcmInlineConnection(manual: GmsConnectionUiState,
    automatic: GmsConnectionObservation?): FcmInlineSelection {
    if (manual.busy) return FcmInlineSelection(null, busy = true)
    val attempted = manual.readAttempted || manual.observation != null
    val autoSample = automatic as? GmsConnectionObservation.Sample
    val automaticTime = autoSample?.let {
        minOf(it.elapsedRealtimeMillis, it.gcmService.elapsedRealtimeMillis ?: it.elapsedRealtimeMillis)
    }
    val completed = manual.readCompletedAtElapsedMillis
    val automaticIsNewer = completed != null && automaticTime != null && automaticTime > completed
    val newAutomaticFailure = completed != null && automatic is GmsConnectionObservation.Unknown &&
        automatic !== manual.automaticObservationAtReadCompletion
    if (!attempted) return FcmInlineSelection(automatic, busy = false)
    if (automaticIsNewer || newAutomaticFailure) return FcmInlineSelection(automatic, busy = false,
        networkInvalid = manual.networkChanged,
        networkTrackingUnavailable = !manual.networkTrackingAvailable || manual.networkKey == "unknown")
    return FcmInlineSelection(manual.observation, busy = false,
        networkInvalid = manual.networkChanged,
        networkTrackingUnavailable = !manual.networkTrackingAvailable || manual.networkKey == "unknown")
}

internal fun fcmInlineStatus(selection: FcmInlineSelection, nowElapsed: Long) =
    if (selection.busy) FcmConnectionStatus("⚪ 读取中…", "FCM 读取中")
    else fcmConnectionStatus(selection.observation, nowElapsed,
        selection.networkInvalid, selection.networkTrackingUnavailable)

@Composable
internal fun FcmInlineConnection(manual: GmsConnectionUiState,
    automatic: GmsConnectionObservation?, onRead: () -> Unit) {
    val selection = selectFcmInlineConnection(manual, automatic)
    val status = fcmInlineStatus(selection, fcmPresentationClock(selection.observation))
    Surface(onClick = onRead, enabled = !selection.busy,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("fcm-inline-read").semantics { contentDescription = "读取 FCM 连接状态" }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = status.accessibilityLabel },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status.text.substringBefore(" · "), style = MaterialTheme.typography.bodyMedium)
                if (" · " in status.text) Text(status.text.substringAfter(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (selection.busy) "读取中…" else "刷新",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
