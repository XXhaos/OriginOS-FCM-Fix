package io.github.originosfcmguardian.doze.cyclebroker

import io.github.originosfcmguardian.core.doze.GmsDozeCycleAutomaticConsent
import io.github.originosfcmguardian.core.doze.GmsDozeCycleAutomaticTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class GmsDozeCycleAutomaticConfirmationTarget(
    val target: GmsDozeCycleAutomaticTarget,
    val localOffSequence: Long,
)

data class GmsDozeCycleAutomaticUiState(
    val loaded: Boolean = false,
    val configured: Boolean = false,
    val busy: Boolean = false,
    val eligible: Boolean = false,
    val blocked: Boolean = false,
    val detail: String = "自动电池保护尚未读取；写入门保持关闭",
    val lastOutcome: String? = null,
    val nextAttemptAtElapsedMillis: Long? = null,
    val confirmationTarget: GmsDozeCycleAutomaticConfirmationTarget? = null,
)

/**
 * Application-owned ADD-only admission. Existing actual-host hints are the only wake-up source:
 * no timer, replay, catch-up, new service, preferences or implicit automatic consent.
 */
internal class GmsDozeCycleAutomaticController(
    private val bridge: GmsDozeCycleBusinessBridge,
    private val applicationScope: CoroutineScope,
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long,
    private val ledgerChanged: () -> Unit = {},
) {
    private class Host(val id: String) {
        var generation = -1L
        var eligible = false
        var getter: (() -> Boolean)? = null
    }
    private val monitor = Any()
    private val mutable = MutableStateFlow(GmsDozeCycleAutomaticUiState())
    val state: StateFlow<GmsDozeCycleAutomaticUiState> = mutable.asStateFlow()
    private val endedHosts = HashSet<String>()
    private var host: Host? = null
    private var activeLease: GmsDozeCycleAutomaticLease? = null
    private var saved: GmsDozeCycleAutomaticSavedSnapshot? = null
    private var initialized = false
    private var refreshPending = false
    private var refreshAgain = false
    private var pending = false
    private var nextAttempt: Long? = null
    private var lastElapsed: Long? = null
    private var clockBlocked = false
    private var publicationSequence = 0L
    private var gmsSuspended = false
    private var runtimeSuspended = false
    private var suspendedGeneration: Long? = null
    private var suspendedOffSequence: Long? = null
    private var publishedLedgerId: String? = null
    private var publishedRevision = 0L
    private var publishedGeneration: Long? = null

    fun initialize() {
        synchronized(monitor) { if (initialized) return; initialized = true }
        refresh()
    }

    /** Strict store reload only. No probe, grant or execution on UI entry or cold construction. */
    fun refresh() {
        val sequence = synchronized(monitor) {
            if (refreshPending) { refreshAgain = true; return }
            refreshPending = true
            publicationSequence
        }
        applicationScope.launch {
            val snapshot = bridge.automaticSnapshot()
            val repeat = synchronized(monitor) {
                refreshPending = false
                val stale = sequence != publicationSequence || !acceptSnapshotLocked(snapshot)
                if (!stale) {
                    publishLocked()
                }
                (refreshAgain || stale).also { refreshAgain = false }
            }
            if (repeat) refresh()
        }
    }

    fun attachHost(id: String) {
        require(id.isNotBlank())
        val previous = synchronized(monitor) {
            if (id in endedHosts || host?.id == id) return
            host?.id?.let(endedHosts::add)
            val lease = activeLease
            host = Host(id)
            publishLocked()
            lease
        }
        closeLease(previous)
    }

    /** Actual-host getter evaluation stays outside ALL local authority/storage monitors. */
    fun hostEligible(id: String, generation: Long, eligible: () -> Boolean) {
        if (generation < 0) return
        val actual = try { eligible() } catch (_: Exception) { false }
        val previous = synchronized(monitor) {
            val current = host ?: return
            if (current.id != id || generation < current.generation || id in endedHosts) return
            val staleLease = activeLease?.takeIf { it.hostId == id && it.connectionGeneration != generation }
            current.generation = generation
            current.getter = eligible
            current.eligible = actual
            armDeadlineLocked()
            publishLocked()
            if (!actual) activeLease?.takeIf { it.hostId == id && it.connectionGeneration == generation } else staleLease
        }
        closeLease(previous)
    }

    fun hostLost(id: String, generation: Long? = null) {
        val lease = synchronized(monitor) {
            val current = host ?: return
            if (current.id != id || generation != null && current.generation != generation) return
            current.eligible = false
            current.getter = null
            publishLocked()
            activeLease?.takeIf { it.hostId == id && (generation == null || it.connectionGeneration == generation) }
        }
        closeLease(lease)
    }

    fun hostEnded(id: String) {
        val lease = synchronized(monitor) {
            endedHosts.add(id)
            if (host?.id != id) return
            host = null
            publishLocked()
            activeLease?.takeIf { it.hostId == id }
        }
        closeLease(lease)
    }

    /** Synchronous automatic-only closure. The product controller owns the separate durable OFF. */
    fun gmsOffNow() {
        val lease = synchronized(monitor) {
            publicationSequence = Math.addExact(publicationSequence, 1)
            gmsSuspended = true
            suspendedGeneration = saved?.runningGeneration ?: publishedGeneration
            // Capture BEFORE the product's synchronous beginOff. This boundary remains known
            // even when this owner's initial saved read has not completed yet.
            suspendedOffSequence = bridge.captureAutomaticOffSequence()
            saved = null
            mutable.value = mutable.value.copy(configured = false, eligible = false, blocked = true,
                confirmationTarget = null, detail = "GMS 操作已停止；自动代次不再授权")
            activeLease
        }
        closeLease(lease)
    }

    /** Local subsystem failure only: keep persisted configuration and application settlement. */
    fun suspendAfterRuntimeFailure() {
        val lease = synchronized(monitor) {
            if (runtimeSuspended) return
            runtimeSuspended = true
            publicationSequence = Math.addExact(publicationSequence, 1)
            host?.eligible = false
            publishLocked()
            activeLease
        }
        closeLease(lease)
    }

    fun confirm(target: GmsDozeCycleAutomaticConfirmationTarget) {
        val sequence = synchronized(monitor) {
            if (pending || clockBlocked || gmsSuspended || runtimeSuspended || saved?.confirmation != target) return
            publicationSequence = Math.addExact(publicationSequence, 1)
            pending = true
            publishLocked()
            publicationSequence
        }
        applicationScope.launch {
            val consent = GmsDozeCycleAutomaticConsent(UUID.randomUUID().toString(), wallMillis(), target.target)
            val accepted = bridge.confirmAutomatic(consent, target.localOffSequence)
            val snapshot = bridge.automaticSnapshot()
            synchronized(monitor) {
                pending = false
                if (publicationSequence == sequence) {
                    acceptSnapshotLocked(snapshot)
                    if (accepted && host?.eligible == true) nextAttempt = maxOf(nextAttempt ?: 0L, deadline(nowLocked()))
                    publishLocked(if (accepted) "AUTOMATIC_CONFIRMED" else "BLOCKED")
                } else publishLocked()
                publicationSequence = Math.addExact(publicationSequence, 1)
            }
            refresh()
            if (accepted) ledgerChanged()
        }
    }

    fun hint(id: String, generation: Long, eligible: () -> Boolean) {
        hostEligible(id, generation, eligible)
        val work = synchronized(monitor) {
            val current = host ?: return
            val scope = saved?.scope ?: return
            val now = nowLocked()
            if (current.id != id || current.generation != generation || !current.eligible ||
                pending || clockBlocked || gmsSuspended || runtimeSuspended || nextAttempt == null || now < requireNotNull(nextAttempt)) return
            val getter = current.getter ?: return
            // This closure runs outside lease/control gates, and does not acquire them while
            // checking actual backend/current host. It cannot revive an older attempt lease.
            val lease = bridge.automaticLease(id, generation) {
                val actual = try { getter() } catch (_: Exception) { false }
                actual && synchronized(monitor) {
                    host?.let { it.id == id && it.generation == generation && it.eligible && id !in endedHosts } == true
                }
            }
            activeLease = lease
            publicationSequence = Math.addExact(publicationSequence, 1)
            pending = true
            publishLocked()
            Triple(scope, lease, publicationSequence)
        }
        applicationScope.launch {
            val outcome = bridge.applyAutomaticOnce(work.first, work.second) {
                synchronized(monitor) {
                    val now = nowLocked()
                    if (clockBlocked || gmsSuspended || runtimeSuspended || activeLease !== work.second || work.second.isClosed() ||
                        publicationSequence != work.third || now < (nextAttempt ?: Long.MAX_VALUE)) false
                    else { nextAttempt = deadline(now); publishLocked(); true }
                }
            }
            val snapshot = bridge.automaticSnapshot()
            synchronized(monitor) {
                if (activeLease === work.second) activeLease = null
                pending = false
                if (publicationSequence == work.third) acceptSnapshotLocked(snapshot)
                publishLocked(outcome.code.name)
                publicationSequence = Math.addExact(publicationSequence, 1)
            }
            refresh()
            ledgerChanged()
        }
    }

    /** Late saved reads cannot replace a newer consent/generation/settlement publication. */
    private fun acceptSnapshotLocked(snapshot: GmsDozeCycleAutomaticSavedSnapshot): Boolean {
        if (!snapshot.terminal && publishedLedgerId != null &&
            (snapshot.ledgerId != publishedLedgerId || snapshot.revision < publishedRevision)) return false
        if (snapshot.ledgerId != null) {
            publishedLedgerId = snapshot.ledgerId
            publishedRevision = snapshot.revision
            publishedGeneration = snapshot.runningGeneration
        }
        saved = snapshot
        val newerOnGeneration = suspendedGeneration?.let { snapshot.runningGeneration > it } == true
        val completedOffBoundary = suspendedOffSequence?.let { stopped ->
            snapshot.confirmation?.localOffSequence?.let { it > stopped } == true
        } == true
        // Every unlock requires the exact captured core OFF boundary to have advanced and the
        // strict current confirmation to exist. Generation is only a secondary consistency check.
        // A null boundary (already outstanding/terminal) cannot manufacture success from UI state.
        if (gmsSuspended && snapshot.confirmation != null &&
            completedOffBoundary && (suspendedGeneration == null || newerOnGeneration)) gmsSuspended = false
        armDeadlineLocked()
        return true
    }

    /** First cold interval starts only after BOTH clean consent and a real eligible host exist. */
    private fun armDeadlineLocked() {
        if (!gmsSuspended && !runtimeSuspended && saved?.scope != null && host?.eligible == true && nextAttempt == null)
            nextAttempt = deadline(nowLocked())
    }

    private fun closeLease(lease: GmsDozeCycleAutomaticLease?) {
        val ref = lease?.close() ?: return
        applicationScope.launch { bridge.revokeAutomatic(ref) }
    }
    private fun nowLocked(): Long {
        val now = elapsedMillis()
        if (now < 0 || lastElapsed?.let { now < it } == true) clockBlocked = true
        if (!clockBlocked) lastElapsed = now
        return now
    }
    private fun deadline(now: Long): Long = if (now > Long.MAX_VALUE - MIN_INTERVAL_MILLIS) {
        clockBlocked = true; Long.MAX_VALUE
    } else now + MIN_INTERVAL_MILLIS

    private fun publishLocked(outcome: String? = null) {
        val snapshot = saved
        val configured = snapshot?.scope != null && !gmsSuspended
        mutable.value = GmsDozeCycleAutomaticUiState(snapshot != null, configured, pending,
            host?.eligible == true && configured && !clockBlocked && !runtimeSuspended,
            snapshot?.blocked == true || clockBlocked || gmsSuspended || runtimeSuspended,
            when {
                clockBlocked -> "单调时钟异常，自动操作已阻断"
                runtimeSuspended -> "自动组件异常，已阻断新自动操作；已保存配置保持不变"
                gmsSuspended -> "GMS 操作已停止；须当前新代次的独立确认"
                snapshot == null -> "自动电池保护记录尚未读取"
                snapshot.blocked -> "当前记录不是完整已结算 ON；不会接管未完成操作"
                !configured -> "尚未单独确认自动 ADD；按次操作不会授权周期执行"
                pending -> "自动检查/真实结算处理中；写入门保持关闭"
                host?.eligible != true -> "自动设置已保留，等待实际已授权主机；写入门保持关闭"
                else -> "自动检查最少间隔 15 分钟；写入门保持关闭"
            }, outcome ?: mutable.value.lastOutcome, if (configured) nextAttempt else null,
            if (pending || clockBlocked || gmsSuspended || runtimeSuspended) null else snapshot?.confirmation)
    }
    companion object { const val MIN_INTERVAL_MILLIS = 15L * 60 * 1000 }
}
