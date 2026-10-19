package io.github.originosfcmguardian.autoprotection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.recovery.AndroidRecoveryJournal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import io.github.originosfcmguardian.daemon.DaemonAppRuntime
import io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.recovery.StoppedRecoveryController
import kotlinx.coroutines.*

data class AutomaticProtectionTargetState(
    val identity: SelectedTargetIdentity,
    val savedAuthorization: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val waitingForPrivilege: Boolean = false,
    val detail: String = "自动保护未开启",
    val lastCheckedAtMillis: Long? = null,
    val lastOutcome: String? = null,
    val configuredSelection: Boolean = savedAuthorization,
    val needsReconfirmation: Boolean = false,
)

data class AutomaticProtectionState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val detail: String = "自动保护未开启",
    val lastCheckedAtMillis: Long? = null,
    val lastOutcome: String? = null,
    val savedAuthorization: Boolean = false,
    val revocationSaveFailed: Boolean = false,
    val waitingForPrivilege: Boolean = false,
    val targets: List<AutomaticProtectionTargetState> = emptyList(),
    val waitingForReconciliation: Boolean = false,
    val masterEnabled: Boolean = false,
    val configurationAvailable: Boolean = true,
    /** This process's live core session, never persisted or inferred from saved master ON. */
    val coreSessionStartedAtElapsedMillis: Long? = null,
    val waitingForService: Boolean = false,
) {
    val authorizedIdentities: List<SelectedTargetIdentity> get() = targets.filter { it.savedAuthorization }.map { it.identity }
    val configuredIdentities: List<SelectedTargetIdentity> get() = targets.filter { it.configuredSelection }.map { it.identity }
}

/** Saved choices are presentation, never live write authority. */
internal fun configuredAutomaticProtectionState(previous: AutomaticProtectionState,
    snapshot: ProtectionAuthoritySnapshot?, detail: String): AutomaticProtectionState = previous.copy(
    coreSessionStartedAtElapsedMillis = previous.coreSessionStartedAtElapsedMillis.takeIf {
        snapshot?.mode == ProtectionConsentMode.ACTIVE && !snapshot.storageBlocked
    },
    configurationAvailable = true,
    masterEnabled = snapshot?.mode == ProtectionConsentMode.ACTIVE && !snapshot.storageBlocked,
    savedAuthorization = snapshot?.runnableConsents()?.isNotEmpty() == true,
    targets = snapshot?.consents().orEmpty().map { consent ->
        val prior = previous.targets.firstOrNull { it.identity == consent.identity }
        val invalid = consent.id in snapshot!!.invalidatedConsentIds
        (prior ?: AutomaticProtectionTargetState(consent.identity)).copy(configuredSelection = true,
            savedAuthorization = consent in snapshot.runnableConsents(), needsReconfirmation = invalid,
            enabled = false, busy = false, waitingForPrivilege = false,
            detail = if (invalid) "应用身份已失效；请关闭此选择后重新确认当前安装" else detail)
    }, detail = detail)

internal fun masterOffAutomaticProtectionState(previous: AutomaticProtectionState, detail: String): AutomaticProtectionState =
    previous.copy(masterEnabled = false, enabled = false, busy = false, savedAuthorization = false,
        coreSessionStartedAtElapsedMillis = null,
        waitingForPrivilege = false, waitingForReconciliation = false, detail = detail,
        targets = previous.targets.map { it.copy(savedAuthorization = false, enabled = false, busy = false,
            waitingForPrivilege = false, detail = if (it.needsReconfirmation) it.detail else detail) })

/** Only a successful absence proves an empty configuration; read failures retain cached choices. */
internal fun configuredAutomaticProtectionReadState(previous: AutomaticProtectionState,
    result: Result<ProtectionAuthoritySnapshot?>, detail: String): AutomaticProtectionState = result.fold(
    onSuccess = { configuredAutomaticProtectionState(previous, it, detail) },
    onFailure = { masterOffAutomaticProtectionState(previous,
        "应用选择记录暂不可用；保留上次已知选择，当前未执行保护").copy(configurationAvailable = false) })

/** Target evidence belongs to that identity; general status changes never redistribute it. */
internal fun updateAutomaticProtectionState(previous: AutomaticProtectionState, detail: String,
    busy: Boolean = false, checkedAtMillis: Long? = null, outcome: String? = null,
    identity: SelectedTargetIdentity? = null): AutomaticProtectionState = previous.copy(
    enabled = true, busy = busy, detail = detail,
    lastCheckedAtMillis = checkedAtMillis ?: previous.lastCheckedAtMillis,
    lastOutcome = outcome ?: previous.lastOutcome, savedAuthorization = true, waitingForPrivilege = false,
    waitingForReconciliation = false,
    targets = previous.targets.map { target ->
        if (!target.savedAuthorization) target
        else if (identity == null) target.copy(enabled = true, busy = busy, waitingForPrivilege = false, detail = detail)
        else if (target.identity == identity) target.copy(enabled = true, busy = busy,
            waitingForPrivilege = false, detail = detail,
            lastCheckedAtMillis = checkedAtMillis ?: target.lastCheckedAtMillis,
            lastOutcome = outcome ?: target.lastOutcome) else target.copy(busy = false)
    })

/** Retained consent is not active detection; preserve every authorization and evidence field. */
internal fun waitingForNextAutomaticCheckState(previous: AutomaticProtectionState, detail: String): AutomaticProtectionState =
    previous.copy(enabled = false, busy = false, waitingForPrivilege = false, waitingForReconciliation = false,
        detail = detail, targets = previous.targets.map { it.copy(enabled = false, busy = false,
            waitingForPrivilege = false, detail = detail) })

/** Production transition also invalidates every old write ticket before publishing the wait. */
internal fun waitForNextAutomaticCheck(writes: AutomaticWriteGate, previous: AutomaticProtectionState,
    detail: String): AutomaticProtectionState {
    writes.block()
    return waitingForNextAutomaticCheckState(previous, detail)
}

internal class AutomaticProtectionGrant(
    val id: String,
    val leases: List<TargetConsentLease>,
    val store: ProtectionConsentStore,
) {
    val live = AtomicBoolean(true)
    val writes = AutomaticWriteGate()
    var cancel: (() -> Unit)? = null
    var reconciliationRefresh: ((Boolean) -> Unit)? = null
}

/** Presentation only: called at actual core entry, after the live owner has been claimed. */
internal fun startedAutomaticProtectionSessionState(previous: AutomaticProtectionState,
    current: AutomaticProtectionGrant, activeOwner: AutomaticProtectionGrant?, sessionActive: Boolean,
    elapsedMillis: Long): AutomaticProtectionState {
    if (activeOwner !== current || !sessionActive || !current.live.get() || current.cancel == null ||
        previous.coreSessionStartedAtElapsedMillis != null) return previous
    val startedAt = elapsedMillis.takeIf { it >= 0 } ?: return previous
    return previous.copy(coreSessionStartedAtElapsedMillis = startedAt)
}

/** Unexpected host loss retires every live capability without modifying persistent intent. */
internal fun interruptAutomaticProtectionGrant(current: AutomaticProtectionGrant,
    gate: AutomaticSessionGate, previous: AutomaticProtectionState, detail: String): AutomaticProtectionState {
    current.writes.block()
    current.live.set(false)
    gate.revoke(current.id)
    return previous.copy(enabled = false, busy = false, waitingForPrivilege = false,
        coreSessionStartedAtElapsedMillis = null,
        waitingForReconciliation = false, detail = detail,
        targets = previous.targets.map { it.copy(enabled = false, busy = false,
            waitingForPrivilege = false, detail = detail) })
}

/** A tile observation cannot cross an intervening OFF, pause or replacement operation. */
internal fun recoveryRevisionMatches(expected: Long?, current: Long): Boolean =
    expected == null || expected == current

internal enum class LegacyNegativeAdmission { LOCAL, REMOTE, STALE }

/** A retired original notification may carry OFF across migration, never across local replacement. */
internal fun matchesLegacyNotificationStop(id: String, pendingSessionId: String?, activeSessionId: String?,
    remoteClaimed: Boolean, migrationRetiredSessionId: String?): Boolean =
    id == pendingSessionId || id == activeSessionId || remoteClaimed && id == migrationRetiredSessionId

/** Notification identity was matched before this boundary; a handoff cannot discard its OFF. */
internal fun legacyNegativeAdmission(remoteClaimed: Boolean, expectedSessionId: String?, expectedRevision: Long?,
    currentRevision: Long, pendingSessionId: String?, activeSessionId: String?,
    migrationRetiredSessionId: String? = null): LegacyNegativeAdmission = when {
    remoteClaimed && expectedSessionId != null && expectedSessionId != migrationRetiredSessionId &&
        (expectedRevision != currentRevision ||
            pendingSessionId != expectedSessionId && activeSessionId != expectedSessionId) -> LegacyNegativeAdmission.STALE
    remoteClaimed -> LegacyNegativeAdmission.REMOTE
    expectedSessionId != null && (expectedRevision != currentRevision ||
        pendingSessionId != expectedSessionId && activeSessionId != expectedSessionId) -> LegacyNegativeAdmission.STALE
    else -> LegacyNegativeAdmission.LOCAL
}

/** Called under the runtime lock: an old removal must never retire a replacement owner. */
internal fun finishAutomaticIdleRetirement(expectedRevision: Long, currentRevision: Long,
    hasSession: Boolean, retire: () -> Unit): Boolean {
    if (expectedRevision != currentRevision || hasSession) return false
    retire()
    return true
}

/** Admission before each restore-side persistent action; already-admitted IO remains FIFO. */
internal fun isCurrentStartupRestore(expectedRevision: Long, currentRevision: Long,
    expectedId: String, pendingId: String?, blocked: Boolean, saveFailed: Boolean): Boolean =
    expectedRevision == currentRevision && expectedId == pendingId && !blocked && !saveFailed

/** Inert service-lifetime callback survives revoked grants and successive pending operations. */
internal class AutomaticServiceRetirementHandoff {
    private var callback: (() -> Unit)? = null
    fun attach(retire: () -> Unit) { callback = retire }
    fun detach(retire: () -> Unit) { if (callback === retire) callback = null }
    fun finish(expectedRevision: Long, currentRevision: Long, hasSession: Boolean): Boolean =
        finishAutomaticIdleRetirement(expectedRevision, currentRevision, hasSession) { callback?.invoke() }
}

/** One owner for exact legacy policy2 scope and new explicitly granted recipient policy4 scopes. */
object AutomaticProtectionRuntime {
    internal const val CHANNEL_ID = "telegram_automatic_recovery_v1"
    private val gate = AutomaticSessionGate()
    // FIFO disk operations are enqueued under the state lock, executed without holding that lock.
    // In particular, an OFF then ON cannot commit in the opposite order.
    private val authorityExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "guardian-authority").apply { isDaemon = true }
    }
    private var store: ProtectionConsentStore? = null // authority executor only
    private var pendingStart: String? = null
    private var revision = 0L
    private var restoreBlocked = false // latched stop/failure; only fresh explicit consent clears it
    private var grant: AutomaticProtectionGrant? = null
    private val serviceRetirement = AutomaticServiceRetirementHandoff() // runtime lock only
    private val mutable = MutableStateFlow(AutomaticProtectionState(configurationAvailable = false))
    val state: StateFlow<AutomaticProtectionState> = mutable.asStateFlow()
    @Volatile private var migrationPrepared = false
    private var migrationRetiredSessionId: String? = null // exact original notification, negative continuity only

    fun localExecutionAllowed(context: Context): Boolean = !migrationPrepared && !DaemonAppRuntime.isRemoteOwned(context)

    /** Close only process-local capabilities. The caller persists MIGRATING before awaiting IO. */
    fun prepareForDaemonMigration(context: Context): Boolean {
        val cancel = synchronized(this) {
            val firstPreparation = !migrationPrepared
            migrationPrepared = true
            restoreBlocked = true
            pendingStart = null
            ++revision
            val current = grant
            if (firstPreparation) migrationRetiredSessionId = current?.id
            current?.writes?.block()
            current?.live?.set(false)
            current?.let { gate.revoke(it.id) }
            grant = null
            current?.cancel
        }
        ShizukuExecutor.prepareForDaemonMigration()
        StoppedRecoveryController.prepareForDaemonMigration()
        FcmProtectionRuntime.prepareForDaemonMigration()
        cancel?.invoke()
        return true
    }

    /** Success means actual workers and FIFO persistence have settled, not Future cancellation. */
    suspend fun awaitDaemonMigrationDrain(context: Context): Boolean = withContext(Dispatchers.IO) { withTimeoutOrNull(45_000) {
        if (!migrationPrepared) return@withTimeoutOrNull false
        if (!AutomaticProtectionService.awaitDaemonMigrationDrain()) return@withTimeoutOrNull false
        if (!StoppedRecoveryController.awaitDaemonMigrationDrain()) return@withTimeoutOrNull false
        if (AndroidRecoveryJournal.open(context.applicationContext).load().latest?.requiresReconcile == true)
            return@withTimeoutOrNull false
        val persisted = CompletableDeferred<Unit>()
        synchronized(this@AutomaticProtectionRuntime) { authorityExecutor.execute { persisted.complete(Unit) } }
        persisted.await()
        if (!FcmProtectionRuntime.awaitDaemonMigrationDrain(context)) return@withTimeoutOrNull false
        val drained = ShizukuExecutor.awaitDaemonMigrationDrain()
        if (drained) io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime.markMigrationWorkersDrained()
        drained
    } ?: false }

    @Synchronized internal fun publishDaemonState(value: AutomaticProtectionState) { mutable.value = value }

    fun start(context: Context, identity: SelectedTargetIdentity): Boolean {
        if (!localExecutionAllowed(context)) return false
        val app = context.applicationContext
        synchronized(this) {
            if (migrationPrepared || pendingStart != null || mutable.value.busy || mutable.value.revocationSaveFailed) return false
            if (!validRuntimeFingerprint(Build.FINGERPRINT) ||
                android.os.Process.myUid() / 100_000 != 0) {
                mutable.value = mutable.value.copy(detail = "未添加该应用：当前系统版本或用户不受支持")
                return false
            }
            if (runCatching { ensureChannel(app) }.isFailure) {
                mutable.value = mutable.value.copy(detail = "未添加该应用：无法建立前台服务通知渠道")
                return false
            }
            val request = UUID.randomUUID().toString()
            val selected = identity.copy(signerSha256 = identity.signerSha256.toList())
            pendingStart = request
            val startingRevision = ++revision
            // Keep the old owner and its presentation intact until the new target passes reads.
            mutable.value = mutable.value.copy(detail = if (grant != null)
                "正在核对新增应用，已有授权保持不变" else "正在核对应用保护条件")
            val admission = ProtectionStartAdmission()
            authorityExecutor.execute {
                fun current() = synchronized(this) { pendingStart == request && revision == startingRevision }
                admission.run(
                isCurrent = ::current,
                preflight = {
                    // Read all shared mandatory storage before ordinary target rejection; corruption
                    // must still take the global fail-closed path, even if Shizuku is unavailable.
                    val authority = authorityStore(app)
                    authority.readSnapshot()
                    val selectedTargets = TargetSelectionStore(app).load()
                    val pending = AndroidRecoveryJournal.open(app).load().latest?.requiresReconcile == true
                    when {
                        pending -> "未添加该应用：已有恢复结果等待只读复检，原授权已保留"
                        !ProtectionStartupProbe.privilegeEvidence().shellReady -> "未添加该应用：Shizuku 尚未就绪，原授权已保留"
                        selected !in selectedTargets ->
                            "未添加该应用：应用已变化或不再符合恢复条件，其他应用保护不变"
                        else -> {
                            val packageProbe = TargetPackageProbe(app)
                            val packageRead = StartupTargetReader.read(listOf(StartupPackageInput(selected, true))) { input ->
                                StartupPackageEvidence(packageProbe.identity(input.identity),
                                    packageProbe.isRecoveryCandidate(input.identity))
                            }
                            when (packageRead) {
                                is StartupTargetReadResult.Unavailable -> "未添加该应用：身份读取暂时超时或繁忙，请重试；原授权已保留"
                                is StartupTargetReadResult.Complete -> if (packageRead.values.single().let {
                                        it.identity == selected && it.qualified }) null
                                    else "未添加该应用：应用已变化或不再符合恢复条件，其他应用保护不变"
                            }
                        }
                    }
                },
                admit = {
                    var cancel: (() -> Unit)? = null
                    val accepted = synchronized(this) {
                        if (!current()) false else if (mutable.value.busy || mutable.value.waitingForReconciliation) {
                            pendingStart = null
                            mutable.value = mutable.value.copy(detail = "未添加该应用：已有恢复正在处理，原授权已保留")
                            false
                        } else {
                            val priorSession = grant
                            priorSession?.writes?.block()
                            priorSession?.live?.set(false)
                            priorSession?.let { gate.revoke(it.id) }
                            grant = null
                            cancel = priorSession?.cancel
                            mutable.value = mutable.value.copy(enabled = false, busy = true, coreSessionStartedAtElapsedMillis = null,
                                detail = "正在保存应用选择")
                            true
                        }
                    }
                    cancel?.invoke()
                    accepted
                },
                commit = {
                    val authority = authorityStore(app)
                    authority.grantRecipientExplicit(selected, Build.FINGERPRINT, budgetClock(app))
                    val leases = authority.desiredLeases()
                    val snapshot = authority.readSnapshot()
                    val next = synchronized(this) {
                        if (!current()) null else {
                            pendingStart = null
                            mutable.value = configuredAutomaticProtectionState(mutable.value, snapshot,
                                if (leases.isEmpty()) "应用选择已保存；请在首页开启自动保护总开关" else "应用选择已保存，正在核对运行条件").copy(busy = leases.isNotEmpty())
                            if (leases.isEmpty()) null else AutomaticProtectionGrant(request, leases, authority).also {
                                check(gate.reserve(it.id))
                                grant = it
                                restoreBlocked = false
                            }
                        }
                    }
                    // An intervening stop has already queued its durable revocation after this grant.
                    if (next != null) Handler(Looper.getMainLooper()).post { launchService(app, next) }
                    else retireServiceWhenIdle(startingRevision)
                },
                reject = { detail ->
                    val rejected = synchronized(this) {
                        if (current()) {
                            pendingStart = null
                            mutable.value = mutable.value.copy(detail = detail)
                            true
                        } else false
                    }
                    // A rejected START may have superseded an old target-retirement callback.
                    // Finish only an idle existing service; never disturb a retained owner.
                    if (rejected) retireServiceWhenIdle(startingRevision)
                },
                failClosed = failure@ { commitMayHaveWritten ->
                    if (migrationPrepared) return@failure
                    val retirement = synchronized(this) {
                        // OFF/removal can invalidate a failed read after the helper's last check.
                        // Only an admitted, possibly durable commit may retire authority when stale.
                        if (!current()) commitMayHaveWritten to null else {
                            val previous = grant
                            previous?.live?.set(false)
                            previous?.let { gate.revoke(it.id) }
                            grant = null
                            mutable.value = mutable.value.copy(coreSessionStartedAtElapsedMillis = null)
                            restoreBlocked = true
                            true to previous?.cancel
                        }
                    }
                    if (!retirement.first) {
                        val idleRevision = synchronized(this) {
                            if (pendingStart == request) pendingStart = null
                            revision
                        }
                        retireServiceWhenIdle(idleRevision)
                        return@failure
                    }
                    runCatching { retirement.second?.invoke() }
                    // A failed commit/readback must not leave a partly saved policy2 grant available
                    // for automatic startup; the storage retirement marker also covers interrupted IO.
                    val retired = runCatching { authorityStore(app).suspendLease(null,
                        ProtectionSuspensionReason.SERVICE_UNAVAILABLE) }.getOrDefault(false)
                    synchronized(this) {
                        if (current()) {
                            restoreBlocked = true
                            pendingStart = null
                            mutable.value = masterOffAutomaticProtectionState(mutable.value,
                                detail = if (retired) "开启前核对或保存失败；全部自动保护已暂停，请逐项重新确认"
                                else "开启前核对或保存失败；全部自动保护已暂停，但暂停状态未能保存，请检查存储后重试关闭").copy(revocationSaveFailed = !retired)
                        }
                    }
                    retireServiceWhenIdle(startingRevision)
                },
                obsolete = {
                    val idleRevision = synchronized(this) {
                        if (pendingStart == request) pendingStart = null
                        revision
                    }
                    retireServiceWhenIdle(idleRevision)
                },
                )
            }
        }
        return true
    }

    private fun launchService(app: Context, next: AutomaticProtectionGrant) {
        // Stop may have happened after the IO commit but before this main-thread runnable.
        if (!authorized(next)) return
        try {
            app.startForegroundService(Intent(app, AutomaticProtectionService::class.java)
                .setAction(AutomaticProtectionService.ACTION_START)
                .putExtra(AutomaticProtectionService.EXTRA_SESSION, next.id))
        } catch (failure: RuntimeException) {
            ProtectionLifecycleDiagnostics.recordFailure(ProtectionLifecycleEventKind.RESTORE_LAUNCH_FAILED, failure)
            pause(next.id, "保护不可用：系统未允许前台服务启动，请在应用内重新确认")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val waiting = synchronized(this) { grant?.takeIf { it.id == next.id && it.cancel == null } }
            if (waiting != null) pause(next.id, "保护不可用：前台服务未就绪，请重新确认")
        }, 6_000)
    }

    /** Explicit master ON reuses exact saved tokens and acknowledges only startup exit history. */
    fun enableMaster(context: Context): Boolean {
        if (!localExecutionAllowed(context)) return false
        val app = context.applicationContext
        synchronized(this) {
            if (migrationPrepared || pendingStart != null || mutable.value.busy || mutable.value.revocationSaveFailed) return false
            // Capture the user's action before queued IO. Later stop/crash events are not acknowledged.
            val actionClock = runCatching { budgetClock(app) }.getOrNull() ?: return false
            val request = UUID.randomUUID().toString()
            val operationRevision = ++revision
            pendingStart = request
            val previous = grant
            previous?.writes?.block()
            previous?.live?.set(false)
            previous?.let { gate.revoke(it.id) }
            grant = null
            previous?.cancel?.invoke()
            mutable.value = mutable.value.copy(enabled = false, busy = true, coreSessionStartedAtElapsedMillis = null,
                detail = "正在核对总开关开启条件")
            authorityExecutor.execute {
                fun current() = synchronized(this) { revision == operationRevision && pendingStart == request }
                var precommitSnapshot: ProtectionAuthoritySnapshot? = null
                val result = runCatching {
                    val authority = authorityStore(app)
                    val expected = authority.readSnapshot()
                    precommitSnapshot = expected
                    check(expected?.storageBlocked != true)
                    check(validRuntimeFingerprint(Build.FINGERPRINT) && android.os.Process.myUid() / 100_000 == 0)
                    check(app.getSystemService(UserManager::class.java).isUserUnlocked)
                    // Unknown journal or selection cannot be acknowledged by the master switch.
                    AndroidRecoveryJournal.open(app).load()
                    val selected = TargetSelectionStore(app).load()
                    val candidates = expected?.consents().orEmpty().filter { it.id !in expected!!.invalidatedConsentIds }
                    candidates.forEach {
                        check(it.restartConsented() && it.acceptsRuntimeFingerprint(Build.FINGERPRINT) &&
                            it.identity in selected)
                    }
                    check(ProtectionStartupProbe(app, authority).historySince(actionClock.epoch) == StartupExitHistoryStatus.CLEAR)
                    check(current())
                    val packageProbe = TargetPackageProbe(app)
                    val packageInputs = candidates.map { StartupPackageInput(
                        it.identity.copy(signerSha256 = it.identity.signerSha256.toList()), it !is TargetConsent.Legacy) }
                    requireMasterTargetEvidence(StartupTargetReader.read(packageInputs) { input ->
                        StartupPackageEvidence(packageProbe.identity(input.identity),
                            !input.requiresQualification || packageProbe.isRecoveryCandidate(input.identity))
                    }, candidates)
                    check(current())
                    check(authority.enableMaster(expected, actionClock))
                    // Only the committed root can supply new generation leases; no grant routine runs.
                    val probe = ProtectionStartupProbe(app, authority).read()
                    check(current())
                    if (probe.leases.isNotEmpty() && !probe.status.mayCreateWaitingSession()) {
                        error(probe.status.description())
                    }
                    Triple(authority, authority.readSnapshot(), probe)
                }
                val targetReadUnavailable = result.exceptionOrNull() is StartupTargetReadUnavailableException
                val failedSnapshot = if (shouldSuspendFailedMasterEnable(result.exceptionOrNull()) && current()) runCatching {
                    val authority = authorityStore(app)
                    check(authority.suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE))
                    authority.readSnapshot()
                } else null
                var next: AutomaticProtectionGrant? = null
                synchronized(this) {
                    if (!current()) return@synchronized
                    pendingStart = null
                    if (result.isSuccess) {
                        val (authority, snapshot, probe) = result.getOrThrow()
                        restoreBlocked = false
                        mutable.value = configuredAutomaticProtectionState(mutable.value, snapshot,
                            if (probe.leases.isEmpty()) {
                                if (snapshot?.consents().isNullOrEmpty()) "自动保护总开关已开启；请在应用页选择应用"
                                else "总开关已开启；保留的应用选择需重新确认，当前没有可运行应用"
                            }
                            else probe.status.description()).copy(busy = probe.leases.isNotEmpty())
                        if (probe.leases.isNotEmpty()) {
                            next = AutomaticProtectionGrant(request, probe.leases, authority)
                            check(gate.reserve(request))
                            grant = next
                        }
                    } else if (targetReadUnavailable) {
                        // Precommit only: retain exact durable mode/choices without enable or suspend.
                        // The prior live owner was already retired; this is never executing protection.
                        mutable.value = configuredAutomaticProtectionState(mutable.value, precommitSnapshot,
                            "总开关开启核验未完成：应用身份读取暂时超时或繁忙，请重试；原保存状态与应用选择保留，当前未执行保护").copy(
                                enabled = false, busy = false, waitingForPrivilege = false, waitingForReconciliation = false)
                    } else {
                        restoreBlocked = true
                        mutable.value = masterOffAutomaticProtectionState(
                            if (failedSnapshot != null) configuredAutomaticProtectionReadState(mutable.value,
                                failedSnapshot, "应用选择保留") else mutable.value,
                            "未开启自动保护：安装身份、系统、停止历史或存储条件无法确认；应用选择保留").copy(busy = false,
                                revocationSaveFailed = failedSnapshot?.isFailure == true)
                    }
                }
                Handler(Looper.getMainLooper()).post { next?.let { launchService(app, it) } ?: retireServiceWhenIdle(operationRevision) }
            }
        }
        return true
    }

    fun stop(context: Context, reason: ProtectionLifecycleReason = ProtectionLifecycleReason.HOME_MASTER_OFF) {
        stop(context, reason, null)
    }

    private fun stop(context: Context, reason: ProtectionLifecycleReason, expectedSessionId: String?) {
        // Notification actions must still name the exact old session, not a replacement local owner.
        val expectedRevision = if (expectedSessionId == null) null else synchronized(this) {
            if (!matchesLegacyNotificationStop(expectedSessionId, pendingStart, grant?.id,
                    migrationPrepared || DaemonAppRuntime.remoteOwnershipClaimed(), migrationRetiredSessionId)) return
            revision
        }
        ProtectionLifecycleDiagnostics.record(ProtectionLifecycleEventKind.EXPLICIT_MASTER_OFF, reason)
        val initiallyLocal = localExecutionAllowed(context)
        if (!initiallyLocal && expectedSessionId == null) { DaemonAppRuntime.stop(context); return }
        // A stale notification must not even pause a replacement FCM host before the exact recheck.
        // The matched grant's cancellation below performs its identity-scoped hostEnded teardown.
        if (initiallyLocal && expectedSessionId == null) FcmProtectionRuntime.masterOff()
        val app = context.applicationContext
        var forward = false
        val cancel = synchronized(this) {
            when (legacyNegativeAdmission(!initiallyLocal || migrationPrepared || DaemonAppRuntime.remoteOwnershipClaimed(),
                expectedSessionId, expectedRevision, revision, pendingStart, grant?.id, migrationRetiredSessionId)) {
                LegacyNegativeAdmission.REMOTE -> { forward = true; return@synchronized null }
                LegacyNegativeAdmission.STALE -> return
                LegacyNegativeAdmission.LOCAL -> Unit
            }
            restoreBlocked = true
            pendingStart = null
            val current = grant
            current?.writes?.block()
            current?.live?.set(false)
            current?.let { gate.revoke(it.id) }
            grant = null
            val stoppedRevision = ++revision
            mutable.value = masterOffAutomaticProtectionState(mutable.value,
                "自动保护已关闭，应用选择保留；正在保存关闭状态；已派发操作可能完成")
            // Also revoke when the previous process left a saved grant but no live session exists.
            authorityExecutor.execute {
                val result = runCatching {
                    val authority = authorityStore(app)
                    check(authority.pauseMaster(budgetClock(app)))
                    authority.readSnapshot()
                }
                val success = result.isSuccess
                synchronized(this) {
                    if (revision == stoppedRevision) mutable.value = configuredAutomaticProtectionState(mutable.value,
                        result.getOrNull(), if (success) "自动保护已关闭，关闭状态已保存；应用选择保留；已派发操作可能完成"
                        else "本次保护已停止，但关闭状态未能保存；请检查存储后重试").let {
                            if (success) it.copy(revocationSaveFailed = false) else mutable.value.copy(revocationSaveFailed = true, detail = it.detail)
                        }
                }
                retireServiceWhenIdle(stoppedRevision)
            }
            current?.cancel
        }
        // Daemon admission can persist a negative intent; never call it under the old monitor.
        if (forward) { DaemonAppRuntime.stop(app); return }
        cancel?.invoke()
    }

    /** Foreground entry may resume already-explicit policy2, never upgrade legacy consent. */
    fun inspectSavedAuthorization(context: Context) {
        if (!localExecutionAllowed(context)) return
        val app = context.applicationContext
        synchronized(this) {
            if (migrationPrepared || grant != null || pendingStart != null) return
            val observedRevision = revision
            authorityExecutor.execute {
                val saved = runCatching { authorityStore(app).readSnapshot() }
                val desired = saved.map { it?.runnableConsents().orEmpty() }
                synchronized(this) {
                    if (revision != observedRevision || grant != null || pendingStart != null) return@synchronized
                    if (saved.isFailure) restoreBlocked = true
                    val detail = when {
                            desired.isFailure -> "保护不可用：已保存授权记录无法读取"
                            mutable.value.revocationSaveFailed -> mutable.value.detail
                            desired.getOrNull()?.isNotEmpty() == true -> "开机恢复授权已保存，正在核对运行条件"
                            saved.getOrNull()?.mode == ProtectionConsentMode.USER_PAUSED -> "自动保护总开关已关闭；应用选择保留"
                            saved.getOrNull()?.mode == ProtectionConsentMode.SUSPENDED -> "自动保护已暂停；应用选择保留，请检查后开启总开关"
                            saved.getOrNull()?.mode == ProtectionConsentMode.ACTIVE && saved.getOrNull()?.consents()?.isNotEmpty() == true ->
                                "总开关已开启；保留的应用选择需重新确认，当前没有可运行应用"
                            saved.getOrNull()?.mode == ProtectionConsentMode.ACTIVE -> "总开关已开启；尚未选择保护应用"
                            else -> mutable.value.detail
                        }
                    val projected = configuredAutomaticProtectionReadState(mutable.value, saved, detail)
                    mutable.value = if (restoreBlocked || mutable.value.revocationSaveFailed)
                        masterOffAutomaticProtectionState(projected, projected.detail) else projected
                }
                if (desired.getOrNull()?.isNotEmpty() == true && !synchronized(this) { restoreBlocked }) {
                    launchSavedFromTrigger(app)
                }
            }
        }
    }

    /** Allowed boot/package-replaced, foreground or validated tile-listening trigger; never Binder callbacks. */
    @Synchronized internal fun captureRecoveryRevision(): Long = revision

    internal fun launchSavedFromTrigger(context: Context, mayLaunch: () -> Boolean = { true },
        finished: () -> Unit = {}, expectedRevision: Long? = null) {
        if (!localExecutionAllowed(context)) { DaemonAppRuntime.restore(context); finished(); return }
        val app = context.applicationContext
        synchronized(this) {
            if (migrationPrepared || !recoveryRevisionMatches(expectedRevision, revision) || grant != null || pendingStart != null || restoreBlocked || mutable.value.revocationSaveFailed) {
                finished()
                return
            }
            val launchRevision = revision
            authorityExecutor.execute {
                val eligible = runCatching {
                    app.getSystemService(UserManager::class.java).isUserUnlocked &&
                        authorityStore(app).desiredLeases().isNotEmpty()
                }.getOrDefault(false)
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (!eligible || !mayLaunch()) return@post
                        synchronized(this) {
                            if (revision != launchRevision || !recoveryRevisionMatches(expectedRevision, revision) || grant != null || pendingStart != null || restoreBlocked || mutable.value.revocationSaveFailed) return@synchronized
                            ensureChannel(app)
                            ProtectionLifecycleDiagnostics.record(ProtectionLifecycleEventKind.RESTORE_START_REQUESTED)
                            app.startForegroundService(Intent(app, AutomaticProtectionService::class.java)
                                .setAction(AutomaticProtectionService.ACTION_RESTORE))
                        }
                    } catch (failure: RuntimeException) {
                        ProtectionLifecycleDiagnostics.recordFailure(ProtectionLifecycleEventKind.RESTORE_LAUNCH_FAILED, failure)
                        synchronized(this) {
                            if (revision == launchRevision) mutable.value = mutable.value.copy(enabled = false,
                                coreSessionStartedAtElapsedMillis = null,
                                busy = false, savedAuthorization = true, waitingForPrivilege = false, waitingForReconciliation = false,
                                detail = "系统未允许启动恢复，请打开 Guardian 后重试")
                        }
                    } finally { finished() }
                }
            }
        }
    }

    /** The service has already met the foreground deadline; IO can now rebuild a bounded grant. */
    internal fun restoreForService(context: Context, id: String, prepared: (AutomaticProtectionGrant?) -> Unit): Boolean {
        if (!localExecutionAllowed(context)) return false
        val app = context.applicationContext
        synchronized(this) {
            if (migrationPrepared || grant != null || pendingStart != null || restoreBlocked || mutable.value.revocationSaveFailed) return false
            pendingStart = id
            val restoringRevision = ++revision
            mutable.value = mutable.value.copy(enabled = false, busy = true, waitingForPrivilege = false, waitingForReconciliation = false,
                coreSessionStartedAtElapsedMillis = null,
                detail = "正在核对开机恢复授权与停止记录")
            authorityExecutor.execute {
                var next: AutomaticProtectionGrant? = null
                fun current() = synchronized(this) {
                    isCurrentStartupRestore(restoringRevision, revision, id, pendingStart,
                        restoreBlocked, mutable.value.revocationSaveFailed)
                }
                try {
                    val authority = authorityStore(app)
                    var probe = ProtectionStartupProbe(app, authority).read()
                    if (!current()) return@execute
                    if (probe.invalidTargets.isNotEmpty()) {
                        // FIFO + exact durable root protect newer OFF/reconfirmation; no grant method.
                        if (!current()) return@execute
                        check(authority.revokeConfirmedTargets(probe.leases, probe.invalidTargets))
                        probe = ProtectionStartupProbe(app, authority).read()
                    }
                    if (!current()) return@execute
                    val leases = probe.leases
                    if (leases.isNotEmpty() && probe.status.mayCreateWaitingSession() && leases.all { authority.verifyAuthorized(it) }) {
                        val snapshot = authority.readSnapshot()
                        synchronized(this) {
                            if (revision == restoringRevision && pendingStart == id && !restoreBlocked && !mutable.value.revocationSaveFailed) {
                                pendingStart = null
                                next = AutomaticProtectionGrant(id, leases, authority)
                                check(gate.reserve(id))
                                grant = next
                                mutable.value = configuredAutomaticProtectionState(mutable.value, snapshot,
                                    probe.status.description()).copy(busy = true)
                            }
                        }
                    } else {
                        ProtectionLifecycleDiagnostics.record(ProtectionLifecycleEventKind.RESTORE_REJECTED)
                        // Observed user stop/crash/unsafe state becomes durable, not an ephemeral UI flag.
                        val suspend = leases.isNotEmpty() && probe.status !in setOf(
                            StartupEligibilityStatus.OFF, StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
                            StartupEligibilityStatus.WAITING_UNLOCK)
                        // A target-removal/START/OFF queued during either probe owns the next state.
                        // Do not let an obsolete negative observation suspend its saved survivors.
                        if (!current()) return@execute
                        val saved = !suspend || runCatching {
                            authority.suspendLease(leases.firstOrNull(), ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                        }.getOrDefault(false)
                        val snapshot = authority.readSnapshot()
                        synchronized(this) {
                            if (revision == restoringRevision && pendingStart == id) {
                                pendingStart = null
                                mutable.value = configuredAutomaticProtectionState(mutable.value, snapshot,
                                    probe.status.description() + if (!saved) "；暂停状态未保存，请重试关闭" else "").copy(
                                        enabled = false, busy = false, waitingForPrivilege = false, waitingForReconciliation = false,
                                        revocationSaveFailed = !saved)
                                if (suspend) restoreBlocked = true
                            }
                        }
                    }
                } catch (failure: Exception) {
                    ProtectionLifecycleDiagnostics.recordFailure(ProtectionLifecycleEventKind.RESTORE_REJECTED, failure)
                    val saved = if (current()) runCatching {
                        authorityStore(app).suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                    }.getOrDefault(false) else true
                    synchronized(this) {
                        if (revision == restoringRevision && pendingStart == id) {
                            pendingStart = null
                            restoreBlocked = true
                            mutable.value = masterOffAutomaticProtectionState(mutable.value,
                                "启动恢复无法确认，未执行保护；应用选择保留，请检查后重新开启").copy(revocationSaveFailed = !saved)
                        }
                    }
                } finally {
                    Handler(Looper.getMainLooper()).post { prepared(next?.takeIf { authorized(it) }) }
                }
            }
        }
        return true
    }

    /** Normal OS/lifecycle teardown preserves policy2 desire, but never preserves live write authority. */
    internal fun detach(id: String) {
        val cancel = synchronized(this) {
            val pendingMatches = pendingStart == id
            if (pendingMatches) { pendingStart = null; revision++ }
            val current = grant?.takeIf { it.id == id }
            if (!pendingMatches && current == null) return
            if (current != null) {
                current.writes.block()
                current.live.set(false)
                gate.revoke(id)
                grant = null
                revision++
            }
            if (current != null || pendingMatches) {
                mutable.value = mutable.value.copy(enabled = false, busy = false, waitingForPrivilege = false, waitingForReconciliation = false,
                    coreSessionStartedAtElapsedMillis = null,
                    targets = mutable.value.targets.map { it.copy(enabled = false, busy = false, waitingForPrivilege = false, detail = "已保存授权等待系统恢复，尚未执行保护") },
                    detail = "服务已结束，已保存授权等待系统恢复；尚未执行保护")
            }
            current?.cancel
        }
        cancel?.invoke()
    }

    @Synchronized internal fun waiting(current: AutomaticProtectionGrant) {
        if (!authorized(current)) return
        current.writes.block()
        mutable.value = mutable.value.copy(enabled = false, busy = false, savedAuthorization = true,
            targets = mutable.value.targets.map { it.copy(enabled = false, busy = false, waitingForPrivilege = true, detail = "等待 Shizuku，当前未执行保护") },
            waitingForPrivilege = true, waitingForReconciliation = false, detail = "等待 Shizuku 启动并授权，当前未执行保护")
    }

    @Synchronized internal fun blockWrites(current: AutomaticProtectionGrant) {
        if (authorized(current)) current.writes.block()
    }

    @Synchronized internal fun waitForNextCheck(current: AutomaticProtectionGrant, detail: String) {
        if (!authorized(current)) return
        mutable.value = waitForNextAutomaticCheck(current.writes, mutable.value, detail)
    }

    @Synchronized internal fun allowWrites(current: AutomaticProtectionGrant) {
        if (authorized(current)) current.writes.open()
    }

    /** Service core entry, not claim/master ON or readiness polling. Repeated entry is idempotent. */
    @Synchronized internal fun started(current: AutomaticProtectionGrant,
        elapsedMillis: Long = SystemClock.elapsedRealtime()) {
        if (!authorized(current)) return
        mutable.value = startedAutomaticProtectionSessionState(mutable.value, current, grant,
            sessionActive = true, elapsedMillis = elapsedMillis)
    }

    @Synchronized internal fun readOnly(current: AutomaticProtectionGrant, detail: String, busy: Boolean = false) {
        if (!authorized(current)) return
        current.writes.block()
        mutable.value = mutable.value.copy(enabled = false, busy = busy, savedAuthorization = true,
            waitingForPrivilege = false, waitingForReconciliation = true, detail = detail,
            targets = mutable.value.targets.map { it.copy(enabled = false, busy = busy,
                waitingForPrivilege = false, detail = detail) })
    }

    @Synchronized internal fun attachReconciliationRefresh(current: AutomaticProtectionGrant, refresh: (Boolean) -> Unit) {
        if (authorized(current)) current.reconciliationRefresh = refresh
    }

    /** Explicit existing UI action only; foreground/resume refresh never replenishes the read-only round. */
    fun requestReconciliationRefresh(): Boolean {
        val refresh = synchronized(this) {
            grant?.takeIf { authorized(it) && mutable.value.waitingForReconciliation }?.reconciliationRefresh
        } ?: return false
        refresh(true)
        return true
    }

    /** A completed manual journal operation wakes the owner but cannot replenish its bounded round. */
    fun notifyRecoveryJournalChanged() {
        val refresh = synchronized(this) {
            grant?.takeIf { authorized(it) && mutable.value.waitingForReconciliation }?.reconciliationRefresh
        }
        refresh?.invoke(false)
    }

    internal fun consentsAuthorized(current: AutomaticProtectionGrant): Boolean =
        authorized(current) && current.store.verifyAllAuthorized(current.leases) && authorized(current)

    /** Repeat all restore prerequisites after a new connection; this consumes no attempt. */
    internal fun startupStatus(current: AutomaticProtectionGrant, context: Context): StartupEligibilityStatus =
        startupProbe(current, context).status

    internal fun startupProbe(current: AutomaticProtectionGrant, context: Context): StartupProbeResult =
        if (!authorized(current)) StartupProbeResult(emptyList(), StartupEligibilityStatus.OFF)
        else ProtectionStartupProbe(context, current.store).read(current.leases)

    private fun authorityStore(context: Context): ProtectionConsentStore = store ?: ProtectionConsentStore(
        AtomicProtectionAuthorityStorage(context)).also { store = it }

    internal fun budgetClock(context: Context) = BudgetClock(
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong(),
        SystemClock.elapsedRealtime(), System.currentTimeMillis())

    /** IO / guarded RPC only; a cached token or admission result is not write authorization. */
    internal fun durablyAuthorized(current: AutomaticProtectionGrant, lease: TargetConsentLease,
        writeTicket: Long? = current.writes.ticket()): Boolean =
        authorized(current) && current.leases.contains(lease) && current.store.verifyAuthorized(lease) &&
            current.writes.permits(writeTicket) && ProtectionStartupProbe.privilegeEvidence().shellReady &&
            authorized(current) && current.writes.permits(writeTicket)

    internal fun checkRecoveryAuthorization(current: AutomaticProtectionGrant, lease: TargetConsentLease): AutomaticCheckDecision =
        if (!authorized(current) || lease !in current.leases || current.writes.ticket() == null)
            AutomaticCheckDecision.Pause("自动恢复写入尚未就绪")
        else current.store.checkRecoveryAuthorization(lease)

    /** Presentation only; notification visibility does not authorize or revoke automatic recovery. */
    fun notificationsAvailable(context: Context): Boolean =
        notificationVisibility(context) == ProtectionNotificationVisibility.ALLOWED

    fun notificationVisibility(context: Context): ProtectionNotificationVisibility = readProtectionNotificationVisibility {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@readProtectionNotificationVisibility false
        if (!manager.areNotificationsEnabled()) return@readProtectionNotificationVisibility false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return@readProtectionNotificationVisibility true
        channel.importance != NotificationManager.IMPORTANCE_NONE &&
            (channel.group == null || manager.getNotificationChannelGroup(channel.group)?.isBlocked != true)
    }

    /** Opens the standard channel page. The user decides whether to hide its notification. */
    fun openNotificationSettings(context: Context): Boolean = runCatching {
        ensureChannel(context)
        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用自动保护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显式开启的停止标记检测与恢复；可随时停止"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    @Synchronized internal fun claim(id: String, retireIfIdle: () -> Unit,
        cancel: () -> Unit): AutomaticProtectionGrant? {
        if (migrationPrepared) return null
        val current = grant?.takeIf { it.id == id && it.live.get() } ?: return null
        if (!gate.claim(id)) return null
        current.cancel = cancel
        serviceRetirement.attach(retireIfIdle)
        return current
    }

    @Synchronized internal fun authorized(current: AutomaticProtectionGrant): Boolean =
        !migrationPrepared && current.live.get() && grant === current && gate.active(current.id)

    @Synchronized internal fun hasSession(): Boolean = grant != null || pendingStart != null

    @Synchronized internal fun detachServiceRetirement(callback: () -> Unit) {
        serviceRetirement.detach(callback)
    }

    private fun retireServiceWhenIdle(expectedRevision: Long) {
        Handler(Looper.getMainLooper()).post {
            synchronized(this) { serviceRetirement.finish(expectedRevision, revision, hasSession()) }
        }
    }

    /** Tile presentation requires a live, claimed service and actual detection readiness. */
    @Synchronized internal fun isActuallyProtecting(): Boolean =
        grant?.let { it.cancel != null && authorized(it) } == true && mutable.value.enabled &&
            !mutable.value.waitingForPrivilege && !mutable.value.waitingForReconciliation && !mutable.value.revocationSaveFailed

    @Synchronized internal fun update(id: String, detail: String, busy: Boolean = false,
        checkedAtMillis: Long? = null, outcome: String? = null,
        identity: SelectedTargetIdentity? = null) {
        if (grant?.id != id || !gate.active(id)) return
        mutable.value = updateAutomaticProtectionState(mutable.value, detail, busy, checkedAtMillis, outcome, identity)
    }

    internal fun pause(id: String, detail: String, outcome: String? = null) {
        val pausedDetail = "全部自动保护已暂停：$detail"
        val cancel = synchronized(this) {
            if (migrationPrepared) return
            val current = grant?.takeIf { it.id == id } ?: return
            ProtectionLifecycleDiagnostics.recordPause(detail)
            restoreBlocked = true
            current.writes.block()
            current.live.set(false)
            gate.revoke(id)
            grant = null
            val pausedRevision = ++revision
            mutable.value = mutable.value.copy(masterEnabled = false, enabled = false, busy = false, detail = pausedDetail, savedAuthorization = false, waitingForPrivilege = false, waitingForReconciliation = false,
                coreSessionStartedAtElapsedMillis = null,
                targets = mutable.value.targets.map { it.copy(savedAuthorization = false, enabled = false, busy = false, waitingForPrivilege = false, detail = pausedDetail) },
                lastOutcome = outcome ?: mutable.value.lastOutcome)
            authorityExecutor.execute {
                val saved = runCatching { current.store.suspendLease(current.leases.firstOrNull(),
                    ProtectionSuspensionReason.SERVICE_UNAVAILABLE) }.getOrDefault(false)
                synchronized(this) {
                    if (!saved && revision == pausedRevision) mutable.value = mutable.value.copy(
                        revocationSaveFailed = true, detail = "$pausedDetail；暂停状态未能保存，请重试关闭")
                }
                retireServiceWhenIdle(pausedRevision)
            }
            current.cancel
        }
        cancel?.invoke()
    }

    /** A later authorized lifecycle event must re-probe the unchanged durable root before recovery. */
    internal fun interrupt(id: String) {
        val interrupted = synchronized(this) {
            val current = grant?.takeIf { it.id == id } ?: return
            mutable.value = interruptAutomaticProtectionGrant(current, gate, mutable.value,
                "保护服务意外中断，开启意图与应用选择已保留；等待下次启动事件完整核验，当前未执行保护")
            grant = null
            ++revision to current.cancel
        }
        try {
            interrupted.second?.invoke()
        } finally {
            retireServiceWhenIdle(interrupted.first)
        }
    }

    internal fun stopSession(context: Context, id: String) {
        stop(context, ProtectionLifecycleReason.NOTIFICATION_STOP, id)
    }

    /** Stop old RPC authority synchronously; callback runs only after durable exact-target removal. */
    fun stopTarget(context: Context, identity: SelectedTargetIdentity, onRetired: () -> Unit = {}) {
        if (!retireTargets(context, listOf(identity), onRetired = onRetired)) {
            // Submission is not the daemon's durable removal ACK; do not run onRetired here.
            DaemonAppRuntime.disableTarget(context, identity)
        }
    }

    /** Only a shared-safe fresh probe can initiate automatic removal of the exact observed leases. */
    internal fun retireConfirmedTargets(context: Context, current: AutomaticProtectionGrant, probe: StartupProbeResult,
        prepared: (Boolean) -> Unit): Boolean =
        retireTargets(context, probe.invalidTargets.map { it.consent.identity }, current, probe, prepared, {})

    private fun retireTargets(context: Context, identities: List<SelectedTargetIdentity>,
        expected: AutomaticProtectionGrant? = null, probe: StartupProbeResult? = null,
        prepared: ((Boolean) -> Unit)? = null,
        onRetired: () -> Unit): Boolean {
        if (!localExecutionAllowed(context)) return false
        val app = context.applicationContext
        val cancel = synchronized(this) {
            if (migrationPrepared || DaemonAppRuntime.remoteOwnershipClaimed()) return false
            if (expected != null && (!authorized(expected) || pendingStart != null || probe == null ||
                probe.leases != expected.leases || probe.invalidTargets.isEmpty() ||
                !expected.leases.containsAll(probe.invalidTargets))) return false
            val current = grant
            current?.writes?.block()
            current?.live?.set(false)
            current?.let { gate.revoke(it.id) }
            grant = null
            val request = UUID.randomUUID().toString()
            pendingStart = request
            val removalRevision = ++revision
            mutable.value = mutable.value.copy(enabled = false, busy = true, waitingForReconciliation = false,
                coreSessionStartedAtElapsedMillis = null,
                targets = mutable.value.targets.map { it.copy(enabled = false, busy = false) },
                detail = if (expected == null) "正在保存该应用的关闭状态" else "正在隔离已确认失效的应用，保留其他授权")
            authorityExecutor.execute {
                val result = runCatching {
                    val authority = authorityStore(app)
                    if (expected == null) identities.forEach { check(authority.revokeTarget(it)) }
                    else check(authority.revokeConfirmedTargets(expected.leases, requireNotNull(probe).invalidTargets))
                    authority.desiredLeases()
                }
                val snapshot = runCatching { authorityStore(app).readSnapshot() }
                val currentOperation = synchronized(this) {
                    if (revision != removalRevision || pendingStart != request) false else {
                        pendingStart = null
                        restoreBlocked = result.isFailure || snapshot.isFailure || result.getOrNull().isNullOrEmpty()
                        val leases = result.getOrNull().orEmpty()
                        val detail = if (result.isSuccess) {
                                if (expected == null) "该应用授权已撤销；其他应用仍保留原授权"
                                else if (leases.isEmpty()) "已保留失效应用选择；请取消后重新确认，当前没有可运行应用"
                                else "已确认失效的应用已停用；其他应用仍保留原授权"
                            }
                                else "关闭状态未能保存，保护已停止，请重试关闭"
                        val projected = configuredAutomaticProtectionReadState(mutable.value, snapshot, detail)
                        mutable.value = if (result.isFailure) masterOffAutomaticProtectionState(projected,
                            if (snapshot.isFailure) projected.detail else detail).copy(revocationSaveFailed = true)
                        else projected.copy(enabled = false, busy = false, waitingForPrivilege = false,
                            waitingForReconciliation = false, revocationSaveFailed = snapshot.isFailure)
                        true
                    }
                }
                if (currentOperation) Handler(Looper.getMainLooper()).post {
                    if (expected != null) {
                        if (synchronized(this) { revision == removalRevision }) {
                            // The original service retains its FGS while IO runs. It may rebuild from
                            // saved survivors, but must never start a new background service here.
                            prepared?.invoke(result.isSuccess && result.getOrThrow().isNotEmpty())
                        }
                        return@post
                    }
                    // Cancellation may already have retired foreground ownership while pendingStart
                    // prevented stopSelf. Complete that handoff without starting any new service.
                    if (result.isFailure || result.getOrThrow().isEmpty()) synchronized(this) {
                        serviceRetirement.finish(removalRevision, revision, hasSession())
                    }
                    if (!synchronized(this) { revision == removalRevision }) return@post
                    if (result.isSuccess) {
                        onRetired()
                        if (result.getOrThrow().isNotEmpty()) launchSavedFromTrigger(app)
                    }
                }
            }
            current?.cancel
        }
        cancel?.invoke()
        return true
    }

}
