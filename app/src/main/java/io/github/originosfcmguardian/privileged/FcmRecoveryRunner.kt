package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal val FCM_RECOVERY_ARGV = listOf("/system/bin/am", "broadcast", "--user", "0", "-a",
    FcmRecoveryPolicy.ACTION, "-n", "com.google.android.gms/.gcm.GcmSenderProxy")

/** One bounded worker, no queue. It cannot stop GMS, pass extras or borrow heartbeat authority. */
internal class FcmRecoveryRunner(
    private val portFactory: () -> GmsConnectionReadPort = ::SystemGmsConnectionReadPort,
    private val launch: () -> Process = { ProcessBuilder(FCM_RECOVERY_ARGV).start() },
    private val budgetMillis: Long = 8_000,
    private val mcsLaunch: () -> Process = { ProcessBuilder(GMS_HEARTBEAT_ARGV).start() },
) {
    init { require(budgetMillis in 1..8_000) }
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "guardian-fcm-recovery").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val cancellations = ConcurrentHashMap<Long, FcmRecoveryDispatchGate>()
    private val activity = PrivilegedWorkerActivity()
    fun isQuiescent(): Boolean = activity.isQuiescent()
    fun cancel(requestId: Long) { cancellations[requestId]?.cancel() }
    fun request(requestId: Long, callerUid: Int, expected: String, authorize: () -> Boolean,
        cancelled: () -> Boolean, operation: FcmRecoveryOperation = FcmRecoveryOperation.SERVICE_ABSENT_SEND): FcmRecoveryResult =
        requestGuarded(requestId, callerUid, expected, authorize, cancelled, operation, null)

    fun requestGuarded(requestId: Long, callerUid: Int, expected: String, authorize: () -> Boolean,
        cancelled: () -> Boolean, operation: FcmRecoveryOperation, execution: PrivilegedExecutionFence?): FcmRecoveryResult {
        val gate = FcmRecoveryDispatchGate()
        val settlement = activity.track(execution)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun interrupted() = gate.cancelled() || cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        fun result(reason: FcmRecoveryReason) = FcmRecoveryResult(requestId,
            if (reason == FcmRecoveryReason.COMMAND_ACCEPTED) FcmRecoveryStatus.REQUESTED
            else if (gate.entered()) FcmRecoveryStatus.UNKNOWN else FcmRecoveryStatus.NOT_SENT, reason, operation)
        val stateReason = if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) FcmRecoveryReason.NOT_ABSENT else FcmRecoveryReason.NOT_DISCONNECTED
        fun qualified(sample: GmsConnectionObservation.Sample, port: GmsConnectionReadPort) = when (operation) {
            FcmRecoveryOperation.SERVICE_ABSENT_SEND -> FcmRecoveryPolicy.absent(sample, port.elapsedRealtime())
            FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS -> FcmRecoveryPolicy.disconnected(sample, port.elapsedRealtime()) && port.usableInternet()
        }
        fun target(port: GmsConnectionReadPort) = if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND)
            port.recoverySenderQualified() else port.disconnectedMcsAttemptAllowed()
        if (requestId <= 0 || callerUid !in 10_000..99_999 || !expected.matches(Regex("[a-f0-9]{64}"))) {
            settlement.abandonQueued()
            return result(FcmRecoveryReason.INVALID_REQUEST)
        }
        if (cancellations.putIfAbsent(requestId, gate) != null) {
            settlement.abandonQueued(); return result(FcmRecoveryReason.BUSY)
        }
        val future = try { worker.submit<FcmRecoveryResult> {
            if (!settlement.workerEntered()) return@submit result(FcmRecoveryReason.CANCELLED)
            var child: Process? = null
            var launchEntered = false
            var readerOwnsChild = false
            try {
                if (interrupted()) return@submit result(FcmRecoveryReason.CANCELLED)
                val port = portFactory()
                if (port.packagesForUid(callerUid) != listOf("io.github.originosfcmguardian"))
                    return@submit result(FcmRecoveryReason.AUTHORITY_LOST)
                val observed = observeGmsConnection(requestId, port, { if (interrupted()) GmsUnknown.CANCELLED else null })
                val sample = observed as? GmsConnectionObservation.Sample ?: return@submit result(stateReason)
                if (!qualified(sample, port)) return@submit result(stateReason)
                if (FcmRecoveryPolicy.binding(sample) != expected) return@submit result(FcmRecoveryReason.IDENTITY_CHANGED)
                if (!target(port)) return@submit result(FcmRecoveryReason.TARGET_UNQUALIFIED)
                if (execution != null && !execution.prepare()) return@submit result(FcmRecoveryReason.AUTHORITY_LOST)
                // MCS inspection can be slow: make its second check precede the final
                // complete state/identity observation, catching changes during that dump.
                if (operation == FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS && !target(port))
                    return@submit result(FcmRecoveryReason.TARGET_UNQUALIFIED)
                // Repeat qualified absence and identity after component inspection, just before dispatch.
                val fresh = observeGmsConnection(requestId, port, { if (interrupted()) GmsUnknown.CANCELLED else null })
                    as? GmsConnectionObservation.Sample ?: return@submit result(stateReason)
                if (!qualified(fresh, port)) return@submit result(stateReason)
                if (FcmRecoveryPolicy.binding(fresh) != expected) return@submit result(FcmRecoveryReason.IDENTITY_CHANGED)
                if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND && !target(port) ||
                    port.packagesForUid(callerUid) != listOf("io.github.originosfcmguardian"))
                    return@submit result(FcmRecoveryReason.TARGET_UNQUALIFIED)
                if (!qualified(fresh, port)) return@submit result(stateReason)
                val fixedLaunch = if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) launch else mcsLaunch
                val process = gate.dispatch(::interrupted, authorize) {
                    val start = { launchEntered = true; fixedLaunch().also { child = it } }
                    if (execution == null) start() else execution.start(start)
                }
                    ?: return@submit result(FcmRecoveryReason.AUTHORITY_LOST)
                readerOwnsChild = true
                val completion = readHeartbeatProcess(process, deadline, {
                    if (interrupted()) GmsHeartbeatReason.CANCELLED else null
                }, if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) ::fcmRecoveryCompletion else ::heartbeatCompletion)
                result(if (completion == GmsHeartbeatReason.COMMAND_ACCEPTED) FcmRecoveryReason.COMMAND_ACCEPTED
                    else if (interrupted()) FcmRecoveryReason.TIMED_OUT else FcmRecoveryReason.COMMAND_FAILED)
            } catch (_: Throwable) { result(FcmRecoveryReason.BRIDGE_UNAVAILABLE) }
            finally {
                if (!readerOwnsChild) child?.let(::bestEffortTerminatePrivilegedChild)
                settlement.workerEnded(launchEntered, child)
            }
        } } catch (_: RejectedExecutionException) {
            settlement.abandonQueued(); cancellations.remove(requestId); return result(FcmRecoveryReason.BUSY)
        }
        return try {
            while (true) {
                if (interrupted()) return result(if (System.nanoTime() >= deadline) FcmRecoveryReason.TIMED_OUT else FcmRecoveryReason.CANCELLED)
                try { return future.get(20, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
            @Suppress("UNREACHABLE_CODE") result(FcmRecoveryReason.BRIDGE_UNAVAILABLE)
        } catch (_: Throwable) { result(FcmRecoveryReason.BRIDGE_UNAVAILABLE) }
        finally { gate.cancel(); settlement.abandonQueued(); future.cancel(true); cancellations.remove(requestId) }
    }
    fun close() { cancellations.values.forEach { it.cancel() }; worker.shutdownNow() }
}

/** Entry is the dispatch linearization point; uncertainty starts before the final Binder callback.
 * Revocation before entry denies launch. OFF after entry can cancel but cannot undo admission. */
internal class FcmRecoveryDispatchGate {
    // 0 ready, 1 entered, 2 revoked before entry, 3 revoked after entry.
    private val state = AtomicInteger(0)
    fun cancelled() = state.get() in 2..3
    fun entered() = state.get() == 1 || state.get() == 3
    fun cancel() { state.updateAndGet { if (it == 0) 2 else if (it == 1) 3 else it } }
    fun dispatch(interrupted: () -> Boolean, authorize: () -> Boolean, launch: () -> Process?): Process? {
        if (interrupted() || !state.compareAndSet(0, 1)) return null
        if (!authorize() || interrupted()) return null
        return launch()
    }
}

internal fun fcmRecoveryCompletion(bytes: ByteArray): GmsHeartbeatReason {
    if (bytes.isEmpty() || bytes.last() != 10.toByte()) return GmsHeartbeatReason.OUTPUT_INCOMPLETE
    val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    return if (lines.size == 2 && Regex("Broadcasting: Intent \\{ act=com\\.google\\.android\\.gcm\\.intent\\.SEND(?: flg=0x[0-9a-fA-F]+)? cmp=com\\.google\\.android\\.gms/\\.gcm\\.GcmSenderProxy \\}").matches(lines[0].trim()) &&
        lines[1].trim() == "Broadcast completed: result=0") GmsHeartbeatReason.COMMAND_ACCEPTED else GmsHeartbeatReason.COMMAND_FAILED
}
