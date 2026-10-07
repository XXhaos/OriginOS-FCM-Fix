package io.github.originosfcmguardian.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.autostart.AutostartSettingsLaunchResult
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.TargetSelectionUiState
import io.github.originosfcmguardian.autostart.identityStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.withStoppedSnapshot
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.recovery.StoppedRecoveryController
import io.github.originosfcmguardian.recovery.StoppedRecoveryResult
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.RecoveryCountsUiState
import io.github.originosfcmguardian.recovery.AndroidRecoveryCounts
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autoprotection.ProtectionNotificationVisibility
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import io.github.originosfcmguardian.protection.DailyProtectionEvents
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.DailyProtectionScheduler
import io.github.originosfcmguardian.protection.DailyProtectionStore
import io.github.originosfcmguardian.protection.submitManualProtection
import io.github.originosfcmguardian.fcm.FcmConfigurationStatus
import io.github.originosfcmguardian.fcm.FcmEvidenceSnapshot
import io.github.originosfcmguardian.fcm.FcmEvidenceStore
import io.github.originosfcmguardian.fcm.FcmRegistrationClient
import io.github.originosfcmguardian.fcm.FcmRegistrationRequestGate
import io.github.originosfcmguardian.fcm.FcmUiState
import io.github.originosfcmguardian.fcm.NotificationPermissionStatus
import io.github.originosfcmguardian.fcm.RegistrationResult
import io.github.originosfcmguardian.fcm.RegistrationStatus
import io.github.originosfcmguardian.fcm.awaitRegistrationResult
import io.github.originosfcmguardian.fcm.SensitiveToken
import io.github.originosfcmguardian.fcm.SensitiveTokenClipboard
import io.github.originosfcmguardian.notification.NotificationFactsController
import io.github.originosfcmguardian.notification.NotificationFactsUiState
import io.github.originosfcmguardian.notification.NotificationFactsQuery
import io.github.originosfcmguardian.notification.PreparedNotificationFactsExport
import io.github.originosfcmguardian.notification.PreparedExportResult
import io.github.originosfcmguardian.monitoring.DeepMonitoringController
import io.github.originosfcmguardian.monitoring.DeepMonitoringRuntime
import io.github.originosfcmguardian.monitoring.DeepMonitoringUiState
import io.github.originosfcmguardian.monitoring.MonitorSessionQuery
import io.github.originosfcmguardian.monitoring.PreparedMonitorSessionExport
import io.github.originosfcmguardian.monitoring.MonitorExportResult
import io.github.originosfcmguardian.monitoring.encodeMonitorSessionExport
import io.github.originosfcmguardian.diagnostics.CombinedDiagnosticExportResult
import io.github.originosfcmguardian.diagnostics.DiagnosticEnvironment
import io.github.originosfcmguardian.diagnostics.DiagnosticReportFormat
import io.github.originosfcmguardian.diagnostics.PreparedCombinedDiagnosticExport
import io.github.originosfcmguardian.diagnostics.buildCombinedDiagnosticReport
import io.github.originosfcmguardian.diagnostics.encodeDiagnosticReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class HomeUiState(
    val shizuku: ShizukuStatusSnapshot = ShizukuStatusSnapshot(
        ShizukuStatus.ERROR,
        "正在读取 Shizuku 状态",
    ),
    val detecting: Boolean = false,
    val autostartSettingsMessage: String? = null,
    val selectedTargets: List<SelectedTargetUiState> = emptyList(),
    val targetSelectionLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val targetSelection: TargetSelectionUiState = TargetSelectionUiState(),
    val targetReadAttemptCount: Long = 0,
    val targetReadInFlight: Set<SelectedTargetIdentity> = emptySet(),
    val selectedAppsRecheckRunning: Boolean = false,
    val targetSelectionStoreCorrupt: Boolean = false,
    val stoppedRecovery: StoppedRecoveryUiState = StoppedRecoveryUiState(),
    val recoveryCounts: RecoveryCountsUiState = RecoveryCountsUiState(),
    val recoveryFirmwareSupported: Boolean = false,
    val automaticProtection: AutomaticProtectionState = AutomaticProtectionState(),
    val protectionNotificationVisibility: ProtectionNotificationVisibility = ProtectionNotificationVisibility.UNKNOWN,
    val automaticConfirmationIdentity: SelectedTargetIdentity? = null,
    val visibleApps: List<VisibleProtectionApp> = emptyList(),
    val hiddenPreinstalledPackages: Set<String> = emptySet(),
    val visibleAppsLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val appsMessage: String? = null,
    val automaticPreparationPackage: String? = null,
    val hiddenFromRecents: Boolean = false,
    val recentsMessage: String? = null,
    val dailyProtectionReport: DailyProtectionReport? = null,
    val dailyProtectionLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val dailyProtectionSubmitting: Boolean = false,
    val dailyProtectionSubmissionMessage: String? = null,
    val fcm: FcmUiState = FcmUiState(),
    val fcmEvidenceLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val notificationFacts: NotificationFactsUiState = NotificationFactsUiState(
        enabled = false,
        accessGranted = false,
        connected = false,
        targetCount = 0,
        events = emptyList(),
        historyCorrupt = false,
        droppedEvents = 0,
    ),
    val notificationFactsMessage: String? = null,
    val notificationFactsLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val notificationFactsExportFeedback: String? = null,
    val deepMonitoring: DeepMonitoringUiState = DeepMonitoringUiState(),
    val deepMonitoringLoadState: HomeSourceLoadState = HomeSourceLoadState.LOADING,
    val deepMonitoringActionMessage: String? = null,
    val deepMonitoringExportFeedback: String? = null,
    val deepMonitoringExportBusy: Boolean = false,
    val diagnosticReportExportFeedback: String? = null,
    val diagnosticReportExportBusy: Boolean = false,
    val gmsConnection: GmsConnectionUiState = GmsConnectionUiState(),
    val gmsAutoHeartbeat: io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState =
        io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState(),
    val fcmRecovery: io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState(),
    val fcmProtection: io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState = io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState(),
    val fcmProtectionConfirmation: Boolean = false,
)

enum class HomeSourceLoadState {
    LOADING,
    LOADED,
    FAILED,
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val shizukuRuntime = ShizukuRuntime(application)
    private val dailyProtectionSubmissionGate = SingleFlightGate()
    private val targetStore = TargetSelectionStore(application)
    private val targetProbe = TargetPackageProbe(application)
    private val appProtectionRequestGate = AppProtectionRequestGate()
    private var appProtectionRequestToken: Long? = null
    private var visibleAppsGeneration = 0L
    private var visibleAppsRefreshJob: Job? = null
    private val stoppedRecoveryController = StoppedRecoveryController(application)
    private var stoppedRecoveryJob: Job? = null
    private var recoveryUiActive = false
    private var stoppedRecoveryRecordGeneration = 0L
    private var recoverySuccessJob: Job? = null
    private var recoveryCountsGeneration = 0L
    private val dailyProtectionEvents = DailyProtectionEvents(application)
    private val dailyProtectionStore = DailyProtectionStore(application)
    private val fcmRegistrationClient = FcmRegistrationClient(application)
    private val fcmRegistrationRequests = FcmRegistrationRequestGate()
    private val fcmEvidenceStore = FcmEvidenceStore(application)
    private val sensitiveTokenClipboard = SensitiveTokenClipboard(application)
    private val notificationFactsController = NotificationFactsController(application)
    private val deepMonitoringController = DeepMonitoringController(application)
    private val monitorPreparedExport = PreparedMonitorSessionExport()
    private val diagnosticPreparedExport = PreparedCombinedDiagnosticExport()
    private var gmsConnectionJob: Job? = null
    private var gmsConnectionGeneration = 0L
    private var gmsConnectionClosed = false
    @Volatile
    private var gmsNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    fun openGmsConnection() {
        if (gmsConnectionClosed) return
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(visible = true)) }
        ensureGmsNetworkTracking()
    }

    private fun ensureGmsNetworkTracking() {
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        if (gmsNetworkCallback == null) {
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                private fun changed(network: android.net.Network, key: String? = null, lost: Boolean = false) {
                    if (gmsNetworkCallback !== this) return
                    _uiState.update { current ->
                        val value = current.gmsConnection
                        if (gmsReadNetworkInvalidated(value.networkKey, network.networkHandle, key, lost))
                            current.copy(gmsConnection = value.copy(networkChanged = true)) else current
                    }
                }
                override fun onAvailable(network: android.net.Network) = changed(network)
                override fun onLost(network: android.net.Network) = changed(network, lost = true)
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                    val wifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    val cell = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    val vpn = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    val validated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    changed(network, "${network.networkHandle}:$wifi:$cell:$vpn:$validated")
                }
            }
            runCatching { cm.registerDefaultNetworkCallback(callback); gmsNetworkCallback = callback }
        }
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(networkTrackingAvailable = gmsNetworkCallback != null)) }
    }

    fun dismissGmsConnection() {
        // Inline observations outlive page/background transitions. Only the legacy modal closes.
        if (!_uiState.value.gmsConnection.visible) return
        gmsConnectionGeneration++
        gmsConnectionJob?.cancel(); gmsConnectionJob = null
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(
            visible = false, busy = false, heartbeatBusy = false,
        )) }
    }

    private fun closeGmsConnection() {
        gmsConnectionClosed = true
        gmsConnectionGeneration++
        gmsConnectionJob?.cancel(); gmsConnectionJob = null
        gmsNetworkCallback?.let { callback -> runCatching {
            getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } }
        gmsNetworkCallback = null
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(
            visible = false, busy = false, heartbeatBusy = false, networkTrackingAvailable = false,
        )) }
    }

    fun requestFcmProtection() {
        if (_uiState.value.fcmProtection.busy || _uiState.value.gmsAutoHeartbeat.stopPending) return
        _uiState.update { it.copy(fcmProtectionConfirmation = true) }
    }
    fun dismissFcmProtection() { _uiState.update { it.copy(fcmProtectionConfirmation = false) } }
    fun confirmFcmProtection() {
        if (!_uiState.value.fcmProtectionConfirmation) return
        dismissFcmProtection()
        viewModelScope.launch(Dispatchers.IO) { io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.confirm(getApplication()) }
    }
    fun disableFcmProtection() {
        dismissFcmProtection()
        io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.disable(getApplication())
    }

    fun readGmsConnection() {
        if (gmsConnectionClosed || _uiState.value.gmsConnection.busy) return
        ensureGmsNetworkTracking()
        val generation = ++gmsConnectionGeneration
        val network = gmsNetworkContext(getApplication())
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(busy = true, observation = null,
            readAttempted = true, readCompletedAtElapsedMillis = null,
            heartbeatBusy = false, heartbeatStatus = null,
            networkBackground = network.summary, networkKey = network.key, networkChanged = false)) }
        gmsConnectionJob = viewModelScope.launch {
            try {
                val observation = readDirectGmsConnection(generation) { shizukuRuntime.executor.readGmsConnection() }
                coroutineContext.ensureActive()
                val changed = network.key == "unknown" || network.key != gmsNetworkContext(getApplication()).key
                if (generation == gmsConnectionGeneration) _uiState.update {
                    it.copy(gmsConnection = it.gmsConnection.copy(observation = observation,
                        readCompletedAtElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                        automaticObservationAtReadCompletion = it.fcmProtection.latestObservation,
                        networkChanged = it.gmsConnection.networkChanged || changed))
                }
            } finally {
                if (generation == gmsConnectionGeneration) {
                    gmsConnectionJob = null
                    _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(busy = false)) }
                }
            }
        }
    }

    fun requestGmsHeartbeat() {
        if (_uiState.value.gmsAutoHeartbeat.manualBlocked || !_uiState.value.gmsConnection.visible || _uiState.value.gmsConnection.busy) return
        val generation = ++gmsConnectionGeneration
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(busy = true,
            heartbeatBusy = true, heartbeatStatus = null, observation = null)) }
        // Only this explicit callback dispatches. No restore/rebind/lifecycle path replays it.
        gmsConnectionJob = viewModelScope.launch {
            var status = io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus.UNKNOWN
            try {
                status = shizukuRuntime.executor.requestGmsHeartbeat().status
                coroutineContext.ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A bridge exception cannot establish that a possible dispatch never happened.
            } finally {
                if (generation == gmsConnectionGeneration) _uiState.update {
                    it.copy(gmsConnection = it.gmsConnection.copy(busy = false,
                        heartbeatBusy = false, heartbeatStatus = status))
                }
            }
        }
    }
    private var dailyProtectionSubscription: AutoCloseable? = null
    private var fcmEvidenceSubscription: AutoCloseable? = null
    private var notificationFactsSubscription: AutoCloseable? = null
    private var notificationFactsRefreshJob: Job? = null
    private val notificationFactsActionMutex = Mutex()
    private val notificationFactsPreparedExport = PreparedNotificationFactsExport()
    private var notificationFactsExportPreparing = false
    @Volatile private var notificationFactsUiActive = false
    private var fcmRegistrationJob: Job? = null
    private var clipboardClearJob: Job? = null
    private var lastCopiedFcmToken: SensitiveToken? = null
    private var lastShizukuReady: Boolean? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.initialize(application)
        viewModelScope.launch {
            io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.state.collectLatest { snapshot ->
                _uiState.update { it.copy(fcmProtection = snapshot) }
            }
        }
        viewModelScope.launch {
            io.github.originosfcmguardian.fcmrecovery.FcmRecoveryRuntime.state.collectLatest { snapshot ->
                _uiState.update { it.copy(fcmRecovery = snapshot) }
            }
        }
        viewModelScope.launch {
            io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime.state.collectLatest { snapshot ->
                _uiState.update { it.copy(gmsAutoHeartbeat = snapshot) }
            }
        }
        _uiState.update { it.copy(hiddenFromRecents = RecentsVisibility.hidden(application)) }
        viewModelScope.launch {
            AndroidRecoveryCounts.changes.collectLatest { refreshRecoveryCounts() }
        }
        viewModelScope.launch {
            AutomaticProtectionRuntime.state.collectLatest { snapshot ->
                val previous = _uiState.value.automaticProtection
                _uiState.update { it.copy(automaticProtection = snapshot) }
                if (previous.busy && !snapshot.busy && recoveryUiActive) refreshStoppedRecoveryRecord()
            }
        }
        viewModelScope.launch {
            DeepMonitoringRuntime.state.collectLatest { snapshot ->
                if (snapshot.historyCorrupt || snapshot.session?.id != _uiState.value.deepMonitoring.session?.id) {
                    monitorPreparedExport.invalidate()
                }
                _uiState.update { it.copy(deepMonitoring = snapshot) }
            }
        }
        viewModelScope.launch {
            try {
                deepMonitoringController.refresh()
                _uiState.update {
                    it.copy(
                        deepMonitoring = DeepMonitoringRuntime.state.value,
                        deepMonitoringLoadState = HomeSourceLoadState.LOADED,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(deepMonitoringLoadState = HomeSourceLoadState.FAILED) }
            }
        }
        shizukuRuntime.start()
        dailyProtectionSubscription = dailyProtectionStore.registerReportListener {
            loadDailyProtectionReport()
        }
        fcmEvidenceSubscription = fcmEvidenceStore.registerListener { loadFcmEvidence() }
        notificationFactsSubscription = notificationFactsController.registerListener {
            if (notificationFactsUiActive) refreshNotificationFacts(onlyWhenActive = true)
        }
        viewModelScope.launch {
            shizukuRuntime.status.collectLatest { snapshot ->
                _uiState.update { it.copy(shizuku = snapshot) }
                val ready = snapshot.status == ShizukuStatus.READY_SHELL
                if (lastShizukuReady != ready) {
                    lastShizukuReady = ready
                    viewModelScope.launch(Dispatchers.IO) {
                        dailyProtectionEvents.onShizukuAvailableTransition(ready)
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { dailyProtectionEvents.onAppStart() }
        loadSelectedTargets()
        refreshStoppedRecoveryRecord()
        loadDailyProtectionReport()
        refreshFcmStatus()
        refreshNotificationFacts()
    }

    fun refreshShizuku() = shizukuRuntime.refresh()

    suspend fun startDeepMonitoring(packageName: String, isVisible: () -> Boolean) {
        if (_uiState.value.deepMonitoringExportBusy) return
        monitorPreparedExport.invalidate()
        _uiState.update { it.copy(deepMonitoringActionMessage = null) }
        val error = deepMonitoringController.start(packageName, isVisible)
        _uiState.update { it.copy(deepMonitoringActionMessage = error) }
    }

    fun stopDeepMonitoring() {
        _uiState.update { it.copy(deepMonitoringActionMessage = null) }
        deepMonitoringController.stop()
    }

    fun clearDeepMonitoring() {
        if (_uiState.value.deepMonitoringExportBusy) return
        monitorPreparedExport.invalidate()
        viewModelScope.launch {
            val cleared = deepMonitoringController.clearHistory()
            _uiState.update { it.copy(deepMonitoringActionMessage = if (cleared) null else "尚未清除，请先停止监控后重试") }
        }
    }

    fun prepareMonitorSessionExport(query: MonitorSessionQuery, openPicker: () -> Boolean) {
        if (_uiState.value.deepMonitoringExportBusy) return
        val monitor = DeepMonitoringRuntime.state.value
        val session = monitor.session
        if (monitor.historyCorrupt || session == null || session.id != query.sessionId) {
            _uiState.update { it.copy(deepMonitoringExportFeedback = "会话已改变或记录不可用，请重新选择后导出") }
            return
        }
        val ticket = monitorPreparedExport.begin(session.id) ?: return
        val snapshot = session.copy(events = session.events.toList())
        _uiState.update { it.copy(deepMonitoringExportBusy = true, deepMonitoringExportFeedback = null) }
        viewModelScope.launch {
            var pickerOpened = false
            try {
                val bytes = withContext(Dispatchers.Default) { encodeMonitorSessionExport(snapshot, query) }
                val current = DeepMonitoringRuntime.state.value
                check(!current.historyCorrupt && current.session?.id == snapshot.id)
                check(monitorPreparedExport.complete(ticket, bytes))
                pickerOpened = openPicker()
                check(pickerOpened)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(deepMonitoringExportFeedback = "无法生成会话导出或打开保存位置，请重试") }
            } finally {
                if (!pickerOpened) {
                    monitorPreparedExport.cancel(ticket)
                    _uiState.update { it.copy(deepMonitoringExportBusy = false) }
                }
            }
        }
    }

    fun saveMonitorSessionExport(uri: Uri?) {
        val current = DeepMonitoringRuntime.state.value
        if (current.historyCorrupt) monitorPreparedExport.invalidate()
        val result = monitorPreparedExport.consume(uri != null, current.session?.id)
        if (result !is MonitorExportResult.Ready) {
            _uiState.update {
                it.copy(
                    deepMonitoringExportBusy = false,
                    deepMonitoringExportFeedback = if (result == MonitorExportResult.Missing)
                        "会话导出快照已失效，未写入文件；请重新点击导出" else it.deepMonitoringExportFeedback,
                )
            }
            return
        }
        checkNotNull(uri)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(result.bytes) }
                        ?: error("Document provider did not open output")
                }.isSuccess
            }
            _uiState.update {
                it.copy(
                    deepMonitoringExportBusy = false,
                    deepMonitoringExportFeedback = if (saved) "已导出点击时的会话筛选快照" else "导出写入失败，文件可能不完整；请重试",
                )
            }
        }
    }

    fun prepareCombinedDiagnosticReportExport(
        format: DiagnosticReportFormat = DiagnosticReportFormat.JSON,
        openPicker: () -> Boolean,
    ) {
        if (_uiState.value.diagnosticReportExportBusy) return
        val capturedAt = System.currentTimeMillis()
        val report = buildCombinedDiagnosticReport(
            state = _uiState.value.copy(shizuku = shizukuRuntime.status.value),
            environment = DiagnosticEnvironment(
                sdkInt = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                guardianVersionName = BuildConfig.VERSION_NAME,
                guardianVersionCode = BuildConfig.VERSION_CODE.toLong(),
            ),
            capturedAtEpochMillis = capturedAt,
        )
        _uiState.update {
            it.copy(diagnosticReportExportBusy = true, diagnosticReportExportFeedback = null)
        }
        viewModelScope.launch {
            var pickerOpened = false
            try {
                val lifecycle = withContext(Dispatchers.IO) {
                    io.github.originosfcmguardian.autoprotection.ProtectionLifecycleDiagnostics.readSnapshot()
                }
                val bytes = withContext(Dispatchers.Default) {
                    encodeDiagnosticReport(report.copy(protectionLifecycle = lifecycle), format)
                }
                diagnosticPreparedExport.prepare(bytes)
                pickerOpened = openPicker()
                check(pickerOpened)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(diagnosticReportExportFeedback = "无法生成诊断报告或打开保存位置，请重试")
                }
            } finally {
                if (!pickerOpened) {
                    diagnosticPreparedExport.invalidate()
                    _uiState.update { it.copy(diagnosticReportExportBusy = false) }
                }
            }
        }
    }

    fun saveCombinedDiagnosticReportExport(uri: Uri?) {
        when (val result = diagnosticPreparedExport.consume(uri != null)) {
            CombinedDiagnosticExportResult.Cancelled -> {
                _uiState.update { it.copy(diagnosticReportExportBusy = false) }
            }
            CombinedDiagnosticExportResult.Missing -> {
                _uiState.update {
                    it.copy(
                        diagnosticReportExportBusy = false,
                        diagnosticReportExportFeedback = "诊断报告快照已失效，未写入文件；请重新点击导出",
                    )
                }
            }
            is CombinedDiagnosticExportResult.Ready -> {
                checkNotNull(uri)
                viewModelScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                                ?.use { it.write(result.bytes) }
                                ?: error("Document provider did not open output")
                        }.isSuccess
                    }
                    _uiState.update {
                        it.copy(
                            diagnosticReportExportBusy = false,
                            diagnosticReportExportFeedback = if (saved) {
                                "已导出点击时的脱敏综合诊断快照"
                            } else {
                                "导出写入失败，文件可能不完整；请重试"
                            },
                        )
                    }
                }
            }
        }
    }

    fun requestShizukuAccess() = shizukuRuntime.requestOrOpen()

    fun openShizuku() = shizukuRuntime.openManager()

    fun requestFcmToken() {
        val requestGeneration = fcmRegistrationRequests.tryStart() ?: return
        clearOwnedFcmTokenClipboard()
        _uiState.update {
            it.copy(
                fcm = it.fcm.copy(
                    registration = RegistrationStatus.REQUESTING,
                    registrationDetail = "正在向 Firebase 请求当前设备令牌…",
                    token = null,
                    tokenVisible = false,
                    clipboardDetail = null,
                ),
            )
        }
        fcmRegistrationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                awaitRegistrationResult(FCM_REGISTRATION_TIMEOUT_MILLIS) {
                    fcmRegistrationClient.requestToken()
                }
            }
            if (!fcmRegistrationRequests.completeIfCurrent(requestGeneration)) return@launch
            fcmRegistrationJob = null
            _uiState.update { current ->
                val next = when (result) {
                    is RegistrationResult.Success -> current.fcm.copy(
                        registration = RegistrationStatus.TOKEN_READY,
                        registrationDetail = "令牌已获取；默认隐藏且不会写入日志、备份或诊断导出",
                        token = result.token,
                        tokenVisible = false,
                    )
                    RegistrationResult.ConfigMissing -> current.fcm.copy(
                        configuration = FcmConfigurationStatus.MISSING,
                        registration = RegistrationStatus.CONFIG_MISSING,
                        registrationDetail = "未配置 Firebase；添加匹配本应用包名的本地配置后再试",
                    )
                    RegistrationResult.TimedOut -> current.fcm.copy(
                        registration = RegistrationStatus.TIMED_OUT,
                        registrationDetail = "30 秒内未取得结果；注册请求可能仍在继续，不能据此判断断网",
                    )
                    RegistrationResult.SdkIoFailure -> current.fcm.copy(
                        registration = RegistrationStatus.SDK_IO_FAILURE,
                        registrationDetail = "注册请求已返回失败，具体原因待查；未记录令牌或错误正文",
                    )
                    RegistrationResult.Failed -> current.fcm.copy(
                        registration = RegistrationStatus.FAILED,
                        registrationDetail = "令牌请求失败；错误已脱敏且未记录令牌",
                    )
                }
                current.copy(fcm = next)
            }
        }
    }

    fun toggleFcmTokenVisibility() {
        _uiState.update { current ->
            if (current.fcm.token == null) current
            else current.copy(fcm = current.fcm.copy(tokenVisible = !current.fcm.tokenVisible))
        }
    }

    fun copyFcmToken() {
        val token = _uiState.value.fcm.token ?: return
        clearOwnedFcmTokenClipboard()
        val copied = sensitiveTokenClipboard.copy(token)
        if (copied) {
            lastCopiedFcmToken = token
            clipboardClearJob = viewModelScope.launch {
                delay(TOKEN_CLIPBOARD_TTL_MILLIS)
                if (lastCopiedFcmToken === token) {
                    sensitiveTokenClipboard.clearIfOwned(token)
                    lastCopiedFcmToken = null
                    clipboardClearJob = null
                }
                _uiState.update { current ->
                    if (current.fcm.token === token) {
                        current.copy(fcm = current.fcm.copy(clipboardDetail = "令牌剪贴板副本已自动清除"))
                    } else current
                }
            }
        }
        _uiState.update {
            it.copy(
                fcm = it.fcm.copy(
                    clipboardDetail = if (copied) "已复制；若未被替换，60 秒后自动清除" else "复制失败",
                ),
            )
        }
    }

    fun clearFcmTokenFromUi() {
        cancelFcmRegistration()
        clearOwnedFcmTokenClipboard()
        _uiState.update {
            it.copy(
                fcm = it.fcm.copy(
                    registration = RegistrationStatus.NOT_REQUESTED,
                    registrationDetail = "令牌已从界面和本应用剪贴板清除；需要时可再次主动获取",
                    token = null,
                    tokenVisible = false,
                    clipboardDetail = null,
                ),
            )
        }
    }

    fun onAppBackgrounded() {
        dismissGmsConnection()
        recoveryUiActive = false
        dismissAutomaticProtection()
        cancelStoppedRecovery()
        notificationFactsUiActive = false
        notificationFactsRefreshJob?.cancel()
        cancelFcmRegistration()
        clearOwnedFcmTokenClipboard()
        _uiState.update { current ->
            val fcm = if (current.fcm.registration == RegistrationStatus.REQUESTING) {
                current.fcm.copy(
                    registration = RegistrationStatus.NOT_REQUESTED,
                    registrationDetail = "应用进入后台，已停止等待令牌结果；返回后可再次主动获取",
                    tokenVisible = false,
                    clipboardDetail = null,
                )
            } else {
                current.fcm.copy(tokenVisible = false, clipboardDetail = null)
            }
            current.copy(fcm = fcm)
        }
    }

    fun refreshProtectionNotificationVisibility() {
        val visibility = AutomaticProtectionRuntime.notificationVisibility(getApplication<Application>())
        _uiState.update { it.copy(protectionNotificationVisibility = visibility) }
    }

    fun refreshFcmStatus() {
        val application = getApplication<Application>()
        val permission = if (
            Build.VERSION.SDK_INT < 33 ||
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationPermissionStatus.GRANTED
        } else {
            NotificationPermissionStatus.REQUIRED
        }
        val configuration = fcmRegistrationClient.configurationStatus()
        val configurationChanged = configuration != _uiState.value.fcm.configuration
        if (configurationChanged) {
            cancelFcmRegistration()
            clearOwnedFcmTokenClipboard()
        }
        _uiState.update { current ->
            current.copy(
                fcm = current.fcm.copy(
                    configuration = configuration,
                    notificationPermission = permission,
                    token = if (configurationChanged) null else current.fcm.token,
                    tokenVisible = if (configurationChanged) false else current.fcm.tokenVisible,
                    clipboardDetail = if (configurationChanged) null else current.fcm.clipboardDetail,
                ),
            )
        }
        loadFcmEvidence()
    }

    fun refreshNotificationFacts(onlyWhenActive: Boolean = false) {
        viewModelScope.launch {
            if (onlyWhenActive && !notificationFactsUiActive) return@launch
            notificationFactsRefreshJob?.cancel()
            notificationFactsRefreshJob = launch {
                val snapshot = withContext(Dispatchers.IO) {
                    runCatching { notificationFactsController.snapshot() }
                }
                if (snapshot.exceptionOrNull() is CancellationException) {
                    throw snapshot.exceptionOrNull() as CancellationException
                }
                _uiState.update { current ->
                    snapshot.fold(
                        onSuccess = {
                            current.copy(
                                notificationFacts = it,
                                notificationFactsLoadState = HomeSourceLoadState.LOADED,
                            )
                        },
                        onFailure = {
                            current.copy(
                                notificationFactsLoadState = HomeSourceLoadState.FAILED,
                                notificationFactsMessage = "通知记录状态读取失败；请稍后重试",
                            )
                        },
                    )
                }
            }
        }
    }

    fun setNotificationFactsEnabled(enabled: Boolean) {
        val current = _uiState.value
        if (enabled && (current.targetSelectionStoreCorrupt || current.notificationFacts.targetCount < 1)) {
            _uiState.update {
                it.copy(notificationFactsMessage = "请先在“关注应用”中选择至少一个身份有效的应用")
            }
            return
        }
        viewModelScope.launch {
            notificationFactsActionMutex.withLock {
                _uiState.update { it.copy(notificationFactsMessage = null) }
                val changed = withContext(Dispatchers.IO) {
                    runCatching { notificationFactsController.setEnabled(enabled) }.getOrDefault(false)
                }
                if (!changed) {
                    _uiState.update {
                        it.copy(notificationFactsMessage = "无法保存通知记录开关；原状态未确认改变")
                    }
                }
                refreshNotificationFacts()
            }
        }
    }

    fun clearNotificationFactsHistory() {
        viewModelScope.launch {
            notificationFactsActionMutex.withLock {
                notificationFactsPreparedExport.invalidate()
                _uiState.update { it.copy(notificationFactsMessage = null, notificationFactsExportFeedback = null) }
                val cleared = withContext(Dispatchers.IO) {
                    runCatching { notificationFactsController.clearHistory() }.getOrDefault(false)
                }
                if (!cleared) {
                    _uiState.update {
                        it.copy(notificationFactsMessage = "无法清除本地通知记录；现有记录未确认改变")
                    }
                }
                refreshNotificationFacts()
            }
        }
    }

    fun prepareNotificationFactsExport(query: NotificationFactsQuery, onReady: () -> Unit) {
        if (notificationFactsExportPreparing) return
        notificationFactsExportPreparing = true
        _uiState.update { it.copy(notificationFactsMessage = null, notificationFactsExportFeedback = null) }
        viewModelScope.launch {
            try {
                notificationFactsActionMutex.withLock {
                    val bytes = withContext(Dispatchers.IO) { notificationFactsController.exportSnapshot(query) }
                    notificationFactsPreparedExport.prepare(bytes)
                    onReady()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notificationFactsPreparedExport.invalidate()
                _uiState.update {
                    it.copy(notificationFactsExportFeedback = "无法生成导出文件；请确认所选应用身份和本地记录状态")
                }
            } finally {
                notificationFactsExportPreparing = false
            }
        }
    }

    fun saveNotificationFactsExport(uri: Uri?) {
        val result = notificationFactsPreparedExport.consume(uri != null)
        if (result == PreparedExportResult.Cancelled) return
        if (result == PreparedExportResult.Missing) {
            _uiState.update {
                it.copy(notificationFactsExportFeedback = "导出快照已失效，未写入文件；请重新点击导出")
            }
            return
        }
        val bytes = (result as PreparedExportResult.Ready).bytes
        checkNotNull(uri)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(bytes) }
                        ?: error("Document provider did not open output")
                }.isSuccess
            }
            _uiState.update {
                it.copy(notificationFactsExportFeedback = if (saved) "已导出筛选后的通知事实" else "导出写入失败；请重试")
            }
        }
    }

    fun notificationListenerSettingsIntent(): Intent = notificationFactsController.settingsIntent()

    fun onNotificationListenerSettingsOpenFailed() {
        _uiState.update {
            it.copy(notificationFactsMessage = "无法打开系统通知使用权设置")
        }
    }

    fun runDailyProtectionNow() {
        if (!dailyProtectionSubmissionGate.tryEnter()) return
        _uiState.update { it.copy(dailyProtectionSubmitting = true, dailyProtectionSubmissionMessage = null) }
        val job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    submitManualProtection { DailyProtectionScheduler.enqueueManual(getApplication()).result }
                }
                _uiState.update { it.copy(dailyProtectionSubmissionMessage = result.message) }
            } finally {
                _uiState.update { it.copy(dailyProtectionSubmitting = false) }
            }
        }
        dailyProtectionSubmissionGate.releaseOnCompletion(job)
    }

    fun onAutostartSettingsLaunchResult(result: AutostartSettingsLaunchResult) {
        _uiState.update { it.copy(autostartSettingsMessage = when (result) {
            AutostartSettingsLaunchResult.Launched -> null
            AutostartSettingsLaunchResult.Unavailable -> "请在系统设置中手动搜索“自启动”。"
            is AutostartSettingsLaunchResult.Failed -> "无法打开设置，请在系统设置中手动搜索“自启动”。"
        }) }
    }

    fun showTargetSelection() {
        _uiState.update {
            it.copy(targetSelection = it.targetSelection.copy(visible = true, loading = true, message = null))
        }
        viewModelScope.launch {
            val selected = _uiState.value.selectedTargets.map { it.identity.packageName }.toSet()
            val candidates = withContext(Dispatchers.IO) { targetProbe.launcherCandidates(selected) }
            _uiState.update {
                it.copy(
                    targetSelection = TargetSelectionUiState(
                        visible = true,
                        candidates = candidates,
                    ),
                )
            }
        }
    }

    fun dismissTargetSelection() {
        _uiState.update { it.copy(targetSelection = TargetSelectionUiState()) }
    }

    fun clearCorruptTargetSelection() {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { targetStore.clearCorruptSelection() }
            if (cleared) {
                loadSelectedTargetsNow()
                refreshVisibleApps()
            }
        }
    }

    fun setTargetSelected(packageName: String, selected: Boolean) {
        dismissAutomaticProtection()
        val candidates = _uiState.value.targetSelection.candidates
        val isCandidate = candidates.any { it.packageName == packageName }
        val isStoredTarget = _uiState.value.selectedTargets.any { it.identity.packageName == packageName }
        if ((selected && !isCandidate) || (!selected && !isCandidate && !isStoredTarget)) return
        cancelStoppedRecovery()
        val removedIdentity = _uiState.value.selectedTargets.firstOrNull { it.identity.packageName == packageName }?.identity
        if (!selected && removedIdentity != null && removedIdentity in _uiState.value.automaticProtection.authorizedIdentities) {
            AutomaticProtectionRuntime.stopTarget(getApplication(), removedIdentity) {
                persistTargetSelection(packageName, false)
            }
            return
        }
        persistTargetSelection(packageName, selected)
    }

    private fun persistTargetSelection(packageName: String, selected: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (selected) {
                        targetProbe.identity(packageName)?.let(targetStore::replace) ?: false
                    } else {
                        targetStore.remove(packageName)
                    }
                }
            }
            if (result.isFailure) {
                _uiState.update {
                    it.copy(
                        targetSelectionStoreCorrupt = true,
                        targetSelection = it.targetSelection.copy(
                            message = "已保存的应用选择记录损坏；为避免覆盖，需先明确清除记录",
                        ),
                    )
                }
                return@launch
            }
            if (!result.getOrThrow()) {
                _uiState.update {
                    it.copy(
                        targetSelection = it.targetSelection.copy(
                            message = "无法保存选择；请确认应用身份有效且选择记录可保存",
                        ),
                    )
                }
                return@launch
            }
            loadSelectedTargetsNow()
            _uiState.update { current ->
                current.copy(
                    targetSelection = current.targetSelection.copy(
                        candidates = current.targetSelection.candidates.map { candidate ->
                            if (candidate.packageName == packageName) {
                                candidate.copy(selected = selected)
                            } else {
                                candidate
                            }
                        },
                        message = null,
                    ),
                )
            }
        }
    }

    fun checkSelectedTarget(packageName: String) {
        val identity = _uiState.value.visibleApps.singleOrNull { it.packageName == packageName }?.identity ?: return
        checkVisibleApp(identity)
    }

    /** Current visible apps may read the standard flag without enrolling or granting protection. */
    fun checkVisibleApp(identity: SelectedTargetIdentity) {
        if (_uiState.value.selectedAppsRecheckRunning) return
        viewModelScope.launch { checkVisibleAppNow(identity) }
    }

    fun checkSelectedApps() {
        if (!canRecheckSelectedApps(_uiState.value)) return
        val targets = selectedAppsRecheckTargets(_uiState.value)
        val generation = visibleAppsGeneration
        _uiState.update { it.copy(selectedAppsRecheckRunning = true) }
        viewModelScope.launch {
            try {
                recheckSelectedAppsInChunks(targets) { identity ->
                    if (generation == visibleAppsGeneration &&
                        _uiState.value.automaticProtection.configurationAvailable &&
                        identity in _uiState.value.automaticProtection.configuredIdentities) {
                        checkVisibleAppNow(identity)
                    }
                }
            } finally {
                _uiState.update { it.copy(selectedAppsRecheckRunning = false) }
            }
        }
    }

    private suspend fun checkVisibleAppNow(identity: SelectedTargetIdentity) {
        if (!canRecheckVisibleApp(_uiState.value, identity)) return
        val generation = visibleAppsGeneration
        _uiState.update { state ->
            applyVisibleAppStoppedSnapshot(state, identity, generation, visibleAppsGeneration, TargetStoppedSnapshot())
                .copy(targetReadInFlight = state.targetReadInFlight + identity)
        }
            readAndPublishVisibleAppRecheck(
                read = { AppRecheckReader.read { targetProbe.stoppedSnapshot(identity) } },
                publish = { snapshot ->
                    _uiState.update { state ->
                        applyVisibleAppStoppedSnapshot(state, identity, generation, visibleAppsGeneration, snapshot)
                    }
                },
                finish = { _uiState.update { it.copy(targetReadInFlight = it.targetReadInFlight - identity) } },
            )
    }

    private fun recoveryEligible(target: SelectedTargetUiState): Boolean {
        val current = _uiState.value
        return recoveryUiActive && !current.automaticProtection.enabled && !current.automaticProtection.busy && canOfferStoppedRecovery(
            target, current.stoppedRecovery,
            current.targetSelectionLoadState == HomeSourceLoadState.LOADED && !current.targetSelectionStoreCorrupt,
            validRuntimeFingerprint(Build.FINGERPRINT),
            shizukuRuntime.status.value.status == ShizukuStatus.READY_SHELL,
            current.detecting || target.identity in current.targetReadInFlight,
        )
    }

    fun requestStoppedRecovery(identity: SelectedTargetIdentity) {
        val target = _uiState.value.selectedTargets.singleOrNull { it.identity == identity } ?: return
        if (!recoveryEligible(target)) return
        _uiState.update { it.copy(stoppedRecovery = it.stoppedRecovery.copy(confirmationIdentity = identity)) }
    }

    fun dismissStoppedRecovery() {
        _uiState.update { it.copy(stoppedRecovery = it.stoppedRecovery.copy(confirmationIdentity = null)) }
    }

    fun confirmStoppedRecovery() {
        val identity = _uiState.value.stoppedRecovery.confirmationIdentity ?: return
        val valid = recoveryConfirmationStillValid(identity, _uiState.value.selectedTargets, ::recoveryEligible)
        dismissStoppedRecovery()
        if (!valid) return
        runStoppedRecovery(identity) { stoppedRecoveryController.recover(identity, confirmed = true) }
    }

    fun reconcileStoppedRecovery() {
        if (recoveryUiActive && AutomaticProtectionRuntime.requestReconciliationRefresh()) return
        val recovery = _uiState.value.stoppedRecovery
        if (_uiState.value.automaticProtection.enabled || _uiState.value.automaticProtection.busy) return
        if (!recoveryUiActive || !recovery.loaded || recovery.busy || recovery.storageBlocked || !recovery.needsReconcile) return
        if (recovery.recordIdentity in _uiState.value.targetReadInFlight) return
        runStoppedRecovery(recovery.recordIdentity) { stoppedRecoveryController.reconcile() }
    }

    private fun runStoppedRecovery(identity: SelectedTargetIdentity?, operation: suspend () -> StoppedRecoveryResult) {
        if (_uiState.value.stoppedRecovery.busy || stoppedRecoveryJob?.isActive == true) return
        stoppedRecoveryRecordGeneration++
        recoverySuccessJob?.cancel()
        _uiState.update { it.copy(stoppedRecovery = it.stoppedRecovery.copy(busy = true, confirmationIdentity = null,
            activeIdentity = identity, phase = StoppedRecoveryPhase.RECOVERING, showSuccessButton = false,
            message = "正在恢复并核对状态…")) }
        stoppedRecoveryJob = viewModelScope.launch {
            var result: StoppedRecoveryResult? = null
            var fallback: String? = null
            try {
                result = withContext(Dispatchers.IO) { operation() }
            } catch (cancelled: CancellationException) {
                fallback = "操作已中断；已发出的操作不能撤销。请先核对上次结果，勿假定尚未生效。"
                throw cancelled
            } catch (_: Exception) {
                fallback = "操作未能完成；请核对保存的结果，不会自动重复执行。"
            } finally {
                withContext(NonCancellable) {
                    loadStoppedRecoveryRecord(result, fallback, finishOperation = true)
                    AutomaticProtectionRuntime.notifyRecoveryJournalChanged()
                }
                if (recoveryUiActive && _uiState.value.stoppedRecovery.showSuccessButton) {
                    recoverySuccessJob = viewModelScope.launch {
                        delay(3_000)
                        _uiState.update { it.copy(stoppedRecovery = it.stoppedRecovery.copy(showSuccessButton = false)) }
                    }
                }
            }
        }
    }

    private fun cancelStoppedRecovery() {
        recoverySuccessJob?.cancel()
        _uiState.update { it.copy(stoppedRecovery = it.stoppedRecovery.copy(showSuccessButton = false)) }
        dismissStoppedRecovery()
        stoppedRecoveryController.cancel()
        stoppedRecoveryJob?.cancel()
    }

    private fun refreshStoppedRecoveryRecord() {
        if (_uiState.value.stoppedRecovery.busy) return
        viewModelScope.launch { loadStoppedRecoveryRecord() }
    }

    private fun refreshRecoveryCounts() {
        val generation = ++recoveryCountsGeneration
        val identities = _uiState.value.selectedTargets.map { it.identity }
        val requestedIdentities = identities.toSet()
        _uiState.update { current ->
            if (current.recoveryCounts.byIdentity.keys != requestedIdentities)
                current.copy(recoveryCounts = RecoveryCountsUiState()) else current
        }
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { AndroidRecoveryCounts.snapshot(getApplication(), identities) }
                    .getOrElse { RecoveryCountsUiState(detail = "统计记录不可用或不完整；恢复操作不受影响") }
            }
            if (generation == recoveryCountsGeneration) _uiState.update { it.copy(recoveryCounts = counts) }
        }
    }

    private suspend fun loadStoppedRecoveryRecord(
        result: StoppedRecoveryResult? = null,
        fallback: String? = null,
        finishOperation: Boolean = false,
    ) {
        val generation = ++stoppedRecoveryRecordGeneration
        val loaded = withContext(Dispatchers.IO) {
            runCatching { stoppedRecoveryController.loadLatest() to stoppedRecoveryController.storageHealthy() }
        }
        val record = loaded.getOrNull()?.first
        val healthy = loaded.getOrNull()?.second == true
        _uiState.update { current ->
            if (generation != stoppedRecoveryRecordGeneration || (current.stoppedRecovery.busy && !finishOperation)) current else {
                val historical = record != null && current.selectedTargets.none { it.identity == record.identity }
                val outcome = result?.outcome ?: record?.outcome
                val pending = record?.requiresReconcile == true
                val operationIdentity = if (finishOperation) current.stoppedRecovery.activeIdentity else record?.identity
                val matched = operationIdentity != null && current.selectedTargets.any { it.identity == operationIdentity }
                val success = healthy && !pending && outcome in setOf(StoppedRecoveryOutcome.FLAG_CLEARED, StoppedRecoveryOutcome.NO_WRITE_NEEDED)
                val phase = when {
                    !healthy || pending || fallback != null || outcome == StoppedRecoveryOutcome.UNCONFIRMED -> StoppedRecoveryPhase.UNCONFIRMED
                    !finishOperation || record?.stage == io.github.originosfcmguardian.recovery.StoppedRecoveryStage.RECONCILED -> StoppedRecoveryPhase.IDLE
                    success -> StoppedRecoveryPhase.SUCCESS
                    outcome != null -> StoppedRecoveryPhase.FAILED
                    else -> StoppedRecoveryPhase.IDLE
                }
                val detail = if (!healthy) "恢复操作记录不可用；为避免重复执行，已禁止恢复操作。请保留记录排查。"
                    else result?.detail ?: fallback ?: record?.detail
                current.copy(
                    recoveryFirmwareSupported = validRuntimeFingerprint(Build.FINGERPRINT),
                    stoppedRecovery = StoppedRecoveryUiState(
                        loaded = true, busy = false, storageBlocked = !healthy,
                        needsReconcile = pending,
                        recordIdentity = record?.identity, recordedAtMillis = record?.createdAtMillis,
                        recordSource = record?.source,
                        recordStage = record?.stage,
                        recordOutcome = record?.outcome,
                        phase = phase, activeIdentity = operationIdentity, receiptOutcome = if (healthy && !pending) outcome else null,
                        showSuccessButton = finishOperation && recoveryUiActive && success && matched && fallback == null &&
                            record?.stage != io.github.originosfcmguardian.recovery.StoppedRecoveryStage.RECONCILED && outcome == StoppedRecoveryOutcome.FLAG_CLEARED,
                        message = if (historical && detail != null) "历史安装操作记录（不归属当前选择）：$detail" else detail,
                    ),
                    selectedTargets = if (finishOperation && matched) current.selectedTargets.withStoppedSnapshot(
                        operationIdentity, TargetStoppedSnapshot(when {
                            success && fallback == null -> TargetStoppedStatus.NOT_STOPPED
                            healthy && !pending && outcome == StoppedRecoveryOutcome.STILL_STOPPED && fallback == null -> TargetStoppedStatus.STOPPED
                            else -> TargetStoppedStatus.UNKNOWN
                        }, System.currentTimeMillis()),
                    ) else current.selectedTargets,
                )
            }
        }
    }

    fun onActivityResumed() {
        AutomaticProtectionRuntime.inspectSavedAuthorization(getApplication())
        recoveryUiActive = true
        refreshVisibleApps(preserveRechecks = true)
        refreshRecoveryCounts()
        refreshStoppedRecoveryRecord()
        notificationFactsUiActive = true
        _uiState.update { it.copy(notificationFactsMessage = null) }
        refreshNotificationFacts()
    }

    fun closeForActivityExit() {
        closeGmsConnection()
        recoveryUiActive = false
        cancelStoppedRecovery()
        stoppedRecoveryController.close()
        shizukuRuntime.close()
    }

    override fun onCleared() {
        closeGmsConnection()
        recoveryUiActive = false
        cancelStoppedRecovery()
        stoppedRecoveryController.close()
        notificationFactsUiActive = false
        notificationFactsRefreshJob?.cancel()
        dailyProtectionSubscription?.close()
        dailyProtectionSubscription = null
        fcmEvidenceSubscription?.close()
        fcmEvidenceSubscription = null
        notificationFactsSubscription?.close()
        notificationFactsSubscription = null
        cancelFcmRegistration()
        clearOwnedFcmTokenClipboard()
        shizukuRuntime.close()
    }

    private fun cancelFcmRegistration() {
        fcmRegistrationRequests.invalidate()
        fcmRegistrationJob?.cancel()
        fcmRegistrationJob = null
    }

    private fun clearOwnedFcmTokenClipboard() {
        clipboardClearJob?.cancel()
        clipboardClearJob = null
        lastCopiedFcmToken?.let(sensitiveTokenClipboard::clearIfOwned)
        lastCopiedFcmToken = null
    }

    fun requestAutomaticProtection() {
        dismissAutomaticProtection()
        if (recoveryUiActive && canEnableAutomaticProtection(_uiState.value)) {
            cancelStoppedRecovery()
            AutomaticProtectionRuntime.enableMaster(getApplication())
        }
    }

    fun requestTargetAutomaticProtection(identity: SelectedTargetIdentity) {
        requestAppAutomaticProtection(identity.packageName, identity)
    }

    fun requestAppAutomaticProtection(packageName: String) = requestAppAutomaticProtection(packageName, null)

    private fun requestAppAutomaticProtection(packageName: String, expected: SelectedTargetIdentity?) {
        val app = _uiState.value.visibleApps.singleOrNull { it.packageName == packageName } ?: return
        if (!recoveryUiActive || (expected != null && app.identity != expected) || !canPrepareAppProtection(_uiState.value, app)) return
        val token = appProtectionRequestGate.begin() ?: return
        appProtectionRequestToken = token
        _uiState.update { it.copy(automaticPreparationPackage = packageName, appsMessage = null) }
        viewModelScope.launch {
            val expectedIdentity = app.identity ?: return@launch
            val identity = probeAppProtectionConfirmation(expectedIdentity,
                identity = { withContext(Dispatchers.IO) { targetProbe.identity(packageName) } },
                qualified = { withContext(Dispatchers.IO) { targetProbe.isRecoveryCandidate(it) } },
                current = { appProtectionRequestGate.current(token) && recoveryUiActive })
            if (!appProtectionRequestGate.current(token) || !recoveryUiActive) return@launch
            _uiState.update { it.copy(automaticPreparationPackage = null) }
            if (identity == null || !appProtectionPrerequisites(_uiState.value, app)) {
                dismissAutomaticProtection()
                _uiState.update { it.copy(appsMessage = "应用身份或支持条件已变化，请刷新后重试") }
                return@launch
            }
            // No internal selection or authority is written until the user confirms this identity.
            _uiState.update { it.copy(automaticConfirmationIdentity = identity) }
        }
    }

    fun dismissAutomaticProtection() {
        appProtectionRequestGate.cancel()
        appProtectionRequestToken = null
        _uiState.update { it.copy(automaticConfirmationIdentity = null, automaticPreparationPackage = null) }
    }

    fun confirmAutomaticProtection() {
        val identity = _uiState.value.automaticConfirmationIdentity ?: return
        val token = appProtectionRequestToken ?: return
        val app = _uiState.value.visibleApps.singleOrNull { it.identity == identity } ?: return
        if (!recoveryUiActive || !appProtectionPrerequisites(_uiState.value, app)) { dismissAutomaticProtection(); return }
        _uiState.update { it.copy(automaticConfirmationIdentity = null, automaticPreparationPackage = identity.packageName) }
        viewModelScope.launch {
            val prepared = runCatching {
                prepareAppProtectionIdentity(identity.packageName, identity,
                    identity = { withContext(Dispatchers.IO) { targetProbe.identity(identity.packageName) } },
                    qualified = { withContext(Dispatchers.IO) { targetProbe.isRecoveryCandidate(it) } },
                    selected = { withContext(Dispatchers.IO) { targetStore.load() } },
                    authorized = { _uiState.value.automaticProtection.configuredIdentities },
                    enroll = { withContext(Dispatchers.IO) { targetStore.replace(it) } },
                    current = { recoveryUiActive && appProtectionRequestGate.current(token) &&
                        appProtectionPrerequisites(_uiState.value, app) })
            }
            if (prepared.exceptionOrNull() is CancellationException) throw prepared.exceptionOrNull() as CancellationException
            if (!appProtectionRequestGate.current(token)) return@launch
            loadSelectedTargetsNow()
            if (!appProtectionRequestGate.current(token)) return@launch
            val ready = prepared.getOrNull() == identity && recoveryUiActive &&
                canEnableTargetAutomaticProtection(_uiState.value, identity) &&
                withContext(Dispatchers.IO) { targetProbe.identity(identity.packageName) == identity && targetProbe.isRecoveryCandidate(identity) }
            if (!appProtectionRequestGate.current(token)) return@launch
            dismissAutomaticProtection()
            if (ready && recoveryUiActive && canEnableTargetAutomaticProtection(_uiState.value, identity)) {
                cancelStoppedRecovery()
                AutomaticProtectionRuntime.start(getApplication(), identity)
            } else _uiState.update { it.copy(appsMessage = "未开启保护：应用身份、支持条件或记录已变化，请刷新后重试") }
        }
    }

    fun refreshVisibleApps() = refreshVisibleApps(preserveRechecks = false)

    private fun refreshVisibleApps(preserveRechecks: Boolean) {
        if (preserveRechecks && visibleAppsRefreshJob?.isActive == true) return
        dismissAutomaticProtection()
        val preserve = preserveRechecks && _uiState.value.visibleAppsLoadState == HomeSourceLoadState.LOADED
        val generation = if (preserve) visibleAppsGeneration else ++visibleAppsGeneration
        if (!preserve) _uiState.update(::beginVisibleAppsRefresh)
        visibleAppsRefreshJob = viewModelScope.launch {
            loadSelectedTargetsNow()
            val result = runCatching { withContext(Dispatchers.IO) {
                val inventory = targetProbe.userInstalledApps(emptySet())
                inventory.candidates.map { candidate ->
                    val identity = targetProbe.identity(candidate.packageName)
                    VisibleProtectionApp(candidate.packageName, candidate.label, identity,
                        identity != null && targetProbe.isRecoveryCandidate(identity))
                } to inventory.hiddenPreinstalledPackages
            } }
            if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull() as CancellationException
            if (generation != visibleAppsGeneration) return@launch
            _uiState.update { state -> result.fold(
                onSuccess = { (apps, hidden) -> state.copy(visibleApps = if (preserve) retainRechecksForUnchangedInstallations(state, apps) else apps,
                    hiddenPreinstalledPackages = hidden,
                    visibleAppsLoadState = HomeSourceLoadState.LOADED) },
                onFailure = { state.copy(visibleAppsLoadState = HomeSourceLoadState.FAILED,
                    appsMessage = "应用列表读取失败，请重试；旧列表可能已变化") }) }
        }
    }

    fun stopTargetAutomaticProtection(identity: SelectedTargetIdentity) {
        dismissAutomaticProtection()
        AutomaticProtectionRuntime.stopTarget(getApplication(), identity)
    }

    fun stopAutomaticProtection() {
        dismissAutomaticProtection()
        AutomaticProtectionRuntime.stop(getApplication())
    }

    fun refreshRecentsPreference(applied: Boolean) {
        _uiState.update { it.copy(hiddenFromRecents = RecentsVisibility.hidden(getApplication()),
            recentsMessage = if (applied) null else "最近任务显示设置未能完整应用，请重试；后台保护状态不受此设置保证。") }
    }

    private fun loadDailyProtectionReport() {
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { dailyProtectionStore.load() } }
            if (result.exceptionOrNull() is CancellationException) {
                throw result.exceptionOrNull() as CancellationException
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = {
                        current.copy(
                            dailyProtectionReport = it,
                            dailyProtectionLoadState = HomeSourceLoadState.LOADED,
                        )
                    },
                    onFailure = {
                        current.copy(
                            dailyProtectionLoadState = HomeSourceLoadState.FAILED,
                        )
                    },
                )
            }
        }
    }

    private fun loadFcmEvidence() {
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fcmEvidenceStore.load() } }
            if (result.exceptionOrNull() is CancellationException) {
                throw result.exceptionOrNull() as CancellationException
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { snapshot ->
                        current.copy(
                            fcm = current.fcm.copy(
                                latestReceipt = (snapshot as? FcmEvidenceSnapshot.Available)?.receipts?.firstOrNull(),
                                evidenceCorrupt = snapshot == FcmEvidenceSnapshot.Corrupt,
                            ),
                            fcmEvidenceLoadState = HomeSourceLoadState.LOADED,
                        )
                    },
                    onFailure = {
                        current.copy(fcmEvidenceLoadState = HomeSourceLoadState.FAILED)
                    },
                )
            }
        }
    }

    private fun loadSelectedTargets() {
        viewModelScope.launch { loadSelectedTargetsNow() }
    }

    private suspend fun loadSelectedTargetsNow() {
        val loadedResult = withContext(Dispatchers.IO) {
            runCatching {
                targetStore.load().map { stored ->
                    Triple(stored, targetProbe.label(stored.packageName), targetProbe.identity(stored.packageName))
                }
            }
        }
        if (loadedResult.exceptionOrNull() is CancellationException) {
            throw loadedResult.exceptionOrNull() as CancellationException
        }
        val loaded = loadedResult.getOrElse {
            _uiState.update { current ->
                current.copy(
                    selectedTargets = emptyList(),
                    targetSelectionLoadState = HomeSourceLoadState.LOADED,
                    targetSelectionStoreCorrupt = true,
                    targetSelection = current.targetSelection.copy(
                        message = "已保存的应用选择记录损坏；为避免覆盖，需先明确清除记录",
                    ),
                )
            }
            refreshNotificationFacts()
            refreshRecoveryCounts()
            return
        }
        _uiState.update { current ->
            val previous = current.selectedTargets.associateBy { it.identity }
            current.copy(
                selectedTargets = loaded.map { (stored, label, installed) ->
                    when (identityStatus(stored, installed)) {
                        TargetIdentityStatus.MATCH -> previous[stored]?.copy(label = label)
                            ?: SelectedTargetUiState(stored, label)
                        TargetIdentityStatus.MISSING,
                        TargetIdentityStatus.CHANGED,
                        -> SelectedTargetUiState(
                            identity = stored,
                            label = label,
                            status = TargetAutostartStatus.IDENTITY_CHANGED,
                            detail = "应用身份已变化或应用已卸载；请移除后重新选择",
                        )
                    }
                }.sortedBy { it.label.lowercase() },
                targetSelectionLoadState = HomeSourceLoadState.LOADED,
                targetSelectionStoreCorrupt = false,
            )
        }
        refreshNotificationFacts()
        refreshRecoveryCounts()
    }

    private fun updateTarget(
        packageName: String,
        transform: (SelectedTargetUiState) -> SelectedTargetUiState,
    ) {
        _uiState.update { current ->
            current.copy(
                selectedTargets = current.selectedTargets.map { target ->
                    if (target.identity.packageName == packageName) transform(target) else target
                },
            )
        }
    }

    private companion object {
        const val TOKEN_CLIPBOARD_TTL_MILLIS = 60_000L
        const val FCM_REGISTRATION_TIMEOUT_MILLIS = 30_000L
    }
}
