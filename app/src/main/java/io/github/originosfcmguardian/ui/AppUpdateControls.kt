package io.github.originosfcmguardian.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.originosfcmguardian.update.AppUpdatePhase
import io.github.originosfcmguardian.update.AppUpdateViewModel

/** Compact Home/About action. Changing pages does not restart a request or touch protection. */
@Composable
internal fun AppUpdateControls(model: AppUpdateViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirmDownload by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.permissionReturned()
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installerReturned()
    }
    LaunchedEffect(state.launch?.sequence) {
        state.launch?.let { request ->
            model.consumeLaunch(request.sequence)
            try {
                if (request.permission) permission.launch(request.intent) else installer.launch(request.intent)
            } catch (_: Exception) { model.launchFailed() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled = !state.busy, shape = MaterialTheme.shapes.small, onClick = {
            when (state.phase) {
                AppUpdatePhase.AVAILABLE -> confirmDownload = true
                AppUpdatePhase.READY -> model.prepareInstall()
                else -> model.check()
            }
        }) {
            Text(when (state.phase) {
                AppUpdatePhase.CHECKING -> "正在检查…"
                AppUpdatePhase.AVAILABLE -> "更新至 ${state.update?.version}"
                AppUpdatePhase.DOWNLOADING -> "下载中 ${state.percent}%"
                AppUpdatePhase.READY -> "安装更新"
                AppUpdatePhase.PREPARING_INSTALL -> "正在校验…"
                AppUpdatePhase.INSTALLING -> "正在安装…"
                else -> "检查更新"
            })
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (confirmDownload && state.phase == AppUpdatePhase.AVAILABLE) AlertDialog(
        onDismissRequest = { confirmDownload = false }, title = { Text("更新 Guardian") },
        text = { Text("下载并安装 ${state.update?.version}？现有设置将保留。") },
        confirmButton = { TextButton(onClick = { confirmDownload = false; model.download() }) { Text("下载更新") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("取消") } },
    )
}
