package io.github.originosfcmguardian.daemon

import android.content.ComponentName
import android.app.Application
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.originosfcmguardian.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Stable connection only: App detachment never removes/kills the daemon. Bounded RPC lanes. */
internal class DaemonClient(context: Context, private val onDisconnect: () -> Unit = {}) {
    private val app = context.applicationContext as? Application ?: error("Application context required")
    private val binding = Mutex()
    private val remote = AtomicReference<IProtectionDaemon?>()
    private var connection: ServiceConnection? = null
    private var linkedDeath: Pair<IBinder, IBinder.DeathRecipient>? = null
    private val arguments = Shizuku.UserServiceArgs(ComponentName(app.packageName, ProtectionDaemonService::class.java.name))
        .daemon(true).tag("guardian-protection-v1").processNameSuffix("guardian_protection")
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val reads = DaemonReadRpc()
    private val controls = DaemonControlRpc()

    private suspend fun connect(): IProtectionDaemon = binding.withLock {
        remote.get()?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        try {
            if (!Shizuku.pingBinder()) throw DaemonAdoptionException(DaemonAdoptionStage.READINESS, DaemonAdoptionReason.NOT_READY)
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                throw DaemonAdoptionException(DaemonAdoptionStage.READINESS, DaemonAdoptionReason.PERMISSION_DENIED)
        } catch (failure: Exception) { throw classified(DaemonAdoptionStage.READINESS, failure) }
        withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { continuation ->
                val completion = DaemonBindingCompletion<IProtectionDaemon> { result ->
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
                fun failed(failure: Exception) {
                    completion.failed(classified(DaemonAdoptionStage.BIND, failure))
                }
                continuation.invokeOnCancellation { completion.cancel() }
                val next = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (connection !== this) return
                        if (service == null || !service.isBinderAlive) {
                            failed(DaemonAdoptionException(DaemonAdoptionStage.BIND, DaemonAdoptionReason.BIND_DISCONNECTED))
                            return
                        }
                        val api = IProtectionDaemon.Stub.asInterface(service)
                        try {
                            val death = IBinder.DeathRecipient {
                                if (remote.compareAndSet(api, null)) onDisconnect()
                                failed(DaemonAdoptionException(DaemonAdoptionStage.BIND, DaemonAdoptionReason.DEAD_BINDER))
                            }
                            service.linkToDeath(death, 0)
                            linkedDeath = service to death
                            remote.set(api)
                            if (!service.isBinderAlive) {
                                if (remote.compareAndSet(api, null)) onDisconnect()
                                failed(DaemonAdoptionException(DaemonAdoptionStage.BIND, DaemonAdoptionReason.DEAD_BINDER))
                                return
                            }
                        } catch (failure: Exception) {
                            remote.compareAndSet(api, null)
                            failed(failure)
                            return
                        }
                        completion.connected(api)
                    }
                    private fun disconnected() {
                        if (connection !== this) return
                        if (remote.getAndSet(null) != null) onDisconnect()
                        // A cold bind can fail before an endpoint was ever published.
                        failed(DaemonAdoptionException(DaemonAdoptionStage.BIND, DaemonAdoptionReason.BIND_DISCONNECTED))
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = disconnected()
                    override fun onBindingDied(name: ComponentName?) = disconnected()
                    override fun onNullBinding(name: ComponentName?) = disconnected()
                }
                Handler(Looper.getMainLooper()).post {
                    if (!continuation.isActive) return@post
                    try {
                        // Shizuku removes every callback wrapper on unbind: detach before rebinding,
                        // on this same main-thread lane, and never remove the independent daemon.
                        val previous = connection
                        linkedDeath?.let { (binder, death) -> runCatching { binder.unlinkToDeath(death, 0) } }
                        linkedDeath = null
                        remote.set(null)
                        if (previous != null) Shizuku.unbindUserService(arguments, previous, false)
                        connection = next
                        Shizuku.bindUserService(arguments, next)
                    }
                    catch (failure: Exception) { failed(failure) }
                }
                // Do not remove a daemon after a UI timeout; the next request may reconnect to it.
            }
        } ?: throw DaemonAdoptionException(DaemonAdoptionStage.BIND, DaemonAdoptionReason.BIND_TIMEOUT)
    }

    suspend fun call(operation: Int, request: Bundle = Bundle()): Bundle = callOn(connect(), operation, request)

    /** Reporting may use an already attached endpoint, but must never bind/start/replace a daemon. */
    suspend fun readDiagnosticsExisting(): Bundle = readExistingDaemonDiagnostics(
        cached = remote::get,
        alive = { it.asBinder().isBinderAlive },
        read = { callOn(it, DaemonWire.READ_DIAGNOSTICS, Bundle()) })

    private suspend fun callOn(api: IProtectionDaemon, operation: Int, request: Bundle): Bundle {
        val rpcStage = if (operation == DaemonWire.PREFLIGHT) DaemonAdoptionStage.PREFLIGHT else DaemonAdoptionStage.HANDOFF
        val reader = operation in setOf(DaemonWire.PREFLIGHT, DaemonWire.READ_GMS, DaemonWire.STATUS, DaemonWire.BEGIN_SNAPSHOT,
            DaemonWire.SNAPSHOT_CHUNK, DaemonWire.END_SNAPSHOT, DaemonWire.QUERY, DaemonWire.IMPORT_OFFSET,
            DaemonWire.READ_DIAGNOSTICS)
        if (reader) return try {
            reads.call { checkActive ->
                checkActive()
                if (api.getServiceUid() != 2000) throw DaemonAdoptionException(
                    DaemonAdoptionStage.SERVICE_IDENTITY, DaemonAdoptionReason.WRONG_SERVICE_UID)
                checkActive()
                val reply = api.transact(operation, request)
                if (!reply.getBoolean("ok")) {
                    if (operation == DaemonWire.PREFLIGHT)
                        daemonPreflightFailure(reply.getString("adoptionStage"), reply.getString("adoptionReason"))?.let { throw it }
                    throw DaemonAdoptionException(rpcStage, when (reply.getString("error")) {
                        "BUSY" -> DaemonAdoptionReason.BUSY
                        "OWNER_CONFLICT" -> DaemonAdoptionReason.OWNER_CONFLICT
                        "INVALID_REQUEST" -> DaemonAdoptionReason.INVALID_REQUEST
                        "UNAVAILABLE", "NOT_READY" -> DaemonAdoptionReason.REMOTE_UNAVAILABLE
                        else -> DaemonAdoptionReason.INVALID_RESPONSE
                    })
                }
                reply
            }
        } catch (_: TimeoutException) {
            throw DaemonAdoptionException(rpcStage, DaemonAdoptionReason.TIMED_OUT)
        } catch (failure: Exception) { throw classified(rpcStage, failure) }
        val actionEntered = AtomicBoolean()
        val fcmStage = operation == DaemonWire.STAGE && request.getInt("command", -1) == DaemonWire.FCM_ON
        return try {
            controls.call { checkActive ->
                actionEntered.set(true)
                checkActive()
                if (api.getServiceUid() != 2000) throw DaemonAdoptionException(
                    DaemonAdoptionStage.SERVICE_IDENTITY, DaemonAdoptionReason.WRONG_SERVICE_UID)
                checkActive() // A timed-out queued control must never dispatch later.
                val reply = api.transact(operation, request)
                if (!reply.getBoolean("ok")) {
                    if (fcmStage) decodeDaemonFcmNotStaged(reply.getString("error"),
                        reply.getString("fcmPreparationReason"), reply.getString("fcmReadUnknown"))?.let {
                        throw DaemonFcmNotStagedException(it)
                    }
                    throw DaemonAdoptionException(rpcStage, when (reply.getString("error")) {
                        "BUSY" -> DaemonAdoptionReason.BUSY
                        "OWNER_CONFLICT" -> DaemonAdoptionReason.OWNER_CONFLICT
                        "INVALID_REQUEST" -> DaemonAdoptionReason.INVALID_REQUEST
                        "UNAVAILABLE", "NOT_READY" -> DaemonAdoptionReason.REMOTE_UNAVAILABLE
                        else -> DaemonAdoptionReason.INVALID_RESPONSE
                    })
                }
                reply
            }
        } catch (_: TimeoutException) {
            throw DaemonAdoptionException(rpcStage, DaemonAdoptionReason.TIMED_OUT)
        } catch (failure: RejectedExecutionException) {
            if (fcmStage && !actionEntered.get()) throw DaemonFcmNotStagedException(
                DaemonFcmPreparationFailure(DaemonFcmPreparationReason.CLIENT_BUSY))
            throw classified(rpcStage, failure)
        } catch (failure: Exception) { throw classified(rpcStage, failure) }
    }

    private fun classified(stage: DaemonAdoptionStage, failure: Throwable): Throwable = when (failure) {
        is CancellationException, is DaemonAdoptionException, is DaemonFcmNotStagedException -> failure
        is DeadObjectException -> DaemonAdoptionException(stage, DaemonAdoptionReason.DEAD_BINDER)
        is SecurityException -> DaemonAdoptionException(stage, DaemonAdoptionReason.SECURITY_ERROR)
        is RejectedExecutionException -> DaemonAdoptionException(stage, DaemonAdoptionReason.BUSY)
        else -> DaemonAdoptionException(stage, if (stage == DaemonAdoptionStage.BIND)
            DaemonAdoptionReason.BIND_FAILED else DaemonAdoptionReason.REMOTE_UNAVAILABLE)
    }
    suspend fun bootstrap(intent: DaemonMigrationIntent): Bundle = call(DaemonWire.BOOTSTRAP, Bundle().apply {
        putString("instance", intent.owner.installInstance); putLong("epoch", intent.owner.ownerEpoch); putString("migration", intent.migrationId)
    })
    suspend fun import(snapshot: DaemonProtectionRootSnapshot): DaemonControlRecord = withTimeout(60_000) {
        val bytes = DaemonProtectionRootCodec.encode(snapshot)
        val begin = call(DaemonWire.BEGIN_IMPORT, Bundle().apply {
            putInt("size", bytes.size); putString("sha256", DaemonProtectionRootSnapshot.digest(bytes))
        })
        val token = DaemonWire.text(begin, "token")
        var offset = DaemonWire.int(call(DaemonWire.IMPORT_OFFSET, Bundle().apply { putString("token", token) }), "offset")
        check(offset in 0..bytes.size)
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + DaemonImportProtocol.MAX_CHUNK_BYTES)
            val result = call(DaemonWire.APPEND_IMPORT, Bundle().apply {
                putString("token", token); putInt("offset", offset); putByteArray("bytes", bytes.copyOfRange(offset, end))
            })
            check(DaemonWire.int(result, "offset") == end); offset = end
        }
        DaemonWire.record(DaemonWire.nested(call(DaemonWire.FINISH_IMPORT, Bundle().apply { putString("token", token) }), "record"))
    }
    suspend fun snapshot(): DaemonProtectionRootSnapshot = withContext(Dispatchers.IO) { withTimeout(45_000) {
        val header = call(DaemonWire.BEGIN_SNAPSHOT)
        val token = DaemonWire.text(header, "token"); val size = DaemonWire.int(header, "size")
        val revision = DaemonWire.long(header, "dataRevision"); val expected = DaemonWire.record(DaemonWire.nested(header, "record"))
        require(size in 40..DaemonProtectionRootCodec.MAX_BYTES)
        val bytes = ByteArray(size)
        try {
            var offset = 0
            while (offset < size) {
                val count = minOf(DaemonImportProtocol.MAX_CHUNK_BYTES, size - offset)
                val reply = call(DaemonWire.SNAPSHOT_CHUNK, Bundle().apply {
                    putString("token", token); putLong("dataRevision", revision); putInt("offset", offset); putInt("size", count)
                })
                val chunk = DaemonWire.chunk(reply, "bytes"); check(chunk.size == count)
                chunk.copyInto(bytes, offset); offset += count
            }
            check(DaemonProtectionRootSnapshot.digest(bytes) == DaemonWire.text(header, "sha256"))
            DaemonProtectionRootCodec.decode(bytes).also { check(it.dataRevision == revision && it.control == expected) }
        } finally { runCatching { call(DaemonWire.END_SNAPSHOT, Bundle().apply { putString("token", token) }) } }
    } }
}
