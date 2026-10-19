package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.privileged.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact-ticket checks run outside the owner monitor. Unknown operation scopes fail closed. */
internal class DaemonFcmWriteGate(private val elapsed: () -> Long,
    private val evidenceBudgetMillis: Long = 8_000) : DaemonPrerequisitePort {
    init { require(evidenceBudgetMillis in 1..8_000) }
    private class Check(val ticket: DaemonWriteTicket, val ready: () -> Boolean)
    private val current = AtomicReference<Check?>()
    fun install(ticket: DaemonWriteTicket, ready: () -> Boolean): AutoCloseable {
        require(ticket.operationKey.startsWith("fcm:") || ticket.operationKey.startsWith("heartbeat:"))
        val check = Check(ticket, ready)
        check(current.compareAndSet(null, check))
        return AutoCloseable { current.compareAndSet(check, null) }
    }
    override fun readFresh(ticket: DaemonWriteTicket): DaemonWriteEvidence? {
        val check = current.get()?.takeIf { it.ticket === ticket } ?: return null
        val ready = runCatching(check.ready).getOrDefault(false)
        val now = elapsed()
        // The native runner repeats its complete GMS observation AFTER preparation. Its whole
        // operation is still capped at eight seconds; the host kernel must use the same cap.
        return DaemonWriteEvidence(ticket, now, Math.addExact(now, evidenceBudgetMillis), ready && current.get() === check)
    }
}

/** The host constructs ONE kernel with this router, so all three branches share single flight/OFF. */
internal class DaemonProtectionWriteRouter(private val stopped: DaemonPrerequisitePort,
    private val fcm: DaemonPrerequisitePort) : DaemonPrerequisitePort {
    override fun readFresh(ticket: DaemonWriteTicket) = when {
        ticket.operationKey.startsWith("stopped:") -> stopped.readFresh(ticket)
        ticket.operationKey.startsWith("fcm:") || ticket.operationKey.startsWith("heartbeat:") -> fcm.readFresh(ticket)
        else -> null
    }
}

internal interface DaemonFcmShellPort : AutoCloseable {
    fun read(id: Long, cancelled: () -> Boolean): GmsConnectionObservation
    fun usableNetwork(): Boolean
    fun disconnectedNetwork(): Boolean
    /** Old app endpoint closure proof only; ordinary app absence is never revocation. */
    fun previousHeartbeatOwnerGone(value: HeartbeatSnapshot, cancelled: () -> Boolean): Boolean
    fun recover(id: Long, uid: Int, binding: String, authorize: () -> Boolean, cancelled: () -> Boolean,
        operation: FcmRecoveryOperation, fence: PrivilegedExecutionFence): FcmRecoveryResult
    fun arm(id: Long, uid: Int, generation: Long, binding: String, lifetime: GmsHeartbeatLifetime,
        cancelled: () -> Boolean): GmsAutoArmResult
    fun heartbeat(id: Long, uid: Int, session: GmsAutoSession, attempt: String,
        cancelled: () -> Boolean, fence: PrivilegedExecutionFence): GmsHeartbeatResult
    fun revoke(uid: Int, session: GmsAutoSession): Boolean
    /** True only before this native runner has ever owned a capability; not merely no live worker. */
    fun freshHeartbeatEndpoint(): Boolean = false
    /** Actual executor workers, including tasks whose Future has timed out/cancelled. */
    fun isQuiescent(): Boolean = true
}

/** Production shell implementation: no Android Context, App callback or app-side runtime object. */
internal class SystemDaemonFcmShellPort : DaemonFcmShellPort {
    private val reads = GmsConnectionProbeRunner(::SystemGmsConnectionReadPort)
    private val recovery = FcmRecoveryRunner()
    private val heartbeat = GmsHeartbeatRunner()
    override fun read(id: Long, cancelled: () -> Boolean) = reads.read(id, cancelled)
    override fun usableNetwork() = daemonFcmNetworkUsable()
    override fun disconnectedNetwork() = SystemGmsConnectionReadPort().usableInternet()
    override fun previousHeartbeatOwnerGone(value: HeartbeatSnapshot, cancelled: () -> Boolean): Boolean {
        val old = value.callerIdentity ?: return false
        val port = SystemGmsHeartbeatCallerReadPort()
        val before = port.environment()
        if (before.currentUser != 0 || before.serviceUid != 2000 || cancelled() ||
            port.packagesForUid(old.uid) != listOf("io.github.originosfcmguardian")) return false
        val gone = if (old.kernelBootId != before.bootId) value.durableConsent?.version == 3 else
            when (val process = port.process(old.pid, old.uid, cancelled)) {
                GmsHeartbeatCallerProcess.Gone -> true
                is GmsHeartbeatCallerProcess.Present -> process.startTicks != old.startTicks
                GmsHeartbeatCallerProcess.Unknown -> false
            }
        return gone && !cancelled() && port.environment() == before &&
            port.packagesForUid(old.uid) == listOf("io.github.originosfcmguardian")
    }
    override fun recover(id: Long, uid: Int, binding: String, authorize: () -> Boolean, cancelled: () -> Boolean,
        operation: FcmRecoveryOperation, fence: PrivilegedExecutionFence) =
        recovery.requestGuarded(id, uid, binding, authorize, cancelled, operation, fence)
    override fun arm(id: Long, uid: Int, generation: Long, binding: String, lifetime: GmsHeartbeatLifetime,
        cancelled: () -> Boolean) = heartbeat.armAutomatic(id, uid, generation, binding, lifetime, cancelled)
    override fun heartbeat(id: Long, uid: Int, session: GmsAutoSession, attempt: String,
        cancelled: () -> Boolean, fence: PrivilegedExecutionFence) =
        heartbeat.requestAutomaticGuarded(id, uid, session, attempt, cancelled, fence)
    override fun revoke(uid: Int, session: GmsAutoSession) = heartbeat.revokeAutomatic(uid, session.generation, session.id)
    override fun freshHeartbeatEndpoint() = heartbeat.isFreshAutomaticEndpoint()
    override fun isQuiescent() = reads.isQuiescent() && recovery.isQuiescent() && heartbeat.isQuiescent()
    override fun close() { reads.close(); recovery.close(); heartbeat.close() }
}

/** Same INTERNET+VALIDATED check as the app owner; MCS keeps its stronger VPN/suspension gate. */
private fun daemonFcmNetworkUsable(): Boolean = runCatching {
    val binderType = Class.forName("android.os.IBinder")
    val networkType = Class.forName("android.net.Network")
    val capabilitiesType = Class.forName("android.net.NetworkCapabilities")
    val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "connectivity")
    val api = Class.forName("android.net.IConnectivityManager")
    val remote = Class.forName("android.net.IConnectivityManager\$Stub").getMethod("asInterface", binderType).invoke(null, binder)
    val active = api.getMethod("getActiveNetwork").invoke(remote) ?: return false
    val capabilities = invokeShellNetworkCapabilities(api, checkNotNull(remote), networkType, active) ?: return false
    val has = capabilitiesType.getMethod("hasCapability", Int::class.javaPrimitiveType)
    has.invoke(capabilities, 12) == true && has.invoke(capabilities, 16) == true
}.getOrDefault(false)

internal enum class DaemonFcmCycleStatus { CHECKED, BUSY, THROTTLED, PENDING, UNAVAILABLE }
internal data class DaemonFcmCycle(val status: DaemonFcmCycleStatus,
    val observation: GmsConnectionObservation? = null, val heartbeat: GmsHeartbeatResult? = null)

/** Display evidence only, never consulted by admission, recovery or heartbeat dispatch. */
internal data class DaemonRecoveryReadinessEvidence(val record: DaemonControlRecord,
    val sampleRequestId: Long, val sampleElapsedMillis: Long, val checkedAtElapsedMillis: Long,
    val admitted: Boolean) {
    fun matches(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample) =
        this.record == record && sampleRequestId == sample.requestId && sampleElapsedMillis == sample.elapsedRealtimeMillis
    fun isFreshFor(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample,
        sessionStartedElapsed: Long?, now: Long) = matches(record, sample) && sessionStartedElapsed != null &&
        sessionStartedElapsed >= 0 && sampleElapsedMillis >= sessionStartedElapsed &&
        checkedAtElapsedMillis >= sampleElapsedMillis && now >= checkedAtElapsedMillis &&
        now - checkedAtElapsedMillis <= 60_000 && sample.isFresh(now)
}
internal data class DaemonFcmObservation(val observation: GmsConnectionObservation? = null,
    val recoveryReadiness: DaemonRecoveryReadinessEvidence? = null)

/** In-memory host proof, issued only after original-endpoint revoke ACK AND worker drain.
 * Exact imported bytes and owner revision bind it to one handoff; it is never persisted/rebuilt.
 */
internal class DaemonHeartbeatHandoffProof(val record: DaemonControlRecord, val heartbeat: HeartbeatSnapshot,
    val heartbeatBytesDigest: String) {
    private val consumed = AtomicBoolean()
    fun matches(record: DaemonControlRecord, heartbeat: HeartbeatSnapshot, digest: String) =
        !consumed.get() && this.record == record && this.heartbeat == heartbeat && heartbeatBytesDigest == digest &&
            heartbeat.pause == HeartbeatPause.WAITING_HOST && heartbeat.remoteSessionId == null &&
            heartbeat.pendingArm == null && heartbeat.pendingAttempt == null && heartbeat.resumeBlock == null &&
            !heartbeat.currentEpochUnknown()
    fun consume(record: DaemonControlRecord, heartbeat: HeartbeatSnapshot, digest: String) =
        matches(record, heartbeat, digest) && consumed.compareAndSet(false, true)
    /** Trusted host has checked the unchanged parent and exact clean bytes around this control ACK. */
    internal fun retarget(next: DaemonControlRecord, heartbeat: HeartbeatSnapshot,
        digest: String): DaemonHeartbeatHandoffProof? {
        if (record.revision == Long.MAX_VALUE || next.revision != record.revision + 1 || next.owner != record.owner ||
            next.migrationId != record.migrationId || record.mode == DaemonControlMode.PREPARED ||
            next.mode == DaemonControlMode.PREPARED || !consume(record, heartbeat, digest)) return null
        return DaemonHeartbeatHandoffProof(next, heartbeat, digest)
    }
}
internal fun interface DaemonHeartbeatHandoffPort {
    fun closed(record: DaemonControlRecord, heartbeat: HeartbeatSnapshot): DaemonHeartbeatHandoffProof?
}

/** Only the kernel/control actor may replace the exact absent/clean-OFF baseline captured at grant. */
internal fun interface DaemonBundledHeartbeatPort {
    fun derive(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample): HeartbeatSnapshot?
    fun rebindRecovery(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample): Boolean = false
    fun renewRevoked(record: DaemonControlRecord, sample: GmsConnectionObservation.Sample,
        closeOriginal: (DaemonProtectionRootSnapshot) -> DaemonHeartbeatConfirmationClosure?): HeartbeatSnapshot? = null
}

internal interface DaemonHeartbeatOwnerStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray?)
}
/** Proof of a daemon-owned clean endpoint, separate from the app caller's historical PID. */
internal data class DaemonHeartbeatOwner(val record: DaemonControlRecord, val epoch: String,
    val generation: Long, val binding: String, val heartbeatBytesDigest: String) {
    init {
        require(canonicalUuid(epoch) && generation > 0)
        require(binding.matches(Regex("[a-f0-9]{64}")) && heartbeatBytesDigest.matches(Regex("[a-f0-9]{64}")))
    }
}
/** Runtime result diagnostics do not change ownership; uncertain results are separately barred. */
internal fun daemonHeartbeatOwnerDigest(value: HeartbeatSnapshot): String =
    DaemonProtectionRootSnapshot.digest(HeartbeatSnapshotCodec.encode(value.copy(
        lastStatus = null, lastReason = null, lastResultEpoch = null)))
internal object DaemonHeartbeatOwnerCodec {
    const val MAX_BYTES = 1024
    fun encode(value: DaemonHeartbeatOwner): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(0x47444831); it.writeInt(2)
            it.writeUTF(value.record.owner.installInstance); it.writeLong(value.record.owner.ownerEpoch)
            it.writeLong(value.record.revision); it.writeUTF(value.record.authorityDigest)
            it.writeUTF(value.record.mode.name); it.writeUTF(value.record.migrationId)
            it.writeUTF(value.epoch); it.writeLong(value.generation); it.writeUTF(value.binding)
            it.writeUTF(value.heartbeatBytesDigest)
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): DaemonHeartbeatOwner {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use {
            require(it.readInt() == 0x47444831 && it.readInt() == 2)
            val record = DaemonControlRecord(DaemonOwnerKey(it.readUTF(), it.readLong()), it.readLong(),
                it.readUTF(), DaemonControlMode.valueOf(it.readUTF()), it.readUTF())
            DaemonHeartbeatOwner(record, it.readUTF(), it.readLong(), it.readUTF(), it.readUTF()).also { _ -> require(it.available() == 0) }
        }
    }
}

/**
 * One finite cycle on the existing 30-second host poll. Recovery uses the production cycle and
 * codecs; heartbeat uses the existing store and runner capability. No new consent is minted here.
 * A first child over an absent/clean-OFF baseline may only be derived by the kernel/control actor.
 */
internal class DaemonFcmProtection(
    private val owner: DaemonOwnerControl,
    private val gate: DaemonFcmWriteGate,
    private val domains: DaemonDomainStores,
    private val authorityDigest: () -> String?,
    private val environment: DaemonOwnerEnvironmentProbe,
    private val guardian: DaemonGuardianInstallation,
    private val bootId: String,
    private val elapsed: () -> Long,
    private val wall: () -> Long = System::currentTimeMillis,
    private val shell: DaemonFcmShellPort = SystemDaemonFcmShellPort(),
    processId: Int,
    private val handoff: DaemonHeartbeatHandoffPort = DaemonHeartbeatHandoffPort { _, _ -> null },
    private val provenance: DaemonHeartbeatOwnerStorage = domains.heartbeatOwnerProvenance,
    private val bundledHeartbeat: DaemonBundledHeartbeatPort = DaemonBundledHeartbeatPort { _, _ -> null },
    sessionStartedElapsed: Long = elapsed(),
) : AutoCloseable {
    private val consent = ProtectionConsentStore(domains.authority)
    private val parent = FcmProtectionStore(domains.fcmProtection)
    private val recovery = FcmRecoveryStore(domains.fcmRecovery)
    private val heartbeat = GmsAutoHeartbeatStore(domains.heartbeat)
    private val heartbeatStorage = domains.heartbeat
    private val heartbeatBytes = { domains.heartbeatBytes() }
    private val episodes = FcmRecoveryEpisodeJournal(domains.fcmEpisodes, processId, sessionStartedElapsed, wall, elapsed)
    private val closed = AtomicBoolean()
    private val busy = AtomicBoolean()
    private val controlRevokeRequested = AtomicBoolean()
    @Volatile private var failed = false
    private var initialized = false
    private var nextPollAt = 0L
    private var requestId = 0L
    private data class RetainedObservation(val value: DaemonFcmObservation = DaemonFcmObservation(), val epoch: Long = 0)
    private val retainedObservation = AtomicReference(RetainedObservation())
    private var observation: GmsConnectionObservation? = null // Execution retains its original independent input.
    fun diagnosticObservation(): DaemonFcmObservation {
        val value = retainedObservation.get().value
        val sample = value.observation as? GmsConnectionObservation.Sample
        val evidence = value.recoveryReadiness?.takeIf { sample != null &&
            it.isFreshFor(it.record, sample, 0, elapsed()) }
        return value.copy(recoveryReadiness = evidence)
    }
    fun invalidateDiagnosticReadiness() {
        retainedObservation.updateAndGet { RetainedObservation(it.value.copy(recoveryReadiness = null), it.epoch + 1) }
    }
    private var lastObservationKey: String? = null
    private var live: AtomicBoolean? = null
    private class Flight(val fence: DaemonProcessExecutionFence, var bookkeepingDone: Boolean = false)
    private val flights = mutableListOf<Flight>() // Only poll owns mutation; isQuiescent observes under this lock.
    private var session: GmsAutoSession? = null
    private var sessionRevokeAcknowledged = false
    private var sessionRecord: DaemonControlRecord? = null
    private var deadline: HeartbeatDeadline? = null
    private var closedEndpoint: HeartbeatSnapshot? = null
    private fun lifetime(pinned: DaemonControlRecord) = object : GmsHeartbeatLifetime {
        // Native launch calls this under its monitor; the final execution fence supplies disk/lease checks.
        override fun alive() = !closed.get() && !failed && owner.isDispatchReady() && owner.snapshot() == pinned
        override fun link(death: () -> Unit) = Unit // The capability lives in this same shell process.
        override fun unlink() = Unit
    }

    suspend fun pollAsync(): DaemonFcmCycle {
        if (!busy.compareAndSet(false, true)) return DaemonFcmCycle(DaemonFcmCycleStatus.BUSY)
        return try { withContext(Dispatchers.IO) {
            reap()
            if (closed.get() || failed) { invalidateDiagnosticReadiness(); return@withContext result(DaemonFcmCycleStatus.UNAVAILABLE) }
            if (!shell.isQuiescent() || synchronized(flights) { flights.isNotEmpty() }) {
                invalidateDiagnosticReadiness(); return@withContext result(DaemonFcmCycleStatus.PENDING)
            }
            if (elapsed() < nextPollAt) return@withContext result(DaemonFcmCycleStatus.THROTTLED)
            try { cycle() } finally { nextPollAt = Math.addExact(elapsed(), POLL_MILLIS); reap() }
        } } catch (_: Exception) {
            invalidateDiagnosticReadiness()
            failed = true; live?.set(false)
            runCatching { revokeRetained() }
            runCatching { episodes.coverageGap(wall(), elapsed()) }
            result(DaemonFcmCycleStatus.UNAVAILABLE)
        } finally {
            if (closed.get() || controlRevokeRequested.getAndSet(false)) runCatching { revokeRetained() }
            busy.set(false)
        }
    }

    private suspend fun cycle(): DaemonFcmCycle {
        val diagnosticEpoch = retainedObservation.get().epoch
        if (!initialized) { episodes.initialize(); initialized = true }
        val pinned = owner.snapshot() ?: run { invalidateDiagnosticReadiness(); return result(DaemonFcmCycleStatus.UNAVAILABLE) }
        if (!authorized(pinned)) {
            invalidateDiagnosticReadiness()
            revokeRetained()
            episodes.coverageGap(wall(), elapsed())
            return result(DaemonFcmCycleStatus.UNAVAILABLE)
        }
        val cycleFlights = mutableListOf<Flight>()
        FcmRecoveryCycle(object : FcmRecoveryCycleAccess {
            override val store = recovery
            override fun wallMillis() = wall()
            override fun elapsedMillis() = elapsed()
            override fun current() = memoryOwnerCurrent(pinned)
            override fun authorized() = this@DaemonFcmProtection.authorized(pinned) && shell.usableNetwork()
            override fun admitted(sample: GmsConnectionObservation.Sample): Boolean {
                if (parent.read().admits(sample, true)) return true
                if (!shell.isQuiescent() || synchronized(flights) { flights.isNotEmpty() } || !authorized(pinned)) return false
                return bundledHeartbeat.rebindRecovery(pinned, sample) && authorized(pinned) && parent.read().admits(sample, true)
            }
            override fun usableNetwork() = shell.usableNetwork()
            override fun disconnectedNetwork() = shell.disconnectedNetwork()
            override suspend fun read() = shell.read(nextId()) { !memoryOwnerCurrent(pinned) }
            override fun retainLive(value: AtomicBoolean) { live = value }
            override fun observed(value: GmsConnectionObservation, authorized: Boolean) {
                observation = value
                val sample = value as? GmsConnectionObservation.Sample
                val evidence = sample?.let { DaemonRecoveryReadinessEvidence(pinned, it.requestId,
                    it.elapsedRealtimeMillis, elapsed(), authorized) }
                // Invalidating a control/permission epoch also rejects a late old-cycle publication.
                retainedObservation.updateAndGet { old -> if (old.epoch == diagnosticEpoch)
                    RetainedObservation(DaemonFcmObservation(value, evidence), diagnosticEpoch) else old }
                episodes.observe(event(FcmRecoveryEventAction.OBSERVATION, sample, authorized,
                    unknown = (value as? GmsConnectionObservation.Unknown)?.reason),
                    sample != null && FcmRecoveryPolicy.qualified(sample, elapsed()),
                    sample != null && FcmRecoveryPolicy.connected(sample, elapsed()))
            }
            override suspend fun request(binding: String, authorizeDispatch: () -> Boolean) =
                request(FcmRecoveryOperation.SERVICE_ABSENT_SEND, binding, authorizeDispatch)
            override suspend fun request(operation: FcmRecoveryOperation, binding: String,
                authorizeDispatch: () -> Boolean): FcmRecoveryResult {
                val ticket = owner.admit("fcm:${operation.name}:$binding")
                    ?: return FcmRecoveryResult(nextId(), FcmRecoveryStatus.NOT_SENT, FcmRecoveryReason.BUSY, operation)
                val flight = Flight(DaemonProcessExecutionFence(owner, ticket))
                synchronized(flights) { flights += flight }; cycleFlights += flight
                gate.install(ticket) {
                    this@DaemonFcmProtection.authorized(pinned) && shell.usableNetwork() &&
                        parent.read().let { it.admits(null, true) && it.recoveryBinding == binding } &&
                        recovery.read().let { it.configured && it.binding == binding && it.consumed(operation) } &&
                        (operation != FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS || shell.disconnectedNetwork())
                }.use {
                    return shell.recover(nextId(), guardian.identity.uid, binding, authorizeDispatch,
                        { !memoryOwnerCurrent(pinned) }, operation, flight.fence)
                }
            }
            override fun record(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample?,
                authorized: Boolean, result: FcmRecoveryResult?, unknown: GmsUnknown?) {
                if (action == FcmRecoveryEventAction.OBSERVATION) {
                    val key = "${sample?.gcmService?.state}|${sample?.gcmService?.completeness}|${sample?.observation}|$authorized|$unknown"
                    if (key == lastObservationKey) return
                    lastObservationKey = key
                }
                recovery.append(event(action, sample, authorized, result, unknown))
            }
            override fun operationRecord(action: FcmRecoveryEventAction, operation: FcmRecoveryOperation,
                sample: GmsConnectionObservation.Sample, authorized: Boolean, result: FcmRecoveryResult?) {
                recovery.append(event(action, sample, authorized, result).copy(operation = operation))
            }
            override fun requestStarted(event: FcmRecoveryEvent, correlation: String) {
                invalidateDiagnosticReadiness()
                episodes.requestStarted(event, correlation)
            }
            override fun requestCompleted(event: FcmRecoveryEvent, correlation: String) {
                if (event.status == FcmRecoveryStatus.UNKNOWN) invalidateDiagnosticReadiness()
                episodes.requestCompleted(event, correlation)
            }
            override fun bindingChanged(sample: GmsConnectionObservation.Sample, saved: FcmRecoverySnapshot) = Unit
            override fun finished(saved: FcmRecoverySnapshot, result: FcmRecoveryResult, connected: Boolean) = Unit
        }).run()
        // Complete only after production-cycle result/readback/rearm bookkeeping, never at runner return.
        cycleFlights.forEach { it.bookkeepingDone = true }
        reap()
        if (synchronized(flights) { flights.isNotEmpty() }) {
            invalidateDiagnosticReadiness(); return result(DaemonFcmCycleStatus.PENDING)
        }
        val pulse = heartbeatCycle(pinned)
        return DaemonFcmCycle(if (synchronized(flights) { flights.isNotEmpty() })
            DaemonFcmCycleStatus.PENDING else DaemonFcmCycleStatus.CHECKED, observation, pulse)
    }

    private fun heartbeatCycle(pinned: DaemonControlRecord): GmsHeartbeatResult? {
        var bundled = parent.read()
        var derived: HeartbeatSnapshot? = null
        val baseline = heartbeat.read().let { original ->
            val sample = observation as? GmsConnectionObservation.Sample
            val changed = sample != null && original?.bindingDigest != gmsHeartbeatBindingDigest(sample)
            if (original != null && (changed || original.resumeBlock == HeartbeatResumeBlock.SECURITY_FAILURE &&
                original.lastStatus == GmsHeartbeatStatus.NOT_SENT && original.lastReason == GmsHeartbeatReason.SESSION_REVOKED)) {
                revokeRetained()
                if (sample != null && authorized(pinned))
                    bundledHeartbeat.renewRevoked(pinned, sample, ::closedHeartbeatProof)?.also { bundled = parent.read() }
                        ?: heartbeat.read() ?: original else heartbeat.read() ?: original
            } else original
        }
        val cleanOff = baseline != null && !baseline.configured && baseline.pause == null &&
            baseline.remoteSessionId == null && baseline.pendingArm == null && baseline.pendingAttempt == null &&
            baseline.resumeBlock == null && baseline.lastStatus != GmsHeartbeatStatus.UNKNOWN
        var saved = if (baseline == null || cleanOff) run {
            val sample = observation as? GmsConnectionObservation.Sample ?: return null
            if (!authorized(pinned) || !bundled.admits(sample, false) ||
                GmsHeartbeatPolicy.rejection(sample, elapsed()) != null ||
                !FcmRecoveryPolicy.connected(sample, elapsed())) return null
            derived = bundledHeartbeat.derive(pinned, sample)
            val child = derived ?: return null
            if (!authorized(pinned) || heartbeat.read() != child) return null
            child
        } else requireNotNull(baseline)
        val epoch = saved.durableConsent?.epoch ?: return null
        // This guard precedes EVERY endpoint/provenance alternative: old v2 is same-boot only.
        if (saved.callerIdentity?.kernelBootId != bootId && saved.durableConsent?.version != 3) {
            revokeRetained(); return null
        }
        if (!bundled.admits(null, false, epoch) || saved.bindingDigest != bundled.installationBinding ||
            !saved.configured || saved.schemaVersion != 3 || saved.durableConsent?.version !in 2..3 ||
            saved.resumeBlock != null || saved.pendingArm != null || saved.pendingAttempt != null ||
            saved.currentEpochUnknown() || heartbeatStorage.coldBarrier() != null) { revokeRetained(); return null }
        if (sessionRecord != null && sessionRecord != pinned) {
            revokeRetained()
            if (session != null) return null // The actual original endpoint must acknowledge closure first.
            saved = heartbeat.read() ?: return null
        }
        if (session == null) {
            // A remote app endpoint is never replaced while its old process may still own it.
            val proof = handoff.closed(pinned, saved)
            val original = saved
            val originalDigest = DaemonProtectionRootSnapshot.digest(requireNotNull(heartbeatBytes()))
            val handoffClosed = proof?.matches(pinned, saved, originalDigest) == true
            val locallyClosed = closedEndpoint == saved
            val daemonOwned = provenance.read()?.let(DaemonHeartbeatOwnerCodec::decode)?.let {
                it.record == pinned && it.epoch == epoch && it.generation == saved.generation &&
                    it.binding == saved.bindingDigest && it.heartbeatBytesDigest == daemonHeartbeatOwnerDigest(saved)
            } == true
            val firstDerived = derived == saved && saved.pause == HeartbeatPause.WAITING_HOST && saved.remoteSessionId == null
            if (!handoffClosed && !locallyClosed && !daemonOwned && !firstDerived &&
                !shell.previousHeartbeatOwnerGone(saved) { !memoryOwnerCurrent(pinned) }) return null
            if (!authorized(pinned) || heartbeat.read() != saved) return null
            if (saved.pause != HeartbeatPause.WAITING_HOST || saved.remoteSessionId != null) {
                saved = heartbeatTransaction {
                    heartbeat.reconstruct()?.also { persistProvenance(pinned, it) }
                } ?: return null
            }
            if (saved.pause != HeartbeatPause.WAITING_HOST || saved.remoteSessionId != null) return null
            val fresh = shell.read(nextId()) { !memoryOwnerCurrent(pinned) }
            if (!authorized(pinned) || fresh !is GmsConnectionObservation.Sample ||
                !parent.read().admits(fresh, false, epoch) || GmsHeartbeatPolicy.rejection(fresh, elapsed()) != null) return null
            if (handoffClosed && proof?.consume(pinned, original, originalDigest) != true) return null
            val operation = UUID.randomUUID().toString()
            val pending = heartbeatTransaction {
                val prepared = heartbeat.prepareResume(saved, epoch, saved.callerIdentity) ?: return@heartbeatTransaction null
                heartbeat.beginArm(prepared.generation, operation)
            } ?: return null
            closedEndpoint = null
            if (!authorized(pinned) || !parent.read().admits(fresh, false, epoch)) return null
            val armed = shell.arm(nextId(), guardian.identity.uid, pending.generation,
                requireNotNull(pending.bindingDigest), lifetime(pinned)) { !memoryOwnerCurrent(pinned) }
            val acquired = armed.session
            if (acquired == null) {
                val uncertain = armed.reason in setOf(GmsAutoArmReason.TIMED_OUT, GmsAutoArmReason.BRIDGE_UNAVAILABLE,
                    GmsAutoArmReason.MALFORMED_RESPONSE)
                heartbeatTransaction {
                    heartbeat.block(pending.generation, if (uncertain) HeartbeatResumeBlock.UNCERTAIN_OWNER else HeartbeatResumeBlock.SECURITY_FAILURE)
                    if (!uncertain) heartbeat.armRejected(pending.generation)?.let { heartbeat.settleArm(it, operation) }
                }
                return null
            }
            session = acquired; sessionRecord = pinned; sessionRevokeAcknowledged = false
            check(acquired.generation == pending.generation && acquired.bindingDigest == pending.bindingDigest)
            if (!authorized(pinned)) { revokeRetained(); return null }
            heartbeatTransaction {
                val value = heartbeat.armed(acquired.generation, acquired.id) ?: return@heartbeatTransaction null
                heartbeat.settleArm(value, operation)
                persistProvenance(pinned, value)
                value
            } ?: return null
            deadline = HeartbeatDeadline().apply { reset(elapsed()) }
            return null
        }
        val active = requireNotNull(session)
        if (saved.remoteSessionId != active.id || saved.generation != active.generation || saved.pause != null) {
            revokeRetained(); return null
        }
        val clock = deadline ?: return null
        if (clock.remaining(elapsed()) > 0) return null
        if (!authorized(pinned)) { revokeRetained(); return null }
        val ticket = owner.admit("heartbeat:$epoch:${active.bindingDigest}") ?: return null
        val flight = Flight(DaemonProcessExecutionFence(owner, ticket))
        synchronized(flights) { flights += flight }
        val attempt = UUID.randomUUID().toString()
        var runnerEntered = false
        try {
            val pending = heartbeatTransaction { heartbeat.beginAttempt(active.generation, attempt) } ?: return null
            val result = gate.install(ticket) {
                authorized(pinned) && parent.read().admits(null, false, epoch) &&
                    heartbeat.read().let { it == pending && it.durableConsent?.epoch == epoch &&
                        it.bindingDigest == active.bindingDigest && it.pendingAttempt == attempt } &&
                    heartbeatStorage.coldBarrier() == HeartbeatResumeBlock.LEFTOVER_PENDING
            }.use {
                runnerEntered = true
                shell.heartbeat(nextId(), guardian.identity.uid, active, attempt, { !memoryOwnerCurrent(pinned) }, flight.fence)
            }
            heartbeatTransaction {
                val value = heartbeat.complete(active.generation, attempt, result)
                if (value != null) heartbeat.settleAttempt(value, attempt, result)
                if (value != null && !heartbeatResultNeedsConfirmation(result)) persistProvenance(pinned, value)
                if (value != null && heartbeatResultNeedsConfirmation(result)) heartbeat.block(value.generation,
                    if (result.status == GmsHeartbeatStatus.UNKNOWN) HeartbeatResumeBlock.UNKNOWN_RESULT else HeartbeatResumeBlock.SECURITY_FAILURE)
                value
            }
            if (heartbeatResultNeedsConfirmation(result)) {
                revokeRetained()
            }
            flight.bookkeepingDone = true
            clock.reset(elapsed())
            return result
        } finally {
            if (!runnerEntered) {
                flight.fence.workerSettled(PrivilegedExecutionSettlement.NOT_STARTED)
                // No runner was invoked; an existing pending marker remains a sticky no-replay barrier.
                flight.bookkeepingDone = true
            }
            reap()
        }
    }

    private fun memoryOwnerCurrent(pinned: DaemonControlRecord) = !closed.get() && !failed &&
        owner.isDispatchReady() && owner.snapshot() == pinned
    private fun sameOwner(pinned: DaemonControlRecord) = memoryOwnerCurrent(pinned) &&
        authorityDigest() == pinned.authorityDigest
    private fun authorized(pinned: DaemonControlRecord): Boolean {
        if (!sameOwner(pinned)) return false
        val state = consent.readSnapshot() ?: return false
        if (state.storageBlocked || state.mode != ProtectionConsentMode.ACTIVE) return false
        val enabledAt = state.masterEnabledAtMillis ?: state.consents().minOfOrNull { it.consentedAtMillis } ?: return false
        return parent.read().admits(null, true) && environment.read(guardian, bootId, enabledAt, closed::get) ==
            DaemonOwnerEnvironmentStatus.READY && sameOwner(pinned)
    }
    private fun event(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample? = null,
        allowed: Boolean = false, result: FcmRecoveryResult? = null, unknown: GmsUnknown? = null) =
        FcmRecoveryEvent(wall(), elapsed(), action, sample?.gcmService?.state ?: GcmConnectionState.UNKNOWN,
            sample?.gcmService?.completeness ?: SourceCompleteness.UNAVAILABLE, sample?.observation ?: SocketObservation.UNKNOWN,
            allowed, result?.status, result?.reason, observationUnknownReason = unknown)
    private fun nextId() = Math.addExact(requestId, 1).also { requestId = it }
    private fun result(status: DaemonFcmCycleStatus) = DaemonFcmCycle(status, observation)
    private fun reap() = synchronized(flights) { flights.removeAll { it.bookkeepingDone && it.fence.completeWhenSafe() } }
    private fun revokeRetained() {
        val old = session ?: return
        if (!sessionRevokeAcknowledged) {
            if (!shell.revoke(guardian.identity.uid, old)) return
            sessionRevokeAcknowledged = true
        }
        // A native ACK closes the capability, but actual workers may still own its result/child.
        // Retain the exact session and its ACK until those workers and kernel flights have drained.
        reap()
        if (!shell.isQuiescent() || synchronized(flights) { flights.isNotEmpty() }) return
        // Revoke ACK alone cannot settle a running request or erase its pending/UNKNOWN marker.
        val saved = heartbeat.read()
        if (saved?.generation == old.generation && saved.remoteSessionId == old.id &&
            (saved.configured || saved.durableConsent == null && saved.lastStatus != GmsHeartbeatStatus.UNKNOWN) &&
            saved.pendingArm == null && saved.pendingAttempt == null && saved.resumeBlock == null &&
            !saved.currentEpochUnknown() && !heartbeat.unsettledOperation()) {
            // An exact in-memory closure can bridge unrelated control revisions while this daemon lives.
            // It also preserves that previously acquired native ownership across a clean close.
            val current = owner.snapshot()
            val previous = sessionRecord
            val preserve = saved.configured && current != null && previous != null && current.owner == previous.owner &&
                current.migrationId == previous.migrationId &&
                authorityDigest() == current.authorityDigest && parent.read().let {
                    it.configured && it.epoch == saved.durableConsent?.epoch && it.installationBinding == saved.bindingDigest }
            heartbeatTransaction {
                if (heartbeat.read() == saved) {
                    heartbeat.pause(saved.generation, HeartbeatPause.OFF_PENDING)
                    closedEndpoint = heartbeat.acknowledgeStop(saved.generation, clean = true)
                    if (preserve) closedEndpoint?.let { persistProvenance(requireNotNull(current), it) }
                }
            }
        }
        session = null; sessionRecord = null; deadline = null; sessionRevokeAcknowledged = false
    }
    /** No kernel/owner calls under this root lock: control commits acquire kernel then root. */
    private fun <T> heartbeatTransaction(action: () -> T): T = domains.heartbeatTransaction {
        provenance.write(null)
        action()
    }
    private fun persistProvenance(pinned: DaemonControlRecord, saved: HeartbeatSnapshot) {
        val clean = saved.pause == null || saved.pause == HeartbeatPause.WAITING_HOST && saved.remoteSessionId == null
        if (heartbeat.read() != saved || !clean ||
            saved.pendingArm != null || saved.pendingAttempt != null || saved.resumeBlock != null ||
            saved.currentEpochUnknown() || heartbeat.unsettledOperation()) return
        val proof = DaemonHeartbeatOwner(pinned, requireNotNull(saved.durableConsent).epoch,
            saved.generation, requireNotNull(saved.bindingDigest), daemonHeartbeatOwnerDigest(saved))
        provenance.write(DaemonHeartbeatOwnerCodec.encode(proof))
    }
    /** Native lifetime already rejects the old record; cleanup never holds the owner monitor. */
    fun controlCommitted() {
        invalidateDiagnosticReadiness()
        live?.set(false)
        controlRevokeRequested.set(true)
        if (busy.compareAndSet(false, true)) {
            try {
                if (controlRevokeRequested.getAndSet(false)) revokeRetained()
            } finally { busy.set(false) }
        }
    }
    /** Quiescence before constructing a new ON proposal, without permanently closing this engine. */
    fun isControlQuiescent(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        return try {
            reap(); revokeRetained()
            session == null && shell.isQuiescent() && synchronized(flights) { flights.isEmpty() }
        } finally { busy.set(false) }
    }
    /** Called only by the authenticated explicit control actor after it closes admission. */
    fun confirmationClosure(snapshot: DaemonProtectionRootSnapshot): DaemonHeartbeatConfirmationClosure? {
        if (!busy.compareAndSet(false, true)) return null
        return try { closedHeartbeatProof(snapshot) } finally { busy.set(false) }
    }
    /** Both callers hold the FCM cycle/control busy token through closure and the consuming CAS. */
    private fun closedHeartbeatProof(snapshot: DaemonProtectionRootSnapshot): DaemonHeartbeatConfirmationClosure? {
        val saved = daemonHeartbeatConfirmationCandidate(snapshot, allowSettledIdentityChange = true)
        if (saved == null) return null
        reap()
        fun stable() = !closed.get() && !failed && owner.snapshot() == snapshot.control &&
            heartbeat.read() == saved && session == null && shell.isQuiescent() &&
            synchronized(flights) { flights.isEmpty() }
        if (!stable()) return null
        val endpoint = saved.remoteSessionId?.let { GmsAutoSession(it, saved.generation, requireNotNull(saved.bindingDigest)) }
        val closedHere = closedEndpoint == saved || endpoint?.let { shell.revoke(guardian.identity.uid, it) } == true
        // The owner lifetime lease excludes an older daemon. A fresh native runner PLUS the
        // independently qualified historical App's death excludes both possible old owners.
        // Never let old-App absence override an existing/mismatched native capability.
        val closedCold = !closedHere && shell.freshHeartbeatEndpoint() &&
            shell.previousHeartbeatOwnerGone(saved) { closed.get() || owner.snapshot() != snapshot.control } &&
            shell.freshHeartbeatEndpoint()
        if ((!closedHere && !closedCold) || !stable()) return null
        closedEndpoint = saved
        return DaemonHeartbeatConfirmationClosure(snapshot.control,
            DaemonProtectionRootSnapshot.digest(requireNotNull(snapshot.bytes(DaemonDomainRole.HEARTBEAT))))
    }
    /** Host must retain its storage lifetime lease until this returns true after close. */
    fun isQuiescent(): Boolean {
        if (!closed.get() || !busy.compareAndSet(false, true)) return false
        return try {
            reap()
            revokeRetained()
            session == null && shell.isQuiescent() && synchronized(flights) { flights.isEmpty() }
        } finally { busy.set(false) }
    }
    override fun close() {
        invalidateDiagnosticReadiness()
        closed.set(true); live?.set(false)
        shell.close()
        if (busy.compareAndSet(false, true)) {
            try { runCatching { revokeRetained() } } finally { busy.set(false) }
        }
    }
    companion object { const val POLL_MILLIS = 30_000L }
}
