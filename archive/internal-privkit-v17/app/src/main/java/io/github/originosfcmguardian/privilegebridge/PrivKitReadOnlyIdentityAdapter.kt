package io.github.originosfcmguardian.privilegebridge

import android.os.Binder
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import java.util.UUID

/**
 * Unwired host candidate: explicitly reads an ALREADY connected app-owned server.
 * This does not pair, activate, reconnect, persist consent, observe stopped state, or authorize writes.
 */
class PrivKitReadOnlyIdentityAdapter : Closeable {
    private val gate = ReadOnlyBridgeGate()
    private val rpc = BoundedIdentityRpc()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cleanup: Job? = null

    suspend fun readIdentity(choice: ReadOnlyTransportChoice): PrivilegeIdentityResult {
        // Selection is surfaced to the caller, who owns the existing external Shizuku adapter.
        if (choice == ReadOnlyTransportChoice.EXTERNAL_SHIZUKU) return PrivilegeIdentityResult.ExternalFallbackSelected
        if (gate.isRevoked()) return unavailable(PrivilegeIdentityResult.Reason.REVOKED)
        if (cleanup?.isCompleted == false || gate.isBusy()) return unavailable(PrivilegeIdentityResult.Reason.BUSY)
        val server = Privilege.serverState.value ?: return unavailable(PrivilegeIdentityResult.Reason.DISCONNECTED)
        if (server.uid != 2000 || Process.myUid() !in 10_000..99_999) {
            return unavailable(PrivilegeIdentityResult.Reason.REJECTED_IDENTITY)
        }
        val ticket = gate.begin(server.lifecycleBinder, server.uid, Process.myUid())
            ?: return unavailable(PrivilegeIdentityResult.Reason.BUSY)
        val spec = PrivilegeUserServiceSpec(
            serviceClassName = ReadOnlyIdentityUserService::class.java.name,
            tag = "guardian_identity_${UUID.randomUUID()}",
            embedded = false,
            daemon = false,
        )
        var connection: PrivilegeUserServiceConnection? = null
        var remote: IReadOnlyPrivilegeIdentity? = null
        var readBinder: IBinder? = null
        var serviceLinked = false
        var serverLinked = false
        val endpointLock = Any()
        var abandoned = false
        val death = IBinder.DeathRecipient { gate.invalidate(ticket) }
        var outcome: PrivilegeIdentityResult
        try {
            server.lifecycleBinder.linkToDeath(death, 0); serverLinked = true
            outcome = withTimeout(READ_TIMEOUT_MILLIS) {
                connection = Privilege.bindUserService(spec)
                val relay = requireNotNull(connection).binder
                val bootstrap = requireNotNull(IReadOnlyPrivilegeIdentity.Stub.asInterface(relay))
                val owner = Binder()
                val readJob = requireNotNull(currentCoroutineContext()[Job])
                // Binder RPC is not reliably interruptible. One worker, no queue; cancellation
                // drops its result immediately, and a stuck worker cannot accumulate more reads.
                // Bootstrap and identity form one work item, avoiding a single-worker handoff race.
                val response = rpc.read {
                    readJob.ensureActive()
                    val direct = bootstrap.readEndpoint
                    val service = requireNotNull(IReadOnlyPrivilegeIdentity.Stub.asInterface(direct))
                    synchronized(endpointLock) {
                        check(!abandoned)
                        readJob.ensureActive()
                        direct.linkToDeath(death, 0)
                        readBinder = direct; serviceLinked = true; remote = service
                    }
                    readJob.ensureActive()
                    check(direct.isBinderAlive && server.lifecycleBinder.isBinderAlive)
                    service.readIdentity(ticket.generation, owner)
                }
                val binder = synchronized(endpointLock) { requireNotNull(readBinder) }
                val current = Privilege.serverState.value
                if (!gate.accepts(ticket, current?.lifecycleBinder,
                        response.getInt(ReadOnlyIdentityUserService.KEY_UID, -1),
                        response.getInt(ReadOnlyIdentityUserService.KEY_USER, -1),
                        response.getLong(ReadOnlyIdentityUserService.KEY_GENERATION, -1)) ||
                    current?.uid != 2000 || !binder.isBinderAlive || !server.lifecycleBinder.isBinderAlive) {
                    unavailable(PrivilegeIdentityResult.Reason.STALE)
                } else {
                    PrivilegeIdentityResult.Verified(ticket.generation, 2000, 0)
                }
            }
        } catch (_: TimeoutCancellationException) {
            outcome = unavailable(PrivilegeIdentityResult.Reason.TIMED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            outcome = unavailable(PrivilegeIdentityResult.Reason.TRANSPORT_FAILED)
        } finally {
            gate.invalidate(ticket)
            val cleanupRemote = synchronized(endpointLock) {
                abandoned = true
                if (serviceLinked) runCatching { readBinder?.unlinkToDeath(death, 0) }
                remote
            }
            if (serverLinked) runCatching { server.lifecycleBinder.unlinkToDeath(death, 0) }
            connection?.let { bound ->
                // Priv Kit unbind is internally NonCancellable. Keep it independent of the
                // caller and disallow another bind while it is pending. The short wait below
                // bounds caller delay, not the library's remote teardown guarantee.
                val job = cleanupScope.launch {
                    try {
                        runCatching { cleanupRemote?.cancel() }
                        runCatching { bound.unbind() }.onFailure { gate.revoke() }
                    } finally { gate.finish(ticket) }
                }
                cleanup = job
                withTimeoutOrNull(CLEANUP_WAIT_MILLIS) { job.join() }
            } ?: gate.finish(ticket)
        }
        // Cleanup can wait or revoke on unbind failure. Never publish an earlier success
        // after explicit close or failed teardown during that wait.
        currentCoroutineContext().ensureActive()
        return if (gate.isRevoked()) unavailable(PrivilegeIdentityResult.Reason.REVOKED) else outcome
    }

    /** Revocation wins over every pending generation; a fresh adapter needs explicit selection. */
    override fun close() { gate.revoke(); rpc.close() }

    private fun unavailable(reason: PrivilegeIdentityResult.Reason) = PrivilegeIdentityResult.Unavailable(reason)

    private companion object {
        const val READ_TIMEOUT_MILLIS = 4_000L
        const val CLEANUP_WAIT_MILLIS = 250L
    }
}
