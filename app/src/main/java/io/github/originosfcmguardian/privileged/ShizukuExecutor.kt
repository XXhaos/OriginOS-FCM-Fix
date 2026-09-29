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
import io.github.originosfcmguardian.core.SafeCommand.GetTargetAutostartRow
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

    fun openSession() = session.open()

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

    private suspend fun ensureBound(): PrivilegedSessionState.Bound<IGuardianUserService, BindingHandle> {
        session.currentBound()?.let { current ->
            if (current.remote.asBinder().pingBinder()) return current
            applyTeardown(session.disconnect(current.ticket) ?: session.cleanup())
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
                    unbind(handle)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val cause = IllegalStateException("UserService disconnected")
                val teardown = session.disconnect(ticket) ?: return
                teardown.pendingConnection?.deferred?.completeExceptionally(cause)
                applyTeardown(teardown, unbind = false)
            }
        }
        if (!session.attachConnection(ticket, handle)) {
            deferred.cancel()
            throw IllegalStateException("Privileged session closed during bind setup")
        }

        try {
            withContext(Dispatchers.Main.immediate) {
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
                unbind(retired)
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
        const val SHELL_UID = 2_000
        const val BIND_TIMEOUT_MILLIS = 5_000L
        const val CLIENT_CALL_TIMEOUT_MILLIS = 11_000L
    }
}

internal data class IpcArguments(val uid: Int, val packageName: String?)

internal fun ipcArguments(command: SafeCommand): IpcArguments = when (command) {
    is GetAppOpByUid -> IpcArguments(command.uid, null)
    is GetTargetAutostartRow -> IpcArguments(IpcContract.NO_UID_ARGUMENT, command.packageName)
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
