package io.github.originosfcmguardian.doze.cyclebroker

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
import io.github.originosfcmguardian.core.doze.*
import io.github.originosfcmguardian.doze.gmsbroker.GMS_MANUAL_BROKER_FINGERPRINT
import io.github.originosfcmguardian.doze.gmsbroker.GmsManualBrokerIdentity
import io.github.originosfcmguardian.doze.gmsbroker.IGmsManualBroker
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Facts from one registered child, never inferred from an RPC timeout or own Binder death. */
internal data class GmsDozeCycleCommandEvidence(val ref: GmsDozeCycleAttemptRef, val cleanup: GmsDozeCycleCleanup,
    val identity: GmsGlobalDozeIdentity, val exitCode: Int?, val outputValid: Boolean)
internal enum class GmsDozeCycleAutomaticDispatch { NOT_DISPATCHED, REJECTED, STARTED }
internal interface GmsDozeCycleConnection {
    fun hello(): GmsManualBrokerHello
    /** Irreversible before any grant CAS; null ref afterwards cannot prove no authority. */
    fun reserveGrantPreparation()
    fun pin(ref: GmsDozeCycleAttemptRef)
    fun probe(): GmsGlobalDozeObservation
    fun activate(ticket: GmsDozeCycleTicket)
    fun start(ticket: GmsDozeCycleTicket)
    /** Host-test defaults; production overrides both to check on its actual executor lanes. */
    fun activateAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): Boolean {
        if (!lease.checkEligible()) return false
        activate(ticket)
        return true
    }
    fun startAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): GmsDozeCycleAutomaticDispatch {
        if (!lease.checkEligible() || !lease.admitDispatch()) return GmsDozeCycleAutomaticDispatch.NOT_DISPATCHED
        start(ticket)
        return GmsDozeCycleAutomaticDispatch.STARTED
    }
    fun awaitCleanup(ticket: GmsDozeCycleTicket): GmsDozeCycleCommandEvidence
    fun release(ticket: GmsDozeCycleTicket): GmsDozeCycleReleaseAck
    fun revoke(ticket: GmsDozeCycleTicket): GmsDozeCycleClosure
    /** Actual death of the previously linked own Binder; INERT-only disposal also permitted. */
    fun destroyOwnAndObserveDeath()
}
internal interface GmsDozeCycleTransport {
    fun bindInert(operation: GmsGlobalDozeOperation): GmsDozeCycleConnection
    fun findOwn(ref: GmsDozeCycleAttemptRef): GmsDozeCycleConnection?
    fun observedDeath(ref: GmsDozeCycleAttemptRef, revokeVersion: Long): GmsDozeCycleClosure?
}

/** One canonical App process transport; immutable consumed ref tombstones outlive executor lanes. */
internal class GmsDozeCycleBrokerTransport private constructor(
    private val context: Context, private val owner: GmsDozeCycleControl,
) : GmsDozeCycleTransport {
    private val appUid = Process.myUid()
    private val registrations = GmsDozeCycleBrokerRegistrations()
    private val attempts = ArrayList<Connection>()
    override fun bindInert(operation: GmsGlobalDozeOperation): GmsDozeCycleConnection {
        background()
        val own = synchronized(registrations.monitor) {
            check(!owner.isTerminal())
            Connection(operation, registrations.register()).also(attempts::add)
        }
        own.bindOnce()
        return own
    }
    override fun findOwn(ref: GmsDozeCycleAttemptRef): GmsDozeCycleConnection? {
        val own = synchronized(registrations.monitor) { attempts.singleOrNull { it.matches(ref) } } ?: return null
        own.pin(ref) // Resolves the tiny grant-CAS/attach race using this existing nonce, never a bind.
        return own
    }
    override fun observedDeath(ref: GmsDozeCycleAttemptRef, revokeVersion: Long): GmsDozeCycleClosure? =
        synchronized(registrations.monitor) { attempts.singleOrNull { it.matches(ref) && it.actualDeath.get() } }?.let {
            it.pin(ref)
            GmsDozeCycleClosure(ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, revokeVersion)
        }

    private inner class Connection(private val operation: GmsGlobalDozeOperation,
        private val registration: GmsDozeCycleBrokerRegistrations.Entry) : GmsDozeCycleConnection {
        private val unique = UUID.randomUUID().toString().replace("-", "")
        private val component = ComponentName(context.packageName, when (operation) {
            GmsGlobalDozeOperation.ADD -> GmsDozeCycleAddUserService::class.java.name
            GmsGlobalDozeOperation.REMOVE -> GmsDozeCycleRemoveUserService::class.java.name
        })
        private val args = Shizuku.UserServiceArgs(component).daemon(false).tag("guardian-cycle-$unique")
            .processNameSuffix("guardian_gms_cycle_$unique").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        private val controlLane = lane("cycle-control-$unique")
        private val probeLane = lane("cycle-probe-$unique")
        private val startLane = lane("cycle-start-$unique")
        private val awaitLane = lane("cycle-await-$unique")
        private val removalLane = lane("cycle-removal-$unique")
        private val lifetime = Binder()
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val bound = AtomicBoolean(false)
        private val failed = AtomicBoolean(false)
        private val removing = AtomicBoolean(false)
        private val normalRemoving = AtomicBoolean(false)
        private val admissionClosed = AtomicBoolean(false)
        private val lanesClosed = AtomicBoolean(false)
        private val remote = AtomicReference<IGmsManualBroker?>()
        private val nonce = AtomicReference<String?>()
        private val ref = AtomicReference<GmsDozeCycleAttemptRef?>()
        private val guardian = AtomicReference<GmsGlobalGuardianBinding?>()
        private val prepared = AtomicReference<ByteArray?>()
        private val removalLock = Any()
        private var ownRemoval: Future<Boolean>? = null
        val actualDeath = AtomicBoolean(false)
        private val death = IBinder.DeathRecipient {
            actualDeath.set(true); admissionClosed.set(true); died.countDown(); connected.countDown()
            if (!removing.get()) failClosed()
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (failed.get() || removing.get() || owner.isTerminal()) { removeLateBinding(); return }
                try {
                    check(name == component && remote.get() == null)
                    binder.linkToDeath(death, 0)
                    remote.set(IGmsManualBroker.Stub.asInterface(binder))
                    if (failed.get() || removing.get() || owner.isTerminal()) removeLateBinding()
                } catch (_: Exception) { failClosed(); removeLateBinding() }
                finally { connected.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!removing.get()) failClosed()
                connected.countDown() // Disconnect does not establish death.
            }
        }
        fun matches(expected: GmsDozeCycleAttemptRef) = registrations.canMatch(registration, expected) &&
            operation == expected.operation && nonce.get() == expected.key.nonce &&
            (ref.get() == null || ref.get() == expected)
        fun bindOnce() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            try {
                rpc(controlLane, 3) {
                    check(bound.compareAndSet(false, true) && Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                    guardian.set(GmsManualBrokerIdentity.app(context, appUid))
                    Shizuku.bindUserService(args, connection)
                }
                check(connected.await(remaining(deadline), TimeUnit.MILLISECONDS) && remote.get() != null && !actualDeath.get() && !failed.get())
            } catch (failure: Exception) { failClosed(); removeLateBinding(); throw failure }
        }
        override fun hello(): GmsManualBrokerHello = rpc(controlLane, 3) {
            val fields = values(requireNotNull(remote.get()).hello(lifetime))
            val actualNonce = fields["nonce"] as? String ?: error("Missing cycle nonce")
            GmsDozeCycleReply.validate(fields, actualNonce, "HELLO", mapOf("shellUid" to intType, "buildId" to intType, "operation" to String::class.java))
            check(actualNonce.matches(Regex("[A-Za-z0-9_-]{1,128}")) && fields["shellUid"] == 2000 &&
                fields["buildId"] == BuildConfig.VERSION_CODE && fields["operation"] == operation.name)
            check(nonce.compareAndSet(null, actualNonce) || nonce.get() == actualNonce)
            GmsManualBrokerHello(actualNonce, operation)
        }
        override fun reserveGrantPreparation() = synchronized(registrations.monitor) {
            check(!failed.get() && !removing.get())
            registrations.reserveGrantPreparation(registration)
        }
        override fun pin(ref: GmsDozeCycleAttemptRef) = synchronized(registrations.monitor) {
            check(matches(ref) && ref.key.generation > 0)
            registrations.pin(registration, ref)
            check(this.ref.compareAndSet(null, ref) || this.ref.get() == ref)
        }
        override fun probe(): GmsGlobalDozeObservation = rpc(probeLane, 40) {
            val fields = checked(requireNotNull(remote.get()).probe(), "PROBE", mapOf("observation" to ByteArray::class.java))
            GmsManualBrokerObservationCodec.decode(fields["observation"] as ByteArray, GMS_MANUAL_BROKER_FINGERPRINT)
                .also { check(it.identity.guardian == guardian.get()) }
        }
        override fun activate(ticket: GmsDozeCycleTicket) = rpc(controlLane, 3) {
            registrations.markActivation(registration)
            check(!admissionClosed.get()); checked(requireNotNull(remote.get()).activate(ownTicket(ticket)), "ACTIVATED"); Unit
        }
        override fun start(ticket: GmsDozeCycleTicket) = rpc(startLane, 40) {
            registrations.markStart(registration)
            check(!admissionClosed.get())
            val exact = ownTicket(ticket)
            check(prepared.compareAndSet(null, exact.clone()))
            checked(requireNotNull(remote.get()).start(exact), "STARTED"); Unit
        }
        override fun activateAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): Boolean = rpc(controlLane, 3) {
            if (!lease.checkEligible()) return@rpc false
            val exact = ownTicket(ticket)
            if (admissionClosed.get() && lease.isClosed()) return@rpc false
            registrations.markActivation(registration)
            val fields = values(requireNotNull(remote.get()).activate(exact))
            val code = fields["code"] as? String ?: error("Missing activation code")
            GmsDozeCycleReply.validate(fields, ownNonce(), code)
            if (code == "REJECTED" && lease.isClosed()) false
            else { check(code == "ACTIVATED"); true }
        }
        override fun startAutomatic(ticket: GmsDozeCycleTicket, lease: GmsDozeCycleAutomaticLease): GmsDozeCycleAutomaticDispatch = rpc(startLane, 40) {
            // A known rejection here never crossed Binder and must not poison unrelated UI work.
            if (!lease.checkEligible()) return@rpc GmsDozeCycleAutomaticDispatch.NOT_DISPATCHED
            val exact = ownTicket(ticket)
            if (admissionClosed.get() && lease.isClosed()) return@rpc GmsDozeCycleAutomaticDispatch.NOT_DISPATCHED
            if (!lease.admitDispatch()) return@rpc GmsDozeCycleAutomaticDispatch.NOT_DISPATCHED
            registrations.markStart(registration)
            check(prepared.compareAndSet(null, exact.clone()))
            val fields = values(requireNotNull(remote.get()).start(exact))
            val code = fields["code"] as? String ?: error("Missing start code")
            GmsDozeCycleReply.validate(fields, ownNonce(), code)
            when {
                code == "STARTED" -> GmsDozeCycleAutomaticDispatch.STARTED
                code == "REJECTED" && lease.isClosed() -> GmsDozeCycleAutomaticDispatch.REJECTED
                else -> error("Unknown automatic start outcome")
            }
        }
        override fun awaitCleanup(ticket: GmsDozeCycleTicket): GmsDozeCycleCommandEvidence = rpc(awaitLane, 12) {
            val exact = ownTicket(ticket)
            check(requireNotNull(prepared.get()).contentEquals(exact))
            val fields = values(requireNotNull(remote.get()).awaitCleanup(exact))
            val required = mapOf("ticketBytes" to ByteArray::class.java, "observation" to ByteArray::class.java,
                "childExited" to boolType, "stdoutEofAndDrained" to boolType, "stderrEofAndDrained" to boolType,
                "collectorsTerminated" to boolType, "handlesClosed" to boolType, "outputValid" to boolType)
            GmsDozeCycleReply.validate(fields, ownNonce(), "CLEANUP", if (fields.containsKey("exitCode")) required + ("exitCode" to intType) else required)
            check((fields["ticketBytes"] as ByteArray).contentEquals(exact))
            val observation = GmsManualBrokerObservationCodec.decode(fields["observation"] as ByteArray, GMS_MANUAL_BROKER_FINGERPRINT)
            check(observation.identity == ticket.grant.identity)
            val cleanup = GmsDozeCycleCleanup(ticket.ref, fields["childExited"] as Boolean, fields["stdoutEofAndDrained"] as Boolean,
                fields["stderrEofAndDrained"] as Boolean, fields["collectorsTerminated"] as Boolean, fields["handlesClosed"] as Boolean)
            check(!fields.containsKey("exitCode") || cleanup.childExited)
            GmsDozeCycleCommandEvidence(ticket.ref, cleanup, observation.identity, fields["exitCode"] as? Int, fields["outputValid"] as Boolean)
        }
        override fun release(ticket: GmsDozeCycleTicket): GmsDozeCycleReleaseAck = rpc(controlLane, 3) {
            val exact = ownTicket(ticket)
            check(ticket.resultSummary != null)
            val fields = checked(requireNotNull(remote.get()).acknowledgeResult(exact), "RELEASED",
                mapOf("ticketBytes" to ByteArray::class.java, "release" to boolType))
            check((fields["ticketBytes"] as ByteArray).contentEquals(exact) && fields["release"] == true)
            GmsDozeCycleReleaseAck(ticket.ref)
        }
        override fun revoke(ticket: GmsDozeCycleTicket): GmsDozeCycleClosure {
            val ownerWasTerminal = owner.isTerminal()
            var exactTicketBound = false
            try {
                val exact = ownTicket(ticket)
                check(ticket.requestedRevoke > 0)
                exactTicketBound = true
                return rpc(controlLane, 3, allowFailed = true, deferPoisonForRevoke = true) {
                    val fields = checked(requireNotNull(remote.get()).revoke(exact), "ACK", mapOf("ticketBytes" to ByteArray::class.java, "revokeVersion" to Long::class.javaObjectType))
                    check((fields["ticketBytes"] as ByteArray).contentEquals(exact) && fields["revokeVersion"] == ticket.requestedRevoke)
                    admissionClosed.set(true)
                    GmsDozeCycleClosure(ticket.ref, GmsDozeCycleClosureKind.REVOKE_ACK, ticket.requestedRevoke)
                }
            } catch (failure: Exception) {
                // Only an already confirmed linked death during this own normal retirement
                // replaces a failed revoke. Unknown RPCs and any prior poison stay terminal.
                if (GmsDozeCycleRevokeFailurePolicy.canUseNormalOwnDeath(
                        exactTicketBound && ref.get() == ticket.ref && matches(ticket.ref),
                        ownerWasTerminal, owner.isTerminal(), normalRemoving.get() && removing.get(), actualDeath.get())) {
                    admissionClosed.set(true)
                    return GmsDozeCycleClosure(ticket.ref, GmsDozeCycleClosureKind.ACTUAL_BINDER_DEATH, ticket.requestedRevoke)
                }
                failClosed()
                throw failure
            }
        }
        override fun destroyOwnAndObserveDeath() {
            background(); check(nonce.get() != null)
            synchronized(registrations.monitor) {
                registrations.beginRetirement(registration)
                normalRemoving.set(true); removing.set(true); admissionClosed.set(true)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            try {
                val removal = synchronized(removalLock) {
                    ownRemoval ?: removalLane.submit<Boolean> { check(bound.get()); Shizuku.unbindUserService(args, connection, true); true }
                        .also { ownRemoval = it }
                }
                check(removal.get(remaining(deadline), TimeUnit.MILLISECONDS) && died.await(remaining(deadline), TimeUnit.MILLISECONDS) && actualDeath.get())
                val lanes = listOf(controlLane, probeLane, startLane, awaitLane, removalLane)
                if (lanesClosed.compareAndSet(false, true)) lanes.forEach { it.shutdown() }
                // Actual executor termination is independent of Binder death. Keep any unfinished
                // task/uncertainty; no cancellation or shutdown call becomes drainage evidence.
                lanes.all { it.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS) }
                synchronized(registrations.monitor) {
                    if (registrations.retireInert(registration, true, actualDeath.get(), lanes))
                        check(attempts.remove(this))
                }
            } catch (failure: Exception) { failClosed(); throw failure }
            // Every reserved/pinned/unknown entry remains, including the grant-CAS-to-pin gap.
        }
        private fun ownTicket(ticket: GmsDozeCycleTicket): ByteArray {
            registrations.requireLive(registration)
            val exact = GmsDozeCycleTicketCodec.encode(ticket)
            check(GmsDozeCycleTicketCodec.decode(exact) == ticket && ticket.ref == ref.get() && matches(ticket.ref) &&
                ticket.grant.identity.guardian == guardian.get())
            return exact
        }
        private fun checked(bundle: Bundle?, code: String, extras: Map<String, Class<*>> = emptyMap()) =
            values(bundle).also { GmsDozeCycleReply.validate(it, ownNonce(), code, extras) }
        private fun ownNonce() = requireNotNull(nonce.get())
        private fun <T> rpc(lane: ExecutorService, seconds: Long, allowFailed: Boolean = false,
            deferPoisonForRevoke: Boolean = false, action: () -> T): T {
            background()
            // Known rejected retired handles never enter RPC or poison a successful retirement.
            // Keep the caller counted through future.get, postchecks and failure classification,
            // independently of the actual lane task's completion/termination.
            registrations.beginCaller(registration, allowRetiring = allowFailed)
            try {
                check(allowFailed || !failed.get() && !owner.isTerminal() && !removing.get())
                registrations.beginRpc(registration, allowRetiring = allowFailed)
                val future = try { lane.submit<T> {
                    try {
                        check(allowFailed || !failed.get() && !owner.isTerminal() && !removing.get())
                        val actual = GmsManualBrokerIdentity.app(context, appUid)
                        guardian.get()?.let { check(it == actual) }
                        action()
                    } finally { registrations.endRpc(registration) }
                } } catch (failure: Exception) {
                    registrations.endRpc(registration)
                    throw failure
                }
                val value = future.get(seconds, TimeUnit.SECONDS)
                check(allowFailed || !failed.get() && !owner.isTerminal() && !removing.get())
                return value
            } catch (failure: Exception) {
                if (!deferPoisonForRevoke) failClosed()
                throw failure
            } finally { registrations.endCaller(registration) }
        }
        private fun failClosed() {
            registrations.markUnknown(registration)
            failed.set(true); admissionClosed.set(true); owner.failClosed()
        }
        private fun removeLateBinding() {
            removing.set(true)
            runCatching { removalLane.submit { runCatching { if (bound.get()) Shizuku.unbindUserService(args, connection, true) } } }
        }
    }
    companion object {
        private val instances = HashMap<String, GmsDozeCycleBrokerTransport>()
        fun get(context: Context, control: GmsDozeCycleControl): GmsDozeCycleBrokerTransport = synchronized(instances) {
            val canonical = context.applicationContext.noBackupFilesDir.canonicalPath
            instances.getOrPut(canonical) { GmsDozeCycleBrokerTransport(context.applicationContext, control) }
                .also { check(it.owner === control) }
        }
        private val intType = Int::class.javaObjectType
        private val boolType = Boolean::class.javaObjectType
        private fun background() { check(Looper.myLooper() != Looper.getMainLooper()) }
        private fun lane(name: String): ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }
        private fun remaining(deadline: Long) = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        @Suppress("DEPRECATION") private fun values(bundle: Bundle?): Map<String, Any?> = requireNotNull(bundle).let { value -> value.keySet().associateWith { value.get(it) } }
    }
}

/**
 * Production registry admission/retirement facts only, not an execution or storage authority.
 * Every entry mutation and transport list mutation uses this same short monitor. No IO/wait here.
 */
internal class GmsDozeCycleBrokerRegistrations {
    internal val monitor = Any()
    private val entries = LinkedHashSet<Entry>()
    internal class Entry internal constructor(internal val owner: GmsDozeCycleBrokerRegistrations) {
        internal var grantPreparationReserved = false
        internal var pinnedRef: GmsDozeCycleAttemptRef? = null
        internal var activationAttempted = false
        internal var startAttempted = false
        internal var activeRpcs = 0
        internal var activeCallers = 0
        internal var unknown = false
        internal var retiring = false
        internal var retiredInert = false
    }
    fun register(): Entry = synchronized(monitor) {
        check(entries.size < GmsDozeCycleLedgerCodec.MAX_CYCLES * 2 + 1)
        Entry(this).also(entries::add)
    }
    fun size(): Int = synchronized(monitor) { entries.size }
    fun contains(entry: Entry): Boolean = synchronized(monitor) { entry in entries }
    fun requireLive(entry: Entry) = synchronized(monitor) {
        check(entry.owner === this && entry in entries && !entry.retiredInert)
    }
    fun reserveGrantPreparation(entry: Entry) = synchronized(monitor) {
        requireLive(entry)
        check(!entry.retiring && !entry.unknown)
        entry.grantPreparationReserved = true
    }
    fun pin(entry: Entry, ref: GmsDozeCycleAttemptRef) = synchronized(monitor) {
        requireLive(entry)
        check(entry.grantPreparationReserved && (entry.pinnedRef == null || entry.pinnedRef == ref))
        entry.pinnedRef = ref // A reserved CAS may return its exact ref after own retirement began.
    }
    fun canMatch(entry: Entry, ref: GmsDozeCycleAttemptRef): Boolean = synchronized(monitor) {
        entry.owner === this && entry in entries && !entry.retiredInert &&
            (entry.pinnedRef == null || entry.pinnedRef == ref)
    }
    fun markActivation(entry: Entry) = synchronized(monitor) {
        requireLive(entry); check(!entry.retiring && entry.grantPreparationReserved && entry.pinnedRef != null)
        entry.activationAttempted = true
    }
    fun markStart(entry: Entry) = synchronized(monitor) {
        requireLive(entry); check(!entry.retiring && entry.grantPreparationReserved && entry.pinnedRef != null)
        entry.startAttempted = true
    }
    fun beginRpc(entry: Entry, allowRetiring: Boolean = false) = synchronized(monitor) {
        requireLive(entry)
        check(!entry.retiring || allowRetiring && entry.grantPreparationReserved && entry.pinnedRef != null)
        entry.activeRpcs = Math.addExact(entry.activeRpcs, 1)
    }
    fun endRpc(entry: Entry) = synchronized(monitor) {
        requireLive(entry); check(entry.activeRpcs > 0); entry.activeRpcs--
    }
    fun beginCaller(entry: Entry, allowRetiring: Boolean = false) = synchronized(monitor) {
        requireLive(entry)
        check(!entry.retiring || allowRetiring && entry.grantPreparationReserved && entry.pinnedRef != null)
        entry.activeCallers = Math.addExact(entry.activeCallers, 1)
    }
    fun endCaller(entry: Entry) = synchronized(monitor) {
        requireLive(entry); check(entry.activeCallers > 0); entry.activeCallers--
    }
    fun markUnknown(entry: Entry) = synchronized(monitor) {
        check(entry.owner === this)
        entry.unknown = true // Never reinsert, clear uncertainty or revive an already retired handle.
    }
    fun beginRetirement(entry: Entry) = synchronized(monitor) {
        requireLive(entry); entry.retiring = true
    }
    fun retireInert(entry: Entry, ownRemovalSucceeded: Boolean, linkedOwnDeath: Boolean,
        actualLanes: List<ExecutorService>): Boolean = synchronized(monitor) {
        requireLive(entry)
        check(actualLanes.size == 5)
        if (!entry.retiring || !ownRemovalSucceeded || !linkedOwnDeath || entry.grantPreparationReserved ||
            entry.pinnedRef != null || entry.activationAttempted || entry.startAttempted || entry.unknown ||
            entry.activeRpcs != 0 || entry.activeCallers != 0 || actualLanes.any { !it.isTerminated }) return@synchronized false
        entry.retiredInert = true
        check(entries.remove(entry))
        true
    }
}

internal object GmsDozeCycleRevokeFailurePolicy {
    fun canUseNormalOwnDeath(exactTicketBound: Boolean, ownerWasTerminal: Boolean,
        ownerIsTerminal: Boolean, normalRemoving: Boolean, actualLinkedDeath: Boolean): Boolean =
        exactTicketBound && !ownerWasTerminal && !ownerIsTerminal && normalRemoving && actualLinkedDeath
}

internal object GmsDozeCycleReply {
    fun validate(reply: Map<String, Any?>, nonce: String, code: String, extras: Map<String, Class<*>> = emptyMap()) {
        val fields = mapOf("schema" to Int::class.javaObjectType, "nonce" to String::class.java, "code" to String::class.java) + extras
        check(reply.keys == fields.keys && fields.all { (key, type) -> type.isInstance(reply[key]) })
        check(reply["schema"] == 3 && reply["nonce"] == nonce && reply["code"] == code)
    }
}
