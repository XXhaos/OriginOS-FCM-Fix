package io.github.originosfcmguardian.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.AutostartReadStatus
import io.github.originosfcmguardian.core.GuardianAutostartParser
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.TargetAutostartParser
import io.github.originosfcmguardian.core.ReadOnlyDetector
import io.github.originosfcmguardian.core.RepairPlan
import io.github.originosfcmguardian.core.RepairPlanner
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.journal.BaselineSnapshot
import io.github.originosfcmguardian.core.journal.CaptureMetadata
import io.github.originosfcmguardian.core.journal.CaptureSource
import io.github.originosfcmguardian.core.journal.MetadataFieldSource
import io.github.originosfcmguardian.core.journal.RecordingPrivilegedExecutor
import io.github.originosfcmguardian.data.journal.AtomicFileJournalStore
import io.github.originosfcmguardian.data.journal.OriginalRecordController
import io.github.originosfcmguardian.data.journal.OriginalRecordState
import io.github.originosfcmguardian.data.journal.OriginalRecordStatus
import io.github.originosfcmguardian.data.journal.applyInitialRecordIfStillLoading
import io.github.originosfcmguardian.device.GmsPackageSnapshot
import io.github.originosfcmguardian.device.NormalDeviceProbe
import io.github.originosfcmguardian.autostart.AutostartReturnTracker
import io.github.originosfcmguardian.autostart.AutostartSettingsLaunchResult
import io.github.originosfcmguardian.autostart.AutostartUiStatus
import io.github.originosfcmguardian.autostart.GuardianAutostartUiState
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartReturnTracker
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.TargetSelectionUiState
import io.github.originosfcmguardian.autostart.identityStatus
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import io.github.originosfcmguardian.protection.DailyProtectionEvents
import io.github.originosfcmguardian.protection.DailyProtectionReport
import io.github.originosfcmguardian.protection.DailyProtectionScheduler
import io.github.originosfcmguardian.protection.DailyProtectionStore
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
    val gms: GmsPackageSnapshot? = null,
    val shizuku: ShizukuStatusSnapshot = ShizukuStatusSnapshot(
        ShizukuStatus.ERROR,
        "正在读取 Shizuku 状态",
    ),
    val detecting: Boolean = false,
    val report: DetectionReport? = null,
    val plan: RepairPlan? = null,
    val detectionMessage: String = "尚未执行特权只读检测",
    val originalRecord: OriginalRecordState = OriginalRecordState(),
    val autostart: GuardianAutostartUiState = GuardianAutostartUiState(),
    val autostartCheckAttemptCount: Long = 0,
    val selectedTargets: List<SelectedTargetUiState> = emptyList(),
    val targetSelection: TargetSelectionUiState = TargetSelectionUiState(),
    val targetReadAttemptCount: Long = 0,
    val targetSelectionStoreCorrupt: Boolean = false,
    val dailyProtectionReport: DailyProtectionReport? = null,
    val dailyProtectionQueued: Boolean = false,
    val fcm: FcmUiState = FcmUiState(),
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
    val notificationFactsExportFeedback: String? = null,
    val deepMonitoring: DeepMonitoringUiState = DeepMonitoringUiState(),
    val deepMonitoringActionMessage: String? = null,
    val deepMonitoringExportFeedback: String? = null,
    val deepMonitoringExportBusy: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val normalProbe = NormalDeviceProbe(application)
    private val shizukuRuntime = ShizukuRuntime(application)
    private val originalRecordController = OriginalRecordController(AtomicFileJournalStore(application))
    private val originalRecordMutex = Mutex()
    private val detectionGate = SingleFlightGate()
    private val autostartGate = SingleFlightGate()
    private val autostartReturnTracker = AutostartReturnTracker()
    private val targetReadGate = SingleFlightGate()
    private val targetReturnTracker = TargetAutostartReturnTracker()
    private val targetStore = TargetSelectionStore(application)
    private val targetProbe = TargetPackageProbe(application)
    private val dailyProtectionEvents = DailyProtectionEvents(application)
    private val dailyProtectionStore = DailyProtectionStore(application)
    private val fcmRegistrationClient = FcmRegistrationClient(application)
    private val fcmRegistrationRequests = FcmRegistrationRequestGate()
    private val fcmEvidenceStore = FcmEvidenceStore(application)
    private val sensitiveTokenClipboard = SensitiveTokenClipboard(application)
    private val notificationFactsController = NotificationFactsController(application)
    private val deepMonitoringController = DeepMonitoringController(application)
    private val monitorPreparedExport = PreparedMonitorSessionExport()
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
        viewModelScope.launch {
            DeepMonitoringRuntime.state.collectLatest { snapshot ->
                if (snapshot.historyCorrupt || snapshot.session?.id != _uiState.value.deepMonitoring.session?.id) {
                    monitorPreparedExport.invalidate()
                }
                _uiState.update { it.copy(deepMonitoring = snapshot) }
            }
        }
        viewModelScope.launch { deepMonitoringController.refresh() }
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
        refreshNormalState()
        loadOriginalRecord()
        loadSelectedTargets()
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

    fun requestShizukuAccess() = shizukuRuntime.requestOrOpen()

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
                _uiState.update { current ->
                    snapshot.fold(
                        onSuccess = { current.copy(notificationFacts = it) },
                        onFailure = {
                            current.copy(notificationFactsMessage = "通知记录状态读取失败；请稍后重试")
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
                it.copy(notificationFactsMessage = "请先选择至少一个身份有效的即时通知应用")
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
        _uiState.update { it.copy(dailyProtectionQueued = true) }
        viewModelScope.launch(Dispatchers.IO) {
            DailyProtectionScheduler.enqueueManual(getApplication())
        }
    }

    fun checkGuardianAutostart() = checkGuardianAutostart(returnedFromSettings = false)

    fun onAutostartSettingsLaunchResult(result: AutostartSettingsLaunchResult) {
        when (result) {
            AutostartSettingsLaunchResult.Launched -> {
                autostartReturnTracker.markSuccessfulLaunch()
                _uiState.update {
                    it.copy(
                        autostart = it.autostart.copy(
                            detail = "已打开系统自启动页面；返回 Guardian 后将自动只读复检一次",
                        ),
                    )
                }
            }
            AutostartSettingsLaunchResult.Unavailable -> {
                autostartReturnTracker.clear()
                _uiState.update {
                    it.copy(
                        autostart = it.autostart.copy(
                            detail = "当前系统未提供已验证的 vivo 自启动设置入口",
                        ),
                    )
                }
            }
            is AutostartSettingsLaunchResult.Failed -> {
                autostartReturnTracker.clear()
                _uiState.update {
                    it.copy(
                        autostart = it.autostart.copy(
                            detail = "打开系统自启动页面失败：${result.detail}",
                        ),
                    )
                }
            }
        }
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
                showTargetSelection()
            }
        }
    }

    fun setTargetSelected(packageName: String, selected: Boolean) {
        val candidates = _uiState.value.targetSelection.candidates
        val isCandidate = candidates.any { it.packageName == packageName }
        val isStoredTarget = _uiState.value.selectedTargets.any { it.identity.packageName == packageName }
        if ((selected && !isCandidate) || (!selected && !isCandidate && !isStoredTarget)) return
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
                            message = "无法保存选择；最多选择 10 个应用，且应用身份必须可验证",
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

    fun checkSelectedTarget(packageName: String) =
        checkSelectedTarget(packageName, expectedReturnIdentity = null)

    fun prepareTargetSettingsLaunch(packageName: String): SelectedTargetIdentity? {
        val stored = _uiState.value.selectedTargets
            .singleOrNull { it.identity.packageName == packageName }
            ?.identity ?: return null
        val current = targetProbe.identity(packageName)
        if (identityStatus(stored, current) != TargetIdentityStatus.MATCH) {
            updateTarget(packageName) {
                it.copy(
                    status = TargetAutostartStatus.IDENTITY_CHANGED,
                    evidence = null,
                    detail = "应用身份已变化或应用已卸载；请移除后重新选择",
                )
            }
            return null
        }
        return stored
    }

    fun onTargetSettingsLaunchResult(
        identity: SelectedTargetIdentity,
        result: AutostartSettingsLaunchResult,
    ) {
        when (result) {
            AutostartSettingsLaunchResult.Launched -> {
                targetReturnTracker.markSuccessfulLaunch(identity)
                updateTarget(identity.packageName) {
                    it.copy(detail = "已打开系统自启动页面；返回后将自动只读复检此应用一次")
                }
            }
            AutostartSettingsLaunchResult.Unavailable -> {
                targetReturnTracker.clear()
                updateTarget(identity.packageName) {
                    it.copy(detail = "当前系统未提供已验证的 vivo 自启动设置入口")
                }
            }
            is AutostartSettingsLaunchResult.Failed -> {
                targetReturnTracker.clear()
                updateTarget(identity.packageName) {
                    it.copy(detail = "打开系统自启动页面失败：${result.detail}")
                }
            }
        }
    }

    fun onActivityPausedAfterAutostartLaunch() {
        autostartReturnTracker.markDeparture()
        targetReturnTracker.markDeparture()
    }

    fun onActivityResumed() {
        notificationFactsUiActive = true
        _uiState.update { it.copy(notificationFactsMessage = null) }
        refreshNotificationFacts()
        if (autostartReturnTracker.consumeReturn()) {
            checkGuardianAutostart(returnedFromSettings = true)
        }
        targetReturnTracker.consumeReturn()?.let { identity ->
            checkSelectedTarget(identity.packageName, expectedReturnIdentity = identity)
        }
    }

    fun detect() {
        if (!detectionGate.tryEnter()) return
        val job = viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        detecting = true,
                        report = null,
                        plan = null,
                        detectionMessage = "正在执行只读检测…",
                    )
                }
                refreshNormalState()
                val status = shizukuRuntime.status.value.status
                if (status != ShizukuStatus.READY_SHELL) {
                    _uiState.update {
                        it.copy(
                            detecting = false,
                            detectionMessage = "仅完成普通 Android API 检测；Shizuku shell 未就绪",
                        )
                    }
                    return@launch
                }

                try {
                    val (report, evidence) = ReadSessionCoordinator.withSession {
                        val recordingExecutor = RecordingPrivilegedExecutor(shizukuRuntime.executor)
                        try {
                            val detected = withContext(Dispatchers.IO) {
                                ReadOnlyDetector(recordingExecutor).detect()
                            }
                            detected to recordingExecutor.snapshotEvidence()
                        } finally {
                            // Keep cleanup inside the globally serialized privileged read session.
                            withContext(NonCancellable) { shizukuRuntime.executor.cleanup() }
                        }
                    }
                    coroutineContext.ensureActive()
                    val plan = RepairPlanner.plan(report)
                    val snapshot = BaselineSnapshot(
                        metadata = CaptureMetadata(
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            captureSource = CaptureSource.SHIZUKU_READ_ONLY,
                            buildFingerprint = report.buildFingerprint,
                            buildFingerprintSource = MetadataFieldSource.SHELL_REPORT,
                            androidApiFingerprint = Build.FINGERPRINT,
                            buildId = Build.ID,
                            buildDisplay = Build.DISPLAY,
                            sdkInt = Build.VERSION.SDK_INT,
                            buildFieldsSource = MetadataFieldSource.ANDROID_BUILD_API,
                            userId = report.currentUser,
                            userIdSource = MetadataFieldSource.SHELL_REPORT,
                            executorUid = if (
                                report.privilegedAccess == PrivilegedAccessState.READY_SHELL
                            ) {
                                2_000
                            } else {
                                null
                            },
                            executorUidSource = MetadataFieldSource.EXECUTOR,
                            gmsAppId = report.gms.appId,
                        ),
                        report = report,
                        commandEvidence = evidence.results,
                        evidenceOverflowed = evidence.overflowed,
                    )
                    val originalRecord = withContext(Dispatchers.IO) {
                        originalRecordMutex.withLock {
                            originalRecordController.captureFirst(snapshot)
                        }
                    }
                    coroutineContext.ensureActive()
                    _uiState.update {
                        it.copy(
                            detecting = false,
                            report = report,
                            plan = plan,
                            originalRecord = originalRecord,
                            detectionMessage = detectionMessage(originalRecord),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(
                            detecting = false,
                            detectionMessage = "检测失败：${error.message?.take(160) ?: error.javaClass.simpleName}",
                        )
                    }
                }
        }
        detectionGate.releaseOnCompletion(job)
    }

    fun closeForActivityExit() = shizukuRuntime.close()

    override fun onCleared() {
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

    private fun refreshNormalState() {
        viewModelScope.launch {
            val gms = withContext(Dispatchers.IO) { normalProbe.readGmsPackage() }
            _uiState.update { it.copy(gms = gms) }
        }
    }

    private fun loadDailyProtectionReport() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { dailyProtectionStore.load() }
            _uiState.update {
                it.copy(
                    dailyProtectionReport = report,
                    dailyProtectionQueued = false,
                )
            }
        }
    }

    private fun loadFcmEvidence() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { fcmEvidenceStore.load() }
            _uiState.update { current ->
                current.copy(
                    fcm = current.fcm.copy(
                        latestReceipt = (snapshot as? FcmEvidenceSnapshot.Available)?.receipts?.firstOrNull(),
                        evidenceCorrupt = snapshot == FcmEvidenceSnapshot.Corrupt,
                    ),
                )
            }
        }
    }

    private fun checkGuardianAutostart(returnedFromSettings: Boolean) {
        if (!autostartGate.tryEnter()) return
        val job = viewModelScope.launch {
            if (Build.FINGERPRINT != VERIFIED_AUTOSTART_FINGERPRINT) {
                _uiState.update {
                    it.copy(
                        autostart = GuardianAutostartUiState(
                            status = AutostartUiStatus.UNKNOWN,
                            detail = "当前系统构建未验证；不解释 vivo 私有自启动字段",
                        ),
                        autostartCheckAttemptCount = it.autostartCheckAttemptCount + 1,
                    )
                }
                return@launch
            }
            if (shizukuRuntime.status.value.status != ShizukuStatus.READY_SHELL) {
                _uiState.update {
                    it.copy(
                        autostart = GuardianAutostartUiState(
                            status = AutostartUiStatus.UNAVAILABLE,
                            detail = "需要已授权的 Shizuku shell 才能读取自启动状态",
                        ),
                        autostartCheckAttemptCount = it.autostartCheckAttemptCount + 1,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    autostart = GuardianAutostartUiState(
                        status = AutostartUiStatus.CHECKING,
                        detail = if (returnedFromSettings) "已返回 Guardian，正在只读复检…" else "正在只读检查…",
                    ),
                )
            }
            try {
                val result = ReadSessionCoordinator.withSession {
                    try {
                        withContext(Dispatchers.IO) {
                            shizukuRuntime.executor.exec(SafeCommand.GetGuardianAutostartRow)
                        }
                    } finally {
                        withContext(NonCancellable) { shizukuRuntime.executor.cleanup() }
                    }
                }
                coroutineContext.ensureActive()
                val parsed = GuardianAutostartParser.parse(
                    result = result,
                    expectedGuardianUid = getApplication<Application>().applicationInfo.uid,
                )
                val status = when {
                    parsed.status != AutostartReadStatus.READ_ONLY_VALUE_PRESENT ->
                        AutostartUiStatus.UNKNOWN
                    parsed.currentState == 0 -> AutostartUiStatus.ALLOWED
                    parsed.currentState == 1 -> AutostartUiStatus.NEEDS_USER_ACTION
                    else -> AutostartUiStatus.UNKNOWN
                }
                val detail = when (status) {
                    AutostartUiStatus.ALLOWED ->
                        "系统自启动已允许（当前构建只读验证）；这不代表 FCM 已连接或消息已送达"
                    AutostartUiStatus.NEEDS_USER_ACTION ->
                        "系统自启动未允许；可打开系统页面手动设置，Guardian 不会自动修改"
                    else -> "无法得到唯一、完整且 UID 匹配的 Guardian 自启动行"
                }
                _uiState.update {
                    it.copy(
                        autostart = GuardianAutostartUiState(status, parsed, detail),
                        autostartCheckAttemptCount = it.autostartCheckAttemptCount + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        autostart = GuardianAutostartUiState(
                            status = AutostartUiStatus.UNKNOWN,
                            detail = "自启动读取失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
                        ),
                        autostartCheckAttemptCount = it.autostartCheckAttemptCount + 1,
                    )
                }
            }
        }
        autostartGate.releaseOnCompletion(job)
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
        val loaded = loadedResult.getOrElse {
            _uiState.update { current ->
                current.copy(
                    selectedTargets = emptyList(),
                    targetSelectionStoreCorrupt = true,
                    targetSelection = current.targetSelection.copy(
                        message = "已保存的应用选择记录损坏；为避免覆盖，需先明确清除记录",
                    ),
                )
            }
            refreshNotificationFacts()
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
                targetSelectionStoreCorrupt = false,
            )
        }
        refreshNotificationFacts()
    }

    private fun checkSelectedTarget(
        packageName: String,
        expectedReturnIdentity: SelectedTargetIdentity?,
    ) {
        if (!targetReadGate.tryEnter()) return
        val job = viewModelScope.launch {
            val stored = _uiState.value.selectedTargets
                .singleOrNull { it.identity.packageName == packageName }
                ?.identity
            if (stored == null || (expectedReturnIdentity != null && expectedReturnIdentity != stored)) {
                _uiState.update { it.copy(targetReadAttemptCount = it.targetReadAttemptCount + 1) }
                return@launch
            }
            if (Build.FINGERPRINT != VERIFIED_AUTOSTART_FINGERPRINT) {
                finishTargetAttempt(
                    packageName,
                    TargetAutostartStatus.UNKNOWN,
                    "当前系统构建未验证；不解释 vivo 私有自启动字段",
                )
                return@launch
            }
            if (shizukuRuntime.status.value.status != ShizukuStatus.READY_SHELL) {
                finishTargetAttempt(
                    packageName,
                    TargetAutostartStatus.UNAVAILABLE,
                    "需要已授权的 Shizuku shell 才能读取自启动状态",
                )
                return@launch
            }
            val currentIdentity = withContext(Dispatchers.IO) { targetProbe.identity(packageName) }
            if (identityStatus(stored, currentIdentity) != TargetIdentityStatus.MATCH) {
                finishTargetAttempt(
                    packageName,
                    TargetAutostartStatus.IDENTITY_CHANGED,
                    "应用身份已变化或应用已卸载；请移除后重新选择",
                )
                return@launch
            }

            updateTarget(packageName) {
                it.copy(status = TargetAutostartStatus.CHECKING, evidence = null, detail = "正在只读检查…")
            }
            try {
                val result = ReadSessionCoordinator.withSession {
                    try {
                        withContext(Dispatchers.IO) {
                            shizukuRuntime.executor.exec(SafeCommand.GetTargetAutostartRow(packageName))
                        }
                    } finally {
                        withContext(NonCancellable) { shizukuRuntime.executor.cleanup() }
                    }
                }
                coroutineContext.ensureActive()
                val postReadIdentity = withContext(Dispatchers.IO) { targetProbe.identity(packageName) }
                if (identityStatus(stored, postReadIdentity) != TargetIdentityStatus.MATCH) {
                    finishTargetAttempt(
                        packageName,
                        TargetAutostartStatus.IDENTITY_CHANGED,
                        "读取期间应用身份发生变化；结果已丢弃，请重新选择",
                    )
                    return@launch
                }
                val parsed = TargetAutostartParser.parse(result, packageName, stored.uid)
                val status = when {
                    parsed.status != AutostartReadStatus.READ_ONLY_VALUE_PRESENT ->
                        TargetAutostartStatus.UNKNOWN
                    parsed.currentState == 0 -> TargetAutostartStatus.ALLOWED
                    parsed.currentState == 1 -> TargetAutostartStatus.NEEDS_USER_ACTION
                    else -> TargetAutostartStatus.UNKNOWN
                }
                val detail = when (status) {
                    TargetAutostartStatus.ALLOWED ->
                        "系统自启动已允许；这不代表 FCM 已连接或消息已送达"
                    TargetAutostartStatus.NEEDS_USER_ACTION ->
                        "系统自启动未允许；请在系统页面手动设置"
                    else -> "系统没有返回唯一、完整且身份匹配的自启动状态"
                }
                finishTargetAttempt(packageName, status, detail, parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                finishTargetAttempt(
                    packageName,
                    TargetAutostartStatus.UNKNOWN,
                    "自启动读取失败：${error.message?.take(120) ?: error.javaClass.simpleName}",
                )
            }
        }
        targetReadGate.releaseOnCompletion(job)
    }

    private fun finishTargetAttempt(
        packageName: String,
        status: TargetAutostartStatus,
        detail: String,
        evidence: io.github.originosfcmguardian.core.GuardianAutostartRead? = null,
    ) {
        _uiState.update { current ->
            current.copy(
                selectedTargets = current.selectedTargets.map { target ->
                    if (target.identity.packageName == packageName) {
                        target.copy(status = status, evidence = evidence, detail = detail)
                    } else {
                        target
                    }
                },
                targetReadAttemptCount = current.targetReadAttemptCount + 1,
            )
        }
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

    private fun loadOriginalRecord() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                originalRecordMutex.withLock { originalRecordController.load() }
            }
            _uiState.update { current ->
                current.copy(
                    originalRecord = applyInitialRecordIfStillLoading(current.originalRecord, state),
                )
            }
        }
    }

    private fun detectionMessage(record: OriginalRecordState): String = when {
        record.status == OriginalRecordStatus.DAMAGED ->
            "只读检测完成，但原始记录未能安全读取或保存；没有覆盖已有记录"
        record.latestCaptureIncomplete ->
            "只读检测完成，但本次读取证据不完整；没有保存或覆盖原记录"
        record.status == OriginalRecordStatus.SAVED ->
            "只读检测完成；已保留首次完整原始记录，没有执行任何系统写入"
        else -> "只读检测完成；没有执行任何系统写入"
    }

    private companion object {
        const val TOKEN_CLIPBOARD_TTL_MILLIS = 60_000L
        const val FCM_REGISTRATION_TIMEOUT_MILLIS = 30_000L
    }
}
