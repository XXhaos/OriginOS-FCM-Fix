package io.github.originosfcmguardian.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.PrivilegedExecutor
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.SafeCommand.GetAppOpByUid
import io.github.originosfcmguardian.core.SafeCommand.GetTargetProcessEvents
import io.github.originosfcmguardian.core.trace.ProcessTraceCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import io.github.originosfcmguardian.core.gms.GmsHeartbeatResult
import io.github.originosfcmguardian.core.gms.GmsHeartbeatReason
import io.github.originosfcmguardian.core.gms.GmsHeartbeatStatus
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCodec
import io.github.originosfcmguardian.core.gms.GmsAutoSession
import io.github.originosfcmguardian.core.gms.GmsAutoArmResult
import io.github.originosfcmguardian.core.gms.GmsAutoArmReason
import io.github.originosfcmguardian.core.gms.GmsAutoArmCodec
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerQualification
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerReason
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerCodec
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime
import android.os.DeadObjectException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal interface ShizukuBindingAdapter {
    fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection)
    fun unbind(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean)
}

private object RealShizukuBindingAdapter : ShizukuBindingAdapter {
    override fun bind(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
        Shizuku.bindUserService(args, connection)
    }

    override fun unbind(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
        remove: Boolean,
    ) {
        Shizuku.unbindUserService(args, connection, remove)
    }
}

class ShizukuExecutor private constructor(
    private val context: Context,
    private val bindingAdapter: ShizukuBindingAdapter,
    private val heartbeatOwner: Boolean = false,
) : PrivilegedExecutor {
    constructor(context: Context) : this(context, RealShizukuBindingAdapter)

    private class BindingHandle(
        val args: Shizuku.UserServiceArgs,
        val deferred: CompletableDeferred<IGuardianUserService>,
    ) {
        lateinit var connection: ServiceConnection
    }

    private val operationMutex = Mutex()
    private val requestIds = AtomicLong(1)
    private val session = PrivilegedSessionState<IGuardianUserService, BindingHandle>()
    private val executorNamespace = newUserServiceNamespace()
    private val stoppedObservationRpc = StoppedObservationRpc()
    private val gmsObservationRpc = GmsObservationRpc()
    private val gmsHeartbeatRevokeRpc = GmsObservationRpc()
    private val autoOwners = ConcurrentHashMap<Long, GmsAutoRemoteOwner>()

    fun openSession() = session.open()

    /** Dedicated short-lived config caller; every synchronous Binder hop is cancellably bounded. */
    internal fun gmsConfigurationExecutor(): PrivilegedExecutor = object : PrivilegedExecutor {
        override suspend fun isAvailable(): Boolean = gmsObservationRpc.bounded { shellAvailable() }
        override suspend fun getUid(): Int? = gmsObservationRpc.bounded {
            if (session.isOpen() && Shizuku.pingBinder()) Shizuku.getUid() else null
        }
        override suspend fun exec(command: SafeCommand): CommandResult {
            require(command == SafeCommand.GetCurrentUser || command == SafeCommand.GetBuildFingerprint ||
                command == SafeCommand.GetGmsPackage || command == SafeCommand.GetGmsEnabledPackage ||
                command == SafeCommand.GetDeviceIdleWhitelist || command is SafeCommand.GetGmsAppOpByPackage ||
                command is GetAppOpByUid)
            return operationMutex.withLock {
                if (!gmsObservationRpc.bounded { shellAvailable() })
                    return@withLock bridgeError(command, "GMS configuration source unavailable")
                val bound = ensureBound(gmsBounded = true)
                val service = bound.remote
                if (gmsObservationRpc.bounded { service.serviceUid } != SHELL_UID)
                    return@withLock bridgeError(command, "Unexpected UserService UID")
                val requestId = nextRequestId()
                if (!session.beginCall(bound, requestId))
                    return@withLock bridgeError(command, "Configuration session closed")
                try {
                    val arguments = ipcArguments(command)
                    val result = gmsObservationRpc.bounded {
                        service.execute(requestId, command.operationId, arguments.uid, arguments.packageName)
                    }.toCommandResult(command)
                    if (session.currentBound() == bound) result else bridgeError(command, "Configuration binding changed")
                } catch (cancelled: CancellationException) {
                    retireGmsConfiguration() // Retire while request is registered, so teardown cancels the remote command.
                    throw cancelled
                } finally {
                    session.finishCall(requestId)
                }
            }
        }
    }

    private fun shellAvailable(): Boolean = session.isOpen() && Shizuku.pingBinder() &&
        Shizuku.getUid() == SHELL_UID && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    internal fun closeGmsConfiguration() {
        retireGmsConfiguration()
        gmsObservationRpc.close()
    }

    internal fun retireGmsConfiguration() {
        val teardown = session.close() // Invalidate admission synchronously before asynchronous cleanup.
        gmsObservationRpc.cleanupBestEffort { applyTeardown(teardown) }
    }

    internal suspend fun <T> boundedGmsConfigurationMetadata(read: () -> T): T =
        gmsObservationRpc.bounded(read)

    override suspend fun isAvailable(): Boolean {
        if (!session.isOpen()) return false
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.getUid() == SHELL_UID &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override suspend fun getUid(): Int? {
        if (!session.isOpen()) return null
        return runCatching {
            if (Shizuku.pingBinder()) Shizuku.getUid() else null
        }.getOrNull()
    }

    override suspend fun exec(command: SafeCommand): CommandResult = operationMutex.withLock {
        if (!session.isOpen()) {
            return@withLock bridgeError(command, "Privileged session is closed")
        }
        if (!isAvailable()) {
            return@withLock bridgeError(command, "Shizuku shell is unavailable or not authorized")
        }

        val bound = try {
            ensureBound()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withLock bridgeError(command, safeMessage(error))
        }
        val service = bound.remote

        val serviceUid = try {
            withContext(Dispatchers.IO) { service.serviceUid }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            invalidate(error)
            return@withLock bridgeError(command, "Cannot verify UserService UID")
        }
        if (serviceUid != SHELL_UID) {
            cleanupInternal(close = false)
            return@withLock bridgeError(
                command,
                if (serviceUid == 0) "Root UserService is explicitly unsupported" else "Unexpected UserService UID",
            )
        }

        val requestId = nextRequestId()
        if (!session.beginCall(bound, requestId)) {
            return@withLock bridgeError(command, "Privileged session closed before execution")
        }
        val arguments = ipcArguments(command)
        try {
            val bundle = withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                    service.execute(
                        requestId,
                        command.operationId,
                        arguments.uid,
                        arguments.packageName,
                    )
                }
            }
            bundle.toCommandResult(command)
        } catch (cancelled: CancellationException) {
            runCatching { service.cancel(requestId) }
            if (cancelled is TimeoutCancellationException) {
                cleanupInternal(close = false)
                CommandResult(
                    command = command,
                    exitCode = IpcContract.EXIT_TIMED_OUT,
                    stdout = "",
                    stderr = "UserService call timed out",
                    timedOut = true,
                )
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            invalidate(error)
            bridgeError(
                command,
                if (command is GetTargetProcessEvents) "Process trace unavailable" else safeMessage(error),
            )
        } finally {
            session.finishCall(requestId)
        }
    }

    /** Fresh read-only prerequisite only; no recovery operation or cached UI identity is sent. */
    internal suspend fun readTelegramStoppedIdentity(): StoppedTargetObservation = operationMutex.withLock {
        val requestId = nextRequestId()
        fun unknown(reason: StoppedObservationUnknown) = StoppedTargetObservation.Unknown(requestId, reason)
        var beganCall = false
        try {
            if (!session.isOpen() || !isAvailable()) return@withLock unknown(StoppedObservationUnknown.SHELL_UNAVAILABLE)
            withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                val bound = ensureBound()
                val service = bound.remote
                if (!session.beginCall(bound, requestId)) return@withTimeout unknown(StoppedObservationUnknown.BRIDGE_UNAVAILABLE)
                beganCall = true
                // The server repeats this guard; client-side availability is never server proof.
                val observation = stoppedObservationRpc.read(
                    requestId,
                    serviceUid = { service.serviceUid },
                    payload = { service.readTelegramStoppedIdentity(requestId).getString(StoppedObservationCodec.BUNDLE_KEY) },
                )
                if (observation is StoppedTargetObservation.Unknown && observation.reason in setOf(
                        StoppedObservationUnknown.CANCELLED, StoppedObservationUnknown.TIMED_OUT,
                        StoppedObservationUnknown.SHELL_UNAVAILABLE, StoppedObservationUnknown.BRIDGE_UNAVAILABLE,
                    )
                ) {
                    retireStoppedObservationBinding()
                    observation
                } else if (session.currentBound() != bound) unknown(StoppedObservationUnknown.BRIDGE_UNAVAILABLE)
                else observation
            }
        } catch (cancelled: CancellationException) {
            retireStoppedObservationBinding()
            unknown(if (cancelled is TimeoutCancellationException) StoppedObservationUnknown.TIMED_OUT else StoppedObservationUnknown.CANCELLED)
        } catch (_: Throwable) {
            retireStoppedObservationBinding()
            unknown(StoppedObservationUnknown.BRIDGE_UNAVAILABLE)
        } finally {
            if (beganCall) session.finishCall(requestId)
        }
    }

    internal suspend fun readGmsConnection(): io.github.originosfcmguardian.core.gms.GmsConnectionObservation {
        val requestId = nextRequestId()
        fun unknown(reason: io.github.originosfcmguardian.core.gms.GmsUnknown) =
            io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Unknown(requestId, reason)
        if (!operationMutex.tryLock()) return unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.BUSY)
        var beganCall = false
        return try {
            withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                if (!session.isOpen()) return@withTimeout unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.SHELL_UNAVAILABLE)
                val available = gmsObservationRpc.bounded {
                    Shizuku.pingBinder() && Shizuku.getUid() == SHELL_UID &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }
                if (!available) return@withTimeout unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.SHELL_UNAVAILABLE)
                val bound = ensureBound(gmsBounded = true)
                val service = bound.remote
                if (!session.beginCall(bound, requestId)) return@withTimeout unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.BRIDGE_UNAVAILABLE)
                beganCall = true
                val observation = gmsObservationRpc.read(requestId, { service.serviceUid }, {
                    service.readGmsConnection(requestId).getString(io.github.originosfcmguardian.core.gms.GmsObservationCodec.BUNDLE_KEY)
                })
                if (observation is io.github.originosfcmguardian.core.gms.GmsConnectionObservation.Unknown && observation.reason in setOf(
                    io.github.originosfcmguardian.core.gms.GmsUnknown.CANCELLED,
                    io.github.originosfcmguardian.core.gms.GmsUnknown.TIMED_OUT,
                    io.github.originosfcmguardian.core.gms.GmsUnknown.SHELL_UNAVAILABLE,
                    io.github.originosfcmguardian.core.gms.GmsUnknown.BRIDGE_UNAVAILABLE,
                )) { retireGmsObservationBinding(); observation }
                else if (session.currentBound() != bound) unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.BRIDGE_UNAVAILABLE)
                else observation
            }
        } catch (cancelled: CancellationException) {
            retireGmsObservationBinding()
            unknown(if (cancelled is TimeoutCancellationException) io.github.originosfcmguardian.core.gms.GmsUnknown.TIMED_OUT
                else io.github.originosfcmguardian.core.gms.GmsUnknown.CANCELLED)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.BUSY)
        } catch (_: Throwable) {
            retireGmsObservationBinding(); unknown(io.github.originosfcmguardian.core.gms.GmsUnknown.BRIDGE_UNAVAILABLE)
        } finally { if (beganCall) session.finishCall(requestId); operationMutex.unlock() }
    }

    /** Fixed read-only proof on a dedicated observation binding; never returns a remote capability. */
    suspend fun qualifyHeartbeatCaller(previous: GmsHeartbeatCallerIdentity?): GmsHeartbeatCallerQualification {
        fun unknown() = GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
        if (!operationMutex.tryLock()) return unknown()
        val requestId = nextRequestId()
        var began = false
        return try {
            withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                if (!gmsObservationRpc.bounded { shellAvailable() }) return@withTimeout unknown()
                val bound = ensureBound(gmsBounded = true)
                if (gmsObservationRpc.bounded { bound.remote.serviceUid } != SHELL_UID) return@withTimeout unknown()
                if (!session.beginCall(bound, requestId)) return@withTimeout unknown()
                began = true
                val invocation = currentCoroutineContext()
                val result = gmsObservationRpc.bounded {
                    if (!invocation.isActive) return@bounded unknown()
                    GmsHeartbeatCallerCodec.decode(bound.remote.qualifyHeartbeatCaller(requestId,
                        previous?.let(GmsHeartbeatCallerCodec::encodeIdentity)).getString(GmsHeartbeatCallerCodec.BUNDLE_KEY), requestId)
                }
                if (session.currentBound() != bound) unknown() else result
            }
        } catch (_: Throwable) { unknown() }
        finally {
            // Includes cancellation/timeout and uses the reader's independent cleanup worker.
            retireGmsObservationBinding()
            if (began) session.finishCall(requestId)
            operationMutex.unlock()
        }
    }

    /** Caller must invoke for one explicit button press; never called by recovery or background checks. */
    suspend fun requestGmsHeartbeat(): GmsHeartbeatResult {
        if (!heartbeatOwner) return heartbeatTransport(context).requestGmsHeartbeat()
        GmsManualHeartbeatEvents.happened()
        val requestId = nextRequestId()
        if (!GmsAutoHeartbeatRuntime.manualHeartbeatAllowed(context))
            return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SESSION_REVOKED)
        if (!operationMutex.tryLock()) { GmsManualHeartbeatEvents.happened(); return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BUSY) }
        var beganCall = false
        val clientDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CLIENT_CALL_TIMEOUT_MILLIS)
        val invocation = currentCoroutineContext()
        val gate = GmsHeartbeatDispatchGate {
            when {
                !GmsAutoHeartbeatRuntime.manualHeartbeatAllowedNow() -> GmsHeartbeatReason.SESSION_REVOKED
                !mayBindManualGmsHeartbeat(autoOwners.values) -> GmsHeartbeatReason.BUSY
                !invocation.isActive -> GmsHeartbeatReason.CANCELLED
                System.nanoTime() >= clientDeadline -> GmsHeartbeatReason.TIMED_OUT
                else -> null
            }
        }
        return try { (try {
            withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                if (!GmsAutoHeartbeatRuntime.manualHeartbeatAllowed(context))
                    return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SESSION_REVOKED)
                if (!mayBindManualGmsHeartbeat(autoOwners.values)) return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BUSY)
                if (!session.isOpen()) return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SHELL_UNAVAILABLE)
                val available = gmsObservationRpc.bounded { shellAvailable() }
                if (!available) return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SHELL_UNAVAILABLE)
                if (!GmsAutoHeartbeatRuntime.manualHeartbeatAllowed(context))
                    return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SESSION_REVOKED)
                val bound = ensureBound(gmsBounded = true)
                val service = bound.remote
                if (gmsObservationRpc.bounded { service.serviceUid } != SHELL_UID)
                    return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SHELL_UNAVAILABLE)
                if (!session.beginCall(bound, requestId))
                    return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE)
                beganCall = true
                val result = gmsObservationRpc.bounded {
                    if (!gate.begin()) return@bounded gate.failure(requestId, GmsHeartbeatReason.CANCELLED)
                    GmsHeartbeatCodec.decode(service.requestGmsHeartbeat(requestId).getString(GmsHeartbeatCodec.BUNDLE_KEY), requestId)
                }
                if (session.currentBound() != bound) gate.failure(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE)
                else {
                    if (result.status == GmsHeartbeatStatus.UNKNOWN) retireGmsObservationBinding()
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            val result = gate.failure(requestId, if (cancelled is TimeoutCancellationException)
                GmsHeartbeatReason.TIMED_OUT else GmsHeartbeatReason.CANCELLED, abandon = true)
            retireGmsObservationBinding()
            result
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            gate.failure(requestId, GmsHeartbeatReason.BUSY, abandon = true)
        } catch (_: Throwable) {
            val result = gate.failure(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE, abandon = true)
            retireGmsObservationBinding()
            result
        }).also { result ->
            // Publish uncertainty while this shared transport still excludes a new ARM.
            // The controller compares this monotonic fence before clearing durable halt;
            // async event collection alone cannot establish that settlement boundary.
            if (result.status == GmsHeartbeatStatus.UNKNOWN) {
                autoOwners.values.forEach { it.disabled.set(true) }
                GmsManualHeartbeatEvents.unknown()
            }
        } } finally {
            gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true)
            if (beganCall) session.finishCall(requestId)
            operationMutex.unlock()
            GmsManualHeartbeatEvents.happened()
        }
    }

    suspend fun armAutomaticGmsHeartbeat(generation: Long, expectedBindingDigest: String, lifetime: IBinder): GmsAutoArmResult {
        if (!heartbeatOwner) return heartbeatTransport(context).armAutomaticGmsHeartbeat(generation, expectedBindingDigest, lifetime)
        fun failure(reason: GmsAutoArmReason) = GmsAutoArmResult(null, reason)
        if (generation <= 0 || !expectedBindingDigest.matches(Regex("[a-f0-9]{64}"))) return failure(GmsAutoArmReason.INVALID_REQUEST)
        if (!operationMutex.tryLock()) return failure(GmsAutoArmReason.BUSY)
        val requestId = nextRequestId()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CLIENT_CALL_TIMEOUT_MILLIS)
        val invocation = currentCoroutineContext()
        val gate = GmsHeartbeatDispatchGate { if (!invocation.isActive || System.nanoTime() >= deadline) GmsHeartbeatReason.CANCELLED else null }
        var owner: GmsAutoRemoteOwner? = null
        var began = false
        try {
            return withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                if (!mayArmGmsHeartbeat(autoOwners.values)) return@withTimeout failure(GmsAutoArmReason.BUSY)
                gmsObservationRpc.bounded {
                    autoOwners.entries.removeIf { (_, retained) ->
                        if (retained.closed.get() || retained.dead.get()) { runCatching { retained.detach() }; true } else false
                    }
                }
                if (!gmsObservationRpc.bounded { shellAvailable() }) return@withTimeout failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                val bound = ensureBound(gmsBounded = true)
                if (gmsObservationRpc.bounded { bound.remote.serviceUid } != SHELL_UID) return@withTimeout failure(GmsAutoArmReason.CALLER_UNAVAILABLE)
                val retained = gmsObservationRpc.bounded { GmsAutoRemoteOwner(bound.remote, generation) }
                owner = retained
                autoOwners[generation] = retained
                if (!session.beginCall(bound, requestId)) { retained.closed.set(true); return@withTimeout failure(GmsAutoArmReason.BRIDGE_UNAVAILABLE) }
                began = true
                val result = gmsObservationRpc.bounded {
                    if (!gate.begin()) { retained.closed.set(true); return@bounded failure(GmsAutoArmReason.TIMED_OUT) }
                    retained.invoked.set(true)
                    GmsAutoArmCodec.decode(bound.remote.armAutomaticGmsHeartbeat(requestId, generation, expectedBindingDigest, lifetime)
                        .getString(GmsAutoArmCodec.BUNDLE_KEY), requestId)
                }
                val armedSession = result.session
                if (armedSession != null) {
                    if (armedSession.generation != generation || armedSession.bindingDigest != expectedBindingDigest)
                        return@withTimeout failure(GmsAutoArmReason.MALFORMED_RESPONSE)
                    retained.session = armedSession
                } else if (result.reason !in setOf(GmsAutoArmReason.TIMED_OUT, GmsAutoArmReason.BRIDGE_UNAVAILABLE, GmsAutoArmReason.MALFORMED_RESPONSE)) retained.closed.set(true)
                result
            }
        } catch (_: TimeoutCancellationException) { return failure(GmsAutoArmReason.TIMED_OUT) }
        catch (_: java.util.concurrent.RejectedExecutionException) { return failure(GmsAutoArmReason.BUSY) }
        catch (_: Throwable) { return failure(GmsAutoArmReason.BRIDGE_UNAVAILABLE) }
        finally {
            gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true)
            owner?.let { retained ->
                if (!retained.invoked.get()) retained.closed.set(true)
                else if (retained.session == null) gmsObservationRpc.cleanupBestEffort { runCatching { retained.remote.cancel(requestId) } }
            }
            if (began) session.finishCall(requestId)
            operationMutex.unlock()
        }
    }

    suspend fun requestAutomaticGmsHeartbeat(session: GmsAutoSession, attemptId: String): GmsHeartbeatResult {
        if (!heartbeatOwner) return heartbeatTransport(context).requestAutomaticGmsHeartbeat(session, attemptId)
        val requestId = nextRequestId()
        val owner = autoOwners[session.generation]?.takeIf { it.session == session }
            ?: return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SESSION_REVOKED)
        if (owner.unavailable()) return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SESSION_REVOKED)
        if (!operationMutex.tryLock()) return GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.BUSY)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CLIENT_CALL_TIMEOUT_MILLIS)
        val invocation = currentCoroutineContext()
        val gate = GmsHeartbeatDispatchGate { when {
            owner.unavailable() -> GmsHeartbeatReason.SESSION_REVOKED
            !invocation.isActive || System.nanoTime() >= deadline -> GmsHeartbeatReason.CANCELLED
            else -> null
        } }
        try {
            return withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                if (!gmsObservationRpc.bounded { shellAvailable() }) return@withTimeout GmsHeartbeatResult.notSent(requestId, GmsHeartbeatReason.SHELL_UNAVAILABLE)
                gmsObservationRpc.bounded {
                    if (!gate.begin()) return@bounded gate.failure(requestId, GmsHeartbeatReason.CANCELLED)
                    GmsHeartbeatCodec.decode(owner.remote.requestAutomaticGmsHeartbeat(requestId, session.id,
                        session.generation, session.bindingDigest, attemptId).getString(GmsHeartbeatCodec.BUNDLE_KEY), requestId)
                        .also { if (it.status == GmsHeartbeatStatus.UNKNOWN) owner.disabled.set(true) }
                }
            }
        } catch (cancelled: CancellationException) {
            owner.disabled.set(true)
            return gate.failure(requestId, if (cancelled is TimeoutCancellationException) GmsHeartbeatReason.TIMED_OUT else GmsHeartbeatReason.CANCELLED, abandon = true)
        } catch (_: java.util.concurrent.RejectedExecutionException) { owner.disabled.set(true); return gate.failure(requestId, GmsHeartbeatReason.BUSY, abandon = true) }
        catch (dead: DeadObjectException) { owner.dead.set(true); return gate.failure(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE, abandon = true) }
        catch (_: Throwable) { owner.disabled.set(true); return gate.failure(requestId, GmsHeartbeatReason.BRIDGE_UNAVAILABLE, abandon = true) }
        finally {
            gate.failure(requestId, GmsHeartbeatReason.CANCELLED, abandon = true)
            operationMutex.unlock()
        }
    }

    suspend fun revokeAutomaticGmsHeartbeat(session: GmsAutoSession): Boolean {
        if (!heartbeatOwner) return heartbeatTransport(context).revokeAutomaticGmsHeartbeat(session)
        val owner = autoOwners[session.generation]?.takeIf { it.session == session } ?: return false
        owner.disabled.set(true) // Close local admission before awaiting the independently bounded remote gate.
        if (owner.closed.get() || owner.dead.get()) return true
        return try {
            withTimeout(CLIENT_CALL_TIMEOUT_MILLIS) {
                val closed = gmsHeartbeatRevokeRpc.bounded { owner.remote.revokeAutomaticGmsHeartbeat(session.generation, session.id) }
                owner.confirmRevoked(closed)
            }
        } catch (_: DeadObjectException) { owner.dead.set(true); true }
        catch (_: Throwable) { owner.dead.get() }
    }

    private fun retireGmsObservationBinding() {
        val teardown = session.cleanup()
        teardown.pendingConnection?.deferred?.cancel()
        gmsObservationRpc.cleanupBestEffort { applyTeardown(teardown) }
    }

    private fun retireStoppedObservationBinding() {
        val teardown = session.cleanup()
        teardown.pendingConnection?.deferred?.cancel()
        stoppedObservationRpc.cleanupBestEffort { applyTeardown(teardown) }
    }

    /** Ends one detection session but deliberately permits a later fresh bind. */
    suspend fun cleanup() = operationMutex.withLock {
        cleanupInternal(close = false)
    }

    /** Ends one detection session from a callback without permanently closing the runtime. */
    fun cleanupBestEffort() {
        applyTeardown(session.cleanup())
    }

    /** Permanently fail-closes this runtime until [openSession] is explicitly called. */
    fun closePermanently() {
        applyTeardown(session.close())
    }

    fun invalidate(cause: Throwable = IllegalStateException("Shizuku binder died")) {
        val teardown = session.cleanup()
        teardown.pendingConnection?.deferred?.completeExceptionally(cause)
        applyTeardown(teardown)
    }

    private suspend fun ensureBound(gmsBounded: Boolean = false): PrivilegedSessionState.Bound<IGuardianUserService, BindingHandle> {
        session.currentBound()?.let { current ->
            val alive = if (gmsBounded) gmsObservationRpc.bounded { current.remote.asBinder().pingBinder() }
                else current.remote.asBinder().pingBinder()
            if (alive) return current
            val teardown = session.disconnect(current.ticket) ?: session.cleanup()
            if (gmsBounded) gmsObservationRpc.cleanupBestEffort { applyTeardown(teardown) } else applyTeardown(teardown)
        }

        val ticket = session.beginBind()
            ?: throw IllegalStateException("Privileged session is closed")
        val deferred = CompletableDeferred<IGuardianUserService>()
        val handle = BindingHandle(userServiceArgs(ticket.generation), deferred)
        handle.connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IGuardianUserService.Stub.asInterface(binder)
                if (session.acceptBind(ticket, handle, service)) {
                    deferred.complete(service)
                } else {
                    deferred.completeExceptionally(IllegalStateException("Stale UserService callback"))
                    if (gmsBounded) gmsObservationRpc.cleanupBestEffort { unbind(handle) } else unbind(handle)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val cause = IllegalStateException("UserService disconnected")
                val teardown = session.disconnect(ticket) ?: return
                teardown.pendingConnection?.deferred?.completeExceptionally(cause)
                if (gmsBounded) gmsObservationRpc.cleanupBestEffort { applyTeardown(teardown, unbind = false) }
                else applyTeardown(teardown, unbind = false)
            }
        }
        if (!session.attachConnection(ticket, handle)) {
            deferred.cancel()
            throw IllegalStateException("Privileged session closed during bind setup")
        }

        try {
            if (gmsBounded) {
                gmsObservationRpc.bounded {
                    // Never hold the session monitor across an uninterruptible Binder call.
                    if (!session.isCurrentBind(ticket, handle)) error("Stale bind")
                    bindingAdapter.bind(handle.args, handle.connection)
                }
            } else withContext(Dispatchers.Main.immediate) {
                val started = session.runIfCurrentBind(ticket, handle) {
                    bindingAdapter.bind(handle.args, handle.connection)
                }
                if (!started) throw IllegalStateException("Privileged session closed before bind")
            }
            return withTimeout(BIND_TIMEOUT_MILLIS) {
                deferred.await()
                session.currentBound()?.takeIf { it.ticket == ticket }
                    ?: throw IllegalStateException("UserService bind became stale")
            }
        } catch (error: Throwable) {
            session.retireBind(ticket)?.let { retired ->
                retired.deferred.completeExceptionally(error)
                if (gmsBounded) gmsObservationRpc.cleanupBestEffort { unbind(retired) } else unbind(retired)
            }
            throw error
        }
    }

    private fun cleanupInternal(close: Boolean) {
        applyTeardown(if (close) session.close() else session.cleanup())
    }

    private fun applyTeardown(
        teardown: PrivilegedSessionState.Teardown<IGuardianUserService, BindingHandle>,
        unbind: Boolean = true,
    ) {
        teardown.activeRequestId?.let { requestId ->
            runCatching { teardown.bound?.remote?.cancel(requestId) }
        }
        teardown.pendingConnection?.deferred?.cancel()
        if (!unbind) return
        teardown.bound?.connection?.let(::unbind)
        teardown.pendingConnection?.let(::unbind)
    }

    private fun unbind(handle: BindingHandle) {
        runCatching { bindingAdapter.unbind(handle.args, handle.connection, true) }
    }

    private fun userServiceArgs(generation: Long): Shizuku.UserServiceArgs {
        val identity = userServiceIdentity(executorNamespace, generation)
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, GuardianUserService::class.java.name),
        )
            .daemon(false)
            .tag(identity.tag)
            .processNameSuffix(identity.processSuffix)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private fun nextRequestId(): Long {
        val value = requestIds.getAndIncrement()
        return if (value > 0) value else {
            requestIds.set(2)
            1
        }
    }

    private fun Bundle.toCommandResult(command: SafeCommand): CommandResult =
        sanitizeProcessTraceIpcResult(
            CommandResult(
                command = command,
                exitCode = getInt(IpcContract.KEY_EXIT_CODE, IpcContract.EXIT_BRIDGE_ERROR),
                stdout = getString(IpcContract.KEY_STDOUT).orEmpty(),
                stderr = getString(IpcContract.KEY_STDERR).orEmpty(),
                timedOut = getBoolean(IpcContract.KEY_TIMED_OUT, false),
                outputTruncated = getBoolean(IpcContract.KEY_OUTPUT_TRUNCATED, false),
            ),
        )

    private fun bridgeError(command: SafeCommand, detail: String) = CommandResult(
        command = command,
        exitCode = IpcContract.EXIT_BRIDGE_ERROR,
        stdout = "",
        stderr = detail.take(256),
    )

    private fun safeMessage(error: Throwable): String =
        error.message?.take(256) ?: error.javaClass.simpleName

    private companion object {
        private var sharedHeartbeat: ShizukuExecutor? = null
        @Synchronized fun heartbeatTransport(context: Context): ShizukuExecutor = sharedHeartbeat ?: ShizukuExecutor(
            context.applicationContext, RealShizukuBindingAdapter, heartbeatOwner = true).apply { openSession(); sharedHeartbeat = this }
        const val SHELL_UID = 2_000
        const val BIND_TIMEOUT_MILLIS = 5_000L
        const val CLIENT_CALL_TIMEOUT_MILLIS = 11_000L
    }
}

internal data class IpcArguments(val uid: Int, val packageName: String?)

internal fun ipcArguments(command: SafeCommand): IpcArguments = when (command) {
    is GetAppOpByUid -> IpcArguments(command.uid, null)
    is GetTargetProcessEvents -> IpcArguments(command.uid, command.packageName)
    else -> IpcArguments(IpcContract.NO_UID_ARGUMENT, null)
}

internal fun sanitizeProcessTraceIpcResult(result: CommandResult): CommandResult {
    if (result.command !is GetTargetProcessEvents) return result
    if (result.completedCleanly && ProcessTraceCodec.decode(result.stdout) != null) return result
    val timedOut = result.timedOut || result.exitCode == IpcContract.EXIT_TIMED_OUT
    val cancelled = result.exitCode == IpcContract.EXIT_CANCELLED
    return CommandResult(
        command = result.command,
        exitCode = when {
            cancelled -> IpcContract.EXIT_CANCELLED
            timedOut -> IpcContract.EXIT_TIMED_OUT
            else -> IpcContract.EXIT_BRIDGE_ERROR
        },
        stdout = "",
        stderr = when {
            cancelled -> "Process trace cancelled"
            timedOut -> "Process trace timed out"
            else -> "Process trace unavailable"
        },
        timedOut = timedOut,
        outputTruncated = false,
    )
}

internal data class UserServiceIdentity(
    val tag: String,
    val processSuffix: String,
)

internal fun userServiceIdentity(namespace: String, generation: Long): UserServiceIdentity {
    require(namespace.matches(Regex("[0-9a-f]{16}")))
    require(generation > 0)
    return UserServiceIdentity(
        tag = "guardian-read-only-v1-$namespace-$generation",
        processSuffix = "guardian_ro_${namespace}_$generation",
    )
}

private fun newUserServiceNamespace(): String =
    UUID.randomUUID().toString().replace("-", "").take(16)
