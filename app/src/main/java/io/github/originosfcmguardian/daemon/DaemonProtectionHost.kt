package io.github.originosfcmguardian.daemon

import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryStoreCodec
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.privileged.*
import io.github.originosfcmguardian.recovery.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DaemonHostStatus(val owner: DaemonOwnerKey, val record: DaemonControlRecord?,
    val status: DaemonOwnerStatus, val dataRevision: Long?, val closing: Boolean,
    val leaseReleased: Boolean, val missingPreconditions: List<String>,
    val sessionStartedElapsed: Long? = null, val sessionStartedAtMillis: Long? = null,
    val lastCheckedAtMillis: Long? = null, val latestGmsObservation: GmsConnectionObservation? = null,
    val needsReconciliation: Boolean = false,
    val recoveryReadiness: DaemonRecoveryReadinessEvidence? = null)
internal data class DaemonSnapshotHeader(val token: String, val size: Int, val sha256: String,
    val dataRevision: Long, val control: DaemonControlRecord)

/**
 * Actual shell-process owner. The Binder service captures and freshly qualifies EVERY caller before
 * entering this object; caller evidence and historical process identities are never wire fields.
 * Root, lifetime lease, approval principal and boot are fixed for the life of this host. Ordinary
 * Binder disconnection has no consent semantics. The lease is retained through actual worker exit.
 */
internal class DaemonProtectionHost(
    private val ownerKey: DaemonOwnerKey,
    guardian: DaemonGuardianInstallation,
    private val bootId: String,
    migrationId: String,
    private val root: DaemonProtectionRoot,
    private val ownerLease: (() -> Unit) -> Boolean,
    private val releaseLease: () -> Unit,
    private val environmentReadPort: DaemonOwnerEnvironmentReadPort = SystemDaemonOwnerEnvironmentReadPort(),
    private val stoppedShell: DaemonStoppedShellPort = SystemDaemonStoppedShellPort(),
    private val fcmShell: DaemonFcmShellPort = SystemDaemonFcmShellPort(),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    private val processId: Int = Process.myPid(),
    private val fingerprint: () -> String = { Build.FINGERPRINT },
    private val commandClock: (() -> BudgetClock)? = null,
    private val recoveryReady: () -> Boolean = { SystemGmsConnectionReadPort().recoverySenderQualified() },
    private val handoff: DaemonHeartbeatHandoffPort = DaemonHeartbeatHandoffPort { _, _ -> null },
    private val startScheduler: Boolean = true,
    private val diagnostics: DaemonDiagnosticsRuntime? = null,
) : AutoCloseable {
    private val guardian = guardian.copy(identity = guardian.identity.copy(signerSha256 =
        java.util.Collections.unmodifiableList(guardian.identity.signerSha256.toList())))
    private val sessionStartedElapsed = elapsed()
    private val sessionStartedAtMillis = wall()
    private val activeSession = AtomicReference<Pair<Long, Long>?>()
    private val environment = DaemonOwnerEnvironmentProbe(environmentReadPort)
    private val attestations = DaemonPointerAttestationRegistry(ownerKey, guardian)
    private val stoppedGate = DaemonStoppedWriteGate(elapsed)
    private val fcmGate = DaemonFcmWriteGate(elapsed, 8_000)
    private val closing = AtomicBoolean()
    private val released = AtomicBoolean()
    private val closeLock = Any()
    private var closingRuntime: Runtime? = null
    private var stoppedCloseSucceeded = false
    private var fcmCloseSucceeded = false
    private val pollBusy = AtomicBoolean()
    private val applyBusy = AtomicBoolean()
    private val controlReads = AtomicInteger()
    private val clockReadBusy = AtomicBoolean()
    private val controlChildren = java.util.concurrent.ConcurrentLinkedQueue<java.lang.Process>()
    @Volatile private var lastCheckedAtMillis: Long? = null
    @Volatile private var diagnosticsStoppedCheckedAtMillis: Long? = null
    @Volatile private var diagnosticsFcmCheckedAtMillis: Long? = null
    @Volatile private var latestGmsObservation: GmsConnectionObservation? = null
    private enum class Lifecycle { READY, WAITING, REVOKED }
    @Volatile private var lifecycle = Lifecycle.WAITING
    private val ids = AtomicLong()
    private val historicalCaller = AtomicReference<GmsHeartbeatCallerIdentity?>()
    private val runtime = AtomicReference<Runtime?>()
    private val initialization = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val issuedPlan = AtomicReference<ByteArray?>()
    private data class ImportUpload(val sessionId: String, val digest: String)
    private data class CompletedImport(val prepared: DaemonControlRecord, val digest: String,
        val heartbeatDigest: String?, val actualCaller: GmsHeartbeatCallerIdentity?)
    private val importUpload = AtomicReference<ImportUpload?>()
    private val completedImport = AtomicReference<CompletedImport?>()
    private val importHandoff = AtomicReference<DaemonHeartbeatHandoffProof?>()
    private val migrationChallengeLock = Any()
    private var challengeEpoch = 0L
    private var issuedChallenge: DaemonPointerChallenge? = null
    private class Pinned(val header: DaemonSnapshotHeader, val bytes: ByteArray)
    private val pinned = AtomicReference<Pinned?>()
    private val planner = DaemonControlCommandPlanner(DaemonControlTargetValidationPort { identity, recipient ->
        val observed = stoppedShell.observe(ids.incrementAndGet(), identity, recipient, closing::get)
        when (observed) {
            is StoppedTargetObservation.Known -> if (observed.identity == identity && observed.hasVerifiedPackageUid &&
                (!recipient || observed.qualifiedRecipient)) DaemonControlTargetValidation.MATCH
                else DaemonControlTargetValidation.INVALID
            is StoppedTargetObservation.Unknown -> if (observed.reason == StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED)
                DaemonControlTargetValidation.INVALID else DaemonControlTargetValidation.UNAVAILABLE
        }
    })
    private inner class Runtime(val owner: DaemonOwnerControl, val control: DaemonRootControlPort,
        val stopped: DaemonStoppedProtection, val fcm: DaemonFcmProtection)
    private val imports = DaemonImportProtocol(ownerKey, migrationId, object : DaemonImportPort {
        override fun authenticateFresh(caller: DaemonCallerEvidence) = authenticatedFresh(caller)
        override fun initializeImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
            check(!closing.get())
            if (runtime.get() == null) {
                root.initializePreparedImport(snapshot, validateFresh)
                initializeRuntime()
            } else verifyPreparedImport(snapshot, validateFresh)
        }
        override fun verifyImported(snapshot: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) =
            verifyPreparedImport(snapshot, validateFresh)
    })

    init {
        // inspect cannot grant authority; constructing the kernel still leaves ACTIVE UNRECONCILED.
        if (runCatching { root.inspectForReconciliation() }.isSuccess) initializeRuntime()
        if (startScheduler) scope.launch {
            while (!released.get()) {
                if (closing.get()) reapClosed() else pollOnce()
                if (!released.get()) delay(POLL_MILLIS)
            }
        }
    }

    private fun initializeRuntime() = synchronized(initialization) {
        if (runtime.get() != null) return@synchronized
        val snapshot = root.snapshot()
        check(snapshot.control.owner == ownerKey)
        val control = DaemonRootControlPort(root, ownerKey, attestations, ::validateTrustedPlan, ownerLease)
        val owner = DaemonOwnerControl(ownerKey, guardian, control,
            DaemonProtectionWriteRouter(stoppedGate, fcmGate), elapsed, maximumEvidenceAgeMillis = 8_000)
        val domains = DaemonDomainStores(root)
        val stopped = DaemonStoppedProtection(owner, stoppedGate, ProtectionConsentStore(domains.authority),
            TargetSelectionStore(domains.selection), StoppedRecoveryJournal(domains.recoveryJournal),
            RecoveryCountsStore(domains.recoveryCounts), root::authorityDigest, environment, guardian, bootId,
            stoppedShell, elapsed, wall)
        val fcm = DaemonFcmProtection(owner, fcmGate, domains, root::authorityDigest, environment, guardian,
            bootId, elapsed, wall, fcmShell, processId, handoff = DaemonHeartbeatHandoffPort { record, heartbeat ->
                importHandoff.get()?.takeIf { it.matches(record, heartbeat, it.heartbeatBytesDigest) }
                    ?: handoff.closed(record, heartbeat)
            },
            bundledHeartbeat = DaemonBundledHeartbeatDerivation(owner, root, environment, guardian, bootId, elapsed),
            sessionStartedElapsed = sessionStartedElapsed)
        runtime.set(Runtime(owner, control, stopped, fcm))
        if (closing.get()) closeBranches()
    }

    fun beginImport(caller: DaemonCallerEvidence, size: Int, sha256: String): String {
        requireCaller(caller)
        if (runtime.get() != null) {
            val existing = root.snapshot()
            check(DaemonPreparedImportVerifier.matches(existing, existing)) { "Import root is not initial PREPARED data" }
        }
        return imports.beginImport(caller, size, sha256).also { importUpload.set(ImportUpload(it, sha256)) }
    }
    fun importOffset(caller: DaemonCallerEvidence, sessionId: String): Int {
        requireCaller(caller); return imports.importOffset(caller, sessionId)
    }
    fun appendImport(caller: DaemonCallerEvidence, sessionId: String, offset: Int, chunk: ByteArray): Int {
        requireCaller(caller); return imports.appendImport(caller, sessionId, offset, chunk)
    }
    fun finishImport(caller: DaemonCallerEvidence, sessionId: String,
        qualifiedHistoricalCaller: GmsHeartbeatCallerIdentity? = null): DaemonControlRecord {
        requireCaller(caller)
        captureHistorical(caller, qualifiedHistoricalCaller)
        val upload = requireNotNull(importUpload.get()).also { require(it.sessionId == sessionId) }
        val prepared = imports.finishImport(caller, sessionId)
        val snapshot = root.snapshot()
        check(snapshot.control == prepared && DaemonPreparedImportVerifier.matches(snapshot, snapshot))
        val heartbeat = snapshot.bytes(DaemonDomainRole.HEARTBEAT)
        completedImport.set(CompletedImport(prepared, upload.digest,
            heartbeat?.let { DaemonProtectionRootSnapshot.digest(it) }, qualifiedHistoricalCaller))
        return prepared
    }

    /** Reupload after process loss is evidence of the same inert bytes, never a second install. */
    private fun verifyPreparedImport(expected: DaemonProtectionRootSnapshot, validateFresh: () -> Boolean) {
        check(!closing.get() && validateFresh()) { "Import caller authentication failed" }
        check(DaemonPreparedImportVerifier.matches(root.snapshot(), expected)) { "Prepared import differs" }
        // Fresh identity IO holds no root, kernel or upload monitor. Recheck the root afterward.
        check(!closing.get() && validateFresh()) { "Import caller authentication failed" }
        check(DaemonPreparedImportVerifier.matches(root.snapshot(), expected)) { "Prepared import changed" }
    }

    /** Only the trusted typed planner constructs the plan; callers cannot supply raw patches. */
    fun stage(caller: DaemonCallerEvidence, expected: DaemonControlRecord, requestId: String,
        command: DaemonProtectionCommand, qualifiedHistoricalCaller: GmsHeartbeatCallerIdentity? = null): DaemonPointerChallenge? =
        stageInternal(caller, expected, requestId, command, qualifiedHistoricalCaller)

    fun stageFcmOn(caller: DaemonCallerEvidence, expected: DaemonControlRecord, requestId: String,
        qualifiedHistoricalCaller: GmsHeartbeatCallerIdentity): DaemonPointerChallenge? =
        stageInternal(caller, expected, requestId, null, qualifiedHistoricalCaller)

    /** A null command is the fixed FCM-on request; all observation material comes from this host. */
    private fun stageInternal(caller: DaemonCallerEvidence, expected: DaemonControlRecord, requestId: String,
        command: DaemonProtectionCommand?, qualifiedHistoricalCaller: GmsHeartbeatCallerIdentity?): DaemonPointerChallenge? {
        requireCaller(caller)
        val serializePrepared = expected.mode == DaemonControlMode.PREPARED
        if (serializePrepared && !applyBusy.compareAndSet(false, true)) return null
        controlReads.incrementAndGet()
        try {
            if (closing.get()) return null
            val live = checkNotNull(runtime.get())
            val challengeVersion = invalidateMigrationChallenge(live.owner.snapshot())
            captureHistorical(caller, qualifiedHistoricalCaller)
            if (!closeAdmission(live, caller, expected)) return null
            live.fcm.controlCommitted()
            if ((command == null || command is DaemonProtectionCommand.FcmOn) && !live.fcm.isControlQuiescent()) return null
            val snapshot = root.snapshot()
            if (snapshot.control != expected) return null
            val clock = readCommandClock()
            val enabling = command != DaemonProtectionCommand.MasterOff && command != DaemonProtectionCommand.FcmOff &&
                command !is DaemonProtectionCommand.TargetOff
            if (enabling) {
                // Only an explicit new master confirmation supersedes historical user-stop.
                // Reconcile/resume and all other enabling commands keep the durable old epoch.
                val enabledAt = if (command == DaemonProtectionCommand.MasterOn) clock.epoch
                    else DaemonHeartbeatDerivation.masterEnabledAt(snapshot) ?: clock.epoch
                if (environment.read(guardian, bootId, enabledAt, closing::get) != DaemonOwnerEnvironmentStatus.READY) return null
            }
            val actualCommand = if (command == null || command is DaemonProtectionCommand.FcmOn) {
                val historical = qualifiedHistoricalCaller ?: return null
                val sample = fcmShell.read(ids.incrementAndGet(), closing::get) as? GmsConnectionObservation.Sample ?: return null
                DaemonProtectionCommand.FcmOn(sample, historical)
            } else command
            val ready = if (actualCommand is DaemonProtectionCommand.FcmOn)
                runCatching(recoveryReady).getOrDefault(false) else false
            val context = DaemonControlCommandContext(clock, fingerprint(), elapsed(), explicitConfirmation = true,
                fcmRecoveryReady = ready, fcmHeartbeatClosure = if (actualCommand is DaemonProtectionCommand.FcmOn)
                    live.fcm.confirmationClosure(snapshot) else null)
            if (actualCommand == DaemonProtectionCommand.MasterOff && expected.mode == DaemonControlMode.PREPARED)
                return stagePreparedMasterOff(caller, live, snapshot, requestId, context, challengeVersion)
            val ordinary = planner.plan(snapshot, expected, requestId, actualCommand, context) ?: return null
            val grant = if (actualCommand == DaemonProtectionCommand.Activate || actualCommand is DaemonProtectionCommand.FcmOn)
                historicalCaller.get()?.let { historical ->
                    DaemonHeartbeatDerivation.prepare(projectDaemonControl(snapshot, ordinary), ordinary.next, historical)
                } else null
            if (actualCommand is DaemonProtectionCommand.FcmOn && grant == null) return null
            val plan = if (grant == null) ordinary else DaemonControlTransitionPlan(ordinary.requestId,
                ordinary.expected, ordinary.next, ordinary.transition, ordinary.patch(), nativeHeartbeatDerivation = grant)
            issuedPlan.set(DaemonControlProposalCodec.encode(plan))
            return live.control.stage(caller, plan) { closeAdmission(live, caller, expected) }
                .also { rememberMigrationChallenge(challengeVersion, it) }
        } finally {
            controlReads.decrementAndGet()
            if (serializePrepared) applyBusy.set(false)
        }
    }

    /** Replace an interrupted initial ACTIVATE atomically; the imported selections stay intact. */
    private fun stagePreparedMasterOff(caller: DaemonCallerEvidence, live: Runtime,
        before: DaemonProtectionRootSnapshot, requestId: String, context: DaemonControlCommandContext,
        challengeVersion: Long): DaemonPointerChallenge? {
        if (before.control.mode != DaemonControlMode.PREPARED || before.control.revision != 1L ||
            before.halt != null || before.receipts.isNotEmpty() ||
            before.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER) != null ||
            before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION) != null) return null
        val previousBytes = before.bytes(DaemonDomainRole.CONTROL_PROPOSAL)
        val scratch = DaemonProtectionRootSnapshot(before.control,
            before.domains() + (DaemonDomainRole.CONTROL_PROPOSAL to null), dataRevision = before.dataRevision)
        val plan = planner.plan(scratch, before.control, requestId, DaemonProtectionCommand.MasterOff, context) ?: return null
        val proposal = DaemonControlProposalCodec.encode(plan)
        if (previousBytes != null) {
            val previous = runCatching { DaemonControlProposalCodec.decode(previousBytes) }.getOrNull() ?: return null
            if (!DaemonControlProposalCodec.encode(previous).contentEquals(previousBytes)) return null
            val sameStop = previousBytes.contentEquals(proposal)
            val initialActivate = previous.transition == DaemonControlTransition.ACTIVATE &&
                previous.expected == before.control &&
                previous.next == before.control.copy(revision = 2, mode = DaemonControlMode.ACTIVE) &&
                previous.patch().isEmpty()
            if (!sameStop && !initialActivate) return null
        }
        // No old pointer or endpoint closure proof can survive the durable negative replacement.
        attestations.invalidateCurrent()
        importHandoff.set(null)
        issuedPlan.set(proposal)
        if (previousBytes?.contentEquals(proposal) != true) root.transaction(before.dataRevision) { change ->
            check(change.control == before.control && change.halt == null)
            val current = change.bytes(DaemonDomainRole.CONTROL_PROPOSAL)
            check(if (previousBytes == null) current == null else current?.contentEquals(previousBytes) == true)
            change.put(DaemonDomainRole.CONTROL_PROPOSAL, proposal)
        }
        val saved = root.snapshot()
        check(saved.control == before.control && saved.halt == null &&
            saved.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.contentEquals(proposal) == true)
        return live.control.resumeStaged(caller, plan.next) { closeAdmission(live, caller, before.control) }
            .also { rememberMigrationChallenge(challengeVersion, it) }
    }

    fun resumeStaged(caller: DaemonCallerEvidence, pointerRecord: DaemonControlRecord): DaemonPointerChallenge? {
        requireCaller(caller)
        val live = checkNotNull(runtime.get())
        val challengeVersion = invalidateMigrationChallenge(live.owner.snapshot())
        return live.control.resumeStaged(caller, pointerRecord) {
            val expected = live.owner.snapshot() ?: return@resumeStaged false
            closeAdmission(live, caller, expected)
        }.also { rememberMigrationChallenge(challengeVersion, it) }
    }
    fun currentPointerChallenge(caller: DaemonCallerEvidence): DaemonPointerChallenge? {
        requireCaller(caller)
        val live = checkNotNull(runtime.get())
        val challengeVersion = invalidateMigrationChallenge(live.owner.snapshot())
        return live.control.challengeCurrentPointer(caller) {
            val expected = live.owner.snapshot() ?: return@challengeCurrentPointer false
            closeAdmission(live, caller, expected)
        }.also { rememberMigrationChallenge(challengeVersion, it) }
    }
    fun attest(caller: DaemonCallerEvidence, value: DaemonAppPointerAttestation): Boolean {
        requireCaller(caller); return checkNotNull(runtime.get()).control.attest(caller, value)
    }
    /**
     * Trusted App drain attestation after original endpoint revoke ACK and actual workers/FIFO
     * drain. This is not independent shell proof of App execution. The service supplies the fresh
     * real Binder UID/PID qualification; none of that identity is decoded from App drain fields.
     * The ordinary ACTIVATE nonce is reused, and no proof is persisted/reconstructed on restart.
     */
    fun attestMigrationDrain(caller: DaemonCallerEvidence, challenge: DaemonPointerChallenge,
        importDigest: String, heartbeatDigest: String,
        qualifiedActualAppCaller: GmsHeartbeatCallerIdentity): Boolean {
        requireCaller(caller)
        val imported = completedImport.get() ?: return false
        if (imported.digest != importDigest || imported.actualCaller != qualifiedActualAppCaller ||
            qualifiedActualAppCaller.uid != caller.realUid || qualifiedActualAppCaller.kernelBootId != bootId ||
            challenge.purpose != DaemonPointerPurpose.TRANSITION) return false
        val snapshot = root.snapshot()
        if (snapshot.control != imported.prepared || snapshot.control.mode != DaemonControlMode.PREPARED ||
            snapshot.halt != null || listOf(DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED,
                DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.HEARTBEAT_HALT,
                DaemonDomainRole.HEARTBEAT_PENDING, DaemonDomainRole.FCM_PROTECTION_HALT,
                DaemonDomainRole.FCM_RECOVERY_HALT).any { snapshot.bytes(it) != null }) return false
        val request = requestForChallenge(caller, challenge) ?: return false
        val intent = request.intent as? DaemonControlIntent.Activate ?: return false
        val target = intent.pointer.record
        if (target != snapshot.control.copy(revision = snapshot.control.revision + 1, mode = DaemonControlMode.ACTIVE) ||
            intent.pointer != challenge.pointer) return false
        val raw = snapshot.bytes(DaemonDomainRole.HEARTBEAT)
        val proof = if (raw == null) {
            if (imported.heartbeatDigest != null) return false
            null // An absent child needs no endpoint handoff; native derivation has its own gates.
        } else {
            val digest = DaemonProtectionRootSnapshot.digest(raw)
            if (digest != heartbeatDigest || digest != imported.heartbeatDigest ||
                snapshot.bytes(DaemonDomainRole.HEARTBEAT_INITIALIZED)?.contentEquals(byteArrayOf(1)) != true) return false
            val heartbeat = HeartbeatSnapshotCodec.decode(raw)
            if (!heartbeat.configured || heartbeat.schemaVersion != 3 || heartbeat.durableConsent?.version !in 2..3 ||
                heartbeat.callerIdentity?.uid != guardian.identity.uid || heartbeat.generation == Long.MAX_VALUE ||
                heartbeat.pause != HeartbeatPause.WAITING_HOST ||
                heartbeat.remoteSessionId != null || heartbeat.pendingArm != null || heartbeat.pendingAttempt != null ||
                heartbeat.resumeBlock != null || heartbeat.currentEpochUnknown() || heartbeat.lastStatus == GmsHeartbeatStatus.UNKNOWN) return false
            DaemonHeartbeatHandoffProof(target, heartbeat, digest)
        }
        return synchronized(migrationChallengeLock) {
            if (closing.get() || issuedChallenge != challenge || completedImport.get() !== imported) false else {
                issuedChallenge = null // One drain attestation for this exact ACTIVATE challenge.
                importHandoff.set(proof)
                true
            }
        }
    }
    /** Metadata for the exact durable proposal, never a raw App-supplied plan or write authority. */
    fun requestForChallenge(caller: DaemonCallerEvidence, challenge: DaemonPointerChallenge): DaemonControlRequest? {
        requireCaller(caller)
        if (challenge.purpose != DaemonPointerPurpose.TRANSITION) return null
        val snapshot = root.snapshot()
        val bytes = snapshot.bytes(DaemonDomainRole.CONTROL_PROPOSAL) ?: return null
        val plan = DaemonControlProposalCodec.decode(bytes)
        if (plan.expected != snapshot.control || plan.next != challenge.pointer.record || plan.patchDigest != challenge.patchDigest) return null
        return plan.request(challenge.pointer)
    }
    fun apply(caller: DaemonCallerEvidence, request: DaemonControlRequest): DaemonControlReply {
        requireCaller(caller)
        if (!applyBusy.compareAndSet(false, true)) return DaemonControlReply.Rejected(DaemonControlReply.Reason.PENDING)
        return try {
            val live = checkNotNull(runtime.get())
            val before = root.snapshot()
            val reply = if (live.owner.status() == DaemonOwnerStatus.UNRECONCILED)
                live.owner.resumeAttestedTransition(caller, request) else live.owner.apply(caller, request)
            // The control commit and ACK have completed; cleanup/proof carry holds no owner lock.
            if (reply is DaemonControlReply.Acknowledged) {
                issuedPlan.set(null)
                val after = root.snapshot()
                if (after.control.mode == DaemonControlMode.OFF ||
                    DaemonHeartbeatDerivation.masterEnabledAt(before) != DaemonHeartbeatDerivation.masterEnabledAt(after))
                    activeSession.set(null)
                if (after.control == reply.receipt.committed) carryImportHandoff(before, after)
                live.fcm.controlCommitted()
            }
            reply
        } finally {
            applyBusy.set(false)
            if (closing.get()) reapClosed()
        }
    }
    fun query(caller: DaemonCallerEvidence, request: DaemonControlRequest): DaemonControlReply {
        requireCaller(caller); return checkNotNull(runtime.get()).owner.query(caller, request)
    }
    fun reconcile(caller: DaemonCallerEvidence, proof: DaemonReconcileProof,
        freshCaller: (() -> GmsHeartbeatCallerIdentity)? = null): Boolean {
        requireCaller(caller)
        val live = checkNotNull(runtime.get())
        val before = root.snapshot()
        val permit = before.bytes(DaemonDomainRole.HEARTBEAT_DERIVATION)?.let(DaemonHeartbeatDerivationPermitCodec::decode)
        val refresh = before.control.mode == DaemonControlMode.ACTIVE && before.control == proof.pointer.record &&
            permit != null && !permit.used && permit.record == before.control
        if (!refresh) return live.owner.reconcile(caller, proof)
        if (freshCaller == null || !applyBusy.compareAndSet(false, true)) return false
        if (!pollBusy.compareAndSet(false, true)) { applyBusy.set(false); return false }
        controlReads.incrementAndGet()
        try {
            if (!live.owner.reconcile(caller, proof)) return false
            val prepared = runCatching {
                val actual = freshCaller()
                captureHistorical(caller, actual)
                val pinned = root.snapshot()
                if (pinned.control != before.control) return@runCatching false
                val enabledAt = DaemonHeartbeatDerivation.masterEnabledAt(pinned) ?: return@runCatching false
                val observedAt = elapsed()
                if (environment.read(guardian, bootId, enabledAt, closing::get) != DaemonOwnerEnvironmentStatus.READY)
                    return@runCatching false
                val sample = fcmShell.read(ids.incrementAndGet()) { closing.get() || live.owner.snapshot() != pinned.control }
                    as? GmsConnectionObservation.Sample ?: return@runCatching false
                if (!live.owner.rebindBundledRecovery(root, pinned.control, pinned.dataRevision, sample, bootId, observedAt))
                    return@runCatching false
                val rebound = root.snapshot()
                live.owner.refreshUnusedHeartbeatPermit(root, caller, pinned.control, rebound.dataRevision,
                    actual, sample, bootId, observedAt)
            }.getOrDefault(false)
            // Failed preparation must remain visible to the next restore; ACTIVE alone is not ACK.
            if (!prepared) closeAdmission(live, caller, before.control)
            return prepared
        } finally {
            controlReads.decrementAndGet()
            pollBusy.set(false)
            applyBusy.set(false)
            if (closing.get()) reapClosed()
        }
    }
    fun appDisconnected(caller: DaemonCallerEvidence) {
        requireCaller(caller); runtime.get()?.owner?.appDisconnected()
    }
    /** Exact first-import ARM cleanup after actual drain; never arms or grants readiness here. */
    fun reconcileInitialHeartbeatArm(caller: DaemonCallerEvidence, expected: DaemonControlRecord,
        dataRevision: Long, originalPreparedRaw: ByteArray, importDigest: String): Boolean {
        requireCaller(caller)
        // Canonical decoding and hashing must finish outside poll/owner/root locks.
        val source = DaemonInitialArmReconciliation.capture(expected, originalPreparedRaw, importDigest) ?: return false
        if (!applyBusy.compareAndSet(false, true)) return false
        if (!pollBusy.compareAndSet(false, true)) { applyBusy.set(false); return false }
        controlReads.incrementAndGet()
        return try {
            val live = runtime.get() ?: return false
            val before = root.snapshot()
            if (before.control != expected || before.dataRevision != dataRevision ||
                live.owner.snapshot() != expected ||
                live.owner.status() !in setOf(DaemonOwnerStatus.ACTIVE, DaemonOwnerStatus.PENDING_RECOVERY) ||
                DaemonInitialArmReconciliation.project(before, source) == null) return false
            val value = before.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return false
            if (!live.stopped.isControlQuiescent() || !live.fcm.isControlQuiescent() ||
                !fcmShell.isQuiescent()) return false
            val enabledAt = DaemonHeartbeatDerivation.masterEnabledAt(source.prepared) ?: return false
            val observedAt = elapsed()
            if (!fcmShell.previousHeartbeatOwnerGone(value, closing::get) ||
                environment.read(guardian, bootId, enabledAt, closing::get) != DaemonOwnerEnvironmentStatus.READY ||
                closing.get()) return false
            val current = root.snapshot()
            if (current.control != expected || current.dataRevision != dataRevision) return false
            live.owner.reconcileInitialHeartbeatArm(root, caller, expected, dataRevision, source, observedAt) != null
        } catch (_: Exception) { false }
        finally {
            controlReads.decrementAndGet()
            pollBusy.set(false)
            applyBusy.set(false)
            if (closing.get()) reapClosed()
        }
    }
    internal fun isFullyReleased(): Boolean = released.get()
    internal fun isDiagnosticsFinished(): Boolean = runCatching { diagnostics?.isFinished() != false }.getOrDefault(true)
    /** User-requested read only; it cannot arm, recover, change consent or replace the poll sample. */
    fun readGms(caller: DaemonCallerEvidence, requestId: Long): GmsConnectionObservation {
        requireCaller(caller)
        require(requestId > 0)
        controlReads.incrementAndGet()
        return try { fcmShell.read(requestId, closing::get) } finally { controlReads.decrementAndGet() }
    }
    fun status(caller: DaemonCallerEvidence): DaemonHostStatus {
        requireCaller(caller)
        val live = runtime.get()
        val snapshot = if (live == null) null else runCatching { root.snapshot() }.getOrNull()
        val running = live?.owner?.isDispatchReady() == true && !closing.get() && lifecycle == Lifecycle.READY
        val fcmObservation = live?.fcm?.diagnosticObservation()
        val sample = fcmObservation?.observation as? GmsConnectionObservation.Sample
        val readiness = fcmObservation?.recoveryReadiness?.takeIf { evidence ->
            running && snapshot != null && sample != null && live?.owner?.snapshot() == snapshot.control &&
                evidence.isFreshFor(snapshot.control, sample, activeSession.get()?.first, elapsed())
        }
        return DaemonHostStatus(ownerKey, live?.owner?.snapshot(), live?.owner?.status() ?: DaemonOwnerStatus.UNRECONCILED,
            snapshot?.dataRevision, closing.get(), released.get(), buildList {
                if (live == null) add("PREPARED import missing")
                if (live != null && !live.owner.isDispatchReady()) add("fresh committed App pointer reconciliation or ACTIVE control missing")
                if (lifecycle != Lifecycle.READY) add("fresh independent owner environment not ready")
                if (snapshot?.bytes(DaemonDomainRole.HEARTBEAT) != null && snapshot.bytes(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER) == null)
                    add("heartbeat endpoint closure or native derivation not established")
                if (historicalCaller.get() == null) add("qualified historical App caller not captured in this host")
            }, activeSession.get()?.first.takeIf { running }, activeSession.get()?.second.takeIf { running },
            lastCheckedAtMillis, fcmObservation?.observation, live?.owner?.let {
                it.status() == DaemonOwnerStatus.UNRECONCILED || it.status() == DaemonOwnerStatus.ACTIVE && !it.isDispatchReady()
            } == true, readiness)
    }
    /** Independent memory snapshot; never flushes disk or enters a CONTROL transaction. */
    fun diagnostics(caller: DaemonCallerEvidence): DaemonDiagnosticsSnapshot {
        requireCaller(caller)
        return runCatching { diagnostics?.snapshot() }.getOrNull() ?: DaemonDiagnosticsSnapshot()
    }
    /** One pinned bounded upload; every chunk names that exact immutable data revision. */
    fun beginSnapshot(caller: DaemonCallerEvidence): DaemonSnapshotHeader {
        requireCaller(caller); check(runtime.get() != null)
        val snapshot = root.snapshot()
        val bytes = DaemonProtectionRootCodec.encode(snapshot)
        val header = DaemonSnapshotHeader(UUID.randomUUID().toString(), bytes.size,
            DaemonProtectionRootSnapshot.digest(bytes), snapshot.dataRevision, snapshot.control)
        pinned.set(Pinned(header, bytes))
        return header
    }
    fun snapshotChunk(caller: DaemonCallerEvidence, token: String, dataRevision: Long, offset: Int, size: Int): ByteArray {
        requireCaller(caller)
        val snapshot = checkNotNull(pinned.get())
        require(snapshot.header.token == token && snapshot.header.dataRevision == dataRevision)
        require(size in 1..DaemonImportProtocol.MAX_CHUNK_BYTES && offset >= 0 && offset <= snapshot.bytes.size &&
            size <= snapshot.bytes.size - offset)
        return snapshot.bytes.copyOfRange(offset, offset + size)
    }
    fun endSnapshot(caller: DaemonCallerEvidence, token: String) {
        requireCaller(caller)
        val current = pinned.get() ?: return
        require(current.header.token == token)
        pinned.compareAndSet(current, null)
    }

    /** Scheduler/test seam; actual execution uses the two real engines and their shared kernel. */
    internal suspend fun pollOnce() {
        if (closing.get() || !pollBusy.compareAndSet(false, true)) return
        try {
            // This also runs before an import or pointer reconciliation exists. A revoked owner
            // must eventually drain/release the global lease so a new installation can take it.
            // Every system read finishes outside kernel/root/staging monitors.
            lifecycle = readLifecycle()
            if (lifecycle == Lifecycle.REVOKED) { close(); return }
            if (lifecycle != Lifecycle.READY) { runtime.get()?.fcm?.invalidateDiagnosticReadiness(); return }
            val live = runtime.get() ?: return
            // Even read-only pending reconciliation needs the fresh trusted pointer first.
            if (live.owner.status() == DaemonOwnerStatus.UNRECONCILED || live.owner.status() == DaemonOwnerStatus.FAULT) {
                live.fcm.invalidateDiagnosticReadiness(); return
            }
            if (live.owner.isDispatchReady()) activeSession.compareAndSet(null, elapsed() to wall())
            val stopped = live.stopped.pollAsync()
            if (stopped.status == DaemonStoppedCycleStatus.CHECKED || stopped.status == DaemonStoppedCycleStatus.RECONCILED) {
                lastCheckedAtMillis = wall()
                diagnosticsStoppedCheckedAtMillis = lastCheckedAtMillis
            }
            if (!closing.get()) {
                val fcm = live.fcm.pollAsync()
                if (fcm.status == DaemonFcmCycleStatus.CHECKED) diagnosticsFcmCheckedAtMillis = wall()
                if (fcm.status == DaemonFcmCycleStatus.CHECKED || fcm.observation != latestGmsObservation)
                    lastCheckedAtMillis = wall()
                latestGmsObservation = fcm.observation
            }
        } finally {
            pollBusy.set(false)
            offerDiagnostics()
            if (closing.get()) reapClosed()
        }
    }
    private fun offerDiagnostics() {
        runCatching {
            val live = runtime.get()
            val state = when {
                closing.get() -> DaemonDiagnosticsState.CLOSING
                live?.owner?.snapshot()?.mode == DaemonControlMode.OFF -> DaemonDiagnosticsState.OFF
                lifecycle == Lifecycle.READY && live?.owner?.isDispatchReady() == true -> DaemonDiagnosticsState.READY
                else -> DaemonDiagnosticsState.WAITING
            }
            diagnostics?.opportunity(state, diagnosticsStoppedCheckedAtMillis, diagnosticsFcmCheckedAtMillis)
        }
    }
    private fun closeAdmission(live: Runtime, caller: DaemonCallerEvidence, expected: DaemonControlRecord): Boolean {
        live.fcm.invalidateDiagnosticReadiness()
        return if (live.owner.status() == DaemonOwnerStatus.UNRECONCILED)
            live.owner.snapshot() == expected && attestations.authenticated(caller)
        else live.owner.quiesceForControl(caller, expected)
    }

    private fun validateTrustedPlan(plan: DaemonControlTransitionPlan): Boolean {
        if (closing.get()) return false
        val before = root.snapshot()
        if (before.control != plan.expected) return false
        val encoded = DaemonControlProposalCodec.encode(plan)
        // A durable proposal can only have been staged by this trusted host. Raw import strips it.
        return issuedPlan.get()?.contentEquals(encoded) == true ||
            before.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.contentEquals(encoded) == true
    }
    private fun captureHistorical(caller: DaemonCallerEvidence, value: GmsHeartbeatCallerIdentity?) {
        if (value == null) return
        require(value.uid == caller.realUid && value.kernelBootId == bootId)
        historicalCaller.set(value)
    }
    private fun invalidateMigrationChallenge(current: DaemonControlRecord? = null): Long = synchronized(migrationChallengeLock) {
        challengeEpoch = Math.addExact(challengeEpoch, 1)
        issuedChallenge = null
        // Before activation the drain belongs to this nonce. After activation it is a retained
        // one-use closure capability; a later target/master control may carry it unchanged.
        if (current == null || current.mode == DaemonControlMode.PREPARED) importHandoff.set(null)
        challengeEpoch
    }
    private fun carryImportHandoff(before: DaemonProtectionRootSnapshot, after: DaemonProtectionRootSnapshot) {
        val proof = importHandoff.get() ?: return
        val raw = after.bytes(DaemonDomainRole.HEARTBEAT) ?: run { importHandoff.compareAndSet(proof, null); return }
        val heartbeat = HeartbeatSnapshotCodec.decode(raw)
        val digest = DaemonProtectionRootSnapshot.digest(raw)
        // ACTIVATE commits the exact target already bound by the trusted drain attestation.
        if (proof.record == after.control && proof.matches(after.control, heartbeat, digest)) return
        val oldRaw = before.bytes(DaemonDomainRole.HEARTBEAT)
        val barriers = listOf(DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.STOPPED_BLOCKED,
            DaemonDomainRole.INTERRUPTED_ARTIFACTS, DaemonDomainRole.HEARTBEAT_HALT, DaemonDomainRole.HEARTBEAT_PENDING,
            DaemonDomainRole.FCM_PROTECTION_HALT, DaemonDomainRole.FCM_RECOVERY_HALT)
        val parentBefore = before.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode)
        val parentAfter = after.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode)
        val recoveryBefore = before.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode)
        val recoveryAfter = after.bytes(DaemonDomainRole.FCM_RECOVERY)?.let(FcmRecoveryStoreCodec::decode)
        val unchanged = proof.record == before.control && oldRaw?.contentEquals(raw) == true &&
            before.halt == null && after.halt == null && barriers.all { before.bytes(it) == null && after.bytes(it) == null } &&
            parentBefore != null && parentAfter != null && parentBefore == parentAfter && parentAfter.configured &&
            parentAfter.epoch == heartbeat.durableConsent?.epoch && parentAfter.installationBinding == heartbeat.bindingDigest &&
            recoveryBefore != null && recoveryAfter != null && recoveryBefore.configured && recoveryAfter.configured &&
            recoveryBefore.binding == recoveryAfter.binding && parentAfter.recoveryBinding == recoveryAfter.binding
        val replacement = if (unchanged) proof.retarget(after.control, heartbeat, digest) else null
        importHandoff.compareAndSet(proof, replacement)
    }
    private fun rememberMigrationChallenge(version: Long, value: DaemonPointerChallenge?) = synchronized(migrationChallengeLock) {
        if (challengeEpoch == version && !closing.get()) issuedChallenge = value
    }
    private fun readCommandClock(): BudgetClock {
        commandClock?.let { return it() }
        check(clockReadBusy.compareAndSet(false, true)) { "Current boot count read busy" }
        return try {
            controlChildren.removeAll { runCatching { !it.isAlive }.getOrDefault(false) }
            check(controlChildren.isEmpty()) { "Previous boot count read has not exited" }
            systemDaemonBudgetClock(elapsed, wall) { controlChildren.add(it) }
        } finally { clockReadBusy.set(false) }
    }
    private fun requireCaller(caller: DaemonCallerEvidence) {
        check(!closing.get() && authenticatedFresh(caller)) { "Daemon caller or current privilege unavailable" }
    }
    private fun authenticatedFresh(caller: DaemonCallerEvidence): Boolean = try {
        if (closing.get() || caller.realUid != guardian.identity.uid || caller.installation != guardian) false else {
            val expected = DaemonOwnerEnvironment(2000, 0, bootId, true)
            val before = environmentReadPort.environment()
            val principal = environmentReadPort.guardianPackage()
            before == expected && principal?.installation == guardian && principal.installed && principal.enabled &&
                !principal.stopped && principal.requestsRuntimePermission &&
                environmentReadPort.packagesForUid(caller.realUid) == listOf(GUARDIAN_PACKAGE_NAME) &&
                environmentReadPort.shizukuRuntimePermission(caller.realUid) == true &&
                environmentReadPort.guardianPackage() == principal && environmentReadPort.environment() == before
        }
    } catch (_: Exception) { false }

    /** Definite current revocation tears down runtime only; it never rewrites durable consent. */
    private fun readLifecycle(): Lifecycle = try {
        val expected = DaemonOwnerEnvironment(2000, 0, bootId, true)
        val before = environmentReadPort.environment()
        val principal = environmentReadPort.guardianPackage()
        val owners = environmentReadPort.packagesForUid(guardian.identity.uid)
        val permission = environmentReadPort.shizukuRuntimePermission(guardian.identity.uid)
        val afterPrincipal = environmentReadPort.guardianPackage()
        val afterOwners = environmentReadPort.packagesForUid(guardian.identity.uid)
        val afterPermission = environmentReadPort.shizukuRuntimePermission(guardian.identity.uid)
        val after = environmentReadPort.environment()
        when {
            closing.get() -> Lifecycle.REVOKED
            before != after || principal != afterPrincipal || owners != afterOwners || permission != afterPermission -> Lifecycle.WAITING
            // A locked user or failed/incomplete package read is not proof of consent revocation.
            before != expected -> Lifecycle.WAITING
            permission == false -> Lifecycle.REVOKED
            principal != null && (principal.installation != guardian || !principal.installed || !principal.enabled ||
                principal.stopped || !principal.requestsRuntimePermission) -> Lifecycle.REVOKED
            owners != null && owners != listOf(GUARDIAN_PACKAGE_NAME) -> Lifecycle.REVOKED
            principal == null || owners == null || permission == null -> Lifecycle.WAITING
            else -> {
                val enabledAt = runtime.get()?.let { DaemonHeartbeatDerivation.masterEnabledAt(root.snapshot()) }
                if (enabledAt == null) Lifecycle.READY else when (environment.read(guardian, bootId, enabledAt, closing::get)) {
                    DaemonOwnerEnvironmentStatus.READY -> Lifecycle.READY
                    DaemonOwnerEnvironmentStatus.USER_STOPPED -> {
                        // A currently usable principal may explicitly confirm ON again. Historical
                        // stop must block automatic restore, but cannot permanently brick its host.
                        runtime.get()?.let { live ->
                            live.owner.snapshot()?.let { expected ->
                                closeAdmission(live, DaemonCallerEvidence(guardian.identity.uid, guardian), expected)
                            }
                            live.fcm.controlCommitted()
                        }
                        Lifecycle.WAITING
                    }
                    // The initial stable principal/permission reads prove revocation; a later
                    // unavailable probe alone only blocks this opportunity and retains the lease.
                    else -> Lifecycle.WAITING
                }
            }
        }
    } catch (_: Exception) { Lifecycle.WAITING }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            pinned.set(null); issuedPlan.set(null); attestations.invalidateCurrent()
            invalidateMigrationChallenge(); completedImport.set(null); importUpload.set(null)
        }
        offerDiagnostics()
        reapClosed()
    }
    /** One failing branch must neither strand the other nor make a later close a no-op. */
    private fun closeBranches(): Boolean = synchronized(closeLock) {
        val live = runtime.get()
        if (closingRuntime !== live) {
            closingRuntime = live
            stoppedCloseSucceeded = false
            fcmCloseSucceeded = false
        }
        if (!stoppedCloseSucceeded) stoppedCloseSucceeded = runCatching {
            if (live == null) stoppedShell.close() else live.stopped.close()
        }.isSuccess
        if (!fcmCloseSucceeded) fcmCloseSucceeded = runCatching {
            if (live == null) fcmShell.close() else live.fcm.close()
        }.isSuccess
        stoppedCloseSucceeded && fcmCloseSucceeded
    }
    /** No coroutine cancellation, storage close or lease release until actual workers/children exit. */
    internal fun reapClosed(): Boolean = synchronized(closeLock) {
        if (!closing.get()) return@synchronized false
        if (released.get()) return@synchronized true
        if (!closeBranches() || pollBusy.get() || applyBusy.get() || controlReads.get() != 0) return@synchronized false
        controlChildren.removeAll { runCatching { !it.isAlive }.getOrDefault(false) }
        if (controlChildren.isNotEmpty()) return@synchronized false
        val live = runtime.get()
        if (live != null && (!live.stopped.isQuiescent() || !live.fcm.isQuiescent())) return@synchronized false
        if (live == null && (!stoppedShell.isQuiescent() || !fcmShell.isQuiescent())) return@synchronized false
        if (runCatching { releaseLease() }.isFailure) return@synchronized false
        released.set(true)
        runCatching { diagnostics?.normalEnd() }
        scope.cancel()
        true
    }
    companion object { const val POLL_MILLIS = 30_000L }
}

/** Fixed read-only clock; no caller-controlled argv, no app-private Context or permission cache. */
private fun systemDaemonBudgetClock(elapsed: () -> Long, wall: () -> Long,
    retain: (java.lang.Process) -> Unit): BudgetClock {
    val child = ProcessBuilder("/system/bin/settings", "get", "global", "boot_count").start()
    retain(child)
    try {
        check(child.waitFor(2, TimeUnit.SECONDS)) { "Current boot count unavailable" }
        check(child.exitValue() == 0)
        val bytes = child.inputStream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            repeat(33) { val next = input.read(); if (next < 0) return@use buffer.toByteArray(); buffer.write(next) }
            error("Current boot count exceeded bound")
        }
        val boot = String(bytes, Charsets.US_ASCII).trim().toLong().also { require(it >= 0) }
        return BudgetClock(boot, elapsed(), wall())
    } finally {
        if (child.isAlive) child.destroyForcibly()
        runCatching { child.waitFor(1_000, TimeUnit.MILLISECONDS) }
        child.outputStream.close(); child.inputStream.close(); child.errorStream.close()
    }
}
