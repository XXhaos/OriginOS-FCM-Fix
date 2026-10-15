package io.github.originosfcmguardian.recovery

import android.content.Context
import android.os.Binder
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.privileged.StoppedObservationCodec
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.privileged.StoppedObservationUnknown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.privileged.PrivilegedWorkerActivity
import kotlinx.coroutines.delay

/** Manual actions and explicitly opted-in runtime requests. Unresolved requests are never replayed. */
class StoppedRecoveryController internal constructor(context: Context, private val transport: RecoveryTransport) : AutoCloseable {
    constructor(context: Context) : this(context, ExternalShizukuRecoveryTransport(context.applicationContext))
    private val app = context.applicationContext
    private val journal = AndroidRecoveryJournal.open(app)
    private val selection = TargetSelectionStore(app)
    private val current = AtomicReference<Session?>()
    private val closed = AtomicBoolean(false)
    private val cancellationEpoch = AtomicLong(0)
    init { synchronized(instances) { instances.add(this) } }

    fun loadLatest(): StoppedRecoveryRecord? = journal.load().latest
    fun storageHealthy(): Boolean = runCatching { journal.load(); true }.getOrDefault(false)
    fun isBusy(): Boolean = singleFlight.isBusy()

    /** Fresh target-bound read, using the same execution owner without claiming a request. */
    internal suspend fun observe(identity: SelectedTargetIdentity): StoppedTargetObservation {
        var observation: StoppedTargetObservation = StoppedTargetObservation.Unknown(1, StoppedObservationUnknown.BRIDGE_UNAVAILABLE)
        val result = run(identity) { session, remote ->
            val read = StoppedObservationCodec.decode(remote.observeTarget(1, TargetSelectionCodec.encode(listOf(identity)), session.callback), 1, identity)
            check(session.active())
            observation = read
            StoppedRecoveryResult(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "只读检查")
        }
        return if (result.outcome == StoppedRecoveryOutcome.NO_WRITE_NEEDED) observation
        else StoppedTargetObservation.Unknown(1, StoppedObservationUnknown.BRIDGE_UNAVAILABLE)
    }

    suspend fun recover(identity: SelectedTargetIdentity, confirmed: Boolean): StoppedRecoveryResult = run(identity) { session, remote ->
        check(confirmed) { "需要本次主动确认及运行时风险告知" }
        recoverStopped(identity, session, remote, identity.packageName != "org.telegram.messenger") { known -> journal.prepare(identity, known, confirmed) }
    }

    suspend fun recoverAutomatically(
        identity: SelectedTargetIdentity,
        consentId: String,
        authorized: () -> Boolean,
        consentedAtMillis: Long = System.currentTimeMillis(),
        consentVersion: Int = 1,
    ): StoppedRecoveryResult = run(identity, AutomaticRecoveryAuthorization(authorized)) { session, remote ->
        val consent = AutomaticRecoveryConsent(consentId, consentedAtMillis, consentVersion)
        consent.validate()
        check(consentVersion == 4 || identity.packageName == "org.telegram.messenger")
        recoverStopped(identity, session, remote, consentVersion == 4) { known -> journal.prepareAutomatically(identity, known, consent) }
    }

    private fun recoverStopped(identity: SelectedTargetIdentity, session: Session, remote: IStoppedRecovery,
        targetAware: Boolean,
        prepare: (StoppedTargetObservation.Known) -> StoppedRecoveryRecord): StoppedRecoveryResult {
        val state = journal.load()
        check(state.latest?.requiresReconcile != true) { "先只读复检上次待确认操作" }
        val nextId = Math.addExact(maxOf(state.claimedHighWater, state.latest?.requestId ?: 0), 1)
        fun observe(id: Long) = StoppedObservationCodec.decode(
            if (targetAware) remote.observeTarget(id, TargetSelectionCodec.encode(listOf(identity)), session.callback)
            else remote.observe(id, session.callback), id, if (targetAware) identity else null)
        val before = observe(nextId)
        check(session.active())
        val known = before as? StoppedTargetObservation.Known ?: error("新鲜身份、停止状态或能力读取不可用")
        check(known.identity == identity && known.hasVerifiedPackageUid) { "完整安装身份或 UID 归属证据已变化" }
        check(!targetAware || known.qualifiedRecipient) { "目标恢复资格未确认" }
        if (!known.stopped) return StoppedRecoveryResult(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "停止标记已解除，消息接收尚未验证", journal.load().latest)
        val record = prepare(known)
        session.recordId.set(record.requestId)
        check(session.active())
        val commandResult = RecoveryResultCodec.decode(
            if (targetAware) remote.recoverTarget(record.requestId, TargetSelectionCodec.encode(listOf(identity)), session.callback)
            else remote.recover(record.requestId, TargetSelectionCodec.encode(listOf(identity)), session.callback))
        check(session.active())
        val result = awaitStoppedRecoveryReadback(identity, commandResult,
            read = { observe(record.requestId) },
            active = session::active)
        check(session.active())
        val confirmedAtMillis = System.currentTimeMillis()
        val finished = if (result.outcome == StoppedRecoveryOutcome.UNCONFIRMED) result.copy(record = journal.load().latest)
            else result.copy(record = journal.finish(record.requestId, result.outcome, result.detail))
        // Optional statistics are queued only after the existing journal commit. Statistics disk
        // IO cannot delay recovery, change its outcome, or poison its authorization record.
        runCatching { confirmedRecoveryReceipt(finished, confirmedAtMillis)?.let { AndroidRecoveryCounts.submit(app, it) } }
        return finished
    }

    suspend fun reconcile(authorized: (() -> Boolean)? = null): StoppedRecoveryResult =
        run(null, authorized?.let(::AutomaticRecoveryAuthorization)) { session, remote ->
        val record = journal.load().latest ?: return@run StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, "没有待复检操作")
        if (!record.requiresReconcile) return@run StoppedRecoveryResult(record.outcome ?: StoppedRecoveryOutcome.BLOCKED, record.detail, record)
        val targetAware = record.consentVersion == 4 || record.identity.packageName != "org.telegram.messenger"
        val observed = StoppedObservationCodec.decode(
            if (targetAware) remote.observeTarget(record.requestId, TargetSelectionCodec.encode(listOf(record.identity)), session.callback)
            else remote.observe(record.requestId, session.callback), record.requestId, if (targetAware) record.identity else null)
        check(session.active())
        if (targetAware && (observed as? StoppedTargetObservation.Unknown)?.reason == StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED) {
            val detail = "只读复检发现完整安装身份已变化，旧操作不归属于当前应用"
            return@run StoppedRecoveryResult(StoppedRecoveryOutcome.BLOCKED, detail,
                guardedReconciliationFinish(session::active) {
                    journal.finish(record.requestId, StoppedRecoveryOutcome.BLOCKED, detail, reconciled = true)
                })
        }
        val known = observed as? StoppedTargetObservation.Known
            ?: return@run StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "只读复检不可用，未重新执行恢复", record)
        if (!known.hasVerifiedPackageUid || targetAware && !known.qualifiedRecipient) {
            return@run StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "只读复检 UID 归属或目标资格不可用，未重新执行恢复", record)
        }
        val outcome = when {
            known.identity != record.identity -> StoppedRecoveryOutcome.BLOCKED
            known.stopped -> StoppedRecoveryOutcome.STILL_STOPPED
            else -> StoppedRecoveryOutcome.FLAG_CLEARED
        }
        val detail = when (outcome) {
            StoppedRecoveryOutcome.BLOCKED -> "只读复检发现安装身份变化，旧操作不归属于当前应用"
            StoppedRecoveryOutcome.STILL_STOPPED -> if (authorized == null) "只读复检：停止标记仍为停止；如需恢复，重新主动确认"
                else "只读复检：停止标记仍为停止；后续自动恢复仍需新请求及完整检查，之前命令是否生效尚未验证"
            else -> "只读复检：标准停止标记已解除；消息接收及之前命令是否生效尚未验证"
        }
        StoppedRecoveryResult(outcome, detail, guardedReconciliationFinish(session::active) {
            journal.finish(record.requestId, outcome, detail, reconciled = true)
        })
    }

    fun cancel() { cancellationEpoch.incrementAndGet(); current.get()?.stop() }
    override fun close() { closed.set(true); cancel() }

    private suspend fun run(identity: SelectedTargetIdentity?, authorization: AutomaticRecoveryAuthorization? = null,
        action: (Session, IStoppedRecovery) -> StoppedRecoveryResult): StoppedRecoveryResult {
        if (migrationClosed || !AutomaticProtectionRuntime.localExecutionAllowed(app)) return StoppedRecoveryResult(
            StoppedRecoveryOutcome.BLOCKED, "保护执行权已迁移，旧恢复入口已关闭")
        val callerJob = currentCoroutineContext().job
        currentCoroutineContext().ensureActive()
        val epoch = cancellationEpoch.get()
        return singleFlight.withAdmission(closed::get,
            { fallback("恢复操作不可用或正在进行") }) operation@{
            val session = try { Session(identity, callerJob, epoch, authorization) }
                catch (_: Throwable) { return@operation fallback("恢复传输不可用，未绑定其他后端") }
            current.set(session)
            try {
                withTimeout(24_000) {
                    suspendCancellableCoroutine { continuation ->
                        val settlement = rpcActivity.track()
                        val future = try {
                            rpc.submit {
                                if (!settlement.workerEntered()) return@submit
                                try {
                                val result = try {
                                    check(continuation.isActive)
                                    check(session.active())
                                    check(validRuntimeFingerprint(android.os.Build.FINGERPRINT))
                                    bindBeforeRecoveryAction(bind = session::bind,
                                        fallback = { fallback("恢复传输暂不可用，尚未开始恢复操作") }) { remote ->
                                        check(remote.serviceUid == 2000)
                                        check(session.active())
                                        action(session, remote)
                                    }
                                } catch (_: Throwable) { fallback("恢复能力、身份或连接不可用；待确认操作须先只读复检") }
                                if (continuation.isActive) continuation.resume(result)
                                } finally { settlement.workerEnded(false, null) }
                            }
                        } catch (_: Throwable) {
                            settlement.abandonQueued()
                            continuation.resume(fallback("上次连接仍未退出，请稍后只读复检"))
                            return@suspendCancellableCoroutine
                        }
                        continuation.invokeOnCancellation { session.stop(); future.cancel(true); settlement.abandonQueued() }
                    }
                }
            } catch (_: CancellationException) {
                fallback("操作已中断；已派发操作可能生效，先只读复检")
            } finally {
                session.stop(); current.compareAndSet(session, null)
            }
        }
    }

    private fun fallback(detail: String): StoppedRecoveryResult {
        val record = runCatching { journal.load().latest }.getOrNull()
        return StoppedRecoveryResult(if (record?.requiresReconcile == true) StoppedRecoveryOutcome.UNCONFIRMED else StoppedRecoveryOutcome.BLOCKED, detail, record)
    }

    private inner class Session(private val expected: SelectedTargetIdentity?, private val callerJob: Job, private val epoch: Long,
        private val authorization: AutomaticRecoveryAuthorization?) {
        private val live = AtomicBoolean(true)
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(24)
        val recordId = AtomicReference<Long?>(null)
        private val binding = RecoveryBindingLifecycle(transport.newBinding(), ::localActive, ::active,
            ::stop, { action ->
                val settlement = cleanupActivity.track()
                try { cleanup.execute {
                    if (settlement.workerEntered()) try { action() }
                    finally { settlement.workerEnded(false, null) }
                } } catch (failure: Throwable) { settlement.abandonQueued(); throw failure }
            })
        private val listener = if (expected != null) selection.registerListener { stop() } else null
        private fun localActive(): Boolean = !migrationClosed && callerJob.isActive && cancellationEpoch.get() == epoch && live.get() && !closed.get() && System.nanoTime() < deadline
        fun active(): Boolean = localActive() && binding.isCurrent() &&
            (authorization?.active() != false) &&
            (expected == null || runCatching { selection.load().contains(expected) }.getOrDefault(false))
        val callback = object : IStoppedRecoveryJournal.Stub() {
            override fun isSessionActive(): Boolean = Binder.getCallingUid() == 2000 && active()
            override fun claim(requestId: Long): Boolean {
                check(Binder.getCallingUid() == 2000 && active() && expected != null && recordId.get() == requestId)
                val record = requireNotNull(journal.load().latest)
                check(record.identity == expected && active())
                val claimed = journal.claim(requestId)
                // A delayed commit may consume an ID, but can never authorize a late command.
                return claimed && active()
            }
        }
        fun bind(): IStoppedRecovery = binding.bind()
        fun stop() {
            live.set(false)
            runCatching { listener?.close() }
            binding.close()
        }
    }
    companion object {
        @Volatile private var migrationClosed = false
        private val instances = java.util.Collections.newSetFromMap(java.util.WeakHashMap<StoppedRecoveryController, Boolean>())
        private val rpcActivity = PrivilegedWorkerActivity()
        private val cleanupActivity = PrivilegedWorkerActivity()
        internal fun prepareForDaemonMigration() { migrationClosed = true }
        internal suspend fun awaitDaemonMigrationDrain(): Boolean {
            synchronized(instances) { instances.toList() }.forEach { it.close() }
            while (!rpcActivity.isQuiescent() || !cleanupActivity.isQuiescent() || singleFlight.isBusy()) delay(25)
            if (synchronized(instances) { instances.toList() }.any {
                    runCatching { it.journal.load().latest?.requiresReconcile == true }.getOrDefault(true)
                }) return false
            if (!ExternalShizukuRecoveryTransport.awaitDaemonMigrationDrain()) return false
            return AndroidRecoveryCounts.awaitMigrationDrain()
        }
        private val singleFlight = RecoveryAdmission()
        private val rpc = recoveryWorker("guardian-recovery-rpc")
        private val cleanup = recoveryWorker("guardian-recovery-cleanup")
    }
}

/** Revocation after the read must be observed again before committing its conclusion. */
internal fun <T> guardedReconciliationFinish(active: () -> Boolean, finish: () -> T): T {
    check(active()) { "只读复检会话已失效，保持待确认记录" }
    return finish()
}

/** Catch only the bind itself. UID checks, actions, cancellation and the outer timeout stay distinct. */
internal fun <T> bindBeforeRecoveryAction(bind: () -> T, fallback: () -> StoppedRecoveryResult,
    action: (T) -> StoppedRecoveryResult): StoppedRecoveryResult {
    val remote = try { bind() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return fallback().copy(bindingFailedBeforeAction = true) }
    return action(remote)
}
