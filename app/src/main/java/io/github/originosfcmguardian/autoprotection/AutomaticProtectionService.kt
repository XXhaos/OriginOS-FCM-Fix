package io.github.originosfcmguardian.autoprotection

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.originosfcmguardian.MainActivity
import io.github.originosfcmguardian.R
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.StoppedRecoveryController
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Main-thread, same-service handoff only; a newer owner invalidates every old completion. */
internal class TargetRetirementForegroundHandoff {
    private var owner: String? = null
    val pending: Boolean get() = owner != null
    fun begin(id: String) { check(owner == null); owner = id }
    fun holds(id: String): Boolean = owner == id
    fun mayStopService(runtimeHasSession: Boolean): Boolean = !pending && !runtimeHasSession
    fun clear() { owner = null }
    fun finish(id: String): Boolean {
        if (owner != id) return false
        owner = null
        return true
    }
}

/** Explicit restart consent may restore the FGS; missing privilege has no periodic retries. */
class AutomaticProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeId: String? = null
    private var activeJob: Job? = null
    private var activeController: StoppedRecoveryController? = null
    private var latestStartId = 0
    private var stopPendingIntent: PendingIntent? = null
    private var destroyed = false
    private val targetRetirement = TargetRetirementForegroundHandoff()
    private val idleRetirement: () -> Unit = {
        // Runtime holds its revision/idle lock, and this callback runs on main. No resurrection:
        // only the already-existing, non-foreground service lifetime can be stopped here.
        if (!destroyed && activeId == null) stopSelf(latestStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_SESSION)?.let { id ->
                AutomaticProtectionRuntime.stopSession(this, id)
                if (activeId == id && !AutomaticProtectionRuntime.hasSession()) retire(id)
            }
            if (activeId == null && targetRetirement.mayStopService(AutomaticProtectionRuntime.hasSession())) stopSelf(startId)
            return if (AutomaticProtectionRuntime.hasSession() || activeId != null || targetRetirement.pending) START_STICKY else START_NOT_STICKY
        }
        if (intent == null || intent.action == ACTION_RESTORE) {
            return restoreSavedSession(startId)
        }
        val id = intent.takeIf { it.action == ACTION_START }?.getStringExtra(EXTRA_SESSION)
        if (id == null || !startSession(id)) {
            if (activeId == null && targetRetirement.mayStopService(AutomaticProtectionRuntime.hasSession())) stopSelf(startId)
            return if (AutomaticProtectionRuntime.hasSession() || activeId != null || targetRetirement.pending) START_STICKY else START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun restoreSavedSession(startId: Int): Int {
        if (activeId != null || targetRetirement.pending) return START_STICKY
        targetRetirement.clear()
        val restoreId = UUID.randomUUID().toString()
        activeId = restoreId
        try {
            enterForeground(restoreId, "正在核对启动授权，尚未执行保护")
        } catch (_: RuntimeException) {
            activeId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val accepted = AutomaticProtectionRuntime.restoreForService(applicationContext, restoreId) { ready ->
            if (activeId != restoreId) {
                AutomaticProtectionRuntime.detach(restoreId)
            } else if (ready == null || !startSession(restoreId)) retire(restoreId)
        }
        if (!accepted) retire(restoreId)
        return if (accepted) START_STICKY else START_NOT_STICKY
    }

    private fun enterForeground(id: String, detail: String) {
        val initial = notification(id, detail)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, initial)
    }

    private fun startSession(id: String): Boolean {
        var job: Job? = null
        var controller: StoppedRecoveryController? = null
        val grant = id.let { value -> AutomaticProtectionRuntime.claim(value, retireIfIdle = idleRetirement) {
            // All write authority is already synchronously revoked before this callback.
            FcmProtectionRuntime.hostEnded(value)
            cancelAutomaticSession(
                cancelRecovery = { controller?.cancel() },
                cancelPolling = { job?.cancel() },
                enqueueRetirement = { Handler(Looper.getMainLooper()).post { retire(value) } },
            )
        } }
        if (grant == null) {
            return false
        }
        targetRetirement.clear()
        activeJob?.cancel()
        activeController?.cancel()
        activeId = grant.id
        stopPendingIntent?.cancel()
        try {
            enterForeground(grant.id, "正在核对运行条件")
        } catch (_: RuntimeException) {
            AutomaticProtectionRuntime.pause(grant.id, "保护不可用：前台服务启动失败，请重新确认")
            retire(grant.id)
            return false
        }
        FcmProtectionRuntime.attachHost(applicationContext, grant)
        // Coroutine construction is lazy so a revocation cannot cancel before its Job is assigned.
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            collect(grant) { controller = it; activeController = it }
        }
        job.invokeOnCompletion {
            // A lazy job cancelled before its body executes has no finally block to retire it.
            Handler(Looper.getMainLooper()).post { retire(grant.id) }
        }
        activeJob = job
        if (AutomaticProtectionRuntime.authorized(grant)) job.start() else {
            job.cancel()
            retire(grant.id)
        }
        return true
    }

    private suspend fun collect(grant: AutomaticProtectionGrant, publishController: (StoppedRecoveryController) -> Unit) {
        var controller: StoppedRecoveryController? = null
        var selectionListener: AutoCloseable? = null
        val connectionEvents = Channel<Unit>(Channel.CONFLATED)
        val connectionGeneration = ProtectionConnectionGeneration()
        val explicitRefresh = AtomicLong(0)
        val reconciliationConnection = AtomicLong(0)
        val reconciliationBudget = PendingReconciliationBudget()
        fun blockHostWrites() {
            AutomaticProtectionRuntime.blockWrites(grant)
        }
        fun waitForHost() {
            AutomaticProtectionRuntime.waiting(grant)
        }
        fun hostReadOnly(detail: String, busy: Boolean = false) {
            AutomaticProtectionRuntime.readOnly(grant, detail, busy)
        }
        fun waitForHostNextCheck(detail: String) {
            AutomaticProtectionRuntime.waitForNextCheck(grant, detail)
        }
        fun matchesGmsConnection(generation: Long): Boolean =
            connectionGeneration.matches(generation)
        fun connectedGmsHost(generation: Long): Boolean =
            connectionGeneration.matches(generation) && shellReady()
        val binderDead = Shizuku.OnBinderDeadListener {
            FcmProtectionRuntime.privilegeLost(grant.id)
            connectionGeneration.changed()
            reconciliationConnection.incrementAndGet()
            blockHostWrites()
            controller?.cancel() // Invalidate any in-flight RPC generation immediately.
            waitForHost()
            connectionEvents.trySend(Unit)
        }
        val binderReceived = Shizuku.OnBinderReceivedListener {
            FcmProtectionRuntime.privilegeLost(grant.id)
            connectionGeneration.changed()
            reconciliationConnection.incrementAndGet()
            blockHostWrites()
            controller?.cancel()
            connectionEvents.trySend(Unit)
        }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result != PackageManager.PERMISSION_GRANTED) FcmProtectionRuntime.securityLost(grant.id)
            if (result != PackageManager.PERMISSION_GRANTED) AutomaticProtectionRuntime.pause(grant.id, "保护不可用：Shizuku 授权已撤销，请重新确认")
            else connectionEvents.trySend(Unit)
        }
        var listenersRegistered = false
        try {
            val selection = TargetSelectionStore(applicationContext)
            selectionListener = selection.registerListener {
                connectionGeneration.changed()
                blockHostWrites()
                controller?.cancel()
                val selected = runCatching { selection.load() }
                if (selected.isFailure) AutomaticProtectionRuntime.pause(grant.id, "保护不可用：应用选择记录无法读取，请重新确认")
                else connectionEvents.trySend(Unit)
            }
            Shizuku.addBinderDeadListener(binderDead)
            listenersRegistered = true
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addRequestPermissionResultListener(permissionResult)
            val recovery = withContext(Dispatchers.IO) { StoppedRecoveryController(applicationContext) }
            controller = recovery
            publishController(recovery)
            AutomaticProtectionRuntime.attachReconciliationRefresh(grant) { explicit ->
                if (explicit) {
                    explicitRefresh.incrementAndGet()
                    recovery.cancel()
                }
                connectionEvents.trySend(Unit)
            }
            val probe = TargetPackageProbe(applicationContext)
            var revalidateStartup = true
            var validatedConnection = -1L
            var targetCursor = 0
            var expectedPending: StoppedRecoveryRecord? = null
            suspend fun waitForTargetRead() {
                blockHostWrites()
                recovery.cancel()
                val detail = StartupEligibilityStatus.WAITING_TARGET_READ.description()
                waitForHostNextCheck(detail)
                publishNotification(grant, detail)
                revalidateStartup = true
                delay(POLL_MILLIS)
            }
            sessionLoop@ while (AutomaticProtectionRuntime.authorized(grant)) {
                val iterationConnection = connectionGeneration.current()
                val privilege = ProtectionStartupProbe.privilegeEvidence()
                if (privilege.binderAlive != true) {
                    waitForHost()
                    publishNotification(grant, "等待 Shizuku，尚未执行保护")
                    revalidateStartup = true
                    // No timer, target polling, network activation or repeated FGS start while absent.
                    connectionEvents.receive()
                    continue
                }
                if (!privilege.shellReady) {
                    AutomaticProtectionRuntime.pause(grant.id, "Shizuku 未授权或身份不支持，请重新确认")
                    break
                }
                val journal = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                if (!AutomaticProtectionRuntime.authorized(grant)) break
                if (expectedPending == null) expectedPending = journal.getOrNull()?.takeIf { it.requiresReconcile }
                if (!matchesGmsConnection(iterationConnection)) continue
                if (journal.isFailure) {
                    AutomaticProtectionRuntime.pause(grant.id, "恢复记录无法读取，禁止自动恢复")
                    break
                }
                val expected = expectedPending
                if (expected != null) {
                    val observed = journal.getOrNull()
                    if (!sameRecoveryRequest(expected, observed) ||
                        (observed?.requiresReconcile != true && !resolvedPendingRecovery(expected, observed))) {
                        AutomaticProtectionRuntime.pause(grant.id, "待复检记录丢失、变化或结论不安全")
                        break
                    }
                    if (resolvedPendingRecovery(expected, observed)) {
                        expectedPending = null
                        revalidateStartup = true
                    }
                }
                val pending = journal.getOrNull()?.takeIf { it.requiresReconcile }
                if (pending != null) {
                    if (expectedPending == null) expectedPending = pending
                    blockHostWrites()
                    recovery.cancel() // Old RPC callbacks cannot regain authority when writes later resume.
                    val status = withContext(Dispatchers.IO) {
                        AutomaticProtectionRuntime.startupStatus(grant, applicationContext)
                    }
                    if (!AutomaticProtectionRuntime.authorized(grant)) break
                    if (!matchesGmsConnection(iterationConnection)) continue
                    if (status == StartupEligibilityStatus.WAITING_PRIVILEGE) continue
                    if (status == StartupEligibilityStatus.WAITING_TARGET_READ) {
                        waitForTargetRead()
                        continue@sessionLoop // PM uncertainty consumes no reconciliation budget or RPC.
                    }
                    if (!status.mayCreateWaitingSession()) {
                        AutomaticProtectionRuntime.pause(grant.id, status.description())
                        break
                    }
                    val refresh = explicitRefresh.get()
                    val backoff = reconciliationBudget.nextDelay(pending.requestId, reconciliationConnection.get(), refresh)
                    val detail = if (backoff == null) "授权已保留，复检仍未确认；全部自动恢复写入已阻止，等待连接变化或主动只读复检"
                        else "授权已保留，正在有界只读复检；全部自动恢复写入已阻止"
                    hostReadOnly(detail)
                    publishNotification(grant, detail)
                    revalidateStartup = true
                    if (backoff == null) {
                        connectionEvents.receive() // No timer/repeated service start after three attempts.
                        continue
                    }
                    if (backoff > 0) delay(backoff)
                    if (!AutomaticProtectionRuntime.authorized(grant)) break
                    if (!matchesGmsConnection(iterationConnection) || explicitRefresh.get() != refresh) continue
                    val beforeRead = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                    if (beforeRead.isFailure) {
                        AutomaticProtectionRuntime.pause(grant.id, "复检前恢复记录无法读取")
                        break
                    }
                    if (beforeRead.getOrNull() != pending) {
                        if (!sameRecoveryRequest(pending, beforeRead.getOrNull()) ||
                            (beforeRead.getOrNull()?.requiresReconcile != true && !resolvedPendingRecovery(pending, beforeRead.getOrNull()))) {
                            AutomaticProtectionRuntime.pause(grant.id, "待复检记录丢失、变化或结论不安全")
                            break
                        }
                        continue
                    }
                    hostReadOnly(detail, busy = true)
                    val result = withContext(Dispatchers.IO) {
                        recovery.reconcile(authorized = { connectionGeneration.authorize(iterationConnection) {
                            explicitRefresh.get() == refresh && AutomaticProtectionRuntime.consentsAuthorized(grant)
                        } })
                    }
                    if (!AutomaticProtectionRuntime.authorized(grant)) break
                    if (!matchesGmsConnection(iterationConnection) || explicitRefresh.get() != refresh) continue
                    val afterRead = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                    if (afterRead.isFailure) {
                        AutomaticProtectionRuntime.pause(grant.id, "复检后恢复记录无法读取")
                        break
                    }
                    if (!sameRecoveryRequest(pending, afterRead.getOrNull()) ||
                        (afterRead.getOrNull()?.requiresReconcile != true && !resolvedPendingRecovery(pending, afterRead.getOrNull()))) {
                        AutomaticProtectionRuntime.pause(grant.id, "只读复检记录丢失、变化或结论不安全", result.outcome.name)
                        break
                    }
                    // Only the next iteration's fresh startup + global journal check can reopen writes.
                    hostReadOnly(if (afterRead.getOrNull()?.requiresReconcile == true)
                        "授权已保留，只读复检仍未确认；全部自动恢复写入已阻止"
                        else "只读复检已结束，将重新核对运行条件；之前命令是否生效和消息接收尚未验证")
                    continue
                }
                if (revalidateStartup || validatedConnection != iterationConnection) {
                    val startup = withContext(Dispatchers.IO) {
                        AutomaticProtectionRuntime.startupProbe(grant, applicationContext)
                    }
                    if (!AutomaticProtectionRuntime.authorized(grant)) break
                    if (!matchesGmsConnection(iterationConnection)) continue
                    if (startup.invalidTargets.isNotEmpty()) {
                        if (retireInvalidTargets(grant, startup)) break
                        delay(POLL_MILLIS) // An explicit add/OFF may supersede this observation; never spin.
                        continue
                    }
                    val status = startup.status
                    if (status == StartupEligibilityStatus.WAITING_TARGET_READ) {
                        waitForTargetRead()
                        continue@sessionLoop
                    }
                    if (status == StartupEligibilityStatus.WAITING_PRIVILEGE) {
                        revalidateStartup = true
                        continue
                    }
                    if (!status.mayCreateWaitingSession()) {
                        AutomaticProtectionRuntime.pause(grant.id, status.description())
                        break
                    }
                    if (status == StartupEligibilityStatus.WAITING_RECONCILIATION) continue
                    revalidateStartup = false
                    validatedConnection = iterationConnection
                }
                AutomaticProtectionRuntime.allowWrites(grant)
                AutomaticProtectionRuntime.update(grant.id, "检测中")
                FcmProtectionRuntime.hostEligible(grant.id)
                publishNotification(grant, "检测中")
                var retainedWaitDetail = false
                var targetReads = emptyList<AutomaticTargetObservation>()
                // Only PM observations overlap. Decisions, authority, journal and recovery stay serial.
                for (offset in grant.leases.indices) {
                if (offset % BoundedAutomaticTargetRead.CHUNK_SIZE == 0) {
                    val chunk = (offset until minOf(offset + BoundedAutomaticTargetRead.CHUNK_SIZE, grant.leases.size))
                        .map { grant.leases[(targetCursor + it) % grant.leases.size] }
                    targetReads = AutomaticTargetReader.readChunk(chunk) { target ->
                        val expected = target.consent.identity
                        val qualified = target.consent is TargetConsent.Legacy || probe.isRecoveryCandidate(expected)
                        AutomaticTargetObservation(qualified,
                            if (qualified) probe.stoppedSnapshot(expected) else null)
                    }
                    if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                    if (!connectedGmsHost(iterationConnection)) {
                        revalidateStartup = true
                        continue@sessionLoop // OFF, replacement and reconnect discard the whole chunk.
                    }
                }
                val lease = grant.leases[(targetCursor + offset) % grant.leases.size]
                val identity = lease.consent.identity
                val observation = targetReads[offset % BoundedAutomaticTargetRead.CHUNK_SIZE]
                var decisionPending: StoppedRecoveryRecord? = null
                val decision = withContext(Dispatchers.IO) {
                    val prerequisites = Build.FINGERPRINT == VERIFIED_AUTOSTART_FINGERPRINT && android.os.Process.myUid() / 100_000 == 0 &&
                        runCatching { selection.load().contains(identity) && shellReady() &&
                            (observation.qualified == true || observation.locallyUnavailable) }.getOrDefault(false)
                    val receipt = runCatching { recovery.loadLatest() }
                    decisionPending = receipt.getOrNull()?.takeIf { it.requiresReconcile }
                    val snapshot = if (prerequisites && receipt.isSuccess) observation.stopped else null
                    val checkedAt = snapshot?.checkedAtEpochMillis
                    if (checkedAt != null) AutomaticProtectionRuntime.update(grant.id, "检测中", checkedAtMillis = checkedAt, identity = identity)
                    automaticCheckDecision(
                        AutomaticProtectionRuntime.durablyAuthorized(grant, lease), prerequisites && receipt.isSuccess,
                        receipt.getOrNull()?.requiresReconcile == true, recovery.isBusy(),
                        snapshot?.status ?: TargetStoppedStatus.UNKNOWN,
                    )
                }
                if (expectedPending == null) expectedPending = decisionPending
                if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                if (!connectedGmsHost(iterationConnection)) {
                    revalidateStartup = true
                    val receipt = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                    if (expectedPending == null) expectedPending = receipt.getOrNull()?.takeIf { it.requiresReconcile }
                    if (receipt.isFailure || receipt.getOrNull()?.requiresReconcile == true) {
                        if (receipt.isFailure) {
                            AutomaticProtectionRuntime.pause(grant.id, "连接变化后恢复记录无法读取")
                            break@sessionLoop
                        }
                        blockHostWrites()
                        recovery.cancel()
                    }
                    continue@sessionLoop // Discard observations from the previous connection, not a durable stop.
                }
                if (maySkipLocallyUnavailableTarget(observation, decision)) {
                    // Only our local timeout/no-capacity can skip one target after fresh shared gates.
                    // A real UNKNOWN/qualification failure still follows the existing Recheck/Pause path.
                    val detail = "应用读取暂时超时或繁忙，本轮跳过；授权已保留，等待下次检测"
                    AutomaticProtectionRuntime.update(grant.id, detail, identity = identity)
                    publishNotification(grant, detail)
                    retainedWaitDetail = true
                    continue // Finite round; no same-target immediate retry or invented stopped state.
                }
                when (decision) {
                    is AutomaticCheckDecision.Recheck -> {
                        blockHostWrites()
                        val startup = withContext(Dispatchers.IO) {
                            AutomaticProtectionRuntime.startupProbe(grant, applicationContext)
                        }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (startup.invalidTargets.isNotEmpty()) {
                            if (retireInvalidTargets(grant, startup)) break@sessionLoop
                            revalidateStartup = true
                            delay(POLL_MILLIS)
                            continue@sessionLoop
                        }
                        if (startup.status == StartupEligibilityStatus.WAITING_RECONCILIATION) {
                            recovery.cancel()
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (startup.status == StartupEligibilityStatus.WAITING_TARGET_READ) {
                            waitForTargetRead()
                            continue@sessionLoop
                        }
                        if (!mayRetainAfterFreshAutomaticCheck(startup, grant.leases)) {
                            AutomaticProtectionRuntime.pause(grant.id, startup.status.description())
                            break@sessionLoop
                        }
                        val freshReceipt = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (freshReceipt.isFailure) {
                            AutomaticProtectionRuntime.pause(grant.id, "停止标记复查后恢复记录无法读取")
                            break@sessionLoop
                        }
                        if (freshReceipt.getOrNull()?.requiresReconcile == true) {
                            expectedPending = freshReceipt.getOrNull()
                            recovery.cancel()
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        val consentsCurrent = withContext(Dispatchers.IO) { AutomaticProtectionRuntime.consentsAuthorized(grant) }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (!consentsCurrent) {
                            AutomaticProtectionRuntime.pause(grant.id, "保存授权无法确认，请重新确认")
                            break@sessionLoop
                        }
                        waitForHostNextCheck(decision.detail)
                        publishNotification(grant, decision.detail)
                        revalidateStartup = true
                        retainedWaitDetail = true
                        break // Normal 30s delay; next iteration repeats full startup before opening writes.
                    }
                    is AutomaticCheckDecision.Pause -> {
                        // Only disambiguate a target failure with the same full shared-safety probe.
                        // Common permission/journal faults retain global fail-closed.
                        blockHostWrites()
                        val startup = withContext(Dispatchers.IO) {
                            AutomaticProtectionRuntime.startupProbe(grant, applicationContext)
                        }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (startup.invalidTargets.isNotEmpty()) {
                            if (retireInvalidTargets(grant, startup)) break@sessionLoop
                            revalidateStartup = true
                            delay(POLL_MILLIS)
                            continue@sessionLoop
                        }
                        AutomaticProtectionRuntime.pause(grant.id, decision.detail)
                        break@sessionLoop
                    }
                    AutomaticCheckDecision.Wait -> Unit
                    AutomaticCheckDecision.Reconcile -> {
                        blockHostWrites()
                        recovery.cancel()
                        revalidateStartup = true
                        continue@sessionLoop
                    }
                    is AutomaticCheckDecision.Cooldown -> {
                        AutomaticProtectionRuntime.pause(grant.id, "恢复授权返回了不支持的状态，请重新确认")
                        break@sessionLoop
                    }
                    AutomaticCheckDecision.Recover -> {
                        val authorization = withContext(Dispatchers.IO) {
                            AutomaticProtectionRuntime.checkRecoveryAuthorization(grant, lease)
                        }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (authorization != AutomaticCheckDecision.Recover) {
                            val detail = (authorization as? AutomaticCheckDecision.Pause)?.detail
                                ?: "当前恢复授权无法确认，请重新确认"
                            AutomaticProtectionRuntime.pause(grant.id, detail)
                            break@sessionLoop
                        }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        if (!matchesGmsConnection(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop // A stale observation never dispatches a write.
                        }
                        val beforeRecovery = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                        if (expectedPending == null) expectedPending = beforeRecovery.getOrNull()?.takeIf { it.requiresReconcile }
                        if (beforeRecovery.isFailure || beforeRecovery.getOrNull()?.requiresReconcile == true) {
                            if (beforeRecovery.isFailure) {
                                AutomaticProtectionRuntime.pause(grant.id, "恢复记录无法读取")
                                break@sessionLoop
                            }
                            blockHostWrites()
                            recovery.cancel()
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        val previousRequestId = beforeRecovery.getOrNull()?.requestId ?: 0L
                        val writeTicket = grant.writes.ticket()
                        AutomaticProtectionRuntime.update(grant.id, "正在恢复标准停止标记", busy = true, identity = identity)
                        publishNotification(grant, "正在恢复标准停止标记")
                        val result = withContext(Dispatchers.IO) {
                            recovery.recoverAutomatically(identity, lease.consent.id,
                                authorized = { connectionGeneration.authorize(iterationConnection) {
                                    AutomaticProtectionRuntime.durablyAuthorized(grant, lease, writeTicket) } },
                                consentedAtMillis = lease.consent.consentedAtMillis,
                                consentVersion = lease.consent.policyVersion)
                        }
                        if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                        val finalReceipt = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                        if (expectedPending == null) expectedPending = finalReceipt.getOrNull()?.takeIf { it.requiresReconcile }
                        if (finalReceipt.isFailure) {
                            AutomaticProtectionRuntime.pause(grant.id, "恢复后记录无法读取")
                            break@sessionLoop
                        }
                        if (finalReceipt.getOrNull()?.requiresReconcile == true) {
                            blockHostWrites()
                            recovery.cancel()
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        // A dead connection may have cancelled before dispatch. Only a clear journal
                        // can return to waiting; unresolved operations enter read-only coordination, never replayed.
                        if (!connectedGmsHost(iterationConnection)) {
                            revalidateStartup = true
                            continue@sessionLoop
                        }
                        if (mayWaitAfterRecoveryAdmission(result, finalReceipt)) {
                            val detail = "已有恢复操作正在进行，等待下次检测；授权已保留"
                            AutomaticProtectionRuntime.update(grant.id, detail, identity = identity)
                            publishNotification(grant, detail)
                            retainedWaitDetail = true
                            break // Normal polling delay before a new complete observation.
                        }
                        if (mayWaitAfterRecoveryBinding(result, beforeRecovery, finalReceipt)) {
                            blockHostWrites()
                            val startup = withContext(Dispatchers.IO) {
                                AutomaticProtectionRuntime.startupProbe(grant, applicationContext)
                            }
                            if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                            if (!connectedGmsHost(iterationConnection)) {
                                revalidateStartup = true
                                continue@sessionLoop
                            }
                            if (startup.invalidTargets.isNotEmpty()) {
                                if (retireInvalidTargets(grant, startup)) break@sessionLoop
                                revalidateStartup = true
                                delay(POLL_MILLIS)
                                continue@sessionLoop
                            }
                            if (startup.status == StartupEligibilityStatus.WAITING_RECONCILIATION) {
                                recovery.cancel()
                                revalidateStartup = true
                                continue@sessionLoop
                            }
                            if (startup.status == StartupEligibilityStatus.WAITING_TARGET_READ) {
                                waitForTargetRead()
                                continue@sessionLoop
                            }
                            if (!mayRetainAfterFreshAutomaticCheck(startup, grant.leases)) {
                                AutomaticProtectionRuntime.pause(grant.id, startup.status.description(), result.outcome.name)
                                break@sessionLoop
                            }
                            val freshReceipt = withContext(Dispatchers.IO) { runCatching { recovery.loadLatest() } }
                            if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                            if (!connectedGmsHost(iterationConnection)) {
                                revalidateStartup = true
                                continue@sessionLoop
                            }
                            if (freshReceipt.isSuccess && freshReceipt.getOrNull()?.requiresReconcile == true) {
                                expectedPending = freshReceipt.getOrNull()
                                recovery.cancel()
                                revalidateStartup = true
                                continue@sessionLoop
                            }
                            val consentsCurrent = withContext(Dispatchers.IO) { AutomaticProtectionRuntime.consentsAuthorized(grant) }
                            if (!AutomaticProtectionRuntime.authorized(grant)) break@sessionLoop
                            if (!connectedGmsHost(iterationConnection)) {
                                revalidateStartup = true
                                continue@sessionLoop
                            }
                            if (!mayWaitAfterRecoveryBinding(result, beforeRecovery, freshReceipt) || !consentsCurrent) {
                                AutomaticProtectionRuntime.pause(grant.id, "恢复传输失败后的记录或授权无法确认", result.outcome.name)
                                break@sessionLoop
                            }
                            val detail = "恢复连接暂时不可用，等待下次完整检测；授权已保留，当前未执行保护"
                            waitForHostNextCheck(detail)
                            publishNotification(grant, detail)
                            revalidateStartup = true
                            retainedWaitDetail = true
                            break // Normal polling delay; the next pass repeats all prerequisites.
                        }
                        when (result.outcome) {
                            StoppedRecoveryOutcome.FLAG_CLEARED, StoppedRecoveryOutcome.NO_WRITE_NEEDED ->
                                AutomaticProtectionRuntime.update(grant.id,
                                    "等待下次检测；标准停止标记已解除，消息接收尚未验证", outcome = result.outcome.name, identity = identity)
                            StoppedRecoveryOutcome.STILL_STOPPED -> {
                                if (!mayContinueAfterStoppedRecovery(result, finalReceipt.getOrNull(), previousRequestId, lease.consent)) {
                                    AutomaticProtectionRuntime.pause(grant.id, "恢复回执无法确认，请先只读复检", result.outcome.name)
                                    break@sessionLoop
                                }
                                val detail = "标准停止标记仍为停止；授权已保留，等待下一次完整检测后重新恢复；消息接收尚未验证"
                                AutomaticProtectionRuntime.update(grant.id, detail, outcome = result.outcome.name, identity = identity)
                                publishNotification(grant, detail)
                                retainedWaitDetail = true
                            }
                            else -> {
                                AutomaticProtectionRuntime.pause(grant.id, "已暂停：${result.detail}", result.outcome.name)
                                break@sessionLoop
                            }
                        }
                    }
                }
                }
                targetCursor = (targetCursor + 1) % grant.leases.size
                if (!AutomaticProtectionRuntime.authorized(grant)) break
                if (!retainedWaitDetail) {
                    AutomaticProtectionRuntime.update(grant.id, "等待下次检测；检测可能受休眠影响")
                    publishNotification(grant, "等待下次检测；不保证连续检测或消息接收")
                }
                delay(POLL_MILLIS)
            }
        } catch (_: CancellationException) {
            // Revocation details are set synchronously by the stop/loss path.
        } catch (_: Exception) {
            AutomaticProtectionRuntime.pause(grant.id, "保护不可用：读取或恢复异常，请先复检后重新确认")
        } finally {
            FcmProtectionRuntime.hostEnded(grant.id)
            controller?.close()
            runCatching { selectionListener?.close() }
            if (listenersRegistered) {
                runCatching { Shizuku.removeBinderDeadListener(binderDead) }
                runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
                runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
            }
            connectionEvents.close()
            AutomaticProtectionRuntime.detach(grant.id)
            retire(grant.id)
        }
    }

    private fun shellReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getUid() == 2000 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun retireInvalidTargets(grant: AutomaticProtectionGrant, probe: StartupProbeResult): Boolean {
        targetRetirement.begin(grant.id)
        val accepted = AutomaticProtectionRuntime.retireConfirmedTargets(applicationContext, grant, probe) { survivors ->
            if (destroyed || !targetRetirement.holds(grant.id)) return@retireConfirmedTargets
            // Retire the old controller while its foreground lifetime is still held.
            if (activeId == grant.id) retire(grant.id)
            if (!targetRetirement.finish(grant.id) || activeId != null) return@retireConfirmedTargets
            if (survivors) restoreSavedSession(latestStartId)
            else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (!AutomaticProtectionRuntime.hasSession()) stopSelf(latestStartId)
            }
        }
        if (!accepted) targetRetirement.finish(grant.id)
        return accepted
    }

    private fun publishNotification(grant: AutomaticProtectionGrant, detail: String) {
        refreshProtectionStatusNotification(
            authorized = { AutomaticProtectionRuntime.authorized(grant) },
            refresh = {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(grant.id, detail))
            },
        )
    }

    private fun notification(id: String, detail: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, AutomaticProtectionService::class.java)
            .setAction(ACTION_STOP).setData(Uri.parse("guardian-auto-stop:$id")).putExtra(EXTRA_SESSION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        stopPendingIntent = stop
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, AutomaticProtectionRuntime.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian).setContentTitle("应用自动保护")
            .setContentText(detail).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
    }

    private fun retire(id: String) {
        if (activeId != id) return
        FcmProtectionRuntime.hostEnded(id)
        activeId = null
        activeJob = null
        activeController = null
        stopPendingIntent?.cancel()
        stopPendingIntent = null
        if (!targetRetirement.holds(id)) stopForeground(STOP_FOREGROUND_REMOVE)
        // A replacement may have reserved its grant before its START command is delivered.
        if (targetRetirement.mayStopService(AutomaticProtectionRuntime.hasSession())) stopSelf(latestStartId)
    }

    override fun onDestroy() {
        destroyed = true
        targetRetirement.clear()
        AutomaticProtectionRuntime.detachServiceRetirement(idleRetirement)
        activeId?.let(FcmProtectionRuntime::hostEnded)
        activeId?.let(AutomaticProtectionRuntime::detach)
        activeController?.cancel()
        scope.cancel()
        stopPendingIntent?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "io.github.originosfcmguardian.autoprotection.START"
        internal const val ACTION_RESTORE = "io.github.originosfcmguardian.autoprotection.RESTORE"
        internal const val ACTION_STOP = "io.github.originosfcmguardian.autoprotection.STOP"
        internal const val EXTRA_SESSION = "automatic_protection_session"
        private const val NOTIFICATION_ID = 41_734
        private const val POLL_MILLIS = 30_000L
    }
}
