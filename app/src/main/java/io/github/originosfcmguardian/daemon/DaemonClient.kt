package io.github.originosfcmguardian.daemon

import android.content.ComponentName
import android.app.Application
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.originosfcmguardian.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    private fun lane(name: String) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val reads = lane("guardian-daemon-read")
    private val controls = lane("guardian-daemon-control")

    private suspend fun connect(): IProtectionDaemon = binding.withLock {
        remote.get()?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        withTimeout(12_000) {
            suspendCancellableCoroutine { continuation ->
                val next = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (connection !== this) return
                        if (service == null || !service.isBinderAlive) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Daemon unavailable"))
                            return
                        }
                        val api = IProtectionDaemon.Stub.asInterface(service)
                        try {
                            val death = IBinder.DeathRecipient {
                                if (remote.compareAndSet(api, null)) onDisconnect()
                            }
                            service.linkToDeath(death, 0)
                            linkedDeath = service to death
                            remote.set(api)
                            if (!service.isBinderAlive && remote.compareAndSet(api, null)) onDisconnect()
                        } catch (failure: Exception) {
                            remote.compareAndSet(api, null)
                            if (continuation.isActive) continuation.resumeWithException(failure)
                            return
                        }
                        if (continuation.isActive) continuation.resume(api)
                    }
                    private fun disconnected() {
                        if (connection === this && remote.getAndSet(null) != null) onDisconnect()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = disconnected()
                    override fun onBindingDied(name: ComponentName?) = disconnected()
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
                    catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
                // Do not remove a daemon after a UI timeout; the next request may reconnect to it.
            }
        }
    }

    suspend fun call(operation: Int, request: Bundle = Bundle()): Bundle = callOn(connect(), operation, request)

    /** Reporting may use an already attached endpoint, but must never bind/start/replace a daemon. */
    suspend fun readDiagnosticsExisting(): Bundle = readExistingDaemonDiagnostics(
        cached = remote::get,
        alive = { it.asBinder().isBinderAlive },
        read = { callOn(it, DaemonWire.READ_DIAGNOSTICS, Bundle()) })

    private suspend fun callOn(api: IProtectionDaemon, operation: Int, request: Bundle): Bundle {
        val reader = operation in setOf(DaemonWire.PREFLIGHT, DaemonWire.READ_GMS, DaemonWire.STATUS, DaemonWire.BEGIN_SNAPSHOT,
            DaemonWire.SNAPSHOT_CHUNK, DaemonWire.END_SNAPSHOT, DaemonWire.QUERY, DaemonWire.IMPORT_OFFSET,
            DaemonWire.READ_DIAGNOSTICS)
        return withTimeout(30_000) {
            suspendCancellableCoroutine { continuation ->
                val future = try { (if (reader) reads else controls).submit {
                    val result = runCatching {
                        check(api.getServiceUid() == 2000)
                        DaemonWire.checkReply(api.transact(operation, request))
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                } } catch (failure: Exception) {
                    continuation.resumeWithException(failure); null
                }
                continuation.invokeOnCancellation { future?.cancel(true) }
            }
        }
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
