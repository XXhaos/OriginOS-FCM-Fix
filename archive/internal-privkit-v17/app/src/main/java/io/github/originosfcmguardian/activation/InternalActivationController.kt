package io.github.originosfcmguardian.activation

import android.content.Context
import android.os.IBinder
import io.github.originosfcmguardian.privilegebridge.PrivKitReadOnlyIdentityAdapter
import io.github.originosfcmguardian.privilegebridge.PrivilegeIdentityResult
import io.github.originosfcmguardian.privilegebridge.ReadOnlyTransportChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeConfig
import priv.kit.core.adb.PrivilegeAdbConnectionOptions
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl

data class InternalActivationStatus(
    val busy: Boolean = false,
    val ready: Boolean = false,
    val needsManualCleanup: Boolean = false,
    val message: String = "内置启动尚未验证。请先完成 Guardian 自有配对。",
    val stage: ActivationStage? = null,
    val failure: ActivationDiagnostic? = null,
    val cleanupFailure: ActivationDiagnostic? = null,
    val paired: Boolean = false,
)

/** Explicit foreground actions only. This status is never recovery/protection authorization. */
class InternalActivationController private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val generations = ActivationGeneration()
    private val wireless = WirelessTransaction(AndroidWirelessPort(context), AtomicWirelessLeaseStore(context))
    private val mutableStatus = MutableStateFlow(InternalActivationStatus())
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null
    private var operationTicket: Long? = null
    private var interruptionMessage: String? = null
    private var readyBinder: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    @Volatile private var notificationPairingOwner: String? = null

    val notificationPairingInProgress: Boolean get() = notificationPairingOwner != null

    internal fun reserveNotificationPairing(owner: String): Boolean = synchronized(lock) {
        if (notificationPairingOwner != null || job?.isCompleted == false || mutableStatus.value.needsManualCleanup) return@synchronized false
        notificationPairingOwner = owner
        true
    }

    /** Activity backgrounding owns only UI operations, never a notification pairing session. */
    fun cancelUiOperation() {
        // onStop shares the service's Main thread; do not wait behind notification preflight.
        if (notificationPairingOwner != null) return
        synchronized(lock) {
            if (notificationPairingOwner == null && mutableStatus.value.busy) cancel()
        }
    }

    internal fun cancelNotificationPairing(owner: String) = synchronized(lock) {
        if (notificationPairingOwner == owner) {
            if (operationTicket != null && job?.isCompleted == false) cancel()
            else notificationPairingOwner = null
        }
    }

    internal fun pairFromNotification(code: String, port: Int, owner: String, budgetMillis: Long, token: PairingOperationToken,
        finished: (InternalActivationStatus) -> Unit): Boolean {
        if (!ActivationPolicy.pairingCode(code) || port !in 1..65535 || budgetMillis !in 1..OPERATION_MILLIS) return false
        return operate(pairing = true, notificationOwner = owner, notificationToken = token, operationBudgetMillis = budgetMillis, finished = finished) { stage ->
            stage(ActivationStage.PREFLIGHT)
            val manager = Privilege.createAdbManager()
            stage(ActivationStage.PAIRING_REQUEST)
            token.ensureLive()
            manager.pair(pairingCode = code, port = port, portDiscoveryTimeoutMillis = DISCOVERY_MILLIS)
            null
        }
    }

    init {
        // No library owner restart/reconnect. Configuration precedes all explicit launches.
        PrivilegeConfig.configure(followDeathDelayMillis = 0, activeReconnectOnOwnerDeath = false)
        val pending = runCatching { wireless.hasPending() }
        if (pending.getOrDefault(true)) {
            mutableStatus.value = InternalActivationStatus(needsManualCleanup = true,
                message = "有未完成的无线事务。请在系统设置关闭无线调试，再确认已手动处理。")
        }
        scope.launch {
            Privilege.serverState.collect { server ->
                synchronized(lock) {
                    val expected = readyBinder
                    if (expected != null && (server?.lifecycleBinder !== expected || server.uid != 2000 || !expected.isBinderAlive)) {
                        invalidateReady("内置服务已断开，请手动重新启动。")
                    }
                }
            }
        }
    }

    fun activate(connectPort: String) {
        val port = parsePort(connectPort) ?: if (connectPort.isBlank()) null else return
        operate(pairing = false) { stage ->
            stage(ActivationStage.PREFLIGHT)
            val manager = Privilege.createAdbManager()
            // Resolve TLS separately instead of allowing any library TCP fallback.
            val resolved = port ?: run {
                stage(ActivationStage.CONNECT_DISCOVERY)
                manager.discoverConnectPort(timeoutMillis = DISCOVERY_MILLIS)
            }
            require(resolved in 1..65535)
            stage(ActivationStage.START_REQUEST)
            Privilege.startAdb(
                options = PrivilegeAdbConnectionOptions(
                    port = resolved,
                    wirelessDebuggingControl = PrivilegeAdbWirelessDebuggingControl.NEVER,
                    disableWirelessDebuggingAfterUse = false,
                    portDiscoveryTimeoutMillis = DISCOVERY_MILLIS,
                    connectRetryCount = 1,
                    connectRetryDelayMillis = 250,
                ),
                timeoutMillis = 15_000,
            ).lifecycleBinder
        }
    }

    fun cancel() = synchronized(lock) {
        interruptionMessage = "已取消，迟到结果已丢弃。"
        generations.cancel()
        unlinkReady()
        job?.cancel()
        mutableStatus.value = mutableStatus.value.copy(ready = false, paired = false,
            failure = ActivationDiagnostic(mutableStatus.value.stage ?: ActivationStage.PREFLIGHT, ActivationFailureKind.CANCELLED),
            message = if (job?.isCompleted == false) "正在取消并清理本次无线事务…" else "内置启动已取消。")
    }

    fun acknowledgeManualCleanup() = synchronized(lock) {
        if (job?.isCompleted == false) return@synchronized
        job = scope.launch {
            val success = runCatching { wireless.acknowledgeManualCleanup() }.isSuccess
            synchronized(lock) {
                mutableStatus.value = InternalActivationStatus(needsManualCleanup = !success,
                    message = if (success) "已确认无线调试关闭，可重新配对或启动。" else "请先在系统设置关闭无线调试，再确认。")
            }
        }
    }

    private fun operate(pairing: Boolean, notificationOwner: String? = null,
        notificationToken: PairingOperationToken? = null,
        operationBudgetMillis: Long = OPERATION_MILLIS,
        finished: ((InternalActivationStatus) -> Unit)? = null,
        action: suspend ((ActivationStage) -> Unit) -> IBinder?): Boolean = synchronized(lock) {
        if (!notificationPairingOwnerAllows(notificationPairingOwner, notificationOwner)) return@synchronized false
        if (notificationToken?.isLive == false) return@synchronized false
        if (job?.isCompleted == false) return@synchronized false
        val pending = runCatching {
            if (notificationToken == null) wireless.hasPending()
            else notificationToken.readWhileLive { wireless.hasPending() }
        }
        if (notificationToken?.isLive == false) return@synchronized false
        if (pending.getOrDefault(true)) {
            unlinkReady()
            mutableStatus.value = InternalActivationStatus(needsManualCleanup = true,
                message = "有未完成的无线事务，请先手动关闭无线调试并确认。",
                stage = ActivationStage.PREFLIGHT,
                failure = pending.exceptionOrNull()?.let { activationDiagnostic(ActivationStage.PREFLIGHT, it) }
                    ?: ActivationDiagnostic(ActivationStage.PREFLIGHT, ActivationFailureKind.PRECONDITION))
            return@synchronized false
        }
        val existing = if (pairing) null else Privilege.serverState.value?.lifecycleBinder
        val preflight = runCatching {
            if (notificationToken == null) wireless.preflightFailure(wirelessRequired = existing == null)
            else notificationToken.readWhileLive { wireless.preflightFailure(wirelessRequired = existing == null) }
        }
        if (notificationToken?.isLive == false) return@synchronized false
        val failure = preflight
            .getOrElse { "无法确认启动前置条件，未执行操作。" }
        if (failure != null) {
            unlinkReady()
            mutableStatus.value = InternalActivationStatus(message = failure, stage = ActivationStage.PREFLIGHT,
                failure = preflight.exceptionOrNull()?.let { activationDiagnostic(ActivationStage.PREFLIGHT, it) }
                    ?: ActivationDiagnostic(ActivationStage.PREFLIGHT, ActivationFailureKind.PRECONDITION))
            return@synchronized false
        }
        val ticket = generations.begin() ?: return@synchronized false
        operationTicket = ticket
        notificationPairingOwner = notificationOwner
        interruptionMessage = null
        unlinkReady()
        mutableStatus.value = InternalActivationStatus(busy = true, stage = ActivationStage.PREFLIGHT,
            message = if (pairing) "正在配对 Guardian 自有密钥…" else "正在启动并核验 shell 身份…")
        job = scope.launch(start = CoroutineStart.LAZY) {
            var binder: IBinder? = null
            var completed = false
            var cancelled = false
            var cleaned = false
            var needsManual = false
            var wirelessStarted = false
            var stage = ActivationStage.PREFLIGHT
            var operationFailure: ActivationDiagnostic? = null
            var cleanupFailure: ActivationDiagnostic? = null
            val markStage: (ActivationStage) -> Unit = { next ->
                stage = next
                synchronized(lock) {
                    if (activationDiagnosticBelongsToOperation(operationTicket, ticket) && generations.accepts(ticket)) {
                        mutableStatus.value = mutableStatus.value.copy(stage = next)
                    }
                }
            }
            try {
                withTimeout(operationBudgetMillis) {
                    notificationToken?.ensureLive()
                    if (existing == null) {
                        markStage(ActivationStage.WIRELESS_PREPARE)
                        notificationToken?.ensureLive()
                        wirelessStarted = true
                        wireless.begin()
                    }
                    currentCoroutineContext().ensureActive()
                    notificationToken?.ensureLive()
                    // An already connected app-owned daemon only needs fresh read-only identity.
                    // Never restart it merely because the user clicks activation again.
                    binder = existing ?: action(markStage)
                    currentCoroutineContext().ensureActive()
                    if (!pairing) {
                        val serverBinder = requireNotNull(binder)
                        markStage(ActivationStage.IDENTITY_BEFORE)
                        verifyIdentity(serverBinder)
                        synchronized(lock) {
                            check(generations.accepts(ticket))
                            readyBinder = serverBinder
                            death = IBinder.DeathRecipient {
                                synchronized(lock) {
                                    if (readyBinder === serverBinder) {
                                        invalidateReady("内置服务已断开，请手动重新启动。")
                                    }
                                }
                            }
                            serverBinder.linkToDeath(requireNotNull(death), 0)
                        }
                    }
                    completed = true
                }
            } catch (failure: TimeoutCancellationException) {
                operationFailure = activationDiagnostic(stage, failure)
            } catch (failure: CancellationException) {
                cancelled = true
                operationFailure = activationDiagnostic(stage, failure)
            } catch (failure: LinkageError) {
                operationFailure = activationDiagnostic(stage, failure)
            } catch (failure: Exception) {
                operationFailure = activationDiagnostic(stage, failure)
            } finally {
                withContext(NonCancellable) {
                    markStage(ActivationStage.CLEANUP)
                    val cleanup = if (wirelessStarted) runCatching { wireless.cleanup() } else Result.success(true)
                    cleaned = cleanup.getOrDefault(false)
                    if (!cleaned) cleanupFailure = cleanup.exceptionOrNull()?.let { activationDiagnostic(ActivationStage.CLEANUP, it) }
                        ?: ActivationDiagnostic(ActivationStage.CLEANUP, ActivationFailureKind.UNKNOWN)
                    if (!cleaned) needsManual = true else {
                        val remaining = runCatching { wireless.hasPending() }
                        needsManual = remaining.getOrDefault(true)
                        if (needsManual) cleanupFailure = remaining.exceptionOrNull()?.let { activationDiagnostic(ActivationStage.CLEANUP, it) }
                            ?: ActivationDiagnostic(ActivationStage.CLEANUP, ActivationFailureKind.PRECONDITION)
                    }
                    if (needsManual) wireless.abandonUncertainOwnership()
                }
            }
            try {
                if (completed && cleaned && !needsManual && !pairing && !cancelled && generations.accepts(ticket)) {
                    // A fresh dedicated RPC after wireless cleanup on the SAME lifecycle Binder.
                    markStage(ActivationStage.IDENTITY_AFTER)
                    verifyIdentity(requireNotNull(binder))
                }
            } catch (failure: LinkageError) {
                operationFailure = activationDiagnostic(stage, failure)
            } catch (failure: Exception) {
                operationFailure = activationDiagnostic(stage, failure)
                if (failure is CancellationException && failure !is TimeoutCancellationException) cancelled = true
            } finally {
                synchronized(lock) {
                    if (activationDiagnosticBelongsToOperation(operationTicket, ticket)) {
                        val accepted = generations.accepts(ticket) && notificationToken?.isLive != false && !cancelled && completed && cleaned && !needsManual && operationFailure == null
                        val ready = accepted && !pairing && currentServerIs(binder)
                        if (!ready) unlinkReady()
                        val visibleFailure = if (notificationToken?.isLive == false || (!generations.accepts(ticket) && interruptionMessage != null))
                            ActivationDiagnostic(operationFailure?.stage ?: stage, ActivationFailureKind.CANCELLED) else operationFailure
                        val diagnostic = primaryActivationDiagnostic(visibleFailure, cleanupFailure)
                        mutableStatus.value = InternalActivationStatus(ready = ready, paired = accepted && pairing, needsManualCleanup = needsManual,
                            stage = diagnostic?.stage ?: stage, failure = diagnostic, cleanupFailure = cleanupFailure,
                            message = when {
                                needsManual -> "无线事务未能确认清理。请手动关闭无线调试并确认已处理。"
                                ready -> "内置 shell UID2000/user0 已通过前后核验。尚未接入推送保护。"
                                accepted && pairing -> "Guardian 自有配对完成。可点击启动并核验。"
                                cancelled || !generations.accepts(ticket) -> interruptionMessage ?: "已取消，迟到结果已丢弃。"
                                else -> diagnostic?.guidance ?: interruptionMessage ?: "操作未通过，请核对无线调试条件。"
                            })
                    }
                    generations.finish(ticket)
                }
            }
        }.also { worker ->
            // A LAZY coroutine cancelled before dispatch never enters its try/finally.
            // Completion terminalizes that generation too, so foreground cancellation cannot
            // leave the UI permanently busy or let an old completion overwrite newer work.
            generations.finishOnCompletion(worker, ticket) {
                synchronized(lock) {
                    if (operationTicket == ticket) {
                        operationTicket = null
                        notificationPairingOwner = null
                        if (mutableStatus.value.busy) mutableStatus.value = mutableStatus.value.copy(
                            busy = false, ready = false,
                            message = interruptionMessage ?: "操作已结束，请手动重新启动。")
                        finished?.invoke(mutableStatus.value)
                    }
                }
            }
            worker.start()
        }
        true
    }

    private suspend fun verifyIdentity(binder: IBinder) = withTimeout(IDENTITY_MILLIS) {
        currentCoroutineContext().ensureActive()
        check(currentServerIs(binder))
        // Adapter teardown is asynchronous. A separate finite adapter per read avoids BUSY
        // from the earlier unbind while keeping each dedicated RPC tied to this Binder.
        PrivKitReadOnlyIdentityAdapter().use { adapter ->
            val result = adapter.readIdentity(ReadOnlyTransportChoice.APP_OWNED)
            check(result is PrivilegeIdentityResult.Verified && result.shellUid == 2000 && result.activeUserId == 0)
        }
        currentCoroutineContext().ensureActive()
        check(currentServerIs(binder))
    }
    private fun currentServerIs(binder: IBinder?): Boolean = binder != null && binder.isBinderAlive &&
        Privilege.serverState.value?.let { it.lifecycleBinder === binder && it.uid == 2000 } == true

    private fun parsePort(text: String): Int? = try { ActivationPolicy.port(text) }
        catch (_: IllegalArgumentException) { showInputFailure("端口须为 1–65535；留空使用自动发现"); null }
    private fun showInputFailure(message: String) = synchronized(lock) {
        if (job?.isCompleted != false) mutableStatus.value = mutableStatus.value.copy(message = message,
            stage = ActivationStage.PREFLIGHT,
            failure = ActivationDiagnostic(ActivationStage.PREFLIGHT, ActivationFailureKind.PRECONDITION), cleanupFailure = null)
    }
    private fun unlinkReady() {
        death?.let { recipient -> runCatching { readyBinder?.unlinkToDeath(recipient, 0) } }
        readyBinder = null; death = null
    }
    private fun invalidateReady(message: String) {
        interruptionMessage = message
        generations.cancel()
        job?.cancel()
        unlinkReady()
        mutableStatus.value = mutableStatus.value.copy(ready = false, message = message)
    }

    companion object {
        private const val DISCOVERY_MILLIS = 8_000L
        private const val OPERATION_MILLIS = 40_000L
        private const val IDENTITY_MILLIS = 6_000L
        @Volatile private var instance: InternalActivationController? = null
        fun get(context: Context): InternalActivationController = instance ?: synchronized(this) {
            instance ?: InternalActivationController(context.applicationContext).also { instance = it }
        }
    }
}
