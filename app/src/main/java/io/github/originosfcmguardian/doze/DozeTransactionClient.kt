package io.github.originosfcmguardian.doze

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import io.github.originosfcmguardian.core.doze.DozeOutcome
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import io.github.originosfcmguardian.core.doze.DozePhase
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-demand transaction client. No UI path calls this yet. The caller must use a background thread.
 * A JVM future supplies a real bound for blocking Binder calls; coroutine cancellation alone does not.
 */
class DozeTransactionClient internal constructor(
    private val context: Context,
    private val store: DozeJournalPort,
    private val endpoint: DozeEndpoint,
) {
    constructor(context: Context) : this(context.applicationContext, DozeAtomicJournalStore(context), DozeEndpoint.GMS)

    internal constructor(context: Context, testStore: DozeJournalPort, @Suppress("UNUSED_PARAMETER") testOnly: Boolean) :
        this(context.applicationContext, testStore, DozeEndpoint.GMS)

    fun inspect(): DozeOutcome = execute(Mode.INSPECT)
    fun apply(): DozeOutcome = execute(Mode.APPLY)
    fun restore(): DozeOutcome = execute(Mode.RESTORE)

    private fun execute(mode: Mode): DozeOutcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Doze transaction cannot block the UI thread" }
        if (!singleSession.compareAndSet(false, true)) {
            return DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Another Doze transaction is active")
        }
        val active = AtomicBoolean(true)
        val deadline = System.nanoTime() + TOTAL_CLIENT_MILLIS * 1_000_000L
        var binding: Binding? = null
        try {
            check(Build.FINGERPRINT == KNOWN_FINGERPRINT) { "Unknown device fingerprint" }
            check(endpoint == DozeEndpoint.GMS || BuildConfig.DEBUG) { "Owned Doze endpoint requires debug build" }
            check(Shizuku.pingBinder() && Shizuku.getUid() == SHELL_UID &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) { "Authorized Shizuku shell is unavailable" }
            val callback = object : IDozeJournalCallback.Stub() {
                private fun requireTrustedShell() {
                    check(Binder.getCallingUid() == SHELL_UID && active.get() && remaining(deadline) > 0) {
                        "Doze journal callback is unauthorized or closed"
                    }
                }

                override fun isSessionActive(): Boolean =
                    Binder.getCallingUid() == SHELL_UID && active.get() && remaining(deadline) > 0

                override fun load(): ByteArray? {
                    requireTrustedShell()
                    val value = store.load()
                    requireTrustedShell()
                    return value
                }

                override fun compareAndSet(expected: ByteArray?, replacement: ByteArray?): Boolean {
                    requireTrustedShell()
                    val candidate = requireNotNull(replacement) { "Missing Doze journal replacement" }
                    val committed = store.compareAndSet(expected, candidate)
                    requireTrustedShell()
                    return committed
                }
            }
            binding = Binding(context, active, endpoint)
            val service = binding.bind(remaining(deadline))
            check(callBounded(deadline) { service.serviceUid } == SHELL_UID) {
                "Doze UserService is not shell UID 2000"
            }
            val bundle = callBounded(deadline) {
                when (mode) {
                    Mode.INSPECT -> service.inspect(callback)
                    Mode.APPLY -> service.apply(callback)
                    Mode.RESTORE -> service.restore(callback)
                }
            }
            val code = DozeOutcomeCode.entries.singleOrNull { it.name == bundle.getString("code") }
                ?: throw IllegalStateException("Unknown Doze service result")
            val phaseName = bundle.getString("phase")
            val phase = phaseName?.let { value ->
                DozePhase.entries.singleOrNull { it.name == value }
                    ?: throw IllegalStateException("Unknown Doze journal phase")
            }
            return DozeOutcome(code, phase, bundle.getString("detail").orEmpty().take(192))
        } catch (failure: Throwable) {
            return DozeOutcome(
                code = if (mode == Mode.INSPECT) DozeOutcomeCode.BLOCKED else DozeOutcomeCode.UNCERTAIN,
                detail = failure.message?.take(192) ?: failure.javaClass.simpleName,
            )
        } finally {
            active.set(false)
            binding?.close()
            singleSession.set(false)
        }
    }

    private class Binding(
        private val context: Context,
        private val active: AtomicBoolean,
        endpoint: DozeEndpoint,
    ) : AutoCloseable {
        private val token = UUID.randomUUID().toString().replace("-", "").take(16)
        private val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, endpoint.serviceClassName),
        )
            .daemon(false)
            .tag("guardian-${endpoint.tag}-doze-v2-$token")
            .processNameSuffix("guardian_${endpoint.tag}_doze_$token")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        private val connected = CountDownLatch(1)
        private val remote = AtomicReference<IGmsDozeTransaction?>()
        private val bindLifecycle = DozeBindLifecycle {
            Shizuku.unbindUserService(args, connection, true)
        }
        private val dead = AtomicBoolean(false)
        private val death = IBinder.DeathRecipient {
            dead.set(true)
            active.set(false)
            connected.countDown()
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!active.get()) {
                    close()
                    return
                }
                try {
                    binder.linkToDeath(death, 0)
                    remote.set(IGmsDozeTransaction.Stub.asInterface(binder))
                } catch (_: Throwable) {
                    dead.set(true)
                    active.set(false)
                } finally {
                    connected.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                dead.set(true)
                active.set(false)
                remote.set(null)
                connected.countDown()
            }
        }

        fun bind(remainingMillis: Long): IGmsDozeTransaction {
            check(remainingMillis > 0) { "Doze bind deadline exhausted" }
            bindLifecycle.beforeDispatch()
            callBounded(System.nanoTime() + minOf(remainingMillis, BIND_MILLIS) * 1_000_000L) {
                Shizuku.bindUserService(args, connection)
                bindLifecycle.afterReturn()
            }
            check(connected.await(minOf(remainingMillis, BIND_MILLIS), TimeUnit.MILLISECONDS)) {
                "Doze UserService bind timed out"
            }
            check(!dead.get()) { "Doze UserService died during bind" }
            return requireNotNull(remote.get()) { "Doze UserService did not connect" }
        }

        override fun close() {
            active.set(false)
            val binder = remote.getAndSet(null)?.asBinder()
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "guardian-doze-cleanup").apply { isDaemon = true }
            }
            try {
                executor.submit {
                    binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
                    runCatching { bindLifecycle.close() }
                }.get(CLEANUP_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                // Callback is already closed. A stalled cleanup cannot hold the public operation.
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private enum class Mode { INSPECT, APPLY, RESTORE }

    private companion object {
        const val SHELL_UID = 2_000
        const val BIND_MILLIS = 5_000L
        const val CLEANUP_MILLIS = 2_000L
        const val TOTAL_CLIENT_MILLIS = 46_000L
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
        val singleSession = AtomicBoolean(false)

        fun remaining(deadline: Long): Long = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)

        fun <T> callBounded(deadline: Long, block: () -> T): T {
            val budget = remaining(deadline)
            check(budget > 0) { "Doze Binder deadline exhausted" }
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "guardian-doze-binder").apply { isDaemon = true }
            }
            try {
                return executor.submit(Callable(block)).get(budget, TimeUnit.MILLISECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}

/** The release constructor is permanently GMS; only debug source uses OWNED_DEBUG. */
internal enum class DozeEndpoint(val serviceClassName: String, val tag: String) {
    GMS(GmsDozeUserService::class.java.name, "gms"),
    OWNED_DEBUG("io.github.originosfcmguardian.doze.OwnedDozeTransactionUserService", "owned"),
}

/** An early timeout may race a late successful bind; both sides request removal. */
internal class DozeBindLifecycle(private val unbind: () -> Unit) {
    private val attempted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun beforeDispatch() {
        check(!closed.get()) { "Doze binding already closed" }
        attempted.set(true)
    }

    fun afterReturn() {
        if (closed.get()) runCatching(unbind)
    }

    fun close() {
        closed.set(true)
        if (attempted.getAndSet(false)) unbind()
    }
}
