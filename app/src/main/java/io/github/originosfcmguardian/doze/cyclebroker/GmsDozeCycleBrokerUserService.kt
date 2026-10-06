package io.github.originosfcmguardian.doze.cyclebroker

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.*
import io.github.originosfcmguardian.doze.*
import io.github.originosfcmguardian.doze.gmsbroker.GMS_MANUAL_BROKER_FINGERPRINT
import io.github.originosfcmguardian.doze.gmsbroker.GmsManualBrokerIdentity
import io.github.originosfcmguardian.doze.gmsbroker.GmsManualBrokerUserService
import io.github.originosfcmguardian.doze.gmsbroker.IGmsManualBroker
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class GmsDozeCycleAddUserService : GmsDozeCycleBrokerUserService {
    @Keep constructor() : super(GmsGlobalDozeOperation.ADD)
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(GmsGlobalDozeOperation.ADD)
}

@Keep
class GmsDozeCycleRemoveUserService : GmsDozeCycleBrokerUserService {
    @Keep constructor() : super(GmsGlobalDozeOperation.REMOVE)
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super(GmsGlobalDozeOperation.REMOVE)
}

/** Independent schema3 cycle executor. The reused AIDL has no command/package/UID inputs. */
sealed class GmsDozeCycleBrokerUserService(private val operation: GmsGlobalDozeOperation) : IGmsManualBroker.Stub() {
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val monitor = Any()
    private val admission = GmsDozeCycleShellAdmission(nonce, operation)
    private var ownerUid: Int? = null
    private var lifetime: IBinder? = null
    private var linked = false
    private var terminal = false
    @Volatile private var readsClosed = false
    private var boundTicket: GmsDozeCycleTicket? = null
    private var preparedBytes: ByteArray? = null
    private var prestartObservation: ByteArray? = null
    private var rawProcess: java.lang.Process? = null
    private var child: GmsDozeBrokerProcessChild? = null
    private var cleanup: GmsDozeCycleCleanup? = null
    private var successfulOutput = false
    private val observing = AtomicBoolean(false)
    private val awaiting = AtomicBoolean(false)
    private val clientDeath = IBinder.DeathRecipient { closeFutureStarts() }

    init {
        check(Process.myUid() == 2000 && Build.FINGERPRINT == GMS_MANUAL_BROKER_FINGERPRINT)
        check(instanceConsumed.compareAndSet(false, true))
    }
    private val expiry = DozeServiceExpiry(Process.myUid(), { closeFutureStarts(); System.exit(0) }, lifetimeMillis = 65_000)

    override fun hello(clientLifetime: IBinder?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        authenticate(caller)
        val token = requireNotNull(clientLifetime)
        val first = synchronized(monitor) {
            if (ownerUid == null) {
                check(!terminal && !readsClosed)
                ownerUid = caller; lifetime = token; true
            } else { check(ownerUid == caller && lifetime === token); false }
        }
        if (first) try {
            token.linkToDeath(clientDeath, 0)
            synchronized(monitor) { linked = true }
        } catch (failure: Exception) { closeFutureStarts(); throw failure }
        synchronized(monitor) { requireBound(caller); check(!readsClosed) }
        reply("HELLO").apply {
            putInt("shellUid", Process.myUid()); putInt("buildId", BuildConfig.VERSION_CODE)
            putString("operation", operation.name)
        }
    }

    override fun probe(): Bundle = guarded {
        val caller = Binder.getCallingUid()
        authenticate(caller)
        synchronized(monitor) { requireBound(caller) }
        reply("PROBE").apply { putByteArray("observation", observationBytes(observe(caller))) }
    }

    override fun activate(pin: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val ticket = authenticatedTicket(caller, pin)
        synchronized(monitor) {
            requireBound(caller); check(!terminal && !readsClosed)
            bind(ticket)
            reply(if (admission.activate(ticket)) "ACTIVATED" else "REJECTED")
        }
    }

    override fun start(prepared: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val ticket = authenticatedTicket(caller, prepared)
        val exact = GmsDozeCycleTicketCodec.encode(ticket)
        synchronized(monitor) { requireBound(caller); check(!terminal && !readsClosed) }
        val fresh = observe(caller)
        check(fresh.identity == ticket.grant.identity)
        val hash = if (operation == GmsGlobalDozeOperation.ADD) ticket.grant.baselineSha256 else requireNotNull(ticket.grant.appliedSha256)
        check(GmsDozeBrokerV2Codec.rawSha256(fresh.rawWhitelist) == hash)
        val observed = observationBytes(fresh)
        synchronized(monitor) {
            requireBound(caller); check(!terminal && !readsClosed); bind(ticket)
            val started = admission.start(ticket) { fixed ->
                check(fixed == operation)
                check(GmsDozeCycleShippingWriteGate.approved()) { "Production cycle write not approved" }
                preparedBytes = exact; prestartObservation = observed
                val process = ProcessBuilder(GmsManualBrokerUserService.fixedCommand(operation)).start()
                rawProcess = process
                GmsDozeBrokerProcessChild(process).also { child = it }
            }
            reply(started.status.name)
        }
    }

    override fun awaitCleanup(preparation: ByteArray?): Bundle {
        var ownsAwait = false
        try {
            return guarded {
                val caller = Binder.getCallingUid()
                val ticket = authenticatedTicket(caller, preparation)
                val exact = GmsDozeCycleTicketCodec.encode(ticket)
                val owned = synchronized(monitor) {
                    requireBound(caller)
                    check(preparedBytes?.contentEquals(exact) == true)
                    requireNotNull(child).also { check(awaiting.compareAndSet(false, true)); ownsAwait = true }
                }
                val evidence = owned.awaitEvidence(10_000)
                val outputValid = GmsManualBrokerUserService.validCommandOutput(operation, evidence)
                val observation: ByteArray
                val facts = synchronized(monitor) {
                    check(child === owned)
                    requireNotNull(admission.cleanupEvidence(owned, evidence.childExited, evidence.stdoutEofAndDrained,
                        evidence.stderrEofAndDrained, evidence.stdoutEofAndDrained && evidence.stderrEofAndDrained,
                        evidence.handlesClosed)).also {
                        cleanup = it; successfulOutput = outputValid
                        observation = requireNotNull(prestartObservation).clone()
                    }
                }
                reply("CLEANUP").apply {
                    putByteArray("ticketBytes", exact); putByteArray("observation", observation)
                    putBoolean("childExited", facts.childExited); putBoolean("stdoutEofAndDrained", facts.stdoutEofAndDrained)
                    putBoolean("stderrEofAndDrained", facts.stderrEofAndDrained); putBoolean("collectorsTerminated", facts.collectorsTerminated)
                    putBoolean("handlesClosed", facts.handlesClosed); putBoolean("outputValid", outputValid)
                    evidence.exitCode?.let { putInt("exitCode", it) }
                }
            }
        } finally { if (ownsAwait) awaiting.set(false) }
    }

    override fun revoke(off: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val ticket = authenticatedTicket(caller, off)
        val closure = synchronized(monitor) {
            requireBound(caller); bind(ticket)
            requireNotNull(admission.revoke(ticket)).also { terminal = true }
        }
        reply("ACK").apply {
            putByteArray("ticketBytes", GmsDozeCycleTicketCodec.encode(ticket)); putLong("revokeVersion", closure.revokeVersion)
        }
    }

    override fun acknowledgeResult(result: ByteArray?): Bundle = guarded {
        val caller = Binder.getCallingUid()
        val ticket = authenticatedTicket(caller, result)
        val ack = synchronized(monitor) {
            requireBound(caller); bind(ticket)
            val facts = requireNotNull(cleanup)
            check(facts.complete() && successfulOutput)
            requireNotNull(admission.release(requireNotNull(child), facts, ticket)).also {
                child = null; rawProcess = null; cleanup = null; preparedBytes = null; prestartObservation = null
            }
        }
        check(ack.ref == ticket.ref)
        reply("RELEASED").apply { putByteArray("ticketBytes", GmsDozeCycleTicketCodec.encode(ticket)); putBoolean("release", true) }
    }

    override fun destroy() {
        check(Process.myUid() == 2000 && Build.FINGERPRINT == GMS_MANUAL_BROKER_FINGERPRINT)
        val caller = Binder.getCallingUid()
        if (caller != 2000) { authenticate(caller); synchronized(monitor) { requireBound(caller) } }
        expiry.terminateNow() // Own process only. Never cleanup/release evidence.
    }

    private fun authenticatedTicket(caller: Int, bytes: ByteArray?): GmsDozeCycleTicket {
        val exact = requireNotNull(bytes)
        check(exact.size <= GmsDozeCycleTicketCodec.MAX_BYTES)
        val guardian = authenticate(caller)
        return GmsDozeCycleTicketCodec.decode(exact).also {
            check(it.ref.key.nonce == nonce && it.ref.operation == operation && it.grant.identity.guardian == guardian)
        }
    }
    private fun bind(ticket: GmsDozeCycleTicket) {
        check(ticket.ref.key.nonce == nonce && ticket.ref.operation == operation)
        if (boundTicket == null) boundTicket = ticket
        else check(boundTicket?.grant == ticket.grant)
    }
    private fun authenticate(caller: Int): GmsGlobalGuardianBinding {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_000)
        val calls = DozeEffectiveOwnerGetterCalls({ check(!Thread.currentThread().isInterrupted) }, { remaining(deadline) }, ::closeFutureStarts)
        return try { calls.read { GmsManualBrokerIdentity.shell(caller) } } finally { calls.close() }
    }
    private fun observe(caller: Int): GmsGlobalDozeObservation {
        check(observing.compareAndSet(false, true))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40_000)
        fun active() = !readsClosed && !Thread.currentThread().isInterrupted && remaining(deadline) > 0
        fun requireActive() { check(active()) }
        fun fixedRead(argv: List<String>): String {
            requireActive()
            val readDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6_000))
            return DozeCommandRunner({ active() && remaining(readDeadline) > 0 }, { remaining(readDeadline) }, {}).small(argv)
        }
        var source: SystemDozeEffectiveOwnerReadPort? = null
        var guardians: DozeEffectiveOwnerGetterCalls? = null
        try {
            val users = parseAndroidUsers(fixedRead(listOf("/system/bin/pm", "list", "users")))
            val reader = DozeOwnerCandidateReader(::active, { remaining(deadline) })
            val getters = SystemDozeEffectiveOwnerReadPort({ reader.read(users) }, ::requireActive, { remaining(deadline) }, ::closeFutureStarts)
            source = getters
            val identities = DozeEffectiveOwnerGetterCalls(::requireActive, { remaining(deadline) }, ::closeFutureStarts)
            guardians = identities
            return GmsManualBrokerObservation(getters, { identities.read { GmsManualBrokerIdentity.shell(caller) } },
                { fixedRead(listOf("/system/bin/cmd", "deviceidle", "whitelist")) },
                { getters.uncertainBinderExit || identities.uncertainExit }, GMS_MANUAL_BROKER_FINGERPRINT, ::requireActive).read()
        } finally {
            if (source?.uncertainBinderExit == true || guardians?.uncertainExit == true) closeFutureStarts()
            source?.close(); guardians?.close(); observing.set(false)
        }
    }
    private fun observationBytes(value: GmsGlobalDozeObservation) = GmsManualBrokerObservationCodec.encode(value, GMS_MANUAL_BROKER_FINGERPRINT)
    private fun requireBound(caller: Int) { check(ownerUid == caller && linked) }
    private fun closeFutureStarts() = synchronized(monitor) {
        terminal = true; readsClosed = true
        // Terminal is checked under the SAME monitor as start; retain actual child/preparation.
    }
    private fun reply(code: String) = Bundle().apply { putInt("schema", 3); putString("nonce", nonce); putString("code", code) }
    private inline fun guarded(action: () -> Bundle): Bundle = try { action() } catch (_: Exception) { reply("REJECTED") }
    private companion object {
        val instanceConsumed = AtomicBoolean(false)
        fun remaining(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
    }
}
