package io.github.originosfcmguardian.doze.cyclebroker

import android.content.Context
import io.github.originosfcmguardian.core.doze.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GmsDozeCycleUiLoadState { LOADING, ABSENT, CLEAN, BLOCKED }

/** Exact snapshot captured when the dialog opens, not manufactured when it is confirmed. */
data class GmsDozeCycleOnTarget(val ledgerId: String?, val revision: Long, val sha256: String?,
    val runningGeneration: Long, val localSequence: Long, val localOffSequence: Long? = null)

data class GmsDozeCycleRestoreTarget(val ledgerId: String, val revision: Long, val sha256: String,
    val runningGeneration: Long, val cycleId: String, val transactionId: String, val transactionEpoch: Long)

/** Saved running intent is presentation, not an autonomous scheduler or live executor capability. */
data class GmsDozeCycleUiState(
    val loadState: GmsDozeCycleUiLoadState = GmsDozeCycleUiLoadState.LOADING,
    val runningIntent: Boolean = false,
    val busy: Boolean = false,
    val stopPending: Boolean = false,
    val detail: String = "电池保护记录尚未读取；本版本仅按次执行",
    val lastOutcome: String? = null,
    val canEnable: Boolean = false,
    val canApplyOnce: Boolean = false,
    val canRestore: Boolean = false,
    val writeApproved: Boolean = false,
    val onTarget: GmsDozeCycleOnTarget? = null,
    val restoreTarget: GmsDozeCycleRestoreTarget? = null,
)

/**
 * One application-owned finite task owner. UI detach cancels collection/confirmation only, never
 * actual cleanup, release or OFF. No timers, host attachment, lifecycle replay or retry backend.
 */
internal class GmsDozeCycleProductController internal constructor(
    private val bridge: GmsDozeCycleBusinessBridge,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long,
) {
    private val monitor = Any()
    private val mutable = MutableStateFlow(GmsDozeCycleUiState())
    val state: StateFlow<GmsDozeCycleUiState> = mutable.asStateFlow()
    private var initialized = false
    private var businessPending = false
    private var offPending = false
    private var localSequence = 0L
    private var publishedLedgerId: String? = null
    private var publishedRevision = 0L

    fun initialize() {
        val sequence = synchronized(monitor) { if (initialized) return; initialized = true; localSequence }
        scope.launch { publish(bridge.savedSnapshot(), sequence = sequence) }
    }

    /** An automatic ledger change refreshes finite controls, never grants or executes a command. */
    fun refresh() {
        val sequence = synchronized(monitor) { localSequence }
        scope.launch { publish(bridge.savedSnapshot(), sequence = sequence) }
    }

    fun confirmOn(target: GmsDozeCycleOnTarget) {
        submit({ it.canEnable && it.onTarget == target }) {
            val current = bridge.savedSnapshot()
            if (!onMatches(current, target)) GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
            else if (current.loadState == GmsDozeCycleUiLoadState.ABSENT)
                bridge.applyOnce(GmsDozeCycleConsent(uuid(), confirmedAt()), requireNotNull(target.localOffSequence))
            else bridge.reenableOnce(GmsDozeCycleReenableConfirmation(uuid(), confirmedAt(),
                requireNotNull(current.offTarget), Math.addExact(target.runningGeneration, 1)),
                requireNotNull(target.localOffSequence))
        }
    }

    fun runOnce() = submit({ it.canApplyOnce }) { bridge.applyOnce() }

    fun confirmRestore(target: GmsDozeCycleRestoreTarget) {
        submit({ it.canRestore && it.restoreTarget == target }) {
            if (bridge.savedSnapshot().restoreTarget != target) GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.BLOCKED)
            else bridge.restoreOnce(GmsDozeCycleRestoreConsent(uuid(), target.ledgerId, target.cycleId,
                target.transactionId, target.transactionEpoch, confirmedAt()), target)
        }
    }

    /** The caller returns only after local ADD admission has closed; disk/RPC use independent IO. */
    fun off() {
        val submission = synchronized(monitor) {
            if (offPending) return
            val ticket = bridge.closeAddsNow() // Register the core OFF sequence before any enqueue.
            localSequence = Math.addExact(localSequence, 1)
            offPending = true
            mutable.value = mutable.value.copy(stopPending = true, canEnable = false,
                canApplyOnce = false, canRestore = false, onTarget = null, restoreTarget = null,
                detail = "已停止本进程新增操作，正在保存关闭并确认原会话撤销；不会自动恢复原值")
            localSequence to ticket
        }
        scope.launch {
            val outcome = try { bridge.off(submission.second) } catch (_: Exception) { GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.UNCERTAIN) }
            val saved = bridge.savedSnapshot()
            synchronized(monitor) { offPending = false }
            publish(saved, outcome, submission.first)
        }
    }

    private fun submit(allowed: (GmsDozeCycleUiState) -> Boolean, action: () -> GmsDozeCycleOutcome) {
        val sequence = synchronized(monitor) {
            if (businessPending || offPending || !allowed(mutable.value)) {
                if (!businessPending && !offPending) mutable.value = mutable.value.copy(lastOutcome = "BLOCKED",
                    detail = "确认对应的记录已变化或操作不可用；请重新打开确认")
                return
            }
            businessPending = true
            mutable.value = mutable.value.copy(busy = true, canEnable = false, canApplyOnce = false,
                canRestore = false, onTarget = null, restoreTarget = null, detail = "正在执行本次电池保护操作；关闭页面不会中断结算")
            localSequence
        }
        scope.launch {
            val outcome = try { action() } catch (_: Exception) { GmsDozeCycleOutcome(GmsDozeCycleOutcomeCode.UNCERTAIN) }
            val saved = bridge.savedSnapshot()
            synchronized(monitor) { businessPending = false }
            publish(saved, outcome, sequence)
        }
    }

    private fun onMatches(saved: GmsDozeCycleSavedSnapshot, target: GmsDozeCycleOnTarget): Boolean = synchronized(monitor) {
        target.localSequence == localSequence && !offPending && !saved.terminal &&
            saved.ledgerId == target.ledgerId && saved.revision == target.revision && saved.sha256 == target.sha256 &&
            saved.runningGeneration == target.runningGeneration &&
            saved.localConsentSequence != null && saved.localConsentSequence == target.localOffSequence &&
            (saved.loadState == GmsDozeCycleUiLoadState.ABSENT || saved.offTarget != null)
    }

    private fun publish(saved: GmsDozeCycleSavedSnapshot, outcome: GmsDozeCycleOutcome? = null,
        sequence: Long, reloadStale: Boolean = true) {
        val stale = synchronized(monitor) {
            if (sequence != localSequence || saved.loadState == GmsDozeCycleUiLoadState.CLEAN &&
                publishedLedgerId == saved.ledgerId && saved.revision < publishedRevision) {
                // Never publish an older ON/restore target over a newer OFF. Flags alone are safe;
                // one fresh store read follows outside this monitor, not a retry or executor probe.
                mutable.value = mutable.value.copy(busy = businessPending, stopPending = offPending,
                    canEnable = false, canApplyOnce = false, canRestore = false, onTarget = null, restoreTarget = null)
                return@synchronized true
            }
            if (saved.loadState == GmsDozeCycleUiLoadState.CLEAN) {
                publishedLedgerId = saved.ledgerId; publishedRevision = saved.revision
            }
            val idle = !businessPending && !offPending && !saved.terminal
            val on = if (idle && (saved.loadState == GmsDozeCycleUiLoadState.ABSENT && saved.localConsentSequence != null || saved.offTarget != null))
                GmsDozeCycleOnTarget(saved.ledgerId, saved.revision, saved.sha256, saved.runningGeneration,
                    localSequence, saved.localConsentSequence) else null
            val restore = saved.restoreTarget.takeIf { idle }
            val accepted = outcome.takeIf { sequence == localSequence }
            val prior = mutable.value
            mutable.value = GmsDozeCycleUiState(saved.loadState, saved.runningIntent, businessPending, offPending,
                accepted?.let(::detail) ?: savedDetail(saved),
                accepted?.code?.name ?: prior.lastOutcome, on != null,
                idle && saved.loadState == GmsDozeCycleUiLoadState.CLEAN && saved.runningIntent &&
                    !saved.localAddsClosed && !saved.unfinished,
                restore != null, false, on, restore)
            false
        }
        if (stale && reloadStale) {
            val currentSequence = synchronized(monitor) { localSequence }
            publish(bridge.savedSnapshot(), sequence = currentSequence, reloadStale = false)
        }
    }

    private fun savedDetail(saved: GmsDozeCycleSavedSnapshot): String = when {
        saved.loadState == GmsDozeCycleUiLoadState.BLOCKED -> "记录不可用或旧操作未确认；保留原始记录，本版本不接管或重发"
        saved.unfinished -> "上次操作尚未完成结算；不创建替代会话或重发"
        saved.loadState == GmsDozeCycleUiLoadState.ABSENT -> "尚未确认电池保护；先按次确认，定期检查须另外授权"
        saved.runningIntent -> "已保存电池保护选择；定期检查须另外授权"
        else -> "操作已关闭；已应用的电池设置保留，恢复原值需单独确认"
    }
    private fun detail(outcome: GmsDozeCycleOutcome): String = when (outcome.code) {
        GmsDozeCycleOutcomeCode.NO_WRITE -> "Google Play 服务已有完整待机豁免，本次未改动，也未取得恢复所有权"
        GmsDozeCycleOutcomeCode.WRITE_NOT_APPROVED -> "系统写入尚未批准，本次未创建写入授权或启动写命令"
        GmsDozeCycleOutcomeCode.APPLIED_RETAINED -> "保留已确认的电池设置，本次无需重复写入"
        GmsDozeCycleOutcomeCode.APPLIED_SETTLED -> "本次电池保护已应用并结算"
        GmsDozeCycleOutcomeCode.RESTORED -> "已按单独确认恢复原值并结算"
        GmsDozeCycleOutcomeCode.OFF -> "操作已关闭；已应用设置保留，恢复原值需单独确认"
        GmsDozeCycleOutcomeCode.BLOCKED -> "记录或确认已变化，未授权本次操作；请核对当前状态"
        GmsDozeCycleOutcomeCode.UNCERTAIN -> "操作或关闭状态无法确认；保留记录，不接管、不重发"
    }
    private fun confirmedAt() = wallClock().also { require(it > 0) }
    private fun uuid() = UUID.randomUUID().toString()

    companion object {
        private val instances = HashMap<String, GmsDozeCycleProductController>()
        fun get(context: Context): GmsDozeCycleProductController = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app.noBackupFilesDir.canonicalPath) {
                GmsDozeCycleProductController(GmsDozeCycleBusinessBridge.get(app),
                    CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis)
            }
        }
    }
}
