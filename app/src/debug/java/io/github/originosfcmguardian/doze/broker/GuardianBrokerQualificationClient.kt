package io.github.originosfcmguardian.doze.broker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCleanup
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCoordinator
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerOperation
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerRevokeAck
import io.github.originosfcmguardian.doze.GmsDozeBrokerRecordStore
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class GuardianBrokerQualificationResult(
    val code: String,
    val stage: String,
    val elapsedMillis: Long,
    val ackWhileChildAlive: Boolean,
    val cleanupFactsComplete: Boolean,
    val resultReleased: Boolean,
    val serviceDeathObserved: Boolean,
)

/** One opt-in debug event using only the fixed shell sleep fixture. No production authority. */
internal class GuardianBrokerQualificationClient(private val context: Context) {
    fun run(expectedGuardianAppUid: Int): GuardianBrokerQualificationResult {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(45)
        val operations = deadline - TimeUnit.SECONDS.toNanos(2)
        if (!entrance.compareAndSet(false, true)) return GuardianBrokerQualificationResult(
            "UNKNOWN", "ENTRANCE_CLOSED", 0, false, false, false, false)
        val open = AtomicBoolean(true)
        val control = lane("guardian-broker-control")
        val awaitLane = lane("guardian-broker-await")
        var binding: Binding? = null
        var stage = "APP_IDENTITY"
        var ackWhileAlive = false
        var cleanupComplete = false
        var released = false
        var passed = false
        var died = false
        var removalComplete = false
        fun <T> short(limit: Long = operations, action: () -> T): T {
            val stop = minOf(operations, limit, System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
            val future = control.submit<T> { check(open.get()); action() }
            val result = future.get(remaining(stop), TimeUnit.MILLISECONDS)
            check(open.get() && remaining(operations) > 0)
            return result
        }
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            short { GuardianBrokerQualificationIdentity.app(context, expectedGuardianAppUid) }
            stage = "EXTERNAL_SHIZUKU_READY"
            check(short { Shizuku.pingBinder() && Shizuku.getUid() == 2_000 &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED })
            stage = "FRESH_FIXED_SCRATCH"
            val store = short { freshStore() }
            check(short { store.readExact().state } == GmsDozeBrokerCodec.initialState())
            val coordinator = GmsDozeBrokerCoordinator(store)
            stage = "FIXTURE_ONLY_ENABLE"
            val enabled = requireNotNull(short { coordinator.enableFromExplicitConsent() })
            check(enabled.state.preparationAllowed && enabled.state.pin == null)
            stage = "BIND_ONCE"
            val ownBinding = Binding(context.applicationContext, open)
            binding = ownBinding
            val bindDeadline = minOf(operations, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            short { ownBinding.dispatchBind() }
            val remote = ownBinding.awaitConnected(bindDeadline)
            val lifetime = Binder()
            stage = "HELLO"
            val hello = requireNotNull(short { remote.hello(lifetime) })
            val nonce = requireNotNull(hello.getString("nonce"))
            check(nonce.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            response(hello, nonce, "HELLO", setOf("shellUid", "buildId", "awaitInProgress", "busy"))
            check(hello.getInt("shellUid") == 2_000 && hello.getInt("buildId") == BuildConfig.VERSION_CODE &&
                !hello.getBoolean("awaitInProgress") && !hello.getBoolean("busy"))
            stage = "PIN_EXACT_CAS_READBACK"
            val pinned = requireNotNull(short { coordinator.pin(nonce) })
            val key = requireNotNull(pinned.state.pin)
            stage = "ACTIVATE_ONCE"
            response(short { remote.activate(pinned.exactBytes()) }, nonce, "ACTIVATED")
            stage = "PREPARE_ONCE"
            val prepared = requireNotNull(short { coordinator.prepare(key,
                UUID.randomUUID().toString(), GmsDozeBrokerOperation.GLOBAL_ADD) })
            val preparedBytes = prepared.exactBytes()
            // This semantic operation is consumed only by the debug service's fixed sleep child.
            val intent = requireNotNull(GmsDozeBrokerCodec.decode(preparedBytes).intent)
            stage = "START_ONCE"
            response(short { remote.start(preparedBytes.clone()) }, nonce, "STARTED")
            stage = "AWAIT_IN_CALLEE"
            val awaitDeadline = minOf(operations, System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
            val waiting: Future<Bundle?> = awaitLane.submit<Bundle?> {
                check(open.get())
                remote.awaitCleanup(preparedBytes.clone())
            }
            val observationDeadline = minOf(operations, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
            var awaitObserved = false
            repeat(5) {
                if (!awaitObserved && remaining(observationDeadline) > 0) {
                    val observed = requireNotNull(short(observationDeadline) { remote.hello(lifetime) })
                    response(observed, nonce, "HELLO", setOf("shellUid", "buildId", "awaitInProgress", "busy"))
                    check(observed.getInt("shellUid") == 2_000 &&
                        observed.getInt("buildId") == BuildConfig.VERSION_CODE && observed.getBoolean("busy"))
                    awaitObserved = observed.getBoolean("awaitInProgress")
                    if (!awaitObserved && remaining(observationDeadline) > 0)
                        CountDownLatch(1).await(minOf(150, remaining(observationDeadline)), TimeUnit.MILLISECONDS)
                }
            }
            check(awaitObserved && !waiting.isDone && remaining(observationDeadline) > 0)
            stage = "DURABLE_OFF_FIRST"
            val off = requireNotNull(short { coordinator.disable() })
            check(!off.state.preparationAllowed && off.state.intent == intent)
            stage = "REVOKE_WHILE_AWAIT"
            val ack = requireNotNull(short { remote.revoke(off.exactBytes()) })
            response(ack, nonce, "ACK", setOf("generation", "revokeVersion", "childAlive", "awaitInProgress"))
            check(ack.getLong("generation") == key.generation &&
                ack.getLong("revokeVersion") == off.state.revokeVersion &&
                ack.getBoolean("childAlive") && ack.getBoolean("awaitInProgress") && !waiting.isDone)
            ackWhileAlive = true
            requireNotNull(short { coordinator.acceptRevokeAck(GmsDozeBrokerRevokeAck(key, off.state.revokeVersion)) })
            stage = "OLD_PREPARATION_REJECTED"
            response(short { remote.start(preparedBytes.clone()) }, nonce, "REJECTED")
            stage = "REAL_CHILD_CLEANUP"
            val cleanupReply = requireNotNull(waiting.get(remaining(awaitDeadline), TimeUnit.MILLISECONDS))
            check(open.get() && remaining(operations) > 0)
            response(cleanupReply, nonce, "CLEANUP", setOf("preparedBytes", "childExited",
                "stdoutEofAndDrained", "stderrEofAndDrained", "handlesClosed"))
            val correlated = requireNotNull(cleanupReply.getByteArray("preparedBytes"))
            check(correlated.contentEquals(preparedBytes))
            val correlatedIntent = requireNotNull(GmsDozeBrokerCodec.decode(correlated).intent)
            check(correlatedIntent == intent)
            val cleanup = GmsDozeBrokerCleanup(correlatedIntent, cleanupReply.getBoolean("childExited"),
                cleanupReply.getBoolean("stdoutEofAndDrained"), cleanupReply.getBoolean("stderrEofAndDrained"),
                cleanupReply.getBoolean("handlesClosed"))
            check(cleanup.complete())
            cleanupComplete = true
            stage = "RESULT_EXACT_CAS_READBACK"
            val committed = requireNotNull(short { coordinator.commitResult(cleanup) })
            check(committed.state.intent == null && committed.state.completedResult == intent)
            stage = "RESULT_RELEASE"
            val result = requireNotNull(short { remote.acknowledgeResult(committed.exactBytes()) })
            response(result, nonce, "RELEASED", setOf("release", "busy"))
            check(result.getBoolean("release") && !result.getBoolean("busy"))
            released = true
            stage = "LATE_MESSAGES_REJECTED"
            response(short { remote.activate(pinned.exactBytes()) }, nonce, "REJECTED")
            response(short { remote.start(preparedBytes.clone()) }, nonce, "REJECTED")
            passed = true
        } catch (_: Throwable) {
            // Timeouts/Future completion/disconnection never become ACK, death or child cleanup.
            open.set(false)
        } finally {
            open.set(false)
            if (passed) stage = "OWN_SERVICE_BINDER_DEATH"
            removalComplete = binding?.close(deadline, successful = passed) ?: false
            died = binding?.deathObserved() == true
            control.shutdownNow()
            awaitLane.shutdownNow()
        }
        return GuardianBrokerQualificationResult(if (passed && died && removalComplete) "PASS_SLEEP_ONLY" else "UNKNOWN", stage,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ackWhileAlive, cleanupComplete, released, died)
    }

    private fun freshStore(): GmsDozeBrokerRecordStore {
        val parent = context.applicationContext.noBackupFilesDir.canonicalFile
        val directory = File(parent, SCRATCH_DIRECTORY)
        check(directory.canonicalPath == directory.absolutePath)
        if (!directory.isDirectory) {
            check(directory.mkdir())
        }
        // Also covers an empty directory left by an earlier failure before its parent was synced.
        // fsync of only the child directory does not durably publish its name in this parent.
        val descriptor = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        val base = File(directory, GmsDozeBrokerRecordStore.FILE_NAME)
        check(listOf(base, File(base.path + ".new"), File(base.path + ".bak"),
            File(directory, GmsDozeBrokerRecordStore.SESSION_FENCE_NAME)).none { it.exists() })
        return GmsDozeBrokerRecordStore(directory)
    }

    private class Binding(context: Context, private val open: AtomicBoolean) {
        private val component = ComponentName(context.packageName, GuardianBrokerQualificationUserService::class.java.name)
        private val token = UUID.randomUUID().toString().replace("-", "")
        private val args = Shizuku.UserServiceArgs(component).daemon(false)
            .tag("guardian-single-broker-qualification-v1-$token")
            .processNameSuffix("guardian_broker_$token").debuggable(true).version(BuildConfig.VERSION_CODE)
        private val closing = AtomicBoolean(false)
        private val attempted = AtomicBoolean(false)
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val remote = AtomicReference<IGuardianBrokerQualification?>()
        private val death = IBinder.DeathRecipient {
            died.countDown()
            connected.countDown()
            if (!closing.get()) open.set(false)
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closing.get() || !open.get()) { removeLateBinding(); return }
                try {
                    check(name == component)
                    binder.linkToDeath(death, 0)
                    remote.set(IGuardianBrokerQualification.Stub.asInterface(binder))
                    if (closing.get() || !open.get()) removeLateBinding()
                } catch (_: Throwable) { open.set(false); removeLateBinding() }
                finally { connected.countDown() }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!closing.get()) open.set(false)
                connected.countDown()
                // A disconnect callback deliberately does not count down the death latch.
            }
        }

        fun dispatchBind() {
            check(open.get() && !closing.get() && attempted.compareAndSet(false, true))
            Shizuku.bindUserService(args, connection)
            if (!open.get() || closing.get()) removeLateBinding()
        }

        fun awaitConnected(deadline: Long): IGuardianBrokerQualification {
            check(connected.await(remaining(deadline), TimeUnit.MILLISECONDS) && open.get() && !closing.get())
            return requireNotNull(remote.get())
        }

        private fun removeLateBinding() {
            // No hello/activate/start on a late callback. Remove only this component/tag binding.
            Thread({ runCatching { Shizuku.unbindUserService(args, connection, true) } },
                "guardian-broker-late-remove").apply { isDaemon = true; start() }
        }

        fun close(deadline: Long, successful: Boolean): Boolean {
            closing.set(true)
            val stop = minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
            val cleanup = Executors.newFixedThreadPool(2) { task ->
                Thread(task, "guardian-broker-own-cleanup").apply { isDaemon = true }
            }
            try {
                if (!successful) cleanup.submit { runCatching { remote.get()?.destroy() } }
                val removal = cleanup.submit<Boolean> {
                    check(attempted.get())
                    Shizuku.unbindUserService(args, connection, true)
                    true
                }
                cleanup.shutdown()
                val exited = cleanup.awaitTermination(remaining(stop), TimeUnit.MILLISECONDS)
                val removed = removal.get(remaining(stop), TimeUnit.MILLISECONDS)
                val deathObserved = died.await(remaining(stop), TimeUnit.MILLISECONDS)
                return exited && removed && deathObserved
            } catch (_: Throwable) { return false }
            finally {
                if (died.count == 0L) remote.get()?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
                cleanup.shutdownNow()
            }
        }

        fun deathObserved() = died.count == 0L
    }

    private companion object {
        const val SCRATCH_DIRECTORY = "guardian-single-broker-qualification-v1"
        val entrance = AtomicBoolean(false)
        fun remaining(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        fun lane(name: String): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, name).apply { isDaemon = true }
        }
    }
}

@Suppress("DEPRECATION")
private fun response(reply: Bundle?, nonce: String, code: String, extra: Set<String> = emptySet()) {
    val value = requireNotNull(reply)
    check(value.keySet() == setOf("schema", "nonce", "code") + extra)
    check(value.get("schema") is Int && value.get("nonce") is String && value.get("code") is String)
    for (field in extra) check(when (field) {
        "shellUid", "buildId" -> value.get(field) is Int
        "generation", "revokeVersion" -> value.get(field) is Long
        "preparedBytes" -> value.get(field) is ByteArray
        else -> value.get(field) is Boolean
    })
    check(value.getInt("schema") == 1 && value.getString("nonce") == nonce && value.getString("code") == code)
}
