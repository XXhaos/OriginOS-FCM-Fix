package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class GmsHeartbeatArmStage { QUEUED, PORT, CALLER, OBSERVATION, BINDING, FINAL_CALLER, LIFETIME, CAPABILITY }
internal data class GmsHeartbeatArmDiagnostic(val stage: GmsHeartbeatArmStage,
    val reason: GmsAutoArmReason, val durationMillis: Long, val workerQuiescent: Boolean)

internal val GMS_HEARTBEAT_ARGV = listOf("/system/bin/am", "broadcast", "--user", "0", "-a",
    "com.google.android.intent.action.MCS_HEARTBEAT", "-p", "com.google.android.gms")

/** Serializes abandonment with launch intent: after the gate opens, every failure is uncertain. */
internal class GmsHeartbeatDispatchGate(private val interruption: () -> GmsHeartbeatReason?) {
    private var mayHaveDispatched = false
    private var abandoned: GmsHeartbeatReason? = null
    @Synchronized fun begin(): Boolean {
        if (abandoned != null || interruption() != null) return false
        mayHaveDispatched = true
        return true
    }
    @Synchronized fun failure(requestId: Long, reason: GmsHeartbeatReason, abandon: Boolean = false): GmsHeartbeatResult {
        if (abandon) abandoned = reason
        return if (mayHaveDispatched) GmsHeartbeatResult.unknown(requestId, reason) else GmsHeartbeatResult.notSent(requestId, reason)
    }
    @Synchronized fun stopped() = abandoned != null
}

/** One worker and no queue; even an abandoned Binder validation occupies admission until it exits. */
internal class GmsHeartbeatRunner(
    private val portFactory: () -> GmsConnectionReadPort = ::SystemGmsConnectionReadPort,
    private val launch: () -> Process = { ProcessBuilder(GMS_HEARTBEAT_ARGV).start() },
    private val budgetMillis: Long = 8_000,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val armDiagnostic: (GmsHeartbeatArmDiagnostic) -> Unit = { value ->
        // Only one failed initialization record: no raw identities, payloads or exception messages.
        runCatching { android.util.Log.w("GuardianHeartbeat", "arm=${value.reason} stage=${value.stage} " +
            "durationMs=${value.durationMillis} quiescent=${value.workerQuiescent}") }
    },
) {
    init { require(budgetMillis in 1..8_000) }
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-manual-gms-heartbeat").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val automatic = GmsAutomaticHeartbeatGate(clock)
    private val activity = PrivilegedWorkerActivity()
    fun isQuiescent(): Boolean = activity.isQuiescent()
    fun isFreshAutomaticEndpoint(): Boolean = isQuiescent() && automatic.isFreshEndpoint()

    private fun declined(stage: String, reason: GmsHeartbeatReason) {
        // At most one bounded record per declined request; no package/process/session identities.
        runCatching { android.util.Log.w("GuardianHeartbeat", "dispatch=NOT_SENT stage=$stage reason=$reason") }
    }

    fun request(requestId: Long, wasCancelled: () -> Boolean): GmsHeartbeatResult {
        automatic.manualEvent()
        return perform(requestId, wasCancelled, null, null)
    }
    fun requestAutomatic(requestId: Long, callerUid: Int, session: GmsAutoSession, attemptId: String,
                         wasCancelled: () -> Boolean): GmsHeartbeatResult {
        return requestAutomaticGuarded(requestId, callerUid, session, attemptId, wasCancelled, null)
    }
    fun requestAutomaticGuarded(requestId: Long, callerUid: Int, session: GmsAutoSession, attemptId: String,
        wasCancelled: () -> Boolean, execution: PrivilegedExecutionFence?): GmsHeartbeatResult {
        val (cap, reason) = automatic.prepare(session, callerUid, attemptId)
        if (reason != null) {
            PrivilegedWorkSettlement(execution).abandonQueued()
            if (reason == GmsHeartbeatReason.SESSION_REVOKED) declined("CAPABILITY", reason)
            return GmsHeartbeatResult.notSent(requestId, reason)
        }
        return perform(requestId, wasCancelled, checkNotNull(cap), execution)
    }
    fun revokeAutomatic(callerUid: Int, generation: Long, sessionId: String?): Boolean {
        val closed = automatic.revoke(callerUid, generation, sessionId)
        if (closed) runCatching { automatic.retiredLifetime()?.unlink() }
        return closed
    }

    fun armAutomatic(requestId: Long, callerUid: Int, generation: Long, expectedDigest: String,
                     lifetime: GmsHeartbeatLifetime, wasCancelled: () -> Boolean): GmsAutoArmResult {
        val startedAt = System.nanoTime()
        val stage = AtomicReference(GmsHeartbeatArmStage.QUEUED)
        val result = armAutomaticWithStages(requestId, callerUid, generation, expectedDigest, lifetime, wasCancelled, stage)
        // Log the public return, not a worker result that may lose a race to the caller deadline.
        if (result.session == null) runCatching {
                armDiagnostic(GmsHeartbeatArmDiagnostic(stage.get(), result.reason,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), isQuiescent()))
        }
        return result
    }

    private fun armAutomaticWithStages(requestId: Long, callerUid: Int, generation: Long, expectedDigest: String,
        lifetime: GmsHeartbeatLifetime, wasCancelled: () -> Boolean,
        stage: AtomicReference<GmsHeartbeatArmStage>): GmsAutoArmResult {
        fun failure(reason: GmsAutoArmReason) = GmsAutoArmResult(null, reason)
        if (requestId <= 0 || generation <= 0 || !expectedDigest.matches(Regex("[a-f0-9]{64}"))) return failure(GmsAutoArmReason.INVALID_REQUEST)
        val settlement = activity.track()
        val abandoned = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun stopped() = abandoned.get() || wasCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        val future = try { worker.submit<GmsAutoArmResult> {
            if (!settlement.workerEntered()) return@submit failure(GmsAutoArmReason.TIMED_OUT)
            automatic.workerEntered()
            var cap: GmsAutoCapability? = null
            var armed = false
            try {
                if (stopped()) return@submit failure(GmsAutoArmReason.TIMED_OUT)
                stage.set(GmsHeartbeatArmStage.PORT)
                val port = portFactory()
                stage.set(GmsHeartbeatArmStage.CALLER)
                if (!guardianCaller(port, callerUid)) return@submit failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                stage.set(GmsHeartbeatArmStage.OBSERVATION)
                val observation = observeGmsConnection(requestId, port, { if (stopped()) GmsUnknown.CANCELLED else null })
                if (stopped()) return@submit failure(GmsAutoArmReason.TIMED_OUT)
                if (GmsHeartbeatPolicy.rejection(observation, port.elapsedRealtime()) != null) return@submit failure(GmsAutoArmReason.NOT_ELIGIBLE)
                val sample = observation as GmsConnectionObservation.Sample
                stage.set(GmsHeartbeatArmStage.BINDING)
                if (gmsHeartbeatBindingDigest(sample) != expectedDigest) return@submit failure(GmsAutoArmReason.IDENTITY_CHANGED)
                stage.set(GmsHeartbeatArmStage.FINAL_CALLER)
                if (!guardianCaller(port, callerUid)) return@submit failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                runCatching { automatic.retiredLifetime()?.unlink() }
                cap = automatic.newCapability(generation, sample, callerUid, lifetime)
                val next = checkNotNull(cap)
                stage.set(GmsHeartbeatArmStage.LIFETIME)
                lifetime.link { next.live.set(false); automatic.revoke(callerUid, generation, next.session.id) }
                if (!lifetime.alive() || !next.live.get()) return@submit failure(GmsAutoArmReason.LIFETIME_ENDED)
                stage.set(GmsHeartbeatArmStage.CAPABILITY)
                if (!automatic.arm(next, ::stopped)) return@submit failure(if (stopped()) GmsAutoArmReason.TIMED_OUT else GmsAutoArmReason.BUSY)
                armed = true
                GmsAutoArmResult(next.session, GmsAutoArmReason.ARMED)
            } catch (_: Throwable) { failure(if (stopped()) GmsAutoArmReason.TIMED_OUT else GmsAutoArmReason.BRIDGE_UNAVAILABLE) }
            finally {
                if (!armed) { cap?.live?.set(false); runCatching { lifetime.unlink() } }
                automatic.workerExited(); settlement.workerEnded(false, null)
            }
        } } catch (_: RejectedExecutionException) { settlement.abandonQueued(); return failure(GmsAutoArmReason.BUSY) }
        var returned = false
        try {
            while (true) {
                if (stopped()) return failure(GmsAutoArmReason.TIMED_OUT)
                try {
                    val result = future.get(minOf((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
                    if (stopped()) return failure(GmsAutoArmReason.TIMED_OUT)
                    returned = result.session != null
                    return result
                } catch (_: TimeoutException) { }
            }
        } catch (_: Throwable) { return failure(GmsAutoArmReason.BRIDGE_UNAVAILABLE) }
        finally {
            abandoned.set(true); settlement.abandonQueued(); future.cancel(true)
            if (!returned) automatic.revoke(callerUid, generation, null)
        }
    }
    private fun guardianCaller(port: GmsConnectionReadPort, uid: Int) = uid in 10_000..99_999 &&
        port.packagesForUid(uid) == listOf("io.github.originosfcmguardian")

    private fun perform(requestId: Long, wasCancelled: () -> Boolean, cap: GmsAutoCapability?, execution: PrivilegedExecutionFence?): GmsHeartbeatResult {
        val settlement = activity.track(execution)
        if (requestId <= 0) {
            settlement.abandonQueued(); return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.INVALID_REQUEST)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        lateinit var gate: GmsHeartbeatDispatchGate
        fun interruption(): GmsHeartbeatReason? = when {
            gate.stopped() || wasCancelled() || Thread.currentThread().isInterrupted -> GmsHeartbeatReason.CANCELLED
            cap != null && !cap.live.get() -> GmsHeartbeatReason.SESSION_REVOKED
            System.nanoTime() >= deadline -> GmsHeartbeatReason.TIMED_OUT
            else -> null
        }
        gate = GmsHeartbeatDispatchGate(::interruption)
        val future = try { worker.submit<GmsHeartbeatResult> {
            if (!settlement.workerEntered()) return@submit gate.failure(requestId, GmsHeartbeatReason.CANCELLED)
            automatic.workerEntered()
            var child: Process? = null
            var launchEntered = false
            var readerOwnsChild = false
            try {
                interruption()?.let { return@submit gate.failure(requestId, it) }
                val port = portFactory()
                val observation = observeGmsConnection(requestId, port, {
                    when (interruption()) {
                        GmsHeartbeatReason.TIMED_OUT -> GmsUnknown.TIMED_OUT
                        null -> null
                        else -> GmsUnknown.CANCELLED
                    }
                })
                interruption()?.let { return@submit gate.failure(requestId, it) }
                GmsHeartbeatPolicy.rejection(observation, port.elapsedRealtime())?.let {
                    return@submit gate.failure(requestId, it)
                }
                var sample = observation as GmsConnectionObservation.Sample
                if (cap != null && !guardianCaller(port, cap.callerUid)) return@submit gate.failure(requestId, GmsHeartbeatReason.CALLER_UNAVAILABLE)
                if (execution != null) {
                    // Ownership reads can block: repeat GMS service/socket evidence afterwards.
                    if (!execution.prepare()) {
                        declined("OWNER_PREPARE", GmsHeartbeatReason.EXECUTION_NOT_READY)
                        return@submit gate.failure(requestId, GmsHeartbeatReason.EXECUTION_NOT_READY)
                    }
                    val fresh = observeGmsConnection(requestId, port, {
                        if (interruption() == null) null else GmsUnknown.CANCELLED
                    })
                    interruption()?.let { return@submit gate.failure(requestId, it) }
                    GmsHeartbeatPolicy.rejection(fresh, port.elapsedRealtime())?.let {
                        return@submit gate.failure(requestId, it)
                    }
                    val next = fresh as GmsConnectionObservation.Sample
                    if (gmsHeartbeatBindingDigest(next) != gmsHeartbeatBindingDigest(sample))
                        return@submit gate.failure(requestId, GmsHeartbeatReason.IDENTITY_CHANGED)
                    sample = next
                }
                // Repeat exact installation/owner/boot/user guards immediately before opening dispatch.
                val identity = port.gmsPackage()
                val owners = port.packagesForUid(sample.uid)
                val environment = port.environment()
                if (!gmsIdentityMatchesSample(identity, sample) ||
                    owners != listOf(GMS_OBSERVATION_PACKAGE) || environment.currentUser != 0 ||
                    environment.serviceUid != 2000 || environment.bootId != sample.bootId) {
                    return@submit gate.failure(requestId, GmsHeartbeatReason.IDENTITY_CHANGED)
                }
                GmsHeartbeatPolicy.rejection(sample, port.elapsedRealtime())?.let { return@submit gate.failure(requestId, it) }
                // Never run fresh Binder/PM IO inside automatic.launch's capability monitor.
                val (process, rejected) = automatic.launch(cap, sample, gate::begin) {
                    val start = { launchEntered = true; launch().also { child = it } }
                    if (execution == null) start() else execution.start(start)
                }
                if (rejected != null) return@submit gate.failure(requestId, interruption() ?: rejected)
                readerOwnsChild = true
                val reason = readHeartbeatProcess(checkNotNull(process), deadline, ::interruption)
                if (reason == GmsHeartbeatReason.COMMAND_ACCEPTED) GmsHeartbeatResult(requestId, GmsHeartbeatStatus.REQUESTED, reason)
                else gate.failure(requestId, reason)
            } catch (_: Throwable) { gate.failure(requestId, interruption() ?: GmsHeartbeatReason.BRIDGE_UNAVAILABLE) }
            finally {
                if (!readerOwnsChild) child?.let(::bestEffortTerminatePrivilegedChild)
                automatic.workerExited(); settlement.workerEnded(launchEntered, child)
            }
        } } catch (_: RejectedExecutionException) {
            settlement.abandonQueued(); return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BUSY)
        }
        try {
            while (true) {
                interruption()?.let { return gate.failure(requestId, it, abandon = true).also { result -> automatic.ended(cap, result.status, future.isDone) } }
                try {
                    val result = future.get(minOf((deadline - System.nanoTime()).coerceAtLeast(1),
                        TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
                    return (interruption()?.let { gate.failure(requestId, it, abandon = true) } ?: result)
                        .also { automatic.ended(cap, it.status, true) }
                } catch (_: TimeoutException) { }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true).also { automatic.ended(cap, it.status, future.isDone) }
        } catch (_: Throwable) { return gate.failure(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE, abandon = true).also { automatic.ended(cap, it.status, future.isDone) } }
        finally { gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true); settlement.abandonQueued(); future.cancel(true) }
    }
    fun close() { automatic.close(); worker.shutdownNow() }
}

/** No drain threads or independent wait/get budgets: validation and pipe reads share one deadline. */
internal fun readHeartbeatProcess(process: Process, deadline: Long, interruption: () -> GmsHeartbeatReason?,
    completion: (ByteArray) -> GmsHeartbeatReason = ::heartbeatCompletion): GmsHeartbeatReason {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(4096)
    var bytes = 0
    var stderr = false
    fun drain(stream: InputStream, stdout: Boolean): GmsHeartbeatReason? {
        while (true) {
            interruption()?.let { return it }
            if (System.nanoTime() >= deadline) return GmsHeartbeatReason.TIMED_OUT
            val available = stream.available()
            if (available <= 0) return null
            val count = stream.read(chunk, 0, minOf(available, chunk.size, 16_384 - bytes + 1))
            if (count <= 0) return null
            bytes += count
            if (bytes > 16_384) return GmsHeartbeatReason.OUTPUT_INCOMPLETE
            if (stdout) out.write(chunk, 0, count) else stderr = true
        }
    }
    return try {
        while (true) {
            interruption()?.let { return it }
            if (System.nanoTime() >= deadline) return GmsHeartbeatReason.TIMED_OUT
            drain(process.inputStream, true)?.let { return it }
            drain(process.errorStream, false)?.let { return it }
            if (!process.isAlive) {
                drain(process.inputStream, true)?.let { return it }
                drain(process.errorStream, false)?.let { return it }
                return if (process.exitValue() == 0 && !stderr) completion(out.toByteArray())
                else GmsHeartbeatReason.COMMAND_FAILED
            }
            process.waitFor(10, TimeUnit.MILLISECONDS)
        }
        @Suppress("UNREACHABLE_CODE") GmsHeartbeatReason.COMMAND_FAILED
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); GmsHeartbeatReason.CANCELLED }
    catch (_: Exception) { GmsHeartbeatReason.COMMAND_FAILED }
    finally {
        bestEffortTerminatePrivilegedChild(process)
        out.reset()
    }
}

internal fun heartbeatCompletion(bytes: ByteArray): GmsHeartbeatReason {
    if (bytes.isEmpty() || bytes.last() != 10.toByte()) return GmsHeartbeatReason.OUTPUT_INCOMPLETE
    val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    return if (lines.size == 2 && Regex("Broadcasting: Intent \\{ act=com\\.google\\.android\\.intent\\.action\\.MCS_HEARTBEAT(?: flg=0x[0-9a-fA-F]+)? pkg=com\\.google\\.android\\.gms \\}").matches(lines[0].trim()) &&
        lines[1].trim() == "Broadcast completed: result=0") GmsHeartbeatReason.COMMAND_ACCEPTED else GmsHeartbeatReason.COMMAND_FAILED
}
