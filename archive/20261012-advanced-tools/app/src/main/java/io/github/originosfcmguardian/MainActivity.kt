package io.github.originosfcmguardian

import android.Manifest
import android.app.StatusBarManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.originosfcmguardian.ui.GuardianTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.originosfcmguardian.ui.HomeScreen
import io.github.originosfcmguardian.ui.HomeViewModel
import io.github.originosfcmguardian.ui.RecentsVisibility
import io.github.originosfcmguardian.autostart.AutostartSettingsLauncher
import io.github.originosfcmguardian.notification.NotificationFactsQuery
import io.github.originosfcmguardian.monitoring.MonitorSessionQuery
import io.github.originosfcmguardian.autoprotection.GuardianTileService
import io.github.originosfcmguardian.autoprotection.GUARDIAN_TILE_LABEL
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshFcmStatus()
        viewModel.refreshProtectionNotificationVisibility()
    }
    private val notificationFactsExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.saveNotificationFactsExport(uri) }
    private val monitorSessionExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.saveMonitorSessionExport(uri) }
    private val combinedDiagnosticExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DiagnosticReportFormat.JSON.mimeType),
    ) { uri -> viewModel.saveCombinedDiagnosticReportExport(uri) }
    private val markdownDiagnosticExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DiagnosticReportFormat.MARKDOWN.mimeType),
    ) { uri -> viewModel.saveCombinedDiagnosticReportExport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Retire only the obsolete experimental pairing result, not active protection notifications.
        getSystemService(NotificationManager::class.java).cancel(4107)
        enableEdgeToEdge()
        setContent {
            GuardianTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onShizukuAction = viewModel::requestShizukuAccess,
                    onOpenAutostartSettings = {
                        openGuardianAutostartSettings()
                    },
                    onShowTargetSelection = viewModel::showTargetSelection,
                    onDismissTargetSelection = viewModel::dismissTargetSelection,
                    onSetTargetSelected = viewModel::setTargetSelected,
                    onCheckTarget = viewModel::checkSelectedTarget,
                    onCheckVisibleApp = viewModel::checkVisibleApp,
                    onRecheckSelectedApps = viewModel::checkSelectedApps,
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
                    onExportCombinedDiagnosticReport = ::exportCombinedDiagnosticReport,
                    onOpenFcmDiagnostics = { io.github.originosfcmguardian.fcm.openGmsDiagnostics(this) },
                    onReadGmsConnection = viewModel::readGmsConnection,
                    onRequestFcmProtection = viewModel::requestFcmProtection,
                    onConfirmFcmProtection = viewModel::confirmFcmProtection,
                    onDismissFcmProtection = viewModel::dismissFcmProtection,
                    onDisableFcmProtection = viewModel::disableFcmProtection,
                    onRequestStoppedRecovery = viewModel::requestStoppedRecovery,
                    onConfirmStoppedRecovery = viewModel::confirmStoppedRecovery,
                    onDismissStoppedRecovery = viewModel::dismissStoppedRecovery,
                    onReconcileStoppedRecovery = viewModel::reconcileStoppedRecovery,
                    onRequestAutomaticProtection = viewModel::requestAutomaticProtection,
                    onConfirmAutomaticProtection = viewModel::confirmAutomaticProtection,
                    onDismissAutomaticProtection = viewModel::dismissAutomaticProtection,
                    onStopAutomaticProtection = viewModel::stopAutomaticProtection,
                    onRequestTargetAutomaticProtection = viewModel::requestTargetAutomaticProtection,
                    onRequestAppAutomaticProtection = viewModel::requestAppAutomaticProtection,
                    onRefreshApps = viewModel::refreshVisibleApps,
                    onStopTargetAutomaticProtection = viewModel::stopTargetAutomaticProtection,
                    onOpenProtectionNotificationSettings = {
                        io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime.openNotificationSettings(this)
                    },
                    onRefreshShizuku = viewModel::refreshShizuku,
                    onOpenShizuku = viewModel::openShizuku,
                    onRequestAddGuardianTile = ::requestAddGuardianTile,
                    onSetHiddenFromRecents = { hidden ->
                        viewModel.refreshRecentsPreference(RecentsVisibility.set(this, hidden))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshShizuku()
        viewModel.refreshFcmStatus()
        viewModel.refreshProtectionNotificationVisibility()
        viewModel.onActivityResumed()
        viewModel.refreshRecentsPreference(RecentsVisibility.apply(this))
    }

    override fun onPause() {
        viewModel.onAppBackgrounded()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) viewModel.closeForActivityExit()
        super.onDestroy()
    }

    internal fun openGuardianAutostartSettings() {
        viewModel.onAutostartSettingsLaunchResult(AutostartSettingsLauncher.open(this))
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

    private fun exportCombinedDiagnosticReport(format: DiagnosticReportFormat) {
        viewModel.prepareCombinedDiagnosticReportExport(format) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || isDestroyed) {
                false
            } else {
                when (format) {
                    DiagnosticReportFormat.JSON -> combinedDiagnosticExportLauncher.launch(format.fileName)
                    DiagnosticReportFormat.MARKDOWN -> markdownDiagnosticExportLauncher.launch(format.fileName)
                }
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

    private fun requestAddGuardianTile(result: (String) -> Unit) {
        val manual = "下拉控制中心，进入编辑，将“推送保护”拖到常用区域。"
        if (Build.VERSION.SDK_INT < 33) { result(manual); return }
        runCatching {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, GuardianTileService::class.java), GUARDIAN_TILE_LABEL,
                Icon.createWithResource(this, R.drawable.ic_guardian_tile), mainExecutor,
            ) { response ->
                result(when (response) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加“推送保护”，可在控制中心调整位置。"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "“推送保护”已在控制中心，可进入编辑调整位置。"
                    else -> "未添加。$manual"
                })
            }
        }.onFailure { result("系统添加入口暂不可用。$manual") }
    }
}
