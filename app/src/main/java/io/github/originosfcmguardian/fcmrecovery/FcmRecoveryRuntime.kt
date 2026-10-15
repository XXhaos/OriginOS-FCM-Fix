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

/** A consumed slot with no exact known result remains pending, including ring-evicted evidence. */
internal fun unsettledFcmDaemonMigrationWork(saved: FcmRecoverySnapshot): Boolean =
    FcmRecoveryOperation.entries.any { operation ->
        if (!saved.consumed(operation)) false else {
            val at = if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) saved.consumedAt else saved.mcsConsumedAt
            val elapsed = if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) saved.consumedElapsed else saved.mcsConsumedElapsed
            val last = saved.events.lastOrNull {
                (it.operation ?: FcmRecoveryOperation.SERVICE_ABSENT_SEND) == operation &&
                    it.atMillis >= at && it.elapsedMillis >= elapsed &&
                    it.action in setOf(FcmRecoveryEventAction.ATTEMPT_CONSUMED, FcmRecoveryEventAction.REQUEST_RESULT)
            }
            last?.action != FcmRecoveryEventAction.REQUEST_RESULT || last.status == null || last.status == FcmRecoveryStatus.UNKNOWN
        }
    }

/** Reuses existing protection polling/FGS; no independent timer or privileged-service restart. */
object FcmRecoveryRuntime {
    @Volatile internal var bundledAdmission: (GmsConnectionObservation.Sample?) -> Boolean = { false }
    private val latest = MutableStateFlow<GmsConnectionObservation?>(null)
    internal val latestObservation: StateFlow<GmsConnectionObservation?> = latest.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(FcmRecoveryUiState())
    val state: StateFlow<FcmRecoveryUiState> = mutable.asStateFlow()
    private val revision = AtomicLong(0)
    // Retiring a host/read cycle must not discard the process's saved configuration load.
    private val configurationRevision = AtomicLong(0)
    private val loadGeneration = AtomicLong(0)
    private val enabled = AtomicBoolean(false)
    private var app: Context? = null
    private var store: FcmRecoveryStore? = null
    @Volatile private var episodeJournal: FcmRecoveryEpisodeJournal? = null
    @Volatile private var episodeQueue: BoundedFcmEpisodeQueue? = null
    @Volatile private var host: AutomaticProtectionGrant? = null
    @Volatile private var eligible = false
    @Volatile private var job: Job? = null
    @Volatile private var executor: ShizukuExecutor? = null
    @Volatile private var authorityLive: AtomicBoolean? = null
    private var lastObservationKey: String? = null
    private var lastEligibility: FcmRecoveryEligibility? = null
    @Volatile private var migrationPrepared = false

    @Synchronized internal fun prepareForDaemonMigration() {
        migrationPrepared = true
        enabled.set(false); eligible = false; authorityLive?.set(false)
        configurationRevision.incrementAndGet(); loadGeneration.incrementAndGet(); revision.incrementAndGet()
        job?.cancel()
    }

    internal suspend fun awaitDaemonMigrationDrain(context: Context): Boolean {
        val children = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        children.forEach { it.cancel() }; children.forEach { it.join() }
        if (episodeQueue?.awaitMigrationDrain() == false) return false
        val saved = (store ?: FcmRecoveryStore(AtomicFcmRecoveryStorage(context.applicationContext))).read()
        return !unsettledFcmDaemonMigrationWork(saved)
    }

    @Synchronized private fun prepare(context: Context): FcmRecoveryStore {
        app = context.applicationContext
        if (episodeQueue == null) {
            val application = context.applicationContext
            episodeQueue = BoundedFcmEpisodeQueue(
                initialize = { episodeJournal = FcmRecoveryEpisodeJournal.create(application) },
                coverageGap = { at, elapsed -> episodeJournal?.coverageGap(at, elapsed) },
                published = ::publishEpisodeSnapshot,
                dropped = { count -> mutable.update { it.copy(episodesSnapshot = droppedFcmEpisodeSnapshot(it.episodesSnapshot, count)) } })
        }
        return store ?: FcmRecoveryStore(AtomicFcmRecoveryStorage(context.applicationContext)).also { store = it }
    }
    fun initialize(context: Context) {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return
        loadConfiguration(prepare(context))
    }
    @Synchronized internal fun loadConfiguration(storage: FcmRecoveryStore): Job {
        val captured = configurationRevision.get()
        val loading = loadGeneration.incrementAndGet()
        return scope.launch {
            val result = runCatching { storage.read() }
            synchronized(this@FcmRecoveryRuntime) {
                if (migrationPrepared || captured != configurationRevision.get() || loading != loadGeneration.get()) return@synchronized
                result.onSuccess { enabled.set(it.configured); publish(it) }.onFailure { blocked() }
            }
        }
    }
    private fun publish(value: FcmRecoverySnapshot, detail: String = mutable.value.detail,
        confirmation: Boolean = false) {
        mutable.update { it.copy(configured = value.configured, events = value.events,
            detail = detail, requiresConfirmation = confirmation, storageBlocked = false) }
    }
    /** Optional evidence only: never call blocked(), alter authority or acquire new privileged data. */
    private fun episodeEvidence(action: (FcmRecoveryEpisodeJournal) -> Unit) {
        val queue = episodeQueue ?: return
        val capturedAt = System.currentTimeMillis()
        val capturedElapsed = SystemClock.elapsedRealtime()
        queue.offer(capturedAt, capturedElapsed) { episodeJournal?.let(action) }
    }
    /** Worker-only snapshot: a slow diagnostic file can never lock a core check/export. */
    private fun publishEpisodeSnapshot(dropped: Long, coverageLost: Boolean) {
        val saved = episodeJournal?.snapshot() ?: FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.UNAVAILABLE)
        val visible = saved.copy(droppedUpdates = dropped, availability =
            if (coverageLost) FcmEpisodeAvailability.UNAVAILABLE else saved.availability)
        mutable.update { it.copy(episodesSnapshot = mergeFcmEpisodeSnapshot(it.episodesSnapshot, visible)) }
    }
    private fun observeEpisode(observation: GmsConnectionObservation, allowed: Boolean) {
        val sample = observation as? GmsConnectionObservation.Sample
        val now = SystemClock.elapsedRealtime()
        // Preserve the actual source timestamp, not a new wall-clock timestamp pretending to be a read.
        val evidence = FcmRecoveryEvent(
            sample?.gcmService?.observedAtMillis ?: System.currentTimeMillis(),
            sample?.gcmService?.elapsedRealtimeMillis ?: now,
            FcmRecoveryEventAction.OBSERVATION,
            sample?.gcmService?.state ?: GcmConnectionState.UNKNOWN,
            sample?.gcmService?.completeness ?: SourceCompleteness.UNAVAILABLE,
            sample?.observation ?: SocketObservation.UNKNOWN, allowed,
            observationUnknownReason = (observation as? GmsConnectionObservation.Unknown)?.reason)
        episodeEvidence { it.observe(evidence, sample != null && FcmRecoveryPolicy.qualified(sample, now),
            sample != null && FcmRecoveryPolicy.connected(sample, now)) }
    }
    private fun episodeCoverageGap() {
        if (episodeQueue == null) return
        val at = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        episodeEvidence { it.coverageGap(at, elapsed) }
    }
    @Synchronized private fun blocked() {
        configurationRevision.incrementAndGet()
        enabled.set(false); authorityLive?.set(false)
        episodeCoverageGap()
        mutable.update { it.copy(storageBlocked = true, busy = false,
            detail = "恢复记录无法安全读取或保存；已暂停，不会重发。") }
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
    private fun mcsNetwork(): Boolean = runCatching {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return false
        fun facts(c: NetworkCapabilities) = FcmMcsNetwork(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        val active = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(active) ?: return false
        usableFcmMcsNetwork(facts(capabilities), if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it)?.let(::facts) } else emptyList())
    }.getOrDefault(false)
    private fun hostReady(grant: AutomaticProtectionGrant) = host === grant && eligible &&
        AutomaticProtectionRuntime.authorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting() &&
        AutomaticProtectionRuntime.consentsAuthorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting()

    @Synchronized internal fun attachHost(context: Context, grant: AutomaticProtectionGrant) {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return
        prepare(context); invalidate(); host = grant; eligible = false
        initialize(context)
    }
    /** Sole serial read/decision/dispatch/readback cycle; one opportunity per existing poll. */
    @Synchronized internal fun updateHostReadiness(id: String): Boolean {
        if (migrationPrepared) return false
        if (host?.id != id) return false
        eligible = true; return true // No read, write admission or cycle is started here.
    }
    @Synchronized internal fun hostEligible(id: String) {
        if (migrationPrepared) return
        val grant = host?.takeIf { it.id == id } ?: return
        eligible = true
        if (!enabled.get() || job?.isCompleted == false || mutable.value.storageBlocked) return
        val captured = revision.get()
        job = scope.launch { cycle(grant, captured) }
    }
    @Synchronized private fun invalidate() {
        revision.incrementAndGet(); authorityLive?.set(false); executor?.cancelFcmRecovery(); job?.cancel()
        episodeCoverageGap()
        lastObservationKey = null; lastEligibility = FcmRecoveryEligibility.HOST_UNAVAILABLE
    }
    @Synchronized internal fun hostEnded(id: String) {
        if (migrationPrepared) return
        if (host?.id != id) return
        eligible = false; invalidate(); host = null
        scope.launch { record(event(FcmRecoveryEventAction.HOST_ENDED)) }
    }
    @Synchronized internal fun privilegeLost(id: String) {
        if (migrationPrepared) return
        if (host?.id != id) return
        eligible = false; invalidate()
        scope.launch { record(event(FcmRecoveryEventAction.PRIVILEGE_LOST)) }
    }
    @Synchronized internal fun masterOff() { if (!migrationPrepared) { eligible = false; invalidate() } }
    @Synchronized private fun revokeConfiguration() {
        configurationRevision.incrementAndGet()
        enabled.set(false); invalidate()
    }
    @Synchronized private fun configurationCommitted(saved: FcmRecoverySnapshot) {
        // A newly confirmed identity/epoch cannot inherit an old request's diagnostic association.
        episodeCoverageGap()
        configurationRevision.incrementAndGet()
        revision.incrementAndGet() // Supersede older read cycles as well as configuration loads.
        enabled.set(saved.configured)
    }
    @Synchronized fun disable(context: Context) {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return
        val storage = prepare(context)
        revokeConfiguration()
        mutable.update { it.copy(configured = false, detail = "FCM 恢复已停止，正在保存关闭状态。") }
        // The durable OFF barrier is synchronous: process death cannot restore an older ON backup.
        runCatching { storage.disable() }.onSuccess {
            record(event(FcmRecoveryEventAction.CONSENT_OFF)); publish(storage.read(), "FCM 恢复已关闭；已派发操作可能完成。")
        }.onFailure { blocked() }
    }
    suspend fun confirm(context: Context): Boolean {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return false
        val storage = prepare(context)
        val grant = host ?: return false
        if (!hostReady(grant) || !shell() || !network() || job?.isCompleted == false) return false
        val captured = revision.get()
        mutable.update { it.copy(busy = true) }
        return try { ReadSessionCoordinator.withSession {
            val remote = ShizukuExecutor(context.applicationContext); remote.openSession()
            try {
                val sample = remote.readGmsConnection() as? GmsConnectionObservation.Sample ?: return@withSession false
                if (captured != revision.get() || !hostReady(grant) || !network() ||
                    !FcmRecoveryPolicy.qualified(sample, SystemClock.elapsedRealtime())) return@withSession false
                synchronized(this@FcmRecoveryRuntime) {
                    if (captured != revision.get() || !hostReady(grant)) return@withSession false
                    val saved = storage.confirm(FcmRecoveryPolicy.binding(sample))
                    configurationCommitted(saved)
                }
                record(event(FcmRecoveryEventAction.CONSENT_ON, sample, true))
                publish(storage.read(), "恢复已开启；完整确认服务缺失或已适配版本断开且无连接时，各尝试一次。手机重启或 GMS 更新后需重新确认。")
                true
            } finally { remote.retireObservationSession() }
        } } catch (_: Throwable) { blocked(); false }
        finally { mutable.update { it.copy(busy = false) } }
    }
    /** Parent consent is committed first; this prepares only the existing episode/log document. */
    internal fun prepareBundled(context: Context, sample: GmsConnectionObservation.Sample): FcmRecoveryPreparation {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return FcmRecoveryPreparation.HOST_UNAVAILABLE
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
                configurationCommitted(saved)
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
        if (eligibility != FcmRecoveryEligibility.ELIGIBLE) { episodeCoverageGap(); return }
        val storage = store ?: return
        mutable.update { it.copy(busy = true) }
        try { ReadSessionCoordinator.withSession {
            val remote = ShizukuExecutor.backgroundObservation(checkNotNull(app)); executor = remote; remote.openSession()
            try {
                FcmRecoveryCycle(object : FcmRecoveryCycleAccess {
                    override val store = storage
                    override fun wallMillis() = System.currentTimeMillis()
                    override fun elapsedMillis() = SystemClock.elapsedRealtime()
                    override fun current() = captured == revision.get()
                    override fun authorized() = enabled.get() && bundledAdmission(null) && current() && hostReady(grant) && shell() && network()
                    override fun admitted(sample: GmsConnectionObservation.Sample) = bundledAdmission(sample)
                    override fun usableNetwork() = network()
                    override fun disconnectedNetwork() = mcsNetwork()
                    override suspend fun read() = remote.readGmsConnection()
                    override suspend fun request(binding: String, authorizeDispatch: () -> Boolean): FcmRecoveryResult =
                        remote.requestFcmRecovery(binding, object : IFcmRecoveryAuthority.Stub() {
                            override fun authorizeDispatch() = authorizeDispatch.invoke()
                        })
                    override suspend fun request(operation: FcmRecoveryOperation, binding: String,
                        authorizeDispatch: () -> Boolean): FcmRecoveryResult =
                        remote.requestFcmRecovery(binding, object : IFcmRecoveryAuthority.Stub() {
                            override fun authorizeDispatch() = authorizeDispatch.invoke()
                        }, operation)
                    override fun operationRecord(action: FcmRecoveryEventAction, operation: FcmRecoveryOperation,
                        sample: GmsConnectionObservation.Sample, authorized: Boolean, result: FcmRecoveryResult?) {
                        this@FcmRecoveryRuntime.record(event(action, sample, authorized, result).copy(operation = operation))
                    }
                    override fun retainLive(value: AtomicBoolean) { authorityLive = value }
                    override fun observed(observation: GmsConnectionObservation, authorized: Boolean) {
                        latest.value = observation
                        observeEpisode(observation, authorized)
                    }
                    override fun record(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample?,
                        authorized: Boolean, result: FcmRecoveryResult?, unknown: GmsUnknown?) {
                        if (action == FcmRecoveryEventAction.OBSERVATION) {
                            val key = "${sample?.gcmService?.state}|${sample?.gcmService?.completeness}|${sample?.observation}|$authorized|$unknown"
                            if (key == lastObservationKey) return
                            lastObservationKey = key
                        }
                        this@FcmRecoveryRuntime.record(event(action, sample, authorized, result, unknown))
                    }
                    override fun requestStarted(event: FcmRecoveryEvent, correlation: String) =
                        episodeEvidence { it.requestStarted(event, correlation) }
                    override fun requestCompleted(event: FcmRecoveryEvent, correlation: String) =
                        episodeEvidence { it.requestCompleted(event, correlation) }
                    override fun bindingChanged(sample: GmsConnectionObservation.Sample, saved: FcmRecoverySnapshot) {
                        publish(saved, if (sample.signerSha256 != FcmRecoveryPolicy.SIGNER)
                            "当前 GMS 签名未通过核验，恢复已暂停。"
                            else "手机已重启或 Google Play 服务已更新；请重新确认恢复授权。",
                            sample.signerSha256 == FcmRecoveryPolicy.SIGNER)
                    }
                    override fun finished(saved: FcmRecoverySnapshot, result: FcmRecoveryResult, connected: Boolean) {
                        publish(saved, if (connected) "请求结果：${result.status}；随后观察到连接，不代表通知送达或已证明修复。"
                            else "请求结果：${result.status}；本次${if (result.operation == FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS) "断开" else "缺失"}尝试已消耗，不重发；等待完整连接观察。")
                    }
                }).run()
            } finally { authorityLive?.set(false); remote.retireObservationSession(); executor = null }
        } } catch (_: CancellationException) { /* OFF/host loss consumes the durable episode. */ }
        catch (_: Throwable) { blocked() }
        finally { mutable.update { it.copy(busy = false) } }
    }
}
