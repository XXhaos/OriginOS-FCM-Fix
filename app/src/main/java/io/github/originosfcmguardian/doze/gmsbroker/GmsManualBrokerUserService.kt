package io.github.originosfcmguardian.doze.gmsbroker

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerKey
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Admission
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Attempt
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Cleanup
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Codec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerV2Proof
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeObservation
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeOperation
import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsManualBrokerObservationCodec
import io.github.originosfcmguardian.doze.DozeCommandRunner
import io.github.originosfcmguardian.doze.DozeEffectiveOwnerGetterCalls
import io.github.originosfcmguardian.doze.DozeOwnerCandidateReader
import io.github.originosfcmguardian.doze.DozeServiceExpiry
import io.github.originosfcmguardian.doze.GmsDozeBrokerProcessChild
import io.github.originosfcmguardian.doze.GmsDozeBrokerProcessEvidence
import io.github.originosfcmguardian.doze.GmsManualBrokerObservation
import io.github.originosfcmguardian.doze.SystemDozeEffectiveOwnerReadPort
import io.github.originosfcmguardian.doze.parseAndroidUsers
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Concrete entry points select the entire fixed command; Binder input cannot change it. */
@Keep
class GmsManualAddBrokerUserService : GmsManualBrokerUserService {
    constructor() : super(GmsGlobalDozeOperation.ADD)
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(GmsGlobalDozeOperation.ADD)
}

@Keep
class GmsManualRemoveBrokerUserService : GmsManualBrokerUserService {
    constructor() : super(GmsGlobalDozeOperation.REMOVE)
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(GmsGlobalDozeOperation.REMOVE)
}

/**
 * One INERT shell object/process, one immutable grant, one activation and one actual child.
 * The outer short monitor serializes start/register/revoke. Framework reads, proof decoding,
 * lifetime linking and child cleanup are outside it. No storage, callback or automatic retry.
 */
sealed class GmsManualBrokerUserService(private val operation: GmsGlobalDozeOperation) : IGmsManualBroker.Stub() {
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val key = GmsDozeBrokerKey(nonce, if (operation == GmsGlobalDozeOperation.ADD) 1 else 2)
    private val sessionLock = Any()
    private var ownerUid: Int? = null
    private var lifetime: IBinder? = null
    private var lifetimeLinked = false
    private var terminal = false
    @Volatile private var readsClosed = false
    private var admission: GmsDozeBrokerV2Admission? = null
    private var revokeVersion = 0L
    private var preparation: GmsDozeBrokerV2Proof? = null
    private var prestartObservation: ByteArray? = null
    private var rawProcess: java.lang.Process? = null
    private var child: GmsDozeBrokerProcessChild? = null
    private var cleanup: GmsDozeBrokerV2Cleanup? = null
    private var successfulOutput = false
    private val observing = AtomicBoolean(false)
    private val awaiting = AtomicBoolean(false)
    private val clientDeath = IBinder.DeathRecipient { closeFutureStarts(closeReads = true) }

    init {
        check(Process.myUid() == 2_000 && Build.FINGERPRINT == GMS_MANUAL_BROKER_FINGERPRINT)
        check(instanceConsumed.compareAndSet(false, true)) { "A broker instance already existed in this process" }
    }

    private val expiry = DozeServiceExpiry(Process.myUid(), {
        closeFutureStarts(closeReads = true)
        System.exit(0) // This unique own shell process only; never shared Shizuku.
    }, lifetimeMillis = 65_000)

    override fun hello(clientLifetime: IBinder?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        authenticate(caller)
        val token = requireNotNull(clientLifetime)
        val first = synchronized(sessionLock) {
            if (ownerUid == null) {
                check(!terminal && !readsClosed)
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
                token.linkToDeath(clientDeath, 0)
                synchronized(sessionLock) { lifetimeLinked = true }
            } catch (failure: Exception) {
                closeFutureStarts(closeReads = true)
                throw failure
            }
        }
        synchronized(sessionLock) { requireBound(caller); check(!readsClosed) }
        reply("HELLO").apply {
            putInt("shellUid", Process.myUid())
            putInt("buildId", BuildConfig.VERSION_CODE)
            putString("operation", operation.name)
        }
    }

    override fun probe(): Bundle = guarded {
        val caller = Binder.getCallingUid()
        authenticate(caller)
        synchronized(sessionLock) { requireBound(caller) }
        val observed = observe(caller)
        reply("PROBE").apply {
            putByteArray("observation", GmsManualBrokerObservationCodec.encode(observed, GMS_MANUAL_BROKER_FINGERPRINT))
        }
    }

    override fun activate(pin: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val proof = authenticatedProof(caller, pin)
        synchronized(sessionLock) {
            requireBound(caller)
            check(!terminal && !readsClosed)
            val broker = bindAdmission(attempt(proof))
            reply(if (broker.activate(proof)) "ACTIVATED" else "REJECTED")
        }
    }

    override fun start(prepared: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val proof = authenticatedProof(caller, prepared)
        val attempt = attempt(proof)
        synchronized(sessionLock) { requireBound(caller); check(!terminal && !readsClosed) }
        // Full current qualified mapping, installations and raw whitelist immediately before start.
        val observation = observe(caller)
        check(observation.identity == attempt.grant.identity)
        val expectedRawHash = if (operation == GmsGlobalDozeOperation.ADD) attempt.grant.baselineSha256
            else requireNotNull(attempt.grant.appliedSha256)
        check(GmsDozeBrokerV2Codec.rawSha256(observation.rawWhitelist) == expectedRawHash)
        val observationBytes = GmsManualBrokerObservationCodec.encode(observation, GMS_MANUAL_BROKER_FINGERPRINT)
        synchronized(sessionLock) {
            requireBound(caller)
            check(!terminal && !readsClosed)
            val broker = requireNotNull(admission)
            check(broker.grant == attempt.grant)
            val result = broker.start(proof) { admittedOperation ->
                check(admittedOperation == operation)
                preparation = proof
                prestartObservation = observationBytes
                // No streams or waits here; genuinely start then retain the returned handle.
                val process = ProcessBuilder(fixedCommand(operation)).start()
                rawProcess = process
                GmsDozeBrokerProcessChild(process).also { child = it }
            }
            reply(result.status.name)
        }
    }

    override fun awaitCleanup(preparation: ByteArray?): Bundle {
        var ownsAwait = false
        try {
            return guarded {
                val caller = Binder.getCallingUid()
                val proof = authenticatedProof(caller, preparation)
                val owned = synchronized(sessionLock) {
                    requireBound(caller)
                    check(this.preparation?.exactBytes()?.contentEquals(proof.exactBytes()) == true)
                    val value = requireNotNull(child)
                    check(awaiting.compareAndSet(false, true))
                    ownsAwait = true
                    value
                }
                val evidence = owned.awaitEvidence(10_000)
                val outputValid = validCommandOutput(operation, evidence)
                val observationBytes: ByteArray
                val facts = synchronized(sessionLock) {
                    check(child === owned)
                    val facts = requireNotNull(requireNotNull(admission).cleanupEvidence(owned,
                        evidence.childExited, evidence.stdoutEofAndDrained, evidence.stderrEofAndDrained,
                        // Each true EOF+drained flag already requires its real thread TERMINATED.
                        evidence.stdoutEofAndDrained && evidence.stderrEofAndDrained, evidence.handlesClosed))
                    cleanup = facts
                    successfulOutput = outputValid
                    observationBytes = requireNotNull(prestartObservation).clone()
                    facts
                }
                reply("CLEANUP").apply {
                    putByteArray("preparedBytes", proof.exactBytes())
                    putByteArray("observation", observationBytes)
                    putBoolean("childExited", facts.childExited)
                    putBoolean("stdoutEofAndDrained", facts.stdoutEofAndDrained)
                    putBoolean("stderrEofAndDrained", facts.stderrEofAndDrained)
                    putBoolean("collectorsTerminated", facts.collectorsTerminated)
                    putBoolean("handlesClosed", facts.handlesClosed)
                    evidence.exitCode?.let { putInt("exitCode", it) }
                    putBoolean("outputValid", outputValid)
                }
            }
        } finally {
            if (ownsAwait) awaiting.set(false)
        }
    }

    override fun revoke(off: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val proof = authenticatedProof(caller, off)
        val attempt = attempt(proof)
        check(attempt.revokeVersion > 0)
        val ack = synchronized(sessionLock) {
            requireBound(caller)
            val broker = bindAdmission(attempt)
            requireNotNull(broker.revoke(key, attempt.revokeVersion)).also {
                terminal = true
                revokeVersion = it.revokeVersion
            }
        }
        reply("ACK").apply {
            putLong("generation", ack.key.generation)
            putLong("revokeVersion", ack.revokeVersion)
        }
    }

    override fun acknowledgeResult(result: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val proof = authenticatedProof(caller, result)
        attempt(proof)
        val released = synchronized(sessionLock) {
            requireBound(caller)
            val facts = requireNotNull(cleanup)
            // Result commit and OFF delivery may cross; releasing a fully completed child cannot
            // reopen admission. Core still checks the exact grant/key/preparation/result binding.
            check(facts.complete() && successfulOutput)
            requireNotNull(admission).release(requireNotNull(child), facts, proof)?.also {
                child = null
                rawProcess = null
                cleanup = null
                preparation = null
                prestartObservation = null
            }
        }
        if (released == null) reply("REJECTED") else reply("RELEASED").apply {
            putString("grantId", released.grantId)
            putLong("generation", released.key.generation)
            putLong("preparationRevision", released.preparationRevision)
            putBoolean("release", true)
        }
    }

    override fun destroy() {
        check(Process.myUid() == 2_000 && Build.FINGERPRINT == GMS_MANUAL_BROKER_FINGERPRINT)
        val caller = Binder.getCallingUid()
        if (caller != 2_000) {
            authenticate(caller)
            synchronized(sessionLock) { requireBound(caller) }
        }
        expiry.terminateNow() // Destruction never fabricates cleanup or release.
    }

    private fun authenticatedProof(caller: Int, bytes: ByteArray?): GmsDozeBrokerV2Proof {
        val exact = requireNotNull(bytes)
        check(exact.size <= GmsDozeBrokerV2Codec.MAX_BYTES)
        val guardian = authenticate(caller)
        val proof = GmsDozeBrokerV2Codec.decodeProof(exact)
        check(attempt(proof).grant.identity.guardian == guardian)
        return proof
    }

    private fun authenticate(caller: Int): GmsGlobalGuardianBinding {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_000)
        val calls = DozeEffectiveOwnerGetterCalls({ check(!Thread.currentThread().isInterrupted) },
            { remaining(deadline) }, { closeFutureStarts(closeReads = true) })
        return try { calls.read { GmsManualBrokerIdentity.shell(caller) } }
        finally { calls.close() }
    }

    private fun attempt(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerV2Attempt {
        val value = requireNotNull(if (operation == GmsGlobalDozeOperation.ADD) proof.state.add else proof.state.restore)
        check(value.pin == key && value.grant.operation == operation)
        return value
    }

    private fun bindAdmission(value: GmsDozeBrokerV2Attempt): GmsDozeBrokerV2Admission {
        check(value.pin == key && value.grant.operation == operation)
        if (admission == null) admission = GmsDozeBrokerV2Admission(key, value.grant)
        return requireNotNull(admission).also { check(it.grant == value.grant) }
    }

    private fun observe(caller: Int): GmsGlobalDozeObservation {
        check(observing.compareAndSet(false, true))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40_000)
        fun active() = !readsClosed && !Thread.currentThread().isInterrupted && remaining(deadline) > 0
        fun requireActive() { check(active()) }
        fun fixedRead(argv: List<String>): String {
            requireActive()
            val readDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_000))
            return DozeCommandRunner({ active() && remaining(readDeadline) > 0 },
                { remaining(readDeadline) }, {}).small(argv)
        }
        var source: SystemDozeEffectiveOwnerReadPort? = null
        var guardianCalls: DozeEffectiveOwnerGetterCalls? = null
        try {
            val users = parseAndroidUsers(fixedRead(listOf("/system/bin/pm", "list", "users")))
            val reader = DozeOwnerCandidateReader(::active, { remaining(deadline) })
            val getters = SystemDozeEffectiveOwnerReadPort({ reader.read(users) }, ::requireActive,
                { remaining(deadline) }, { closeFutureStarts(closeReads = true) })
            source = getters
            val identity = DozeEffectiveOwnerGetterCalls(::requireActive, { remaining(deadline) },
                { closeFutureStarts(closeReads = true) })
            guardianCalls = identity
            return GmsManualBrokerObservation(getters, { identity.read { GmsManualBrokerIdentity.shell(caller) } },
                { fixedRead(listOf("/system/bin/cmd", "deviceidle", "whitelist")) },
                { getters.uncertainBinderExit || identity.uncertainExit }, GMS_MANUAL_BROKER_FINGERPRINT,
                ::requireActive).read()
        } finally {
            if (source?.uncertainBinderExit == true || guardianCalls?.uncertainExit == true)
                closeFutureStarts(closeReads = true)
            source?.close()
            guardianCalls?.close()
            observing.set(false)
        }
    }

    private fun requireBound(caller: Int) { check(ownerUid == caller && lifetimeLinked) }

    private fun closeFutureStarts(closeReads: Boolean) = synchronized(sessionLock) {
        terminal = true
        if (closeReads) readsClosed = true
        admission?.revoke(key, maxOf(1, revokeVersion))?.let { revokeVersion = it.revokeVersion }
        // Retain the preparation, actual child, raw Process and evidence until genuine release.
    }

    private fun reply(code: String) = Bundle().apply {
        putInt("schema", 2)
        putString("nonce", nonce)
        putString("code", code)
    }
    private inline fun guarded(action: () -> Bundle): Bundle =
        try { action() } catch (_: Exception) { reply("REJECTED") }

    internal companion object {
        private val instanceConsumed = AtomicBoolean(false) // Never reset; unique own process required.
        private fun remaining(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        internal fun fixedCommand(operation: GmsGlobalDozeOperation): List<String> = when (operation) {
            GmsGlobalDozeOperation.ADD -> listOf("/system/bin/cmd", "deviceidle", "whitelist", "+com.google.android.gms")
            GmsGlobalDozeOperation.REMOVE -> listOf("/system/bin/cmd", "deviceidle", "whitelist", "-com.google.android.gms")
        }
        internal fun validCommandOutput(operation: GmsGlobalDozeOperation, evidence: GmsDozeBrokerProcessEvidence): Boolean {
            if (!evidence.childExited || evidence.exitCode != 0 || !evidence.stdoutEofAndDrained ||
                !evidence.stderrEofAndDrained || evidence.outputTruncated || !evidence.stderr.isBlank()) return false
            val expected = if (operation == GmsGlobalDozeOperation.ADD) "Added: com.google.android.gms"
                else "Removed: com.google.android.gms"
            return evidence.stdout == expected || evidence.stdout == "$expected\n" || evidence.stdout == "$expected\r\n"
        }
    }
}
