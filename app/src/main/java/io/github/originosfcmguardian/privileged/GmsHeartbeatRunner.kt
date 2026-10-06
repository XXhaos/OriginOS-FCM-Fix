package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    init { require(budgetMillis in 1..8_000) }
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-manual-gms-heartbeat").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val automatic = GmsAutomaticHeartbeatGate(clock)

    fun request(requestId: Long, wasCancelled: () -> Boolean): GmsHeartbeatResult {
        automatic.manualEvent()
        return perform(requestId, wasCancelled, null)
    }
    fun requestAutomatic(requestId: Long, callerUid: Int, session: GmsAutoSession, attemptId: String,
                         wasCancelled: () -> Boolean): GmsHeartbeatResult {
        val (cap, reason) = automatic.prepare(session, callerUid, attemptId)
        if (reason != null) return GmsHeartbeatResult.notSent(requestId, reason)
        return perform(requestId, wasCancelled, checkNotNull(cap))
    }
    fun revokeAutomatic(callerUid: Int, generation: Long, sessionId: String?): Boolean {
        val closed = automatic.revoke(callerUid, generation, sessionId)
        if (closed) runCatching { automatic.retiredLifetime()?.unlink() }
        return closed
    }

    fun armAutomatic(requestId: Long, callerUid: Int, generation: Long, expectedDigest: String,
                     lifetime: GmsHeartbeatLifetime, wasCancelled: () -> Boolean): GmsAutoArmResult {
        fun failure(reason: GmsAutoArmReason) = GmsAutoArmResult(null, reason)
        if (requestId <= 0 || generation <= 0 || !expectedDigest.matches(Regex("[a-f0-9]{64}"))) return failure(GmsAutoArmReason.INVALID_REQUEST)
        val abandoned = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun stopped() = abandoned.get() || wasCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        val future = try { worker.submit<GmsAutoArmResult> {
            automatic.workerEntered()
            var cap: GmsAutoCapability? = null
            var armed = false
            try {
                if (stopped()) return@submit failure(GmsAutoArmReason.TIMED_OUT)
                val port = portFactory()
                if (!guardianCaller(port, callerUid)) return@submit failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                val observation = observeGmsConnection(requestId, port, { if (stopped()) GmsUnknown.CANCELLED else null })
                if (stopped()) return@submit failure(GmsAutoArmReason.TIMED_OUT)
                if (GmsHeartbeatPolicy.rejection(observation, port.elapsedRealtime()) != null) return@submit failure(GmsAutoArmReason.NOT_ELIGIBLE)
                val sample = observation as GmsConnectionObservation.Sample
                if (gmsHeartbeatBindingDigest(sample) != expectedDigest) return@submit failure(GmsAutoArmReason.IDENTITY_CHANGED)
                if (!guardianCaller(port, callerUid)) return@submit failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                runCatching { automatic.retiredLifetime()?.unlink() }
                cap = automatic.newCapability(generation, sample, callerUid, lifetime)
                val next = checkNotNull(cap)
                lifetime.link { next.live.set(false); automatic.revoke(callerUid, generation, next.session.id) }
                if (!lifetime.alive() || !next.live.get()) return@submit failure(GmsAutoArmReason.LIFETIME_ENDED)
                if (!automatic.arm(next, ::stopped)) return@submit failure(if (stopped()) GmsAutoArmReason.TIMED_OUT else GmsAutoArmReason.BUSY)
                armed = true
                GmsAutoArmResult(next.session, GmsAutoArmReason.ARMED)
            } catch (_: Throwable) { failure(if (stopped()) GmsAutoArmReason.TIMED_OUT else GmsAutoArmReason.BRIDGE_UNAVAILABLE) }
            finally { if (!armed) { cap?.live?.set(false); runCatching { lifetime.unlink() } }; automatic.workerExited() }
        } } catch (_: RejectedExecutionException) { return failure(GmsAutoArmReason.BUSY) }
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
            abandoned.set(true); future.cancel(true)
            if (!returned) automatic.revoke(callerUid, generation, null)
        }
    }
    private fun guardianCaller(port: GmsConnectionReadPort, uid: Int) = uid in 10_000..99_999 &&
        port.packagesForUid(uid) == listOf("io.github.originosfcmguardian")

    private fun perform(requestId: Long, wasCancelled: () -> Boolean, cap: GmsAutoCapability?): GmsHeartbeatResult {
        if (requestId <= 0) return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.INVALID_REQUEST)
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
            automatic.workerEntered()
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
                val sample = observation as GmsConnectionObservation.Sample
                if (cap != null && !guardianCaller(port, cap.callerUid)) return@submit gate.failure(requestId, GmsHeartbeatReason.CALLER_UNAVAILABLE)
                // Repeat exact installation/owner/boot/user guards immediately before opening dispatch.
                val identity = port.gmsPackage()
                val owners = port.packagesForUid(sample.uid)
                val environment = port.environment()
                if (identity == null || identity != GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
                        sample.uid, sample.versionCode, sample.firstInstallTime, sample.lastUpdateTime, sample.signerSha256) ||
                    owners != listOf(GMS_OBSERVATION_PACKAGE) || environment.currentUser != 0 ||
                    environment.serviceUid != 2000 || environment.bootId != sample.bootId) {
                    return@submit gate.failure(requestId, GmsHeartbeatReason.IDENTITY_CHANGED)
                }
                GmsHeartbeatPolicy.rejection(sample, port.elapsedRealtime())?.let { return@submit gate.failure(requestId, it) }
                val (process, rejected) = automatic.launch(cap, sample, gate::begin, launch)
                if (rejected != null) return@submit gate.failure(requestId, interruption() ?: rejected)
                val reason = readHeartbeatProcess(checkNotNull(process), deadline, ::interruption)
                if (reason == GmsHeartbeatReason.COMMAND_ACCEPTED) GmsHeartbeatResult(requestId, GmsHeartbeatStatus.REQUESTED, reason)
                else gate.failure(requestId, reason)
            } catch (_: Throwable) { gate.failure(requestId, interruption() ?: GmsHeartbeatReason.BRIDGE_UNAVAILABLE) }
            finally { automatic.workerExited() }
        } } catch (_: RejectedExecutionException) { return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BUSY) }
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
        finally { gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true); future.cancel(true) }
    }
    fun close() { automatic.close(); worker.shutdownNow() }
}

/** No drain threads or independent wait/get budgets: validation and pipe reads share one deadline. */
internal fun readHeartbeatProcess(process: Process, deadline: Long, interruption: () -> GmsHeartbeatReason?): GmsHeartbeatReason {
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
                return if (process.exitValue() == 0 && !stderr) heartbeatCompletion(out.toByteArray())
                else GmsHeartbeatReason.COMMAND_FAILED
            }
            process.waitFor(10, TimeUnit.MILLISECONDS)
        }
        @Suppress("UNREACHABLE_CODE") GmsHeartbeatReason.COMMAND_FAILED
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); GmsHeartbeatReason.CANCELLED }
    catch (_: Exception) { GmsHeartbeatReason.COMMAND_FAILED }
    finally {
        runCatching { process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        out.reset()
    }
}

internal fun heartbeatCompletion(bytes: ByteArray): GmsHeartbeatReason {
    if (bytes.isEmpty() || bytes.last() != 10.toByte()) return GmsHeartbeatReason.OUTPUT_INCOMPLETE
    val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    return if (lines.size == 2 && Regex("Broadcasting: Intent \\{ act=com\\.google\\.android\\.intent\\.action\\.MCS_HEARTBEAT(?: flg=0x[0-9a-fA-F]+)? pkg=com\\.google\\.android\\.gms \\}").matches(lines[0].trim()) &&
        lines[1].trim() == "Broadcast completed: result=0") GmsHeartbeatReason.COMMAND_ACCEPTED else GmsHeartbeatReason.COMMAND_FAILED
}
