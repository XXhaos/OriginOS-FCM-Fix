package io.github.originosfcmguardian.fcmprotection

import android.content.Context
import android.os.SystemClock
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionGrant
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryRuntime
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryPreparation
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class FcmMaintenanceState { WAITING_CONNECTED, ACTIVE, WAITING_HOST, RECONFIRM, STOP_PENDING, OFF }
enum class FcmProtectionConfirmationFailure(val detail: String) {
    HOST_UNAVAILABLE("尚未开启：实际保护宿主尚未就绪，请稍后重试。"),
    BUSY("本次未开启：上次读取或维护停止仍在处理中，请稍后重试。"),
    STOP_PENDING("本次未开启：旧维护会话停止尚未确认，不能绕过。"),
    READ_UNAVAILABLE("本次未开启：当前服务读取不完整或不可用，请稍后重试。"),
    NOT_QUALIFIED("本次未开启：当前 GMS 版本、安装身份或完整服务状态尚未通过核验。"),
    SHELL_UNAVAILABLE("本次未开启：外置 Shizuku shell 尚未就绪。"),
    NETWORK_UNAVAILABLE("本次未开启：尚未确认可用网络，请稍后重试。"),
    HOST_CHANGED("本次未开启：宿主或关闭状态已变化，没有取得新的写权限。")
}
internal fun fcmPreparationFailure(value: FcmRecoveryPreparation) = when (value) {
    FcmRecoveryPreparation.HOST_UNAVAILABLE -> FcmProtectionConfirmationFailure.HOST_UNAVAILABLE
    FcmRecoveryPreparation.SHELL_UNAVAILABLE -> FcmProtectionConfirmationFailure.SHELL_UNAVAILABLE
    FcmRecoveryPreparation.NETWORK_UNAVAILABLE -> FcmProtectionConfirmationFailure.NETWORK_UNAVAILABLE
    FcmRecoveryPreparation.READ_IN_PROGRESS -> FcmProtectionConfirmationFailure.BUSY
    FcmRecoveryPreparation.NOT_QUALIFIED -> FcmProtectionConfirmationFailure.NOT_QUALIFIED
    else -> null // READY is success; STORAGE_FAILURE remains mandatory fail-closed, not a prerequisite decline.
}
data class FcmProtectionUiState(val configured: Boolean = false, val busy: Boolean = false,
    val storageBlocked: Boolean = false, val requiresConfirmation: Boolean = false,
    val maintenance: FcmMaintenanceState = FcmMaintenanceState.OFF, val recoveryReady: Boolean = false,
    val detail: String = "尚未开启；旧的分开授权不会自动升级。", val latestObservation: GmsConnectionObservation? = null,
    val confirmationFailure: FcmProtectionConfirmationFailure? = null)

/** One bundled owner, existing host/polls only. Branch engines retain their original uncertainty barriers. */
object FcmProtectionRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Mutex()
    private val live = AtomicBoolean(false)
    private val revision = FcmProtectionCommitFence(this)
    private val mutable = MutableStateFlow(FcmProtectionUiState())
    val state = mutable.asStateFlow()
    @Volatile private var consent = FcmProtectionConsent()
    @Volatile private var host: AutomaticProtectionGrant? = null
    private val readiness = FcmProtectionHostReadiness()
    @Volatile private var confirmationFailure: FcmProtectionConfirmationFailure? = null
    private var app: Context? = null
    private var store: FcmProtectionStore? = null
    private var activation: Job? = null
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true; app = context.applicationContext
        val storage = FcmProtectionStore(AtomicFcmProtectionStorage(context.applicationContext)); store = storage
        FcmRecoveryRuntime.bundledAdmission = { sample -> admission(sample, true) }
        GmsAutoHeartbeatRuntime.bundledAdmission = { sample -> admission(sample, false) }
        GmsAutoHeartbeatRuntime.bundledEpochAdmission = { epoch -> admission(null, false) && consent.epoch == epoch }
        runCatching { storage.read() }.onSuccess { consent = it; live.set(it.configured) }.onFailure { blocked() }
        FcmRecoveryRuntime.initialize(context); GmsAutoHeartbeatRuntime.initialize(context)
        if (!consent.configured) {
            // Execution migration only: retain historical events/episode/pending/UNKNOWN records.
            FcmRecoveryRuntime.disable(context); GmsAutoHeartbeatRuntime.disable(context)
        }
        scope.launch { FcmRecoveryRuntime.latestObservation.collect { observation ->
            if (observation != null) {
                mutable.value = mutable.value.copy(latestObservation = observation)
                refresh(); provisionIfConnected(observation)
            }
        } }
        scope.launch { GmsAutoHeartbeatRuntime.state.collect { refresh() } }
        scope.launch { FcmRecoveryRuntime.state.collect { refresh() } }
        refresh()
    }
    private fun ready(): Boolean = host?.let { grant -> readiness.eligible && AutomaticProtectionRuntime.authorized(grant) &&
        AutomaticProtectionRuntime.isActuallyProtecting() && AutomaticProtectionRuntime.consentsAuthorized(grant) } == true
    private fun admission(sample: GmsConnectionObservation.Sample?, recovery: Boolean): Boolean {
        if (!live.get() || !ready() || mutable.value.storageBlocked) return false
        val current = runCatching { store?.read() }.getOrNull() ?: return false
        return current.epoch == consent.epoch && current.admits(sample, recovery)
    }
    private fun refresh() {
        val failure = confirmationFailure
        val heartbeat = GmsAutoHeartbeatRuntime.state.value
        val recovery = FcmRecoveryRuntime.state.value
        val sample = mutable.value.latestObservation as? GmsConnectionObservation.Sample
        val recoveryReady = consent.configured && consent.recoveryPrepared && ready() && !recovery.storageBlocked &&
            sample?.let { it.isFresh(SystemClock.elapsedRealtime()) && consent.accepts(it, true) } == true &&
            recovery.events.lastOrNull { it.action in setOf(FcmRecoveryEventAction.OBSERVATION, FcmRecoveryEventAction.READBACK) }?.eligible == true
        val maintenance = when {
            heartbeat.stopPending -> FcmMaintenanceState.STOP_PENDING
            !consent.configured -> FcmMaintenanceState.OFF
            heartbeat.resumeBlocked || heartbeat.requiresConfirmation && heartbeat.configured -> FcmMaintenanceState.RECONFIRM
            heartbeat.active -> FcmMaintenanceState.ACTIVE
            !ready() -> FcmMaintenanceState.WAITING_HOST
            else -> FcmMaintenanceState.WAITING_CONNECTED
        }
        val confirmation = consent.configured && (!consent.recoveryPrepared || maintenance == FcmMaintenanceState.RECONFIRM ||
            sample != null && !consent.accepts(sample, true))
        mutable.value = mutable.value.copy(configured = consent.configured, maintenance = maintenance,
            recoveryReady = recoveryReady, requiresConfirmation = confirmation, confirmationFailure = failure,
            detail = when {
                mutable.value.storageBlocked -> "授权记录不可确认，FCM 保护已暂停。"
                failure != null -> failure.detail
                !consent.configured -> "尚未开启；需要新的统一确认，旧授权不自动升级。"
                maintenance == FcmMaintenanceState.STOP_PENDING -> "旧维护会话停止待确认，不能绕过或重发。"
                confirmation && !recoveryReady -> "恢复分支需重新确认当前开机/安装身份；维护分支仅按原安全条件接续。"
                maintenance == FcmMaintenanceState.RECONFIRM -> "维护结果或授权不明，需明确重新确认；旧请求不重发。"
                !ready() -> "已保存统一授权；等待实际自动保护与外置 Shizuku 就绪。"
                maintenance == FcmMaintenanceState.ACTIVE -> "保护已开启：完整连接时低频维护；精确服务缺失时每次只尝试一次。"
                else -> "保护已开启；维护等待完整连接，缺失恢复按资格单次尝试。"
            })
    }
    private fun blocked() {
        live.set(false); revision.incrementAndGet()
        runCatching { store?.disable()?.let { consent = it } }
        app?.let { FcmRecoveryRuntime.disable(it); GmsAutoHeartbeatRuntime.disable(it) }
        mutable.value = mutable.value.copy(storageBlocked = true, busy = false, detail = "统一授权无法安全保存，已暂停。")
    }
    @Synchronized internal fun attachHost(context: Context, grant: AutomaticProtectionGrant) {
        initialize(context); masterOff(); host = grant
        readiness.attach(grant.id)
        GmsAutoHeartbeatRuntime.attachHost(context, grant); FcmRecoveryRuntime.attachHost(context, grant)
        live.set(consent.configured && !mutable.value.storageBlocked); refresh()
    }
    @Synchronized internal fun hostEligible(id: String) {
        if (host?.id != id) return
        readiness.poll(id, { FcmRecoveryRuntime.updateHostReadiness(id) },
            { GmsAutoHeartbeatRuntime.updateHostReadiness(id) }, { consent.configured && live.get() }) {
            FcmRecoveryRuntime.hostEligible(id); GmsAutoHeartbeatRuntime.hostEligible(id)
            mutable.value.latestObservation?.let(::provisionIfConnected)
        }
        refresh()
    }
    @Synchronized internal fun hostEnded(id: String) {
        if (host?.id != id) return
        readiness.end(id); revision.incrementAndGet(); activation?.cancel()
        FcmRecoveryRuntime.hostEnded(id); GmsAutoHeartbeatRuntime.hostEnded(id); host = null; refresh()
    }
    @Synchronized internal fun privilegeLost(id: String) {
        if (host?.id != id) return
        readiness.invalidate(); revision.incrementAndGet(); activation?.cancel()
        FcmRecoveryRuntime.privilegeLost(id); GmsAutoHeartbeatRuntime.privilegeLost(id); refresh()
    }
    @Synchronized internal fun securityLost(id: String) {
        privilegeLost(id); GmsAutoHeartbeatRuntime.securityLost(id)
    }
    @Synchronized internal fun masterOff() {
        readiness.invalidate(); revision.incrementAndGet(); activation?.cancel()
        FcmRecoveryRuntime.masterOff(); host?.id?.let(GmsAutoHeartbeatRuntime::hostEnded)
        refresh()
    }
    @Synchronized fun disable(context: Context) {
        initialize(context)
        confirmationFailure = null
        live.set(false); revision.incrementAndGet(); activation?.cancel()
        // Durable bundled OFF precedes any success display, independent of async branch teardown.
        runCatching { consent = checkNotNull(store).disable() }.onFailure { blocked() }
        FcmRecoveryRuntime.disable(context); GmsAutoHeartbeatRuntime.disable(context); refresh()
    }
    suspend fun confirm(context: Context): Boolean = io.withLock {
        initialize(context)
        var parentCommitted = false
        var writingAuthority = false
        fun decline(reason: FcmProtectionConfirmationFailure): Boolean {
            if (parentCommitted) disable(context)
            confirmationFailure = reason; refresh(); return false
        }
        confirmationFailure = null
        if (!ready()) return@withLock decline(FcmProtectionConfirmationFailure.HOST_UNAVAILABLE)
        if (GmsAutoHeartbeatRuntime.state.value.stopPending) return@withLock decline(FcmProtectionConfirmationFailure.STOP_PENDING)
        if (mutable.value.busy || GmsAutoHeartbeatRuntime.state.value.busy) return@withLock decline(FcmProtectionConfirmationFailure.BUSY)
        val captured = revision.get(); mutable.value = mutable.value.copy(busy = true)
        try {
            val observation = ReadSessionCoordinator.withSession {
                val remote = ShizukuExecutor(context.applicationContext); remote.openSession()
                try { remote.readGmsConnection() } finally { remote.retireObservationSession() }
            }
            val sample = observation as? GmsConnectionObservation.Sample ?: return@withLock decline(FcmProtectionConfirmationFailure.READ_UNAVAILABLE)
            if (captured != revision.get() || !ready()) return@withLock decline(FcmProtectionConfirmationFailure.HOST_CHANGED)
            if (!FcmRecoveryPolicy.qualified(sample, SystemClock.elapsedRealtime()))
                return@withLock decline(FcmProtectionConfirmationFailure.NOT_QUALIFIED)
            writingAuthority = true
            val committed = revision.commit(captured, ::ready) {
                live.set(false)
                GmsAutoHeartbeatRuntime.disable(context)
                consent = checkNotNull(store).confirm(sample)
            }
            writingAuthority = false
            if (!committed) return@withLock decline(FcmProtectionConfirmationFailure.HOST_CHANGED)
            parentCommitted = true
            // Cold crash before this second commit leaves recovery gated OFF and requires explicit reconfirmation.
            val preparation = FcmRecoveryRuntime.prepareBundled(context, sample)
            if (preparation == FcmRecoveryPreparation.STORAGE_FAILURE) { blocked(); return@withLock false }
            if (preparation != FcmRecoveryPreparation.READY) return@withLock decline(checkNotNull(fcmPreparationFailure(preparation)))
            writingAuthority = true
            val prepared = revision.commit(captured, ::ready) {
                consent = checkNotNull(store).recoveryPrepared(checkNotNull(consent.epoch)); live.set(true)
            }
            writingAuthority = false
            if (!prepared) return@withLock decline(FcmProtectionConfirmationFailure.HOST_CHANGED)
            mutable.value = mutable.value.copy(latestObservation = sample, storageBlocked = false)
            refresh(); provisionIfConnected(sample); true
        } catch (_: Throwable) {
            if (writingAuthority) { blocked(); false }
            else decline(if (captured != revision.get()) FcmProtectionConfirmationFailure.HOST_CHANGED
                else FcmProtectionConfirmationFailure.READ_UNAVAILABLE)
        }
        finally { mutable.value = mutable.value.copy(busy = false); refresh() }
    }
    @Synchronized private fun provisionIfConnected(observation: GmsConnectionObservation) {
        val sample = observation as? GmsConnectionObservation.Sample ?: return
        if (!admission(sample, false) || !FcmRecoveryPolicy.connected(sample, SystemClock.elapsedRealtime()) ||
            activation?.isCompleted == false || GmsAutoHeartbeatRuntime.state.value.busy) return
        val epoch = consent.epoch ?: return; val captured = revision.get()
        activation = scope.launch { io.withLock {
            if (captured != revision.get() || !admission(sample, false)) return@withLock
            GmsAutoHeartbeatRuntime.provisionBundled(checkNotNull(app), epoch)
            refresh()
        } }
    }
}
