package io.github.originosfcmguardian.fcmrecovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.content.pm.PackageManager
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionGrant
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.privileged.IFcmRecoveryAuthority
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Reuses existing protection polling/FGS; no independent timer or privileged-service restart. */
object FcmRecoveryRuntime {
    @Volatile internal var bundledAdmission: (GmsConnectionObservation.Sample?) -> Boolean = { false }
    private val latest = MutableStateFlow<GmsConnectionObservation?>(null)
    internal val latestObservation: StateFlow<GmsConnectionObservation?> = latest.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(FcmRecoveryUiState())
    val state: StateFlow<FcmRecoveryUiState> = mutable.asStateFlow()
    private val revision = AtomicLong(0)
    private val enabled = AtomicBoolean(false)
    private var app: Context? = null
    private var store: FcmRecoveryStore? = null
    @Volatile private var host: AutomaticProtectionGrant? = null
    @Volatile private var eligible = false
    @Volatile private var job: Job? = null
    @Volatile private var executor: ShizukuExecutor? = null
    @Volatile private var authorityLive: AtomicBoolean? = null
    private var lastObservationKey: String? = null
    private var lastEligibility: FcmRecoveryEligibility? = null

    @Synchronized private fun prepare(context: Context): FcmRecoveryStore {
        app = context.applicationContext
        return store ?: FcmRecoveryStore(AtomicFcmRecoveryStorage(context.applicationContext)).also { store = it }
    }
    fun initialize(context: Context) {
        val storage = prepare(context)
        val captured = revision.get()
        scope.launch { runCatching { storage.read() }.onSuccess {
            synchronized(this@FcmRecoveryRuntime) {
                if (captured == revision.get()) { enabled.set(it.configured); publish(it) }
            }
        }.onFailure { blocked() } }
    }
    private fun publish(value: FcmRecoverySnapshot, detail: String = mutable.value.detail,
        confirmation: Boolean = false) {
        mutable.value = mutable.value.copy(configured = value.configured, events = value.events,
            detail = detail, requiresConfirmation = confirmation, storageBlocked = false)
    }
    private fun blocked() {
        enabled.set(false); authorityLive?.set(false)
        mutable.value = mutable.value.copy(storageBlocked = true, busy = false,
            detail = "试用记录无法安全读取或保存；已暂停，不会重发。")
    }
    private fun event(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample? = null,
        authorized: Boolean = false, result: FcmRecoveryResult? = null, unknown: GmsUnknown? = null) = FcmRecoveryEvent(
        System.currentTimeMillis(), SystemClock.elapsedRealtime(), action,
        sample?.gcmService?.state ?: GcmConnectionState.UNKNOWN,
        sample?.gcmService?.completeness ?: SourceCompleteness.UNAVAILABLE,
        sample?.observation ?: SocketObservation.UNKNOWN, authorized, result?.status, result?.reason,
        observationUnknownReason = unknown, power = app?.let { context ->
            runCatching { FcmPowerReferenceReader.capture(context) }.getOrNull()
        })
    private fun record(value: FcmRecoveryEvent) {
        val storage = store ?: return
        runCatching { storage.append(value) }.onSuccess { publish(it) }.onFailure { blocked() }
    }
    private fun network(): Boolean = runCatching {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(active) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
    private fun shell(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    private fun hostReady(grant: AutomaticProtectionGrant) = host === grant && eligible &&
        AutomaticProtectionRuntime.authorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting() &&
        AutomaticProtectionRuntime.consentsAuthorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting()

    @Synchronized internal fun attachHost(context: Context, grant: AutomaticProtectionGrant) {
        prepare(context); invalidate(); host = grant; eligible = false
        initialize(context)
    }
    /** Sole serial read/decision/dispatch/readback cycle; one opportunity per existing poll. */
    @Synchronized internal fun updateHostReadiness(id: String): Boolean {
        if (host?.id != id) return false
        eligible = true; return true // No read, write admission or cycle is started here.
    }
    @Synchronized internal fun hostEligible(id: String) {
        val grant = host?.takeIf { it.id == id } ?: return
        eligible = true
        if (!enabled.get() || job?.isCompleted == false || mutable.value.storageBlocked) return
        val captured = revision.get()
        job = scope.launch { cycle(grant, captured) }
    }
    @Synchronized private fun invalidate() {
        revision.incrementAndGet(); authorityLive?.set(false); executor?.cancelFcmRecovery(); job?.cancel()
        lastObservationKey = null; lastEligibility = FcmRecoveryEligibility.HOST_UNAVAILABLE
    }
    @Synchronized internal fun hostEnded(id: String) {
        if (host?.id != id) return
        eligible = false; invalidate(); host = null
        scope.launch { record(event(FcmRecoveryEventAction.HOST_ENDED)) }
    }
    @Synchronized internal fun privilegeLost(id: String) {
        if (host?.id != id) return
        eligible = false; invalidate()
        scope.launch { record(event(FcmRecoveryEventAction.PRIVILEGE_LOST)) }
    }
    @Synchronized internal fun masterOff() { eligible = false; invalidate() }
    fun disable(context: Context) {
        val storage = prepare(context)
        enabled.set(false); invalidate()
        mutable.value = mutable.value.copy(configured = false, detail = "FCM 恢复试用已停止，正在保存关闭状态。")
        // The durable OFF barrier is synchronous: process death cannot restore an older ON backup.
        runCatching { storage.disable() }.onSuccess {
            record(event(FcmRecoveryEventAction.CONSENT_OFF)); publish(storage.read(), "FCM 恢复试用已关闭；已派发操作可能完成。")
        }.onFailure { blocked() }
    }
    suspend fun confirm(context: Context): Boolean {
        val storage = prepare(context)
        val grant = host ?: return false
        if (!hostReady(grant) || !shell() || !network() || job?.isCompleted == false) return false
        val captured = revision.get()
        mutable.value = mutable.value.copy(busy = true)
        return try { ReadSessionCoordinator.withSession {
            val remote = ShizukuExecutor(context.applicationContext); remote.openSession()
            try {
                val sample = remote.readGmsConnection() as? GmsConnectionObservation.Sample ?: return@withSession false
                if (captured != revision.get() || !hostReady(grant) || !network() ||
                    !FcmRecoveryPolicy.qualified(sample, SystemClock.elapsedRealtime())) return@withSession false
                synchronized(this@FcmRecoveryRuntime) {
                    if (captured != revision.get() || !hostReady(grant)) return@withSession false
                    val saved = storage.confirm(FcmRecoveryPolicy.binding(sample))
                    revision.incrementAndGet() // Supersede stale initialization and older read cycles.
                    enabled.set(saved.configured)
                }
                record(event(FcmRecoveryEventAction.CONSENT_ON, sample, true))
                publish(storage.read(), "试用已开启；完整确认服务缺失且当前恢复入口可用时尝试一次。手机重启或 GMS 更新后需重新确认。")
                true
            } finally { remote.retireObservationSession() }
        } } catch (_: Throwable) { blocked(); false }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
    /** Parent consent is committed first; this prepares only the existing episode/log document. */
    internal fun prepareBundled(context: Context, sample: GmsConnectionObservation.Sample): FcmRecoveryPreparation {
        val storage = prepare(context); val grant = host ?: return FcmRecoveryPreparation.HOST_UNAVAILABLE
        if (!hostReady(grant)) return FcmRecoveryPreparation.HOST_UNAVAILABLE
        if (!shell()) return FcmRecoveryPreparation.SHELL_UNAVAILABLE
        if (!network()) return FcmRecoveryPreparation.NETWORK_UNAVAILABLE
        if (job?.isCompleted == false) return FcmRecoveryPreparation.READ_IN_PROGRESS
        if (!FcmRecoveryPolicy.qualified(sample, SystemClock.elapsedRealtime())) return FcmRecoveryPreparation.NOT_QUALIFIED
        return runCatching {
            synchronized(this) {
                if (!hostReady(grant)) return FcmRecoveryPreparation.HOST_UNAVAILABLE
                val saved = storage.confirm(FcmRecoveryPolicy.binding(sample))
                revision.incrementAndGet(); enabled.set(saved.configured)
                record(event(FcmRecoveryEventAction.CONSENT_ON, sample, true))
                if (mutable.value.storageBlocked) FcmRecoveryPreparation.STORAGE_FAILURE else FcmRecoveryPreparation.READY
            }
        }.getOrElse { blocked(); FcmRecoveryPreparation.STORAGE_FAILURE }
    }
    private suspend fun cycle(grant: AutomaticProtectionGrant, captured: Long) {
        fun authorized() = enabled.get() && bundledAdmission(null) && captured == revision.get() && hostReady(grant) && shell() && network()
        val eligibility = when {
            !enabled.get() -> FcmRecoveryEligibility.CONSENT_OFF
            captured != revision.get() || !hostReady(grant) -> FcmRecoveryEligibility.HOST_UNAVAILABLE
            !shell() -> FcmRecoveryEligibility.SHELL_UNAVAILABLE
            !network() -> FcmRecoveryEligibility.NETWORK_UNAVAILABLE
            else -> FcmRecoveryEligibility.ELIGIBLE
        }
        if (eligibility != lastEligibility) {
            lastEligibility = eligibility
            record(event(FcmRecoveryEventAction.INELIGIBLE).copy(eligible = eligibility == FcmRecoveryEligibility.ELIGIBLE,
                eligibility = eligibility))
        }
        // Piggyback on this existing poll. Reservation survives process/host recreation and
        // ring eviction; optional API failures only leave null fields, never deny a recovery.
        runCatching {
            if (store?.reservePowerSample(System.currentTimeMillis()) == true)
                record(event(FcmRecoveryEventAction.POWER_SAMPLE).copy(
                    eligible = eligibility == FcmRecoveryEligibility.ELIGIBLE, eligibility = eligibility))
        }.onFailure { blocked() } // Authority document failure still closes the mandatory safety gate.
        if (!enabled.get()) return
        if (eligibility != FcmRecoveryEligibility.ELIGIBLE) return
        val storage = store ?: return
        mutable.value = mutable.value.copy(busy = true)
        try { ReadSessionCoordinator.withSession {
            val remote = ShizukuExecutor(checkNotNull(app)); executor = remote; remote.openSession()
            try {
                val observation = remote.readGmsConnection()
                if (captured != revision.get()) return@withSession
                latest.value = observation
                val sample = observation as? GmsConnectionObservation.Sample
                val allowed = authorized()
                val key = "${sample?.gcmService?.state}|${sample?.gcmService?.completeness}|${sample?.observation}|$allowed|${(observation as? GmsConnectionObservation.Unknown)?.reason}"
                if (key != lastObservationKey) { lastObservationKey = key; record(event(FcmRecoveryEventAction.OBSERVATION, sample, allowed,
                    unknown = (observation as? GmsConnectionObservation.Unknown)?.reason)) }
                if (sample == null || !allowed || !bundledAdmission(sample)) return@withSession
                var saved = storage.read()
                if (!saved.configured || saved.binding != FcmRecoveryPolicy.binding(sample)) {
                    publish(saved, if (sample.signerSha256 != FcmRecoveryPolicy.SIGNER)
                        "当前 GMS 签名未通过核验，恢复试用已暂停。"
                        else "当前开机或 GMS 安装身份已变化；请重新确认 FCM 恢复试用。",
                        sample.signerSha256 == FcmRecoveryPolicy.SIGNER)
                    return@withSession
                }
                saved = storage.rearm(sample, SystemClock.elapsedRealtime(), network())
                if (!FcmRecoveryPolicy.absent(sample, SystemClock.elapsedRealtime()) || saved.consumed || !authorized()) return@withSession
                if (storage.consume(sample) == null) return@withSession
                record(event(FcmRecoveryEventAction.ATTEMPT_CONSUMED, sample, true))
                val live = AtomicBoolean(true); authorityLive = live
                val used = AtomicBoolean(false)
                val authority = object : IFcmRecoveryAuthority.Stub() {
                    override fun authorizeDispatch(): Boolean = runCatching {
                        live.get() && authorized() && bundledAdmission(sample) && storage.read().let {
                            it.configured && it.consumed && it.binding == FcmRecoveryPolicy.binding(sample)
                        } && live.get() && captured == revision.get() && used.compareAndSet(false, true)
                    }.getOrDefault(false)
                }
                val result = remote.requestFcmRecovery(FcmRecoveryPolicy.binding(sample), authority)
                live.set(false)
                record(event(FcmRecoveryEventAction.REQUEST_RESULT, sample, authorized(), result))
                if (captured != revision.get() || !authorized()) return@withSession
                // One immediate readback, then normal polls. Never a resend or a claimed repair.
                val afterObservation = remote.readGmsConnection()
                if (captured == revision.get()) latest.value = afterObservation
                val after = afterObservation as? GmsConnectionObservation.Sample
                record(event(FcmRecoveryEventAction.READBACK, after, authorized(),
                    unknown = (afterObservation as? GmsConnectionObservation.Unknown)?.reason))
                if (after != null && authorized()) storage.rearm(after, SystemClock.elapsedRealtime(), network())
                publish(storage.read(), if (after != null && FcmRecoveryPolicy.connected(after, SystemClock.elapsedRealtime()))
                    "请求结果：${result.status}；随后观察到连接，不代表通知送达或已证明修复。"
                    else "请求结果：${result.status}；本次缺失已消耗，不重发；等待完整连接观察。")
            } finally { authorityLive?.set(false); remote.retireObservationSession(); executor = null }
        } } catch (_: CancellationException) { /* OFF/host loss consumes the durable episode. */ }
        catch (_: Throwable) { blocked() }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
