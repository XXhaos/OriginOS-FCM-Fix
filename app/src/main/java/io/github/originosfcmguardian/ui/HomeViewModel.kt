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
import io.github.originosfcmguardian.daemon.DaemonAppRuntime
import io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome
import io.github.originosfcmguardian.daemon.DaemonFcmCommandReason
import io.github.originosfcmguardian.daemon.DaemonUiSnapshot
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
    val diagnosticReportExportFeedback: String? = null,
    val diagnosticReportExportBusy: Boolean = false,
    val gmsConnection: GmsConnectionUiState = GmsConnectionUiState(),
    val gmsAutoHeartbeat: io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState =
        io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatUiState(),
    val fcmRecovery: io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState = io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState(),
    val fcmProtection: io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState = io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState(),
    val fcmProtectionConfirmation: Boolean = false,
    val pushProtectionBusy: Boolean = false,
    val pushProtectionMessage: String? = null,
    val automaticProtectionFromDaemon: Boolean = false,
)

enum class HomeSourceLoadState {
    LOADING,
    LOADED,
    FAILED,
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val shizukuRuntime = ShizukuRuntime(application)
    private val targetStore by lazy { TargetSelectionStore(application) }
    private val targetProbe = TargetPackageProbe(application)
    private val appProtectionRequestGate = AppProtectionRequestGate()
    private var appProtectionRequestToken: Long? = null
    private var visibleAppsGeneration = 0L
    private var visibleAppsRefreshJob: Job? = null
    private val stoppedRecoveryController by lazy { StoppedRecoveryController(application) }
    private fun remoteOwned() = DaemonAppRuntime.isRemoteOwned(getApplication())
    private var daemonRefreshJob: Job? = null
    private var pushProtectionJob: Job? = null
    private var pushProtectionAction = 0L
    private var stoppedRecoveryJob: Job? = null
    private var recoveryUiActive = false
    private var stoppedRecoveryRecordGeneration = 0L
    private var recoverySuccessJob: Job? = null
    private var recoveryCountsGeneration = 0L
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
        if (_uiState.value.fcmProtection.configured || _uiState.value.fcmProtection.busy ||
            _uiState.value.gmsAutoHeartbeat.stopPending) return
        _uiState.update { it.copy(fcmProtectionConfirmation = true) }
    }

    /** One Home action composes existing commands; it never changes saved resumption rules. */
    fun requestPushProtection() {
        val state = _uiState.value
        if (!recoveryUiActive || state.pushProtectionBusy || state.automaticProtection.busy ||
            state.fcmProtection.busy || state.automaticProtection.revocationSaveFailed) return
        _uiState.update { it.copy(pushProtectionMessage = null) }
        when (pushProtectionEnableAction(state)) {
            PushProtectionEnableAction.INITIAL_FCM_CONFIRMATION -> requestFcmProtection()
            PushProtectionEnableAction.ENABLE_MASTER -> requestAutomaticProtection()
            PushProtectionEnableAction.NONE -> Unit
        }
    }

    fun confirmPushProtection() {
        if (!_uiState.value.fcmProtectionConfirmation || !recoveryUiActive || pushProtectionJob?.isActive == true) return
        dismissFcmProtection()
        if (_uiState.value.fcmProtection.configured) {
            if (!_uiState.value.automaticProtection.masterEnabled) requestAutomaticProtection()
            return
        }
        val action = ++pushProtectionAction
        _uiState.update { it.copy(pushProtectionBusy = true, pushProtectionMessage = null) }
        pushProtectionJob = viewModelScope.launch {
            try {
                if (!_uiState.value.automaticProtection.masterEnabled) {
                    if (!canEnableAutomaticProtection(_uiState.value)) {
                        _uiState.update { it.copy(pushProtectionMessage = "暂时无法开启，请稍后重试。") }
                        return@launch
                    }
                    requestAutomaticProtection()
                }
                // Both commands are asynchronous. Observe actual readiness instead of racing
                // enableFcm against the command gate or treating a delay as an acknowledgement.
                val host = awaitPushProtectionHost(uiState)
                coroutineContext.ensureActive()
                if (action != pushProtectionAction || !recoveryUiActive) return@launch
                if (host == null || !pushProtectionHostReady(host)) {
                    _uiState.update { it.copy(pushProtectionMessage = if (it.automaticProtection.configuredIdentities.isEmpty())
                        "请先在应用页选择需要保护的应用。" else "等待 Shizuku 和保护服务就绪后，请完成开启。") }
                    return@launch
                }
                if (host.fcmProtection.configured) return@launch
                if (remoteOwned()) {
                    val completed = DaemonAppRuntime.enableFcm(getApplication()).await()
                    coroutineContext.ensureActive()
                    if (action != pushProtectionAction || !recoveryUiActive) return@launch
                    val message = if (_uiState.value.fcmProtection.configured && completed.outcome != DaemonFcmCommandOutcome.APPLIED)
                        "FCM 保护暂不可用，请查看保护状态。" else when (completed.outcome) {
                        DaemonFcmCommandOutcome.APPLIED -> null
                        DaemonFcmCommandOutcome.REJECTED -> if (completed.reason == DaemonFcmCommandReason.STOP_REQUESTED)
                            "已取消开启，请查看关闭结果。" else "此次开启未受理，请稍后重新确认。"
                        DaemonFcmCommandOutcome.FAILED -> "此次开启未完成，请检查连接和保护状态后重新确认。"
                        DaemonFcmCommandOutcome.UNKNOWN -> "此次开启结果暂无法确认，请刷新保护状态后再操作。"
                    }
                    _uiState.update { it.copy(pushProtectionMessage = message) }
                } else {
                    withContext(Dispatchers.IO) {
                        io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.confirm(getApplication())
                    }
                }
            } finally {
                if (action == pushProtectionAction) {
                    pushProtectionJob = null
                    _uiState.update { it.copy(pushProtectionBusy = false) }
                }
            }
        }
    }

    private fun cancelPushProtectionAction() {
        ++pushProtectionAction
        pushProtectionJob?.cancel()
        pushProtectionJob = null
        dismissFcmProtection()
        _uiState.update { it.copy(pushProtectionBusy = false) }
    }

    fun stopPushProtection() {
        cancelPushProtectionAction()
        _uiState.update { it.copy(pushProtectionMessage = null) }
        // Master OFF already pauses both execution branches and preserves their saved choices.
        stopAutomaticProtection()
    }
    fun dismissFcmProtection() { _uiState.update { it.copy(fcmProtectionConfirmation = false) } }
    fun confirmFcmProtection() {
        confirmPushProtection()
    }
    fun disableFcmProtection() {
        dismissFcmProtection()
        if (remoteOwned()) { DaemonAppRuntime.disableFcm(getApplication()); return }
        io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.disable(getApplication())
    }

    fun readGmsConnection(preserveObservation: Boolean = false) {
        if (gmsConnectionClosed || _uiState.value.gmsConnection.busy) return
        ensureGmsNetworkTracking()
        val generation = ++gmsConnectionGeneration
        val network = gmsNetworkContext(getApplication())
        _uiState.update { it.copy(gmsConnection = it.gmsConnection.copy(busy = true,
            observation = it.gmsConnection.observation.takeIf { preserveObservation },
            readAttempted = true, readCompletedAtElapsedMillis = it.gmsConnection.readCompletedAtElapsedMillis.takeIf { preserveObservation },
            heartbeatBusy = false, heartbeatStatus = null,
            networkBackground = network.summary,
            networkKey = if (preserveObservation) it.gmsConnection.networkKey else network.key,
            networkChanged = preserveObservation && (it.gmsConnection.networkChanged ||
                it.gmsConnection.networkKey != null && it.gmsConnection.networkKey != network.key))) }
        gmsConnectionJob = viewModelScope.launch {
            try {
                val observation = if (remoteOwned()) DaemonAppRuntime.readGmsConnection(getApplication())
                    else readDirectGmsConnection(generation) { shizukuRuntime.executor.readGmsConnection() }
                coroutineContext.ensureActive()
                val changed = network.key == "unknown" || network.key != gmsNetworkContext(getApplication()).key
                if (generation == gmsConnectionGeneration) _uiState.update {
                    it.copy(gmsConnection = it.gmsConnection.copy(observation = observation,
                        readCompletedAtElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                        automaticObservationAtReadCompletion = it.fcmProtection.latestObservation,
                        networkKey = network.key,
                        networkChanged = changed || (!preserveObservation && it.gmsConnection.networkChanged)))
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
        if (remoteOwned()) return // Remote owner exposes bundled maintenance only; no old endpoint dispatch.
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

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        if (!remoteOwned()) io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.initialize(application)
        viewModelScope.launch {
            io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime.state.collectLatest { snapshot ->
                if (!remoteOwned()) _uiState.update { it.copy(fcmProtection = snapshot,
                    fcmProtectionConfirmation = it.fcmProtectionConfirmation && !snapshot.configured,
                    pushProtectionMessage = it.pushProtectionMessage.takeUnless { snapshot.configured }) }
                else if (DaemonAppRuntime.state.value == null) _uiState.update { it.copy(fcmProtection = snapshot.copy(
                    maintenance = io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState.WAITING_HOST,
                    recoveryReady = false, detail = "等待保护服务"),
                    fcmProtectionConfirmation = it.fcmProtectionConfirmation && !snapshot.configured,
                    pushProtectionMessage = it.pushProtectionMessage.takeUnless { snapshot.configured }) }
            }
        }
        viewModelScope.launch {
            io.github.originosfcmguardian.fcmrecovery.FcmRecoveryRuntime.state.collectLatest { snapshot ->
                if (!remoteOwned()) _uiState.update { it.copy(fcmRecovery = snapshot) }
            }
        }
        viewModelScope.launch {
            io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime.state.collectLatest { snapshot ->
                if (!remoteOwned()) _uiState.update { it.copy(gmsAutoHeartbeat = snapshot) }
                else if (DaemonAppRuntime.state.value == null) _uiState.update { it.copy(gmsAutoHeartbeat = snapshot.copy(
                    active = false, resumeAuthorized = false, manualBlocked = true, waitingForHost = true,
                    busy = false, detail = "等待保护服务")) }
            }
        }
        _uiState.update { it.copy(hiddenFromRecents = RecentsVisibility.hidden(application)) }
        viewModelScope.launch {
            AndroidRecoveryCounts.changes.collectLatest { if (!remoteOwned()) refreshRecoveryCounts() }
        }
        viewModelScope.launch {
            AutomaticProtectionRuntime.state.collectLatest { snapshot ->
                if (remoteOwned()) {
                    if (DaemonAppRuntime.state.value == null) _uiState.update { it.copy(automaticProtection = snapshot.copy(
                        enabled = false, coreSessionStartedAtElapsedMillis = null,
                        detail = if (snapshot.waitingForReconciliation) "关闭结果待确认" else "等待保护服务"),
                        gmsAutoHeartbeat = it.gmsAutoHeartbeat.copy(active = false, resumeAuthorized = false,
                            manualBlocked = true, waitingForHost = true, busy = false, detail = "等待保护服务")) }
                    return@collectLatest
                }
                val previous = _uiState.value.automaticProtection
                _uiState.update { it.copy(automaticProtection = snapshot) }
                if (previous.busy && !snapshot.busy && recoveryUiActive) refreshStoppedRecoveryRecord()
                if (snapshot.enabled && !snapshot.busy) DaemonAppRuntime.adoptWhenReady(application)
            }
        }
        shizukuRuntime.start(openObservationSession = !remoteOwned())
        viewModelScope.launch {
            shizukuRuntime.status.collectLatest { snapshot ->
                _uiState.update { it.copy(shizuku = snapshot) }
            }
        }
        viewModelScope.launch {
            DaemonAppRuntime.state.collectLatest { snapshot ->
                if (remoteOwned() && snapshot != null) publishDaemonSnapshot(snapshot)
            }
        }
        if (remoteOwned()) {
            markRemoteLoading()
            DaemonAppRuntime.restore(application)
        } else {
            loadSelectedTargets()
            refreshStoppedRecoveryRecord()
        }
    }

    private fun markRemoteLoading() {
        _uiState.update { it.copy(automaticProtectionFromDaemon = true, targetSelectionLoadState = HomeSourceLoadState.FAILED,
            automaticProtection = it.automaticProtection.copy(configurationAvailable = false, enabled = false,
                coreSessionStartedAtElapsedMillis = null, detail = "正在读取独立保护宿主配置"),
            recoveryCounts = RecoveryCountsUiState(detail = "等待独立保护宿主统计")) }
    }

    private suspend fun publishDaemonSnapshot(snapshot: DaemonUiSnapshot) {
        val labels = if (snapshot.configurationAvailable) withContext(Dispatchers.IO) {
            snapshot.selectedIdentities.map { stored ->
                Triple(stored, runCatching { targetProbe.label(stored.packageName) }.getOrDefault(stored.packageName),
                    targetProbe.identity(stored))
            }
        } else null
        if (DaemonAppRuntime.state.value !== snapshot) return
        _uiState.update { current ->
            val previous = current.selectedTargets.associateBy { it.identity }
            val targets = labels?.map { (stored, label, installed) ->
                if (identityStatus(stored, installed) == TargetIdentityStatus.MATCH)
                    previous[stored]?.copy(label = label) ?: SelectedTargetUiState(stored, label)
                else SelectedTargetUiState(stored, label, status = TargetAutostartStatus.IDENTITY_CHANGED,
                    detail = "应用身份已变化或应用已卸载；请移除后重新选择")
            }?.sortedBy { it.label.lowercase() } ?: current.selectedTargets
            val record = snapshot.stoppedRecord
            val healthy = snapshot.stoppedRecordAvailable
            val pending = record?.requiresReconcile == true
            val automatic = if (!snapshot.configurationAvailable && snapshot.automatic.targets.isEmpty())
                snapshot.automatic.copy(masterEnabled = current.automaticProtection.masterEnabled,
                    targets = current.automaticProtection.targets.map {
                    it.copy(enabled = false, busy = false, savedAuthorization = false, waitingForPrivilege = false)
                }) else snapshot.automatic
            current.copy(automaticProtectionFromDaemon = true, automaticProtection = automatic, fcmProtection = snapshot.fcm,
                fcmProtectionConfirmation = current.fcmProtectionConfirmation && !snapshot.fcm.configured,
                pushProtectionMessage = current.pushProtectionMessage.takeUnless { snapshot.fcm.configured },
                gmsAutoHeartbeat = snapshot.heartbeatUi, fcmRecovery = snapshot.recoveryUi,
                selectedTargets = targets,
                targetSelectionLoadState = if (snapshot.configurationAvailable) HomeSourceLoadState.LOADED else HomeSourceLoadState.FAILED,
                targetSelectionStoreCorrupt = !snapshot.configurationAvailable, recoveryCounts = snapshot.counts,
                recoveryFirmwareSupported = validRuntimeFingerprint(Build.FINGERPRINT),
                stoppedRecovery = StoppedRecoveryUiState(loaded = healthy, storageBlocked = !healthy,
                    needsReconcile = pending, recordIdentity = record?.identity, recordedAtMillis = record?.createdAtMillis,
                    recordSource = record?.source, recordStage = record?.stage, recordOutcome = record?.outcome,
                    receiptOutcome = record?.outcome.takeIf { healthy && !pending },
                    phase = if (!healthy || pending) StoppedRecoveryPhase.UNCONFIRMED else StoppedRecoveryPhase.IDLE,
                    message = if (!healthy) "独立宿主恢复记录暂不可确认；不会重复执行。" else record?.detail))
        }
    }

    private fun refreshDaemonForeground() {
        if (!remoteOwned() || !recoveryUiActive || daemonRefreshJob?.isActive == true) return
        DaemonAppRuntime.restore(getApplication())
        daemonRefreshJob = viewModelScope.launch {
            while (recoveryUiActive && remoteOwned()) {
                DaemonAppRuntime.refresh(getApplication())
                delay(30_000)
            }
        }
    }

    fun refreshShizuku() = shizukuRuntime.refresh()

    fun prepareCombinedDiagnosticReportExport(
        format: DiagnosticReportFormat = DiagnosticReportFormat.JSON,
        openPicker: () -> Boolean,
    ) {
        if (_uiState.value.diagnosticReportExportBusy) return
        val capturedAt = System.currentTimeMillis()
        val report = buildCombinedDiagnosticReport(
            state = _uiState.value.copy(shizuku = shizukuRuntime.status.value, automaticProtectionFromDaemon = remoteOwned()),
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
                val resources = withContext(Dispatchers.IO) {
                    io.github.originosfcmguardian.autoprotection.ProtectionLifecycleDiagnostics.readResourceSnapshot()
                }
                val daemonDiagnostics = withContext(Dispatchers.IO) {
                    DaemonAppRuntime.diagnosticSnapshot(getApplication())
                }
                val bytes = withContext(Dispatchers.Default) {
                    encodeDiagnosticReport(report.copy(protectionLifecycle = lifecycle, protectionResources = resources,
                        daemonDiagnostics = daemonDiagnostics), format)
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
                        diagnosticReportExportFeedback = "报告已过期，请重新导出",
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
                                "报告已导出"
                            } else {
                                "导出失败，请重试"
                            },
                        )
                    }
                }
            }
        }
    }

    fun requestShizukuAccess() = shizukuRuntime.requestOrOpen()

    fun openShizuku() = shizukuRuntime.openManager()

    fun onAppBackgrounded() {
        cancelPushProtectionAction()
        dismissGmsConnection()
        recoveryUiActive = false
        daemonRefreshJob?.cancel(); daemonRefreshJob = null
        dismissAutomaticProtection()
        cancelStoppedRecovery()
    }

    fun refreshProtectionNotificationVisibility() {
        val visibility = AutomaticProtectionRuntime.notificationVisibility(getApplication<Application>())
        _uiState.update { it.copy(protectionNotificationVisibility = visibility) }
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
        if (remoteOwned()) {
            viewModelScope.launch { DaemonAppRuntime.refresh(getApplication()) }
            return
        }
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { targetStore.clearCorruptSelection() }
            if (cleared) {
                loadSelectedTargetsNow()
                refreshVisibleApps()
            }
        }
    }

    fun setTargetSelected(packageName: String, selected: Boolean) {
        if (remoteOwned()) {
            if (selected) requestAppAutomaticProtection(packageName)
            else _uiState.value.selectedTargets.singleOrNull { it.identity.packageName == packageName }?.let {
                stopTargetAutomaticProtection(it.identity)
            }
            return
        }
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
        if (remoteOwned()) { setTargetSelected(packageName, selected); return }
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
        if (remoteOwned()) return false
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
        if (remoteOwned()) { dismissStoppedRecovery(); return }
        val identity = _uiState.value.stoppedRecovery.confirmationIdentity ?: return
        val valid = recoveryConfirmationStillValid(identity, _uiState.value.selectedTargets, ::recoveryEligible)
        dismissStoppedRecovery()
        if (!valid) return
        runStoppedRecovery(identity) { stoppedRecoveryController.recover(identity, confirmed = true) }
    }

    fun reconcileStoppedRecovery() {
        if (remoteOwned()) {
            if (recoveryUiActive) viewModelScope.launch { DaemonAppRuntime.refresh(getApplication()) }
            return
        }
        if (recoveryUiActive && AutomaticProtectionRuntime.requestReconciliationRefresh()) return
        val recovery = _uiState.value.stoppedRecovery
        if (_uiState.value.automaticProtection.enabled || _uiState.value.automaticProtection.busy) return
        if (!recoveryUiActive || !recovery.loaded || recovery.busy || recovery.storageBlocked || !recovery.needsReconcile) return
        if (recovery.recordIdentity in _uiState.value.targetReadInFlight) return
        runStoppedRecovery(recovery.recordIdentity) { stoppedRecoveryController.reconcile() }
    }

    private fun runStoppedRecovery(identity: SelectedTargetIdentity?, operation: suspend () -> StoppedRecoveryResult) {
        if (remoteOwned()) return
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
        if (!remoteOwned()) stoppedRecoveryController.cancel()
        stoppedRecoveryJob?.cancel()
    }

    private fun refreshStoppedRecoveryRecord() {
        if (remoteOwned()) return
        if (_uiState.value.stoppedRecovery.busy) return
        viewModelScope.launch { loadStoppedRecoveryRecord() }
    }

    private fun refreshRecoveryCounts() {
        if (remoteOwned()) return
        val generation = ++recoveryCountsGeneration
        val source = _uiState.value
        if (!recoveryCountsSelectionReady(source.targetSelectionLoadState, source.targetSelectionStoreCorrupt)) {
            _uiState.update { it.copy(recoveryCounts = RecoveryCountsUiState()) }
            return
        }
        val identities = source.selectedTargets.map { it.identity }
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
        if (remoteOwned()) return
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
        recoveryUiActive = true
        if (remoteOwned()) {
            shizukuRuntime.refresh()
            refreshVisibleApps(preserveRechecks = true)
            refreshDaemonForeground()
            return
        }
        AutomaticProtectionRuntime.inspectSavedAuthorization(getApplication())
        refreshVisibleApps(preserveRechecks = true)
        refreshRecoveryCounts()
        refreshStoppedRecoveryRecord()
    }

    fun closeForActivityExit() {
        cancelPushProtectionAction()
        closeGmsConnection()
        recoveryUiActive = false
        daemonRefreshJob?.cancel(); daemonRefreshJob = null
        cancelStoppedRecovery()
        if (!remoteOwned()) stoppedRecoveryController.close()
        shizukuRuntime.close()
    }

    override fun onCleared() {
        cancelPushProtectionAction()
        closeGmsConnection()
        recoveryUiActive = false
        daemonRefreshJob?.cancel(); daemonRefreshJob = null
        cancelStoppedRecovery()
        if (!remoteOwned()) stoppedRecoveryController.close()
        shizukuRuntime.close()
    }

    fun requestAutomaticProtection() {
        dismissAutomaticProtection()
        if (recoveryUiActive && canEnableAutomaticProtection(_uiState.value)) {
            cancelStoppedRecovery()
            if (remoteOwned()) DaemonAppRuntime.enableMaster(getApplication())
            else AutomaticProtectionRuntime.enableMaster(getApplication())
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
                identity = { withContext(Dispatchers.IO) { targetProbe.identity(expectedIdentity) } },
                qualified = { withContext(Dispatchers.IO) { targetProbe.isRecoveryCandidate(it) } },
                current = { appProtectionRequestGate.current(token) && recoveryUiActive })
            if (!appProtectionRequestGate.current(token) || !recoveryUiActive) return@launch
            _uiState.update { it.copy(automaticPreparationPackage = null) }
            if (identity == null || !appProtectionPrerequisites(_uiState.value, app)) {
                dismissAutomaticProtection()
                _uiState.update { it.copy(appsMessage = "应用已变化，请刷新后重试") }
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
        if (remoteOwned()) {
            viewModelScope.launch {
                val fresh = withContext(Dispatchers.IO) {
                    targetProbe.identity(identity) == identity && targetProbe.isRecoveryCandidate(identity)
                }
                if (!appProtectionRequestGate.current(token)) return@launch
                val ready = fresh && recoveryUiActive && appProtectionPrerequisites(_uiState.value, app)
                dismissAutomaticProtection()
                if (ready) DaemonAppRuntime.enableTarget(getApplication(), identity)
                else _uiState.update { it.copy(appsMessage = "应用已变化或宿主未就绪，请刷新后重试") }
            }
            return
        }
        viewModelScope.launch {
            val prepared = runCatching {
                prepareAppProtectionIdentity(identity.packageName, identity,
                    identity = { withContext(Dispatchers.IO) { targetProbe.identity(identity) } },
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
                withContext(Dispatchers.IO) { targetProbe.identity(identity) == identity && targetProbe.isRecoveryCandidate(identity) }
            if (!appProtectionRequestGate.current(token)) return@launch
            dismissAutomaticProtection()
            if (ready && recoveryUiActive && canEnableTargetAutomaticProtection(_uiState.value, identity)) {
                cancelStoppedRecovery()
                AutomaticProtectionRuntime.start(getApplication(), identity)
            } else _uiState.update { it.copy(appsMessage = "未能开启保护，请刷新后重试") }
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
            val baselineState = _uiState.value
            val baselines = io.github.originosfcmguardian.autostart.uniqueTargetBaselines(
                baselineState.selectedTargets.map { it.identity } + baselineState.automaticProtection.configuredIdentities)
            val result = runCatching { withContext(Dispatchers.IO) {
                val inventory = targetProbe.userInstalledApps(emptySet())
                inventory.candidates.map { candidate ->
                    val identity = baselines[candidate.packageName]?.let(targetProbe::identity)
                        ?: targetProbe.identity(candidate.packageName)
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
                    appsMessage = "应用列表读取失败，请重试") }) }
        }
    }

    fun stopTargetAutomaticProtection(identity: SelectedTargetIdentity) {
        dismissAutomaticProtection()
        if (remoteOwned()) { DaemonAppRuntime.disableTarget(getApplication(), identity); return }
        AutomaticProtectionRuntime.stopTarget(getApplication(), identity)
    }

    fun stopAutomaticProtection() {
        dismissAutomaticProtection()
        if (remoteOwned()) { DaemonAppRuntime.stop(getApplication()); return }
        AutomaticProtectionRuntime.stop(getApplication())
    }

    fun refreshRecentsPreference(applied: Boolean) {
        _uiState.update { it.copy(hiddenFromRecents = RecentsVisibility.hidden(getApplication()),
            recentsMessage = if (applied) null else "设置未生效，请重试。") }
    }

    private fun loadSelectedTargets() {
        viewModelScope.launch { loadSelectedTargetsNow() }
    }

    private suspend fun loadSelectedTargetsNow() {
        if (remoteOwned()) {
            DaemonAppRuntime.state.value?.let { publishDaemonSnapshot(it) }
            return
        }
        val loadedResult = withContext(Dispatchers.IO) {
            runCatching {
                targetStore.load().map { stored ->
                    Triple(stored, targetProbe.label(stored.packageName), targetProbe.identity(stored))
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
