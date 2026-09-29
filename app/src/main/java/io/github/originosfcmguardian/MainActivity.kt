package io.github.originosfcmguardian

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.originosfcmguardian.ui.HomeScreen
import io.github.originosfcmguardian.ui.HomeViewModel
import io.github.originosfcmguardian.autostart.AutostartSettingsLauncher
import io.github.originosfcmguardian.notification.NotificationFactsQuery
import io.github.originosfcmguardian.monitoring.MonitorSessionQuery

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshFcmStatus() }
    private val notificationFactsExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.saveNotificationFactsExport(uri) }
    private val monitorSessionExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.saveMonitorSessionExport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onDetect = viewModel::detect,
                    onShizukuAction = viewModel::requestShizukuAccess,
                    onCheckAutostart = viewModel::checkGuardianAutostart,
                    onOpenAutostartSettings = {
                        openGuardianAutostartSettings()
                    },
                    onShowTargetSelection = viewModel::showTargetSelection,
                    onDismissTargetSelection = viewModel::dismissTargetSelection,
                    onSetTargetSelected = viewModel::setTargetSelected,
                    onCheckTarget = viewModel::checkSelectedTarget,
                    onOpenTargetSettings = ::openSelectedTargetAutostartSettings,
                    onRunDailyProtection = viewModel::runDailyProtectionNow,
                    onClearCorruptTargetSelection = viewModel::clearCorruptTargetSelection,
                    onRequestFcmToken = viewModel::requestFcmToken,
                    onToggleFcmTokenVisibility = viewModel::toggleFcmTokenVisibility,
                    onCopyFcmToken = viewModel::copyFcmToken,
                    onClearFcmToken = viewModel::clearFcmTokenFromUi,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onSetNotificationFactsEnabled = viewModel::setNotificationFactsEnabled,
                    onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                    onClearNotificationFactsHistory = viewModel::clearNotificationFactsHistory,
                    onExportNotificationFacts = ::exportNotificationFacts,
                    onStartDeepMonitoring = { packageName ->
                        lifecycleScope.launch {
                            viewModel.startDeepMonitoring(packageName) {
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isFinishing && !isDestroyed
                            }
                        }
                    },
                    onStopDeepMonitoring = viewModel::stopDeepMonitoring,
                    onClearDeepMonitoring = viewModel::clearDeepMonitoring,
                    onExportMonitorSession = ::exportMonitorSession,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizuku()
        viewModel.refreshFcmStatus()
        viewModel.onActivityResumed()
    }

    override fun onPause() {
        viewModel.onAppBackgrounded()
        viewModel.onActivityPausedAfterAutostartLaunch()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.closeForActivityExit()
        super.onDestroy()
    }

    internal fun openGuardianAutostartSettings() {
        viewModel.onAutostartSettingsLaunchResult(AutostartSettingsLauncher.open(this))
    }

    internal fun openSelectedTargetAutostartSettings(packageName: String) {
        val identity = viewModel.prepareTargetSettingsLaunch(packageName) ?: return
        viewModel.onTargetSettingsLaunchResult(identity, AutostartSettingsLauncher.open(this))
    }

    internal fun openNotificationListenerSettings() {
        runCatching { startActivity(viewModel.notificationListenerSettingsIntent()) }
            .onFailure { viewModel.onNotificationListenerSettingsOpenFailed() }
    }

    private fun exportNotificationFacts(query: NotificationFactsQuery) {
        viewModel.prepareNotificationFactsExport(query) {
            if (!isFinishing && !isDestroyed) {
                notificationFactsExportLauncher.launch("guardian-notification-facts.json")
            }
        }
    }

    private fun exportMonitorSession(query: MonitorSessionQuery) {
        viewModel.prepareMonitorSessionExport(query) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || isDestroyed) {
                false
            } else {
                monitorSessionExportLauncher.launch("guardian-monitor-session.json")
                true
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.refreshFcmStatus()
        }
    }
}
