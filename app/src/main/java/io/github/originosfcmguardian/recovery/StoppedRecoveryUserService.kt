package io.github.originosfcmguardian.recovery

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.privileged.ShellServiceExpiry
import io.github.originosfcmguardian.privileged.StoppedIdentityProbeRunner
import io.github.originosfcmguardian.privileged.StoppedObservationCodec
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import io.github.originosfcmguardian.privileged.SystemStoppedIdentityReadPort
import java.io.File
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Disposable, identity-bound UserService. SafeCommand and general read IPC stay read-only. */
class StoppedRecoveryUserService : IStoppedRecovery.Stub {
    constructor() : super()
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private val closed = AtomicBoolean(false)
    private val activity = io.github.originosfcmguardian.privileged.PrivilegedWorkerActivity()
    private val callbackActivity = io.github.originosfcmguardian.privileged.PrivilegedWorkerActivity()
    private val retainedChildren = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val running = AtomicReference<Process?>()
    private val gate = Any()
    private val executor = recoveryWorker("guardian-recovery-server")
    private val callbacks = recoveryCallbackWorker("guardian-recovery-callback")
    private val reader = StoppedIdentityProbeRunner(::SystemStoppedIdentityReadPort, 4_000)
    private val expiry = ShellServiceExpiry(Os.getuid(), {
        cancel(); executor.shutdownNow(); callbacks.shutdownNow(); reader.close(); System.exit(0)
    }, lifetimeMillis = 45_000)

    override fun getServiceUid(): Int = Os.getuid()
    override fun isWorkQuiescent(): Boolean {
        retainedChildren.removeIf { runCatching { !it.isAlive }.getOrDefault(false) }
        return getServiceUid() == 2000 && closed.get() && activity.isQuiescent() &&
            callbackActivity.isQuiescent() && reader.isQuiescent() && retainedChildren.isEmpty() &&
            PackageUnstopCapability.isQuiescent()
    }
    override fun destroy() { expiry.terminateNow() }
    override fun cancel() = synchronized(gate) {
        closed.set(true)
        running.getAndSet(null)?.destroyForcibly()
        Unit
    }

    override fun observe(requestId: Long, journal: IStoppedRecoveryJournal?): String = execute(requestId, journal) { read, _, _, _ ->
        StoppedObservationCodec.encode(read(requestId))
    }
    override fun observeTarget(requestId: Long, expectedIdentity: String?, journal: IStoppedRecoveryJournal?): String = execute(requestId, journal) { _, readTarget, _, _ ->
        StoppedObservationCodec.encode(readTarget(requestId, decodeRecoveryTarget(expectedIdentity, true)))
    }
    override fun recover(requestId: Long, expectedIdentity: String?, journal: IStoppedRecoveryJournal?): String =
        recoverOperation(requestId, expectedIdentity, journal, false)

    override fun recoverTarget(requestId: Long, expectedIdentity: String?, journal: IStoppedRecoveryJournal?): String =
        recoverOperation(requestId, expectedIdentity, journal, true)

    private fun recoverOperation(requestId: Long, expectedIdentity: String?, journal: IStoppedRecoveryJournal?, targetAware: Boolean): String = execute(requestId, journal) { read, readTarget, active, localActive ->
        val identity = decodeRecoveryTarget(expectedIdentity, targetAware)
        val engine = StoppedRecoveryEngine({ id -> if (targetAware) readTarget(id, identity) else read(id) },
            { id -> callback(localActive) { requireNotNull(journal).claim(id) } }, active, unstop = {
            // Linearizes process creation with service cancellation/death. No arbitrary argv exposed.
            val process = synchronized(gate) {
                check(active())
                ProcessBuilder(stoppedRecoveryArgv(identity)).redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
                    .also { retainedChildren.add(it); running.set(it); it.outputStream.close() }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    check(localActive() && System.nanoTime() < deadline)
                }
                check(active())
                process.exitValue()
            } finally {
                synchronized(gate) { if (process.isAlive) process.destroyForcibly(); running.compareAndSet(process, null) }
            }
        })
        RecoveryResultCodec.encode(engine.recover(requestId, identity) { readRecoveryCapability(localActive, active) })
    }

    private fun execute(id: Long, journal: IStoppedRecoveryJournal?, body: ((Long) -> StoppedTargetObservation,
        (Long, SelectedTargetIdentity) -> StoppedTargetObservation, () -> Boolean, () -> Boolean) -> String): String {
        val caller = Binder.getCallingUid()
        val callback = requireNotNull(journal)
        val binder = callback.asBinder()
        val death = IBinder.DeathRecipient { cancel() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
        val active = { !closed.get() && System.nanoTime() < deadline }
        var linked = false
        var future: java.util.concurrent.Future<String>? = null
        val settlement = activity.track()
        try {
            require(id > 0 && caller in 10_000..99_999 && Os.getuid() == 2000)
            binder.linkToDeath(death, 0); linked = true
            future = executor.submit<String> {
                check(settlement.workerEntered())
                try {
                check(active())
                // Fresh system PackageManager ownership, never accept merely an app-range caller UID.
                check(SystemStoppedIdentityReadPort().packagesForUid(caller) == listOf(GUARDIAN_PACKAGE))
                check(callback(active) { callback.isSessionActive })
                val sessionActive = { active() && callback(active) { callback.isSessionActive } }
                body({ request -> reader.read(request) { !active() } },
                    { request, expected -> readTargetRecoveryObservation(reader, request, expected, active, sessionActive) },
                    sessionActive, active)
                } finally { settlement.workerEnded(false, null) }
            }
            while (active()) {
                try { return future.get(50, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { }
            }
            error("Recovery deadline exhausted")
        } catch (_: Throwable) {
            return RecoveryResultCodec.encode(StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "连接、身份或服务能力不可用，先只读复检"))
        } finally {
            // Each service binding admits one operation at a time; a timeout closes it permanently.
            if (!active()) cancel()
            future?.cancel(true)
            settlement.abandonQueued()
            if (linked) runCatching { binder.unlinkToDeath(death, 0) }
        }
    }

    private fun <T> callback(localActive: () -> Boolean, block: () -> T): T =
        boundedRecoveryCallback(callbacks, localActive, ::cancel, activity = callbackActivity, block = block)
    private companion object {
        const val GUARDIAN_PACKAGE = "io.github.originosfcmguardian"
    }
}

/** The probe polls on two threads. Its cancellation predicate must never issue callback Binder IO. */
internal fun readTargetRecoveryObservation(reader: StoppedIdentityProbeRunner, requestId: Long,
    expected: SelectedTargetIdentity, localActive: () -> Boolean, sessionActive: () -> Boolean): StoppedTargetObservation {
    check(sessionActive())
    val observation = reader.readTarget(requestId, expected) { !localActive() }
    check(sessionActive())
    return observation
}

internal fun decodeRecoveryTarget(raw: String?, targetAware: Boolean): SelectedTargetIdentity {
    require(raw != null && raw.length <= 4096)
    val values = TargetSelectionCodec.decode(raw) as? TargetSelectionDecodeResult.Valid ?: error("Invalid identity")
    return values.values.single().also {
        require(it.signerSha256.size <= 16)
        require(if (targetAware) isRecoveryTargetPackage(it.packageName) else it.packageName == "org.telegram.messenger")
    }
}

internal fun stoppedRecoveryArgv(identity: SelectedTargetIdentity): List<String> {
    require(isRecoveryTargetPackage(identity.packageName))
    return listOf("/system/bin/cmd", "package", "unstop", "--user", "0", identity.packageName)
}

/** A blocked Binder consumes its single worker; later calls fail BUSY rather than queueing. */
internal fun recoveryWorker(name: String) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
    { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
