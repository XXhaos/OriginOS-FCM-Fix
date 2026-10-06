package io.github.originosfcmguardian.doze.broker

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerAdmission
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCleanup
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerKey
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerOperation
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerProof
import io.github.originosfcmguardian.doze.DozeServiceExpiry
import io.github.originosfcmguardian.doze.GmsDozeBrokerProcessChild
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug qualification only. GLOBAL_ADD means the sole fixed /system/bin/sleep 3 fixture here.
 * No GMS command, production caller, native lock, caller-selected operation or storage callback.
 * Fresh metadata and decoding precede the short session lock; actual waits and streams follow it.
 */
class GuardianBrokerQualificationUserService : IGuardianBrokerQualification.Stub {
    constructor() : super()
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val sessionLock = Any()
    private var ownerUid: Int? = null
    private var lifetime: IBinder? = null
    private var lifetimeLinked = false
    private var terminal = false
    private var key: GmsDozeBrokerKey? = null
    private var admission: GmsDozeBrokerAdmission? = null
    private var revokeVersion = 0L
    private var preparation: GmsDozeBrokerProof? = null
    private var rawProcess: java.lang.Process? = null
    private var child: GmsDozeBrokerProcessChild? = null
    private var cleanup: GmsDozeBrokerCleanup? = null
    private val awaitInProgress = AtomicBoolean(false)
    private val clientDeath = IBinder.DeathRecipient { closeFutureStarts() }

    init {
        check(BuildConfig.DEBUG && Process.myUid() == 2_000 && Build.FINGERPRINT == BROKER_FINGERPRINT)
        check(instanceConsumed.compareAndSet(false, true)) { "A broker instance already existed in this process" }
    }

    private val expiry = DozeServiceExpiry(Process.myUid(), {
        closeFutureStarts()
        System.exit(0) // Only this standalone qualification UserService process.
    }, lifetimeMillis = 65_000)

    override fun hello(clientLifetime: IBinder?): Bundle {
        val caller = Binder.getCallingUid()
        return guarded {
            GuardianBrokerQualificationIdentity.shell(caller)
            val token = requireNotNull(clientLifetime)
            val first = synchronized(sessionLock) {
                if (ownerUid == null) {
                    check(!terminal)
                    ownerUid = caller
                    lifetime = token
                    true
                } else {
                    check(ownerUid == caller && lifetime === token)
                    false
                }
            }
            if (first) {
                try {
                    token.linkToDeath(clientDeath, 0) // No outbound Binder call under sessionLock.
                    synchronized(sessionLock) { lifetimeLinked = true }
                } catch (failure: Exception) {
                    closeFutureStarts()
                    throw failure
                }
            }
            synchronized(sessionLock) {
                check(lifetimeLinked)
                reply("HELLO").apply {
                    putInt("shellUid", Process.myUid())
                    putInt("buildId", BuildConfig.VERSION_CODE)
                    putBoolean("awaitInProgress", awaitInProgress.get())
                    putBoolean("busy", admission?.isBusy() == true)
                }
            }
        }
    }

    override fun activate(pin: ByteArray?): Bundle {
        val caller = Binder.getCallingUid()
        return guarded {
            val proof = authenticatedProof(caller, pin)
            synchronized(sessionLock) {
                requireBound(caller)
                check(!terminal)
                val state = proof.state
                check(state.pin?.nonce == nonce && state.preparationAllowed && !state.brokerClosed && state.intent == null)
                val broker = bindKey(requireNotNull(state.pin))
                val activated = broker.activate(proof)
                if (activated) revokeVersion = state.revokeVersion
                reply(if (activated) "ACTIVATED" else "REJECTED")
            }
        }
    }

    override fun start(prepared: ByteArray?): Bundle {
        val caller = Binder.getCallingUid()
        return guarded {
            val proof = authenticatedProof(caller, prepared)
            check(proof.state.intent?.operation == GmsDozeBrokerOperation.GLOBAL_ADD)
            synchronized(sessionLock) {
                requireBound(caller)
                check(!terminal && proof.state.pin == key)
                val broker = requireNotNull(admission)
                val started = broker.start(proof) { operation ->
                    check(operation == GmsDozeBrokerOperation.GLOBAL_ADD)
                    preparation = proof
                    // Check/start/register and revoke share this short boundary. No stream access.
                    val process = ProcessBuilder(listOf("/system/bin/sleep", "3")).start()
                    rawProcess = process
                    GmsDozeBrokerProcessChild(process).also { child = it }
                }
                reply(started.status.name)
            }
        }
    }

    override fun awaitCleanup(preparation: ByteArray?): Bundle {
        val caller = Binder.getCallingUid()
        var ownsAwaitBarrier = false
        try {
            return guarded {
                val proof = authenticatedProof(caller, preparation)
                val owned = synchronized(sessionLock) {
                    requireBound(caller)
                    check(this.preparation?.exactBytes()?.contentEquals(proof.exactBytes()) == true)
                    val registered = requireNotNull(child)
                    // Authenticated actual entry to this registered child's wait, not queued RPC.
                    check(awaitInProgress.compareAndSet(false, true)) { "A child wait is already active" }
                    ownsAwaitBarrier = true
                    registered
                }
                val evidence = owned.awaitEvidence(10_000) // All waits/drains/close outside sessionLock.
                synchronized(sessionLock) {
                    check(child === owned)
                    val facts = requireNotNull(requireNotNull(admission).cleanupEvidence(owned, evidence.childExited,
                        evidence.stdoutEofAndDrained, evidence.stderrEofAndDrained, evidence.handlesClosed))
                    cleanup = facts
                    reply("CLEANUP").apply {
                        putByteArray("preparedBytes", proof.exactBytes())
                        putBoolean("childExited", facts.childExited)
                        putBoolean("stdoutEofAndDrained", facts.stdoutEofAndDrained)
                        putBoolean("stderrEofAndDrained", facts.stderrEofAndDrained)
                        putBoolean("handlesClosed", facts.handlesClosed)
                    }
                }
            }
        } finally {
            if (ownsAwaitBarrier) awaitInProgress.set(false)
        }
    }

    override fun revoke(off: ByteArray?): Bundle {
        val caller = Binder.getCallingUid()
        return guarded {
            val proof = authenticatedProof(caller, off)
            val state = proof.state
            val exactKey = requireNotNull(state.pin)
            check(exactKey.nonce == nonce && !state.preparationAllowed)
            val snapshot = synchronized(sessionLock) {
                requireBound(caller)
                val broker = bindKey(exactKey) // Early OFF can bind the inert instance, never activate.
                val ack = requireNotNull(broker.revoke(exactKey, state.revokeVersion))
                terminal = true
                revokeVersion = ack.revokeVersion
                ack to rawProcess
            }
            val alive = snapshot.second?.isAlive == true // Snapshot only, not cleanup/exit evidence.
            reply("ACK").apply {
                putLong("generation", snapshot.first.key.generation)
                putLong("revokeVersion", snapshot.first.revokeVersion)
                putBoolean("childAlive", alive)
                putBoolean("awaitInProgress", awaitInProgress.get())
            }
        }
    }

    override fun acknowledgeResult(result: ByteArray?): Bundle {
        val caller = Binder.getCallingUid()
        return try {
            val proof = authenticatedProof(caller, result)
            synchronized(sessionLock) {
                requireBound(caller)
                val facts = requireNotNull(cleanup)
                val owned = requireNotNull(child)
                val broker = requireNotNull(admission)
                check(facts.complete() && proof.state.pin == key && proof.state.intent == null &&
                    proof.state.completedResult == facts.intent && proof.state.revokeVersion == revokeVersion)
                if (terminal) check(!proof.state.preparationAllowed)
                val released = broker.release(owned, facts, proof)
                if (released) {
                    child = null
                    rawProcess = null
                    cleanup = null
                    preparation = null
                }
                reply(if (released) "RELEASED" else "REJECTED").apply {
                    putBoolean("release", released)
                    putBoolean("busy", broker.isBusy())
                }
            }
        } catch (_: Exception) {
            synchronized(sessionLock) {
                reply("REJECTED").apply {
                    putBoolean("release", false)
                    putBoolean("busy", admission?.isBusy() == true)
                }
            }
        }
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(BuildConfig.DEBUG && Process.myUid() == 2_000 && Build.FINGERPRINT == BROKER_FINGERPRINT)
        if (caller != 2_000) {
            GuardianBrokerQualificationIdentity.shell(caller)
            synchronized(sessionLock) { check(ownerUid == caller) }
        }
        expiry.terminateNow()
    }

    private fun authenticatedProof(caller: Int, bytes: ByteArray?): GmsDozeBrokerProof {
        GuardianBrokerQualificationIdentity.shell(caller)
        return GmsDozeBrokerCodec.decodeProof(requireNotNull(bytes))
    }

    private fun requireBound(caller: Int) {
        check(ownerUid == caller && lifetimeLinked)
    }

    private fun bindKey(value: GmsDozeBrokerKey): GmsDozeBrokerAdmission {
        check(value.nonce == nonce)
        if (key == null) {
            key = value
            admission = GmsDozeBrokerAdmission(value)
        }
        check(key == value)
        return requireNotNull(admission)
    }

    private fun closeFutureStarts() = synchronized(sessionLock) {
        terminal = true
        key?.let { admission?.revoke(it, revokeVersion) }
        // Retain preparation, child, raw process and cleanup. Death/expiry is not child evidence.
    }

    private fun reply(code: String) = Bundle().apply {
        putInt("schema", 1)
        putString("nonce", nonce)
        putString("code", code)
    }

    private inline fun guarded(action: () -> Bundle): Bundle =
        try { action() } catch (_: Exception) { reply("REJECTED") }

    private companion object {
        val instanceConsumed = AtomicBoolean(false) // Never reset, including after destroy/death.
    }
}
