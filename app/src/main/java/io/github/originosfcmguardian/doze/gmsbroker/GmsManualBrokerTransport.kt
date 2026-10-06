package io.github.originosfcmguardian.doze.gmsbroker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerDeath
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerKey
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerRevokeAck
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Cleanup
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Codec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Preparation
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Proof
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2ReleaseAck
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeObservation
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeOperation
import io.github.originosfcmguardian.core.doze.GmsManualBrokerCommandResult
import io.github.originosfcmguardian.core.doze.GmsManualBrokerConnection
import io.github.originosfcmguardian.core.doze.GmsManualBrokerHello
import io.github.originosfcmguardian.core.doze.GmsManualBrokerObservationCodec
import io.github.originosfcmguardian.core.doze.GmsManualBrokerTransport as CoreGmsManualBrokerTransport
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One process singleton retains both finite own lifetimes and their exact proofs, including after
 * failure. Time bounds close future starts; cancellation/disconnect never establish remote exit.
 * This is future manual production wiring, not permission for a device event or an automatic loop.
 */
internal class GmsManualBrokerTransport private constructor(private val context: Context) : CoreGmsManualBrokerTransport {
    private val terminal = AtomicBoolean(false)
    private val attempts = HashMap<GmsGlobalDozeOperation, Connection>()
    private val appUid = Process.myUid()

    override fun bindInert(operation: GmsGlobalDozeOperation): GmsManualBrokerConnection {
        background()
        val own = synchronized(attempts) {
            check(!terminal.get() && !attempts.containsKey(operation)) { "Finite broker attempt already consumed or closed" }
            Connection(operation).also { attempts[operation] = it }
        }
        own.bindOnce()
        return own
    }

    override fun findOwn(key: GmsDozeBrokerKey): GmsManualBrokerConnection? = synchronized(attempts) {
        attempts.values.singleOrNull { it.key.get() == key }
    }

    override fun observedDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath? = synchronized(attempts) {
        attempts.values.singleOrNull { it.key.get() == key && it.actualDeath.get() }?.let { GmsDozeBrokerDeath(key) }
    }

    private inner class Connection(private val operation: GmsGlobalDozeOperation) : GmsManualBrokerConnection {
        private val component = ComponentName(context.packageName, when (operation) {
            GmsGlobalDozeOperation.ADD -> GmsManualAddBrokerUserService::class.java.name
            GmsGlobalDozeOperation.REMOVE -> GmsManualRemoveBrokerUserService::class.java.name
        })
        private val unique = UUID.randomUUID().toString().replace("-", "")
        private val args = Shizuku.UserServiceArgs(component).daemon(false)
            .tag("guardian-manual-${operation.name.lowercase()}-$unique")
            .processNameSuffix("guardian_gms_${operation.name.lowercase()}_$unique")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        private val control = lane("guardian-gms-control-$unique")
        private val probeLane = lane("guardian-gms-probe-$unique")
        private val startLane = lane("guardian-gms-start-$unique")
        private val awaitLane = lane("guardian-gms-await-$unique")
        private val removalLane = lane("guardian-gms-removal-$unique")
        private val lifetime = Binder()
        private val closed = AtomicBoolean(false)
        private val removing = AtomicBoolean(false)
        private val bound = AtomicBoolean(false)
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val remote = AtomicReference<IGmsManualBroker?>()
        private val removalLock = Any()
        private var ownRemoval: Future<Boolean>? = null
        val key = AtomicReference<GmsDozeBrokerKey?>()
        val actualDeath = AtomicBoolean(false)
        private var preparedBytes: ByteArray? = null
        private var preparation: GmsDozeBrokerV2Preparation? = null
        private val guardian = AtomicReference<io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding?>()
        private val death = IBinder.DeathRecipient {
            actualDeath.set(true)
            closed.set(true)
            died.countDown()
            connected.countDown()
            if (!removing.get()) terminal.set(true)
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closed.get() || terminal.get() || removing.get()) { removeLateBinding(); return }
                try {
                    check(name == component && remote.get() == null)
                    binder.linkToDeath(death, 0)
                    remote.set(IGmsManualBroker.Stub.asInterface(binder))
                    if (closed.get() || terminal.get() || removing.get()) removeLateBinding()
                } catch (_: Exception) { failClosed(); removeLateBinding() }
                finally { connected.countDown() }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!removing.get()) failClosed()
                connected.countDown()
                // A disconnect deliberately does not produce an actual-death observation.
            }
        }

        fun bindOnce() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            try {
                rpc(control, 3) {
                    check(bound.compareAndSet(false, true))
                    check(Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    guardian.set(GmsManualBrokerIdentity.app(context, appUid))
                    Shizuku.bindUserService(args, connection)
                }
                check(connected.await(remaining(deadline), TimeUnit.MILLISECONDS) && remote.get() != null &&
                    !closed.get() && !actualDeath.get() && !terminal.get())
            } catch (failure: Exception) {
                failClosed()
                removeLateBinding()
                throw failure
            }
        }

        override fun hello(): GmsManualBrokerHello = rpc(control, 3) {
            val reply = values(requireNotNull(remote.get()).hello(lifetime))
            val nonce = reply["nonce"] as? String ?: error("Missing shell nonce")
            GmsManualBrokerReply.validate(reply, nonce, "HELLO", mapOf("shellUid" to Int::class.javaObjectType,
                "buildId" to Int::class.javaObjectType, "operation" to String::class.java))
            check(nonce.matches(Regex("[A-Za-z0-9_-]{1,128}")) && reply["shellUid"] == 2000 &&
                reply["buildId"] == BuildConfig.VERSION_CODE && reply["operation"] == operation.name)
            val actualKey = GmsDozeBrokerKey(nonce, if (operation == GmsGlobalDozeOperation.ADD) 1 else 2)
            val before = key.get()
            check(before == null || before == actualKey)
            key.compareAndSet(null, actualKey)
            GmsManualBrokerHello(nonce, operation)
        }

        override fun probe(): GmsGlobalDozeObservation = rpc(probeLane, 40) {
            val reply = checked(requireNotNull(remote.get()).probe(), "PROBE", mapOf("observation" to ByteArray::class.java))
            val observation = GmsManualBrokerObservationCodec.decode(reply["observation"] as ByteArray, GMS_MANUAL_BROKER_FINGERPRINT)
            check(observation.identity.guardian == guardian.get())
            observation
        }

        override fun activate(proof: GmsDozeBrokerV2Proof): Boolean = rpc(control, 3) {
            val exact = proofForOwn(proof)
            checked(requireNotNull(remote.get()).activate(exact), "ACTIVATED")
            true
        }

        // The callee performs a complete fresh qualified sweep before its short actual-start
        // monitor. Give that sweep the existing probe bound, without queuing OFF behind it.
        override fun start(proof: GmsDozeBrokerV2Proof): Boolean = rpc(startLane, 40) {
            val exact = proofForOwn(proof)
            val attempt = requireNotNull(GmsDozeBrokerV2Codec.decode(exact).attempt(ownGrantId(proof)))
            check(preparedBytes == null && preparation == null)
            // Retain preparation before crossing RPC, including an uncertain or late actual start.
            preparedBytes = exact.clone()
            preparation = requireNotNull(attempt.preparation)
            checked(requireNotNull(remote.get()).start(exact.clone()), "STARTED")
            true
        }

        override fun awaitCleanup(preparation: GmsDozeBrokerV2Preparation): GmsManualBrokerCommandResult = rpc(awaitLane, 12) {
            check(this.preparation == preparation)
            val exact = requireNotNull(preparedBytes).clone()
            val reply = values(requireNotNull(remote.get()).awaitCleanup(exact.clone()))
            val fields = mapOf("preparedBytes" to ByteArray::class.java, "observation" to ByteArray::class.java,
                "childExited" to Boolean::class.javaObjectType, "stdoutEofAndDrained" to Boolean::class.javaObjectType,
                "stderrEofAndDrained" to Boolean::class.javaObjectType, "collectorsTerminated" to Boolean::class.javaObjectType,
                "handlesClosed" to Boolean::class.javaObjectType, "outputValid" to Boolean::class.javaObjectType)
            val extras = if (reply.containsKey("exitCode")) fields + ("exitCode" to Int::class.javaObjectType) else fields
            GmsManualBrokerReply.validate(reply, ownKey().nonce, "CLEANUP", extras)
            check((reply["preparedBytes"] as ByteArray).contentEquals(exact))
            val observation = GmsManualBrokerObservationCodec.decode(reply["observation"] as ByteArray, GMS_MANUAL_BROKER_FINGERPRINT)
            val attempt = requireNotNull(GmsDozeBrokerV2Codec.decode(exact).attempt(preparation.grantId))
            check(observation.identity == attempt.grant.identity)
            val cleanup = GmsDozeBrokerV2Cleanup(preparation, reply["childExited"] as Boolean,
                reply["stdoutEofAndDrained"] as Boolean, reply["stderrEofAndDrained"] as Boolean,
                reply["collectorsTerminated"] as Boolean, reply["handlesClosed"] as Boolean)
            check(!reply.containsKey("exitCode") || cleanup.childExited)
            GmsManualBrokerCommandResult(preparation, observation.identity, cleanup, reply["exitCode"] as? Int,
                reply["outputValid"] as Boolean)
        }

        override fun revoke(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerRevokeAck = rpc(control, 3, allowClosed = true) {
            val exact = proofForOwn(proof)
            val attempt = requireNotNull(GmsDozeBrokerV2Codec.decode(exact).attempt(ownGrantId(proof)))
            val reply = checked(requireNotNull(remote.get()).revoke(exact), "ACK",
                mapOf("generation" to Long::class.javaObjectType, "revokeVersion" to Long::class.javaObjectType))
            check(reply["generation"] == ownKey().generation && reply["revokeVersion"] == attempt.revokeVersion && attempt.revokeVersion > 0)
            GmsDozeBrokerRevokeAck(ownKey(), attempt.revokeVersion)
        }

        override fun release(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerV2ReleaseAck = rpc(control, 3) {
            val exact = proofForOwn(proof)
            val attempt = requireNotNull(GmsDozeBrokerV2Codec.decode(exact).attempt(ownGrantId(proof)))
            val prep = requireNotNull(attempt.preparation)
            check(attempt.result != null && prep == preparation)
            val reply = checked(requireNotNull(remote.get()).acknowledgeResult(exact), "RELEASED",
                mapOf("grantId" to String::class.java, "generation" to Long::class.javaObjectType,
                    "preparationRevision" to Long::class.javaObjectType, "release" to Boolean::class.javaObjectType))
            check(reply["grantId"] == prep.grantId && reply["generation"] == ownKey().generation &&
                reply["preparationRevision"] == prep.preparationRevision && reply["release"] == true)
            GmsDozeBrokerV2ReleaseAck(ownKey(), prep.grantId, prep.preparationRevision)
        }

        override fun destroyOwnAndObserveDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath {
            background()
            check(ownKey() == key)
            removing.set(true)
            closed.set(true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            try {
                val removal = requestOwnRemovalOnce()
                check(removal.get(remaining(deadline), TimeUnit.MILLISECONDS) && died.await(remaining(deadline), TimeUnit.MILLISECONDS) && actualDeath.get())
                return GmsDozeBrokerDeath(key)
            } catch (failure: Exception) { failClosed(); throw failure }
            // No cancellation/finally clearing: a late actual Binder death stays observable.
        }

        private fun proofForOwn(proof: GmsDozeBrokerV2Proof): ByteArray {
            val bytes = proof.exactBytes()
            val state = GmsDozeBrokerV2Codec.decode(bytes)
            check(state == proof.state)
            val attempt = requireNotNull(if (operation == GmsGlobalDozeOperation.ADD) state.add else state.restore)
            check(attempt.pin == ownKey() && attempt.grant.operation == operation && attempt.grant.identity.guardian == guardian.get())
            return bytes
        }
        private fun ownGrantId(proof: GmsDozeBrokerV2Proof) = requireNotNull(
            if (operation == GmsGlobalDozeOperation.ADD) proof.state.add else proof.state.restore).grant.grantId
        private fun ownKey() = requireNotNull(key.get()) { "Validated shell HELLO required" }
        private fun checked(bundle: Bundle?, code: String, extra: Map<String, Class<*>> = emptyMap()): Map<String, Any?> =
            values(bundle).also { GmsManualBrokerReply.validate(it, ownKey().nonce, code, extra) }

        private fun <T> rpc(lane: ExecutorService, seconds: Long, allowClosed: Boolean = false, action: () -> T): T {
            background()
            try {
                check(allowClosed || !closed.get() && !terminal.get())
                val future = lane.submit<T> {
                    check(allowClosed || !closed.get() && !terminal.get())
                    val fresh = GmsManualBrokerIdentity.app(context, appUid)
                    guardian.get()?.let { check(it == fresh) }
                    action()
                }
                val result = future.get(seconds, TimeUnit.SECONDS)
                check(allowClosed || !closed.get() && !terminal.get())
                return result
            } catch (failure: Exception) { failClosed(); throw failure }
        }

        private fun failClosed() { closed.set(true); terminal.set(true) }
        private fun removeLateBinding() {
            removing.set(true)
            // Only this unique own tag/component; never activate a callback after local closure.
            // A callback can arrive after an earlier pre-connection removal request returned.
            // That callback needs its own cleanup, not reuse of a pre-connection Future.
            removalLane.submit { runCatching { if (bound.get()) Shizuku.unbindUserService(args, connection, true) } }
        }
        private fun requestOwnRemovalOnce(): Future<Boolean> = synchronized(removalLock) {
            ownRemoval ?: removalLane.submit<Boolean> {
                check(bound.get())
                Shizuku.unbindUserService(args, connection, true)
                true
            }.also { ownRemoval = it }
        }
    }

    companion object {
        @Volatile private var shared: GmsManualBrokerTransport? = null
        fun get(context: Context): GmsManualBrokerTransport = shared ?: synchronized(this) {
            shared ?: GmsManualBrokerTransport(context.applicationContext).also { shared = it }
        }
        private fun background() { check(Looper.myLooper() != Looper.getMainLooper()) { "Manual broker calls require a background thread" } }
        private fun lane(name: String): ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }
        private fun remaining(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        private fun values(bundle: Bundle?): Map<String, Any?> = requireNotNull(bundle).let { value -> value.keySet().associateWith { value.get(it) } }
    }
}

/** Pure host-testable boundary; Bundle defaults are never accepted as typed protocol evidence. */
internal object GmsManualBrokerReply {
    fun validate(reply: Map<String, Any?>, nonce: String, code: String, extras: Map<String, Class<*>> = emptyMap()) {
        val required = mapOf("schema" to Int::class.javaObjectType, "nonce" to String::class.java, "code" to String::class.java) + extras
        check(reply.keys == required.keys && required.all { (name, type) -> type.isInstance(reply[name]) })
        check(reply["schema"] == 2 && reply["nonce"] == nonce && reply["code"] == code)
    }
}
