package io.github.originosfcmguardian.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed icons are a display hint, never evidence of installation identity or readiness. */
@Composable
internal fun TargetAppIcon(target: SelectedTargetUiState) {
    TargetAppIcon(target.identity.packageName, target.label, target.identity)
}

@Composable
internal fun TargetAppIcon(packageName: String, label: String, identityKey: Any? = packageName, size: Dp = 36.dp) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, identityKey, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96).asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center) {
        Text(label.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
