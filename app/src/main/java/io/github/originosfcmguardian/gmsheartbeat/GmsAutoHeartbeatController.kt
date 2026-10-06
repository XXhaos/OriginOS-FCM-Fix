package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class GmsAutoHeartbeatUiState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val active: Boolean = false,
    val paused: Boolean = false,
    val detail: String = "低频心跳未开启",
    val requiresConfirmation: Boolean = false,
    val stopPending: Boolean = false,
    val lastStatus: GmsHeartbeatStatus? = null,
    val durableConsent: Boolean = false,
    val consentVersion: Int? = null,
    val resumeAuthorized: Boolean = false,
    val waitingForHost: Boolean = false,
    val resumeBlocked: Boolean = false,
    val manualBlocked: Boolean = true,
)

internal interface AutomaticHeartbeatBackend {
    val bundledEpochRequired: Boolean get() = false
    fun bundleAuthorized(epoch: String?): Boolean = !bundledEpochRequired
    /** Bounded enum diagnosis only; never exposes the observation's raw identity/dump. */
    val lastBindingRejection: GmsHeartbeatReason? get() = null
    val callerQualificationRequired: Boolean get() = false
    val manualUnknownSerial: Long get() = 0L
    suspend fun qualifyCaller(previous: GmsHeartbeatCallerIdentity?): GmsHeartbeatCallerQualification =
        GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
    suspend fun freshBindingDigest(): String?
    suspend fun arm(generation: Long, digest: String): GmsAutoArmResult
    suspend fun request(session: GmsAutoSession, attempt: String): GmsHeartbeatResult
    /** True only for confirmed revoke or confirmed death of the retained endpoint. */
    suspend fun revoke(session: GmsAutoSession): Boolean
}

internal data class HeartbeatHost(val id: String, val eligible: suspend () -> Boolean,
    val backend: AutomaticHeartbeatBackend)

internal enum class BundledHeartbeatProvision { DEFERRED, ASSOCIATED, BLOCKED }

/** Monotonic, completion-based deadlines. A long sleep never creates historical catch-up attempts. */
internal class HeartbeatDeadline {
    private var lastObserved: Long? = null
    private var deadline: Long? = null
    fun reset(now: Long) {
        require(now >= 0 && lastObserved?.let { now >= it } != false)
        lastObserved = now
        deadline = Math.addExact(now, GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS)
    }
    fun remaining(now: Long): Long {
        require(now >= 0 && lastObserved?.let { now >= it } != false)
        lastObserved = now
        return (requireNotNull(deadline) - now).coerceAtLeast(0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun waitHeartbeatDeadline(remaining: Long, manual: Channel<Unit>): Boolean = select {
    manual.onReceive { true }
    onTimeout(remaining) { false }
}

internal fun heartbeatResultNeedsConfirmation(result: GmsHeartbeatResult): Boolean =
    result.status == GmsHeartbeatStatus.UNKNOWN || result.status == GmsHeartbeatStatus.NOT_SENT &&
        result.reason !in setOf(GmsHeartbeatReason.OBSERVATION_UNKNOWN, GmsHeartbeatReason.REPORTED_DISCONNECTED,
            GmsHeartbeatReason.STALE_OBSERVATION, GmsHeartbeatReason.BUSY, GmsHeartbeatReason.INTERVAL_NOT_ELAPSED)

/** One heartbeat owner; its scope outlives UI and service cancellation solely to acknowledge revoke. */
internal class GmsAutoHeartbeatController(
    private val store: GmsAutoHeartbeatStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wait: suspend (Long, Channel<Unit>) -> Boolean = ::waitHeartbeatDeadline,
) {
    private data class Armed(val host: HeartbeatHost, val session: GmsAutoSession, val revision: Long)
    private val mutable = MutableStateFlow(GmsAutoHeartbeatUiState())
    val state: StateFlow<GmsAutoHeartbeatUiState> = mutable.asStateFlow()
    private val io = Mutex()
    @Volatile private var initialized = false // io mutex; fast manual admission is conservative during initialization.
    private var host: HeartbeatHost? = null // synchronized(this)
    private var revision = 0L
    private var armed: Armed? = null
    private var retained: Armed? = null
    private var uncertainArm = false
    private var disabledLocally = false
    private var schedule: Job? = null
    private val manual = Channel<Unit>(Channel.CONFLATED)
    private var consentEpoch: String? = null // New explicit consent in THIS controller/process only.
    private var closedEpoch: String? = null // Original endpoint ACK/death proof, never read from disk.
    private var coldEpoch: String? = null // Candidate only; same-boot gone/new-boot proof is qualified separately.
    private var blockedLocally = false
    private var localBlock: HeartbeatResumeBlock? = null
    private var resumeJob: Job? = null
    private var resumeToken = 0L
    private var resumeNotBefore = 0L // Survives host-event/job replacement in this process, not durable authority.
    private val resumeWake = Channel<Unit>(Channel.CONFLATED)
    private var attemptPending = false // synchronous acceptance-driver admission only

    suspend fun initialize() = io.withLock {
        if (initialized) return@withLock
        initialized = true
        try {
            val saved = store.reconstruct()
            synchronized(this) {
                if (saved?.schemaVersion == 3 && saved.durableConsent?.version in 2..3 &&
                    saved.pause == HeartbeatPause.WAITING_HOST && saved.resumeBlock == null && saved.callerIdentity != null) {
                    consentEpoch = requireNotNull(saved.durableConsent).epoch; coldEpoch = consentEpoch
                }
            }
            publish(saved, if (saved == null) "低频心跳未开启" else if (saved.pause == HeartbeatPause.OFF_PENDING)
                "旧会话停止无法确认，本版本不自动恢复" else if (synchronized(this) { coldEpoch != null })
                "等待保护服务与 Shizuku 就绪后核验旧会话；接续后重新等待至少15分钟"
                else "需重新确认后才可在当前保护会话运行")
            synchronized(this) { host?.id }?.let(::hostEligible)
        }
        catch (_: Exception) { storageFailure() }
    }

    fun attachHost(next: HeartbeatHost) {
        if (synchronized(this) { host?.id == next.id }) return
        val previous = synchronized(this) { host?.takeIf { it.id != next.id } }
        if (previous != null) endHost(previous.id)
        synchronized(this) { host = next }
        hostEligible(next.id)
    }

    /** Submission only: no disk, fresh GMS read, arm or revoke on the caller's receiver chain. */
    fun reattachForAcceptance(next: HeartbeatHost): Boolean = synchronized(this) {
        if (host?.id != next.id || armed == null || attemptPending || mutable.value.busy ||
            !mutable.value.active || !mutable.value.durableConsent || !mutable.value.resumeAuthorized ||
            blockedLocally || disabledLocally || consentEpoch == null) return false
        endHost(next.id)
        attachHost(next)
        true
    }

    /** Cheap service readiness hint. Repeated ticks merge; fresh reads are limited to 15-minute attempts. */
    fun hostEligible(id: String) {
        synchronized(this) {
            if (host?.id != id || disabledLocally || blockedLocally || closedEpoch == null && coldEpoch == null || resumeJob?.isActive == true) return
            val token = Math.addExact(resumeToken, 1).also { resumeToken = it }
            resumeJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (synchronized(this@GmsAutoHeartbeatController) {
                        !disabledLocally && !blockedLocally && (closedEpoch != null || coldEpoch != null) && host != null
                    }) {
                        val remaining = synchronized(this@GmsAutoHeartbeatController) { (resumeNotBefore - clock()).coerceAtLeast(0) }
                        if (remaining > 0) { wait(remaining, resumeWake); continue }
                        var qualificationStarted = false // This attempt/job only; readiness is not a failed fresh read.
                        if (confirmInternal(durable = true, automatic = true,
                                onQualificationStarted = { qualificationStarted = true })) break
                        if (synchronized(this@GmsAutoHeartbeatController) { closedEpoch == null && coldEpoch == null || blockedLocally || disabledLocally || host == null }) break
                        // Attach can precede the target host's first successful readiness check.
                        // A cheap not-ready result consumes no read budget; the next existing host
                        // event/tick may try again, rather than waiting a full read-failure interval.
                        if (!qualificationStarted) break
                        // This backoff belongs to the consent, not the child/host. Reattachment cannot bypass it.
                        synchronized(this@GmsAutoHeartbeatController) { resumeNotBefore = Math.addExact(clock(), GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS) }
                    }
                } finally { synchronized(this@GmsAutoHeartbeatController) { if (resumeToken == token) resumeJob = null } }
            }.also { it.start() }
        }
    }

    /** Privilege loss/master OFF/service replacement synchronously invalidates local admission. */
    fun endHost(id: String) {
        val shouldPause = synchronized(this) {
            if (host?.id != id) false else {
                host = null; closeLocal(); true
            }
        }
        if (shouldPause) queueStop(false, HeartbeatPause.HOST_LOST)
    }

    /** Host remains attached while paused, so a later explicit confirmation can freshly requalify it. */
    fun privilegeLost(id: String) {
        val shouldPause = synchronized(this) {
            if (host?.id != id || armed == null && retained == null && !uncertainArm) false else { closeLocal(); true }
        }
        if (shouldPause) queueStop(false, HeartbeatPause.HOST_LOST)
    }

    fun securityLost(id: String) {
        val matches = synchronized(this) { if (host?.id != id) false else { closeLocal(); true } }
        if (matches) queueStop(false, HeartbeatPause.RECONFIRM, block = HeartbeatResumeBlock.SECURITY_FAILURE)
    }

    fun disable() {
        synchronized(this) { disabledLocally = true; consentEpoch = null; closedEpoch = null; coldEpoch = null; closeLocal(); mutable.value = mutable.value.copy(configured = false,
            manualBlocked = true,
            active = false, busy = true, detail = "正在关闭低频心跳；已获准请求仍可能生效") }
        queueStop(true, HeartbeatPause.RECONFIRM)
    }

    suspend fun confirm(): Boolean = confirmInternal(durable = false)
    suspend fun confirmDurable(): Boolean = confirmInternal(durable = true)
    /** Only the bundled coordinator calls this. Exact child epoch reconciles parent/child crash windows.
     * Once ANY child consent was saved, never re-confirm this epoch on later polls. */
    suspend fun provisionBundled(epoch: String): BundledHeartbeatProvision {
        require(canonicalUuid(epoch)); initialize()
        val existing = io.withLock { store.read() }
        if (existing?.durableConsent?.epoch == epoch) {
            synchronized(this) { host?.id }?.let(::hostEligible)
            return if (existing.resumeBlock != null || existing.pendingArm != null || existing.pendingAttempt != null ||
                existing.currentEpochUnknown() || existing.pause in setOf(HeartbeatPause.OFF_PENDING, HeartbeatPause.RECONFIRM))
                BundledHeartbeatProvision.BLOCKED else BundledHeartbeatProvision.ASSOCIATED
        }
        val created = confirmInternal(durable = true, bundledEpoch = epoch)
        if (created) return BundledHeartbeatProvision.ASSOCIATED
        val after = io.withLock { store.read() }
        return if (after?.durableConsent?.epoch == epoch || mutable.value.stopPending || mutable.value.resumeBlocked ||
            runCatching { store.unsettledOperation() }.getOrDefault(true)) BundledHeartbeatProvision.BLOCKED
            else BundledHeartbeatProvision.DEFERRED
    }
    suspend fun manualHeartbeatAllowed(): Boolean {
        initialize()
        if (runCatching { store.unsettledOperation() }.getOrDefault(true)) return false
        return !mutable.value.manualBlocked && !mutable.value.busy
    }
    fun manualHeartbeatAllowedNow(): Boolean = initialized && !mutable.value.manualBlocked && !mutable.value.busy

    private suspend fun confirmInternal(durable: Boolean, automatic: Boolean = false,
        onQualificationStarted: (() -> Unit)? = null, bundledEpoch: String? = null): Boolean {
        initialize()
        return io.withLock {
            val entry = synchronized(this) {
                val failure = when {
                    mutable.value.busy -> "低频心跳正在处理，当前操作尚未结束"
                    armed != null -> "低频心跳已在当前保护会话确认"
                    retained != null -> "停止待确认：旧会话远端撤权尚未确认，未取得新的自动写权"
                    uncertainArm -> "旧自动会话建立结果不明，停止待确认；未取得新的自动写权"
                    host == null -> "目标保护服务会话尚未附接，未取得自动写权"
                    else -> null
                }
                if (failure != null) {
                    mutable.value = mutable.value.copy(detail = failure)
                    null
                } else {
                    // A new arm operation has a new local revision even on the same host/consent.
                    // Already queued old stop operations can never acquire its retained endpoint.
                    revision = Math.addExact(revision, 1)
                    if (!automatic) { resumeJob?.cancel(); resumeJob = null; resumeToken = Math.addExact(resumeToken, 1) }
                    Triple(requireNotNull(host), revision, requireNotNull(host).backend.manualUnknownSerial)
                }
            } ?: return@withLock false
            val owner = entry.first; val observedRevision = entry.second
            mutable.value = mutable.value.copy(busy = true,
                manualBlocked = mutable.value.manualBlocked || durable && !automatic && owner.backend.callerQualificationRequired,
                detail = "正在核对当前保护会话与 GMS 安装身份")
            var prepared: HeartbeatSnapshot? = null
            var armStarted = false
            var armOperation: String? = null
            try {
                val previous = store.read()
                val childEpoch = if (automatic) previous?.durableConsent?.epoch else bundledEpoch
                if (owner.backend.bundledEpochRequired && !owner.backend.bundleAuthorized(childEpoch)) {
                    publish(previous, "未取得当前统一授权的维护子授权；旧分开授权不升级")
                    return@withLock false
                }
                val cold = automatic && synchronized(this) { coldEpoch != null }
                val resumeEpoch = synchronized(this) { if (cold) coldEpoch else closedEpoch }
                if (automatic && (previous == null || previous.durableConsent?.epoch != resumeEpoch ||
                    previous.resumeBlock != null || previous.pause != HeartbeatPause.WAITING_HOST ||
                    previous.pendingAttempt != null || previous.pendingArm != null || previous.currentEpochUnknown() ||
                    synchronized(this) { consentEpoch != resumeEpoch || disabledLocally || blockedLocally })) return@withLock false
                if (previous?.pause == HeartbeatPause.OFF_PENDING) {
                    publish(previous, "旧会话停止无法确认，本版本不自动恢复")
                    return@withLock false
                }
                if (owner.backend.callerQualificationRequired && store.unsettledOperation()) {
                    publish(previous, "旧心跳操作尚未结算，不能重新确认或手动绕过")
                    return@withLock false
                }
                if (!current(owner, observedRevision)) {
                    publish(previous, "目标保护服务会话已变化，未取得自动写权")
                    return@withLock false
                }
                if (!owner.eligible()) {
                    publish(previous, "目标保护尚未满足运行资格（实际检测或当前应用授权），未取得自动写权")
                    return@withLock false
                }
                onQualificationStarted?.invoke()
                val qualification = owner.backend.qualifyCaller(if (cold) previous?.callerIdentity else null)
                val caller = qualification.current
                val priorCaller = previous?.callerIdentity
                val sameBootGone = caller != null && priorCaller != null && caller.uid == priorCaller.uid &&
                    caller.kernelBootId == priorCaller.kernelBootId && caller != priorCaller &&
                    qualification.reason == GmsHeartbeatCallerReason.OLD_INSTANCE_GONE
                // BOOT_CHANGED comes only from the bounded real Binder-caller probe, whose
                // current process, user0, exclusive UID and boot survive the complete read.
                // A new boot ends old remote processes; pending/UNKNOWN/OFF barriers still block.
                val newBootGone = previous?.durableConsent?.version == 3 && caller != null && priorCaller != null &&
                    caller.uid == priorCaller.uid && caller.kernelBootId != priorCaller.kernelBootId &&
                    qualification.reason == GmsHeartbeatCallerReason.BOOT_CHANGED
                if (cold && !sameBootGone && !newBootGone) {
                    blockResume(requireNotNull(previous), if (qualification.reason == GmsHeartbeatCallerReason.BOOT_CHANGED)
                        HeartbeatResumeBlock.COLD_RECONSTRUCTION else HeartbeatResumeBlock.UNCERTAIN_OWNER)
                    return@withLock false
                }
                if (!cold && owner.backend.callerQualificationRequired &&
                    (qualification.reason != GmsHeartbeatCallerReason.CURRENT_ONLY || caller == null)) {
                    if (automatic && previous != null) blockResume(previous, HeartbeatResumeBlock.SECURITY_FAILURE)
                    else publish(previous, "当前 App 实例无法安全核验，未取得自动写权")
                    return@withLock false
                }
                if (!current(owner, observedRevision)) return@withLock false
                val digest = owner.backend.freshBindingDigest()
                if (!current(owner, observedRevision)) {
                    publish(previous, "宿主已变化，未建立新自动会话")
                    return@withLock false
                }
                if (digest == null) {
                    if (automatic && owner.backend.lastBindingRejection in setOf(GmsHeartbeatReason.IDENTITY_CHANGED,
                            GmsHeartbeatReason.CALLER_UNAVAILABLE, GmsHeartbeatReason.SHELL_UNAVAILABLE)) {
                        blockResume(requireNotNull(previous), if (owner.backend.lastBindingRejection == GmsHeartbeatReason.IDENTITY_CHANGED)
                            HeartbeatResumeBlock.IDENTITY_CHANGED else HeartbeatResumeBlock.SECURITY_FAILURE)
                        return@withLock false
                    }
                    val rejection = owner.backend.lastBindingRejection?.let { "（${it.name}）" }.orEmpty()
                    publish(previous, "当前 GMS 连接或安装身份无法确认$rejection，尚未取得自动写权")
                    return@withLock false
                }
                if (automatic && digest != previous?.durableConsent?.bindingDigest) {
                    blockResume(requireNotNull(previous), HeartbeatResumeBlock.IDENTITY_CHANGED)
                    return@withLock false
                }
                if (!owner.eligible() || !current(owner, observedRevision) ||
                    owner.backend.bundledEpochRequired && !owner.backend.bundleAuthorized(childEpoch)) {
                    publish(previous, "核对 GMS 后目标保护会话或运行资格已变化，未取得自动写权")
                    return@withLock false
                }
                prepared = if (automatic) store.prepareResume(requireNotNull(previous), requireNotNull(requireNotNull(previous).durableConsent).epoch, caller)
                    else store.prepareConfirmation(previous, digest, durable, caller, bundledEpoch)
                if (prepared == null) {
                    publish(previous, "心跳配置代际已变化或旧停止尚未确认，未取得自动写权")
                    return@withLock false
                }
                synchronized(this) { if (current(owner, observedRevision)) {
                    disabledLocally = false
                    if (!automatic) { consentEpoch = prepared.durableConsent?.epoch; blockedLocally = false; localBlock = null; resumeNotBefore = 0 }
                    if (!automatic) { closedEpoch = null; coldEpoch = null }
                } }
                if (!owner.eligible() || !current(owner, observedRevision) ||
                    owner.backend.bundledEpochRequired && !owner.backend.bundleAuthorized(prepared.durableConsent?.epoch)) {
                    if (automatic) prepared = store.abortUnarmedResume(prepared.generation) ?: prepared
                    publish(prepared, if (automatic) "宿主尚未合格，接续延后至少15分钟；未建立新会话" else "宿主已变化，需重新确认")
                    return@withLock false
                }
                armOperation = UUID.randomUUID().toString()
                prepared = store.beginArm(prepared.generation, armOperation) ?: error("Arm intent root changed")
                if (!owner.eligible() || !current(owner, observedRevision) ||
                    owner.backend.bundledEpochRequired && !owner.backend.bundleAuthorized(prepared.durableConsent?.epoch)) {
                    // No IPC was invoked, but interrupted admission stays fail closed.
                    blockResume(prepared, HeartbeatResumeBlock.LEFTOVER_PENDING)
                    return@withLock false
                }
                armStarted = true
                synchronized(this) { closedEpoch = null; coldEpoch = null }
                val result = owner.backend.arm(prepared.generation, digest)
                val session = result.session
                if (session == null) {
                    // Timeout/transport uncertainty cannot be resolved by ordinary rebind/reconfirm.
                    val uncertain = result.reason in setOf(GmsAutoArmReason.TIMED_OUT, GmsAutoArmReason.BRIDGE_UNAVAILABLE,
                        GmsAutoArmReason.MALFORMED_RESPONSE)
                    synchronized(this) { uncertainArm = uncertain }
                    if (uncertain) blockResume(prepared, HeartbeatResumeBlock.UNCERTAIN_OWNER)
                    else if (automatic) {
                        // A refused automatic ARM is not a clean cold-resume root. Preserve that
                        // refusal independently of AtomicFile rollback before settling its marker.
                        when (result.reason) {
                            GmsAutoArmReason.BUSY, GmsAutoArmReason.NOT_ELIGIBLE -> store.persistHalt()
                            GmsAutoArmReason.IDENTITY_CHANGED -> blockResume(prepared, HeartbeatResumeBlock.IDENTITY_CHANGED)
                            else -> blockResume(prepared, HeartbeatResumeBlock.SECURITY_FAILURE)
                        }
                    }
                    val paused = if (uncertain) store.pause(prepared.generation, HeartbeatPause.OFF_PENDING)
                        else store.armRejected(prepared.generation)
                    if (!uncertain && paused != null) {
                        store.settleArm(paused, requireNotNull(armOperation))
                    }
                    publish(paused, if (uncertain) "停止待确认：自动会话建立结果不明，不能重新绑定绕过"
                        else "未取得自动写权：${result.reason}，需重新确认")
                    return@withLock false
                }
                val acquired = Armed(owner, session, observedRevision)
                synchronized(this) { retained = acquired }
                require(session.generation == prepared.generation && session.bindingDigest == digest && result.reason == GmsAutoArmReason.ARMED)
                if (!owner.eligible() || !current(owner, observedRevision)) {
                    publish(store.pause(prepared.generation, HeartbeatPause.OFF_PENDING), "停止待确认：宿主已变化")
                    queueStop(false, HeartbeatPause.HOST_LOST)
                    return@withLock false
                }
                val saved = store.armed(prepared.generation, session.id) ?: error("Heartbeat arm root changed")
                store.settleArm(saved, requireNotNull(armOperation))
                if (owner.backend.manualUnknownSerial != entry.third) {
                    val unknown = store.manualUnknown(saved.generation) ?: saved
                    blockResume(unknown, HeartbeatResumeBlock.UNKNOWN_RESULT)
                    synchronized(this) { closeLocal() }
                    queueStop(false, HeartbeatPause.UNKNOWN_RESULT, block = HeartbeatResumeBlock.UNKNOWN_RESULT)
                    return@withLock false
                }
                // Shared transport's successful ARM excludes any preceding manual call.
                // Until this exact outcome and snapshot/marker commit, halt guards a new policy3 consent.
                if (!automatic && saved.schemaVersion == 3) store.releaseExplicitConsent(saved)
                synchronized(this) {
                    if (!current(owner, observedRevision)) {
                        queueStop(false, HeartbeatPause.HOST_LOST)
                        return@withLock false
                    }
                    armed = acquired
                    publish(saved, "当前会话已确认；首次请求将在至少15分钟后，休眠可能延后")
                    launchSchedule(acquired)
                }
                true
            } catch (cancelled: CancellationException) {
                if (automatic && !armStarted) {
                    prepared?.let {
                        if (it.pendingArm != null) blockResume(it, HeartbeatResumeBlock.LEFTOVER_PENDING)
                        else store.abortUnarmedResume(it.generation)
                    }
                    throw cancelled // The clean host/OFF event that cancelled the child owns its stop/root.
                }
                synchronized(this) { if (armStarted && retained == null) uncertainArm = true; closeLocal() }
                queueStop(false, HeartbeatPause.RECONFIRM, block = if (armStarted) HeartbeatResumeBlock.UNCERTAIN_OWNER else null)
                throw cancelled
            } catch (_: Exception) {
                synchronized(this) { if (armStarted && retained == null) uncertainArm = true; closeLocal() }
                storageFailure()
                if (prepared != null) queueStop(false, HeartbeatPause.STORAGE_FAILURE, block = HeartbeatResumeBlock.STORAGE_FAILURE)
                false
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun manualHeartbeatChanged() { manual.trySend(Unit) }

    /** A manual event with unknown outcome also invalidates automatic authority, never target consent. */
    fun manualUnknown() {
        synchronized(this) {
            closeLocal()
            mutable.value = mutable.value.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, paused = true,
                requiresConfirmation = true, detail = "手动心跳结果不明，自动心跳已暂停；旧请求不重发")
        }
        queueStop(false, HeartbeatPause.UNKNOWN_RESULT, manualUnknown = true, block = HeartbeatResumeBlock.UNKNOWN_RESULT)
    }

    private fun current(owner: HeartbeatHost, expectedRevision: Long): Boolean = synchronized(this) {
        host === owner && revision == expectedRevision
    }
    private fun live(value: Armed): Boolean = synchronized(this) {
        armed === value && current(value.host, value.revision)
    }
    private fun closeLocal() {
        revision = Math.addExact(revision, 1)
        armed = null
        attemptPending = false
        schedule?.cancel(); schedule = null
        resumeJob?.cancel(); resumeJob = null
        resumeToken = Math.addExact(resumeToken, 1)
        mutable.value = mutable.value.copy(active = false)
    }

    private fun launchSchedule(value: Armed) {
        while (manual.tryReceive().isSuccess) { /* Prior manual history is covered by this confirmation deadline. */ }
        val deadline = HeartbeatDeadline().apply { reset(clock()) }
        schedule = scope.launch {
            var dispatched = false
            try {
                while (live(value)) {
                    val wasManual = wait(deadline.remaining(clock()), manual)
                    if (!live(value)) break
                    if (wasManual) { deadline.reset(clock()); continue }
                    if (deadline.remaining(clock()) > 0) continue
                    if (!value.host.eligible() || value.host.backend.bundledEpochRequired &&
                        !value.host.backend.bundleAuthorized(io.withLock { store.read()?.durableConsent?.epoch })) {
                        mutable.value = mutable.value.copy(detail = "宿主暂未满足运行条件，至少15分钟后再核对；未发送")
                        deadline.reset(clock())
                        continue
                    }
                    val attempt = UUID.randomUUID().toString()
                    synchronized(this@GmsAutoHeartbeatController) { if (live(value)) attemptPending = true }
                    val pending = io.withLock {
                        if (!live(value)) null else store.beginAttempt(value.session.generation, attempt)
                    }
                    if (pending == null) {
                        if (live(value)) error("Heartbeat pending admission no longer matches storage")
                        break
                    }
                    if (!live(value)) break
                    // Disk persistence can outlive the host evidence obtained before admission.
                    // Recheck after the pending commit, then repeat local lifetime admission.
                    val hostStillEligible = value.host.eligible()
                    if (!live(value)) break
                    if (!hostStillEligible) {
                        val unsent = GmsHeartbeatResult.notSent(1, GmsHeartbeatReason.CANCELLED)
                        val settled = io.withLock {
                            if (!live(value)) null else store.complete(value.session.generation, attempt, unsent)?.also {
                                store.settleAttempt(it, attempt, unsent)
                            }
                        }
                        if (settled == null) {
                            if (live(value)) error("Unsent heartbeat no longer matches pending storage")
                            break
                        }
                        publish(settled, "宿主条件已变化，本次未发送；至少15分钟后再核对")
                        synchronized(this@GmsAutoHeartbeatController) { attemptPending = false }
                        deadline.reset(clock())
                        continue
                    }
                    mutable.value = mutable.value.copy(busy = true, detail = "请求处理中；不表示服务器应答或消息收益")
                    dispatched = true
                    val result = value.host.backend.request(value.session, requireNotNull(pending.pendingAttempt))
                    val saved = io.withLock {
                        if (!live(value)) null else store.complete(value.session.generation, attempt, result)?.also {
                            store.settleAttempt(it, attempt, result)
                        }
                    }
                    if (saved == null) {
                        if (live(value)) error("Heartbeat result no longer matches pending storage")
                        break
                    }
                    dispatched = false
                    synchronized(this@GmsAutoHeartbeatController) { attemptPending = false }
                    publish(saved, when (result.status) {
                        GmsHeartbeatStatus.NOT_SENT -> "上次未发送（${result.reason}）；完成后至少15分钟再核对"
                        GmsHeartbeatStatus.REQUESTED -> "上次请求已提交；未验证 ACK、修复或通知收益"
                        GmsHeartbeatStatus.UNKNOWN -> "上次结果不明，已暂停；不自动重试，需重新确认"
                    })
                    if (heartbeatResultNeedsConfirmation(result)) {
                        synchronized(this@GmsAutoHeartbeatController) { if (live(value)) closeLocal() }
                        queueStop(false, if (result.status == GmsHeartbeatStatus.UNKNOWN) HeartbeatPause.UNKNOWN_RESULT else HeartbeatPause.RECONFIRM,
                            block = if (result.status == GmsHeartbeatStatus.UNKNOWN) HeartbeatResumeBlock.UNKNOWN_RESULT
                                else if (result.reason == GmsHeartbeatReason.IDENTITY_CHANGED) HeartbeatResumeBlock.IDENTITY_CHANGED else HeartbeatResumeBlock.SECURITY_FAILURE)
                        break
                    }
                    deadline.reset(clock())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(this@GmsAutoHeartbeatController) { closeLocal() }
                storageFailure()
                if (dispatched) mutable.value = mutable.value.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN,
                    detail = "心跳结果无法保存，已暂停；不重发旧请求，需重新确认")
                queueStop(false, HeartbeatPause.STORAGE_FAILURE, block = HeartbeatResumeBlock.STORAGE_FAILURE)
            } finally {
                synchronized(this@GmsAutoHeartbeatController) { if (armed === value || retained === value) attemptPending = false }
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private fun blockResume(value: HeartbeatSnapshot, reason: HeartbeatResumeBlock) {
        synchronized(this) { blockedLocally = true; localBlock = localBlock ?: reason; closedEpoch = null; coldEpoch = null }
        val saved = store.block(value.generation, reason) ?: value
        publish(saved, "接续已阻断（${reason.name}），需明确重新确认；旧请求不重发")
    }

    private fun queueStop(disablePreference: Boolean, reason: HeartbeatPause, manualUnknown: Boolean = false,
        block: HeartbeatResumeBlock? = null) {
        val stopRevision = synchronized(this) {
            if (block != null) { blockedLocally = true; localBlock = localBlock ?: block }
            if (block != null || disablePreference) { closedEpoch = null; coldEpoch = null }
            revision
        }
        scope.launch { stopRemote(disablePreference, reason, manualUnknown, block, stopRevision) }
    }

    private suspend fun stopRemote(disablePreference: Boolean, reason: HeartbeatPause, manualUnknown: Boolean,
        block: HeartbeatResumeBlock?, stopRevision: Long) {
        io.withLock {
            if (synchronized(this) { revision != stopRevision }) return@withLock
            val remote = synchronized(this) { retained }
            val unknown = synchronized(this) { uncertainArm }
            val effectiveDisable = disablePreference || synchronized(this) { disabledLocally }
            val effectiveBlock = block ?: synchronized(this) { localBlock }
            var saved: HeartbeatSnapshot? = null
            try {
                if (effectiveDisable || effectiveBlock != null) store.persistHalt()
                var old = store.read()
                if (manualUnknown && old != null) old = store.manualUnknown(old.generation) ?: old
                if (effectiveBlock != null && old != null) old = store.block(old.generation, effectiveBlock) ?: old
                val pending = remote != null || unknown || old?.pause == HeartbeatPause.OFF_PENDING
                val alreadyWaiting = !pending && reason == HeartbeatPause.HOST_LOST && old?.pause == HeartbeatPause.WAITING_HOST &&
                    old?.durableConsent?.epoch == synchronized(this) { coldEpoch ?: closedEpoch } && effectiveBlock == null
                saved = if (effectiveDisable) store.off(pending)
                else old?.let { store.pause(it.generation, if (pending) HeartbeatPause.OFF_PENDING else if (alreadyWaiting) HeartbeatPause.WAITING_HOST else reason) }
                publish(saved, if (saved == null) mutable.value.detail else if (pending && remote == null) "旧会话停止无法确认，本版本不自动恢复；已获准请求仍可能生效"
                    else if (pending) "停止待确认；已获准请求仍可能生效"
                    else if (effectiveDisable) "低频心跳已关闭；已提交请求不能撤回"
                    else if (alreadyWaiting) "等待保护服务恢复；合格后重新等待至少15分钟"
                    else "低频心跳已暂停，需重新确认")
            } catch (_: Exception) { storageFailure() }
            val confirmed = remote != null && runCatching { remote.host.backend.revoke(remote.session) }.getOrDefault(false)
            if (synchronized(this) { revision != stopRevision }) return@withLock
            if (confirmed) {
                synchronized(this) { if (retained === remote) retained = null }
                // A later OFF/host loss owns any further persistence; a late ACK cannot change its root.
                try {
                    saved?.let { saved = store.acknowledgeStop(it.generation, clean = !effectiveDisable && reason == HeartbeatPause.HOST_LOST && effectiveBlock == null) ?: saved }
                    synchronized(this) {
                        val consent = saved?.durableConsent
                        if (saved?.pause == HeartbeatPause.WAITING_HOST && !disabledLocally && !blockedLocally &&
                            consent != null && consent.epoch == consentEpoch) closedEpoch = consent.epoch
                    }
                    if (saved != null) publish(saved, if (effectiveDisable) "低频心跳已关闭；已提交请求不能撤回"
                        else if (saved?.pause == HeartbeatPause.WAITING_HOST) "旧会话已确认停止，等待合格宿主干净接续；接续后重新等待至少15分钟"
                        else "低频心跳已暂停，需重新确认；已提交请求不能撤回")
                    synchronized(this) { host?.id }?.let(::hostEligible)
                } catch (_: Exception) { storageFailure() }
            } else if (remote != null || unknown) {
                mutable.value = mutable.value.copy(active = false, paused = true, stopPending = true,
                    requiresConfirmation = true, detail = if (remote == null) "旧会话停止无法确认，本版本不自动恢复"
                        else "停止待确认：远端撤权未确认，不能重新绑定绕过")
            }
        }
    }

    private fun publish(value: HeartbeatSnapshot?, detail: String) {
        val active = synchronized(this) { armed != null && !disabledLocally } && value?.pause == null
        val configured = synchronized(this) { !disabledLocally } && value?.configured == true
        val resumeAuthorized = synchronized(this) { !disabledLocally && !blockedLocally && consentEpoch != null && consentEpoch == value?.durableConsent?.epoch } && value?.resumeBlock == null
        val waiting = resumeAuthorized && value?.pause == HeartbeatPause.WAITING_HOST
        mutable.value = GmsAutoHeartbeatUiState(configured = configured,
            active = active,
            paused = value?.pause != null, detail = detail,
            requiresConfirmation = !waiting && (configured && !active || value?.pause != null),
            stopPending = value?.pause == HeartbeatPause.OFF_PENDING, lastStatus = value?.lastStatus,
            durableConsent = value?.durableConsent != null, consentVersion = value?.durableConsent?.version,
            resumeAuthorized = resumeAuthorized,
            waitingForHost = waiting, resumeBlocked = value?.resumeBlock != null || synchronized(this) { blockedLocally },
            manualBlocked = value?.schemaVersion == 3 && value.durableConsent != null || value?.pause == HeartbeatPause.OFF_PENDING ||
                runCatching { store.unsettledOperation() }.getOrDefault(true) ||
                synchronized(this) { retained != null || uncertainArm })
    }
    private fun storageFailure() {
        synchronized(this) { blockedLocally = true; localBlock = localBlock ?: HeartbeatResumeBlock.STORAGE_FAILURE; closedEpoch = null; coldEpoch = null }
        // Best effort independent barrier; failure remains blocked locally and is never called success.
        runCatching { store.persistHalt() }
        mutable.value = mutable.value.copy(active = false, busy = false, paused = true,
            requiresConfirmation = true, resumeAuthorized = false, resumeBlocked = true, waitingForHost = false, manualBlocked = true,
            detail = "心跳记录无法确认或保存，已暂停；应用选择保持不变")
    }
}
