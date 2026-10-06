package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface GmsHeartbeatCallerProcess {
    data class Present(val startTicks: Long) : GmsHeartbeatCallerProcess
    data object Gone : GmsHeartbeatCallerProcess
    data object Unknown : GmsHeartbeatCallerProcess
}
internal interface GmsHeartbeatCallerReadPort {
    fun environment(): GmsProbeEnvironment
    fun packagesForUid(uid: Int): List<String>?
    fun process(pid: Int, uid: Int, interrupted: () -> Boolean): GmsHeartbeatCallerProcess
}

internal fun qualifyGmsHeartbeatCaller(callerUid: Int, callerPid: Int, previous: GmsHeartbeatCallerIdentity?,
    port: GmsHeartbeatCallerReadPort, interrupted: () -> Boolean): GmsHeartbeatCallerQualification {
    fun unknown() = GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
    if (callerUid !in 10_000..99_999 || callerPid !in 1..4_194_304 || previous?.uid?.let { it != callerUid } == true) return unknown()
    return try {
        if (interrupted()) return unknown()
        val environment = port.environment()
        if (environment.currentUser != 0 || environment.serviceUid != 2000 ||
            port.packagesForUid(callerUid) != listOf(GUARDIAN_PACKAGE_NAME) || interrupted()) return unknown()
        val currentProcess = port.process(callerPid, callerUid, interrupted) as? GmsHeartbeatCallerProcess.Present ?: return unknown()
        val current = GmsHeartbeatCallerIdentity(callerUid, callerPid, currentProcess.startTicks, environment.bootId)
        val reason = when {
            previous == null -> GmsHeartbeatCallerReason.CURRENT_ONLY
            previous.kernelBootId != current.kernelBootId -> GmsHeartbeatCallerReason.BOOT_CHANGED
            else -> when (val old = port.process(previous.pid, previous.uid, interrupted)) {
                GmsHeartbeatCallerProcess.Gone -> GmsHeartbeatCallerReason.OLD_INSTANCE_GONE
                GmsHeartbeatCallerProcess.Unknown -> GmsHeartbeatCallerReason.UNKNOWN
                is GmsHeartbeatCallerProcess.Present -> if (old.startTicks == previous.startTicks)
                    GmsHeartbeatCallerReason.OLD_INSTANCE_ALIVE else GmsHeartbeatCallerReason.OLD_INSTANCE_GONE
            }
        }
        // Caller, boot and exclusive ownership must survive the whole observation.
        if (interrupted() || port.process(callerPid, callerUid, interrupted) != currentProcess ||
            port.environment() != environment || port.packagesForUid(callerUid) != listOf(GUARDIAN_PACKAGE_NAME) ||
            port.environment() != environment || interrupted()) return unknown()
        GmsHeartbeatCallerQualification(current, reason)
    } catch (_: Throwable) { unknown() }
}

/** One worker, no queue; unknown cancellation or a stuck PM Binder cannot accumulate requests. */
internal class GmsHeartbeatCallerProbeRunner(private val portFactory: () -> GmsHeartbeatCallerReadPort,
    private val budgetMillis: Long = 8_000) {
    init { require(budgetMillis in 1..8_000) }
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "guardian-heartbeat-caller-read").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun read(callerUid: Int, callerPid: Int, previous: GmsHeartbeatCallerIdentity?, cancelled: () -> Boolean): GmsHeartbeatCallerQualification {
        fun unknown() = GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
        val abandoned = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun stopped() = abandoned.get() || cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline
        if (stopped()) return unknown()
        val future = try { worker.submit<GmsHeartbeatCallerQualification> {
            qualifyGmsHeartbeatCaller(callerUid, callerPid, previous, portFactory(), ::stopped)
        } } catch (_: RejectedExecutionException) { return unknown() }
        return try {
            while (true) {
                if (stopped()) return unknown()
                try {
                    val value = future.get(minOf((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)
                    return if (stopped()) unknown() else value
                } catch (_: TimeoutException) { }
            }
            @Suppress("UNREACHABLE_CODE") unknown()
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); unknown() }
        catch (_: Throwable) { unknown() }
        finally { abandoned.set(true); future.cancel(true) }
    }
    fun close() { worker.shutdownNow() }
}
