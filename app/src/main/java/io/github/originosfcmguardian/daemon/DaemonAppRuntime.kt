package io.github.originosfcmguardian.daemon

import android.content.Context
import android.app.Application
import android.os.Bundle
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime
import io.github.originosfcmguardian.fcmprotection.FcmProtectionUiState
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatSnapshotCodec
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatPause
import io.github.originosfcmguardian.gmsheartbeat.HeartbeatResumeBlock
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsObservationCodec
import io.github.originosfcmguardian.core.gms.GmsUnknown
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

/** UI/configuration bridge only. No recovery loop or App-lifetime authority lives here. */
internal object DaemonAppRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Mutex()
    private val initialization = Any()
    @Volatile private var app: Application? = null
    @Volatile private var pointerStore: DaemonMigrationPointerStore? = null
    @Volatile private var exportStore: DaemonMigrationExportStore? = null
    @Volatile private var client: DaemonClient? = null
    @Volatile private var remoteOwned: Boolean? = null
    private val reconnectRequests = DaemonReconnectRequests()
    @Volatile private var adopting: DaemonMigrationIntent? = null
    private val adoptionRetry = DaemonAdoptionRetry()
    private val mutable = MutableStateFlow<DaemonProtectionRootSnapshot?>(null)
    val snapshots = mutable.asStateFlow()
    private val projected = MutableStateFlow<DaemonUiSnapshot?>(null)
    val state = projected.asStateFlow()
    private val readIds = AtomicLong()
    private val commandAdmission = DaemonAppCommandAdmission()
    // Protected by commandAdmission, so accepting OFF and publishing an ON result have one order.
    private var fcmConfirmation: DaemonFcmCommandCompletion? = null
    private val unsavedStop = DaemonUnsavedStop()
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        onPrivilegeEvent()
    }
    private val permissionReceived = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) onPrivilegeEvent()
        else adoptionRetry.readiness(null)
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        adoptionRetry.readiness(null)
        if (remoteOwned == true) unavailable()
    }

    private fun onPrivilegeEvent() {
        // Scope belongs to the App, not the current screen. A ready callback creates no consent.
        app?.let { application -> scope.launch { adoptWhenReady(application) } }
    }

    private fun adoptionReadyIdentity(): Any? = runCatching {
        if (Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.getBinder() else null
    }.getOrNull()

    private fun adoptionCandidateReady(): Boolean {
        val saved = AutomaticProtectionRuntime.state.value
        return saved.enabled && saved.masterEnabled && saved.configurationAvailable && !saved.busy
    }

    /** Fresh storage, never the cached LOCAL decision, controls entry into first adoption. */
    private fun adoptionStillLocal(): Boolean {
        if (remoteOwned == true) return false
        val local = pointerStore!!.read() == DaemonMigrationRead.Local &&
            runCatching { !exportStore!!.hasArtifact() }.getOrDefault(false)
        if (!local) { remoteOwned = true; unavailable() }
        return local
    }

    private fun initialize(context: Context) = synchronized(initialization) {
        if (app == null) {
            val application = context.applicationContext as? Application ?: error("Application context required")
            pointerStore = DaemonMigrationPointerStore(AtomicDaemonMigrationFiles(application))
            exportStore = DaemonMigrationExportStore(AtomicDaemonMigrationExportFile(application))
            client = DaemonClient(application) {
                if (remoteOwned == true) restore(application)
            }
            app = application
            // Process-wide event hooks, not a timer/FGS. Boot-before-Shizuku can reconnect later;
            // no UI listener lifetime owns the backend, and callbacks never create consent.
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionReceived)
        }
    }

    /** Only a positively empty marker allows old execution. Unknown storage is never LOCAL. */
    fun remoteOwnershipClaimed(): Boolean = remoteOwned == true

    fun isRemoteOwned(context: Context): Boolean {
        remoteOwned?.let { return it }
        initialize(context)
        return synchronized(initialization) {
            remoteOwned ?: (pointerStore!!.read() != DaemonMigrationRead.Local ||
                runCatching { exportStore!!.hasArtifact() }.getOrDefault(true)).also { remoteOwned = it }
        }
    }

    /** Ordinary reconnect neither recreates consent nor falls back to the old App executor. */
    fun onAppCreated(context: Context) {
        initialize(context)
        // ShizukuProvider may start a fresh App process long after BOOT_COMPLETED. Register the
        // sticky hooks even without a screen; do not create consent or run a local fallback.
        scope.launch { runCatching { if (isRemoteOwned(context)) restore(context) }.onFailure { unavailable() } }
    }

    fun restore(context: Context) {
        initialize(context)
        if (!isRemoteOwned(context) || !reconnectRequests.offer()) return
        scope.launch {
            while (reconnectRequests.takePending()) {
                val restored = runCatching { withTimeout(150_000) {
                    reconnectDaemonWhileReady(
                        ready = { runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) },
                        reconnect = { control.withLock { restoreLocked() } },
                        failed = ::unavailable)
                } }.getOrDefault(false)
                if (!restored) unavailable()
            }
        }
    }

    /** At most two preflights per actual ready epoch, driven by existing events without a timer. */
    fun adoptWhenReady(context: Context) {
        initialize(context)
        if (isRemoteOwned(context)) { restore(context); return }
        val ticket = adoptionRetry.offer(adoptionReadyIdentity(), adoptionCandidateReady(), local = true) ?: return
        val application = checkNotNull(app)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try { migrate(application, ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteOwned == true) unavailable() }
        }
        task.invokeOnCompletion {
            if (adoptionRetry.finish(ticket) && remoteOwned != true)
                scope.launch { adoptWhenReady(application) }
        }
        task.start()
    }

    /** Read-only preflight precedes the permanent marker. Actual old work must settle before export. */
    private suspend fun migrate(context: Context, ticket: DaemonAdoptionRetry.Ticket): Boolean = control.withLock {
        initialize(context)
        if (isRemoteOwned(context)) return@withLock runCatching { restoreLocked(); true }.getOrElse { unavailable(); false }
        // Initial adoption is only offered for a verified live ACTIVE configuration. An OFF or
        // not-yet-loaded installation remains local rather than creating an unusable ACTIVATE.
        if (!adoptionStillLocal()) { restore(context); return@withLock false }
        if (!adoptionCandidateReady()) return@withLock false
        val beforeReady = adoptionReadyIdentity() ?: return@withLock false
        adoptionRetry.readiness(beforeReady)
        currentCoroutineContext().ensureActive()
        if (!adoptionRetry.start(ticket)) return@withLock false
        val bridge = checkNotNull(client)
        val preflight = try { DaemonWire.int(bridge.call(DaemonWire.PREFLIGHT), "uid") == 2000 }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (!preflight) return@withLock false // No marker: existing verified owner has not been changed.
        // A completed preflight from a previous server/permission epoch is not transferable.
        if (!adoptionStillLocal()) { restore(context); return@withLock false }
        if (!adoptionCandidateReady()) return@withLock false
        val afterReady = adoptionReadyIdentity() ?: return@withLock false
        adoptionRetry.readiness(afterReady)
        currentCoroutineContext().ensureActive()
        if (!adoptionRetry.current(ticket)) return@withLock false
        val intent = DaemonMigrationIntent(DaemonOwnerKey(UUID.randomUUID().toString(), 1), UUID.randomUUID().toString())
        synchronized(initialization) { adopting = intent; remoteOwned = true }
        unavailable() // Clear actual-running claims before legacy collectors stop forwarding.
        if (!AutomaticProtectionRuntime.prepareForDaemonMigration(context)) return@withLock false
        // From this point even a failed marker write cannot reopen the old in-memory dispatcher.
        if (!pointerStore!!.begin(intent)) { unavailable(); return@withLock false }
        try {
            withTimeout(150_000) { restoreLocked() }
            true
        } catch (_: Exception) { unavailable(); false } finally { adopting = null }
    }

    /** Permanent INTENT may precede the first export; a frozen export is never recreated. */
    private suspend fun resumeMigrationLocked(intent: DaemonMigrationIntent, stop: DaemonAppStopIntent? = null) {
        if (stop != null) check(stop.intent == intent && stop.command.kind == DaemonWire.MASTER_OFF)
        val context = checkNotNull(app)
        check(AutomaticProtectionRuntime.prepareForDaemonMigration(context))
        check(AutomaticProtectionRuntime.awaitDaemonMigrationDrain(context))
        val frozen = exportStore!!.read(intent) ?: run {
            val imported = AppDaemonDomainExport(AndroidAppDaemonDomainReadPort(context)).prepared(intent.owner, intent.migrationId)
            validateDaemonMigrationHeartbeat(imported)
            check(exportStore!!.saveOnce(intent, DaemonProtectionRootCodec.encode(imported)))
            checkNotNull(exportStore!!.read(intent))
        }
        if (stop == null && pausedDaemonMigrationRequiresStop(frozen.snapshot())) {
            val prior = unsavedStop.current() ?: pointerStore!!.readStopIntent()
            val negative = prior ?: DaemonAppStopIntent(UUID.randomUUID().toString(), intent, DaemonAppCommand(DaemonWire.MASTER_OFF))
            check(negative.intent == intent && negative.command.kind == DaemonWire.MASTER_OFF)
            if (prior == null) unsavedStop.retain(negative)
            unsavedStop.persist(pointerStore!!::saveStopIntent)
            resolveStopLocked(negative)
            return
        }
        val bridge = checkNotNull(client)
        val bootstrapped = DaemonWire.appStatus(bridge.bootstrap(intent))
        val existing = bootstrapped.record?.let { bridge.snapshot() }
        val priorStop = if (stop == null || existing == null) null else
            existing.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.let(DaemonControlProposalCodec::decode)?.takeIf { it.requestId == stop.id }
        val prepared = if (priorStop != null) {
            val base = DaemonProtectionRootSnapshot(existing!!.control,
                existing.domains() + (DaemonDomainRole.CONTROL_PROPOSAL to null), existing.receipts, existing.halt, existing.dataRevision)
            check(DaemonPreparedImportVerifier.matches(base, frozen.snapshot()))
            checkNotNull(daemonStopProposal(existing, checkNotNull(stop)))
            existing.control
        } else bridge.import(frozen.snapshot())
        val aggregate = bridge.snapshot()
        check(aggregate.control == prepared && prepared.mode == DaemonControlMode.PREPARED)
        val staged = if (stop != null) {
            // No ON is used to reach OFF. Other negative requests remain durably inert/pending.
            check(stop.intent == intent && stop.command.kind == DaemonWire.MASTER_OFF)
            if (priorStop == null) stage(prepared, DaemonWire.MASTER_OFF, id = stop.id) else
                bridge.call(DaemonWire.RESUME_STAGED, Bundle().apply { putBundle("record", DaemonWire.record(priorStop.next)) })
        } else {
            check(unsavedStop.current() == null && pointerStore!!.readStopIntent() == null)
            val proposal = aggregate.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.let(DaemonControlProposalCodec::decode)
            if (proposal == null) stage(prepared, DaemonWire.ACTIVATE) else {
                check(proposal.transition == DaemonControlTransition.ACTIVATE && proposal.expected == prepared)
                bridge.call(DaemonWire.RESUME_STAGED, Bundle().apply { putBundle("record", DaemonWire.record(proposal.next)) })
            }
        }
        // The live closure proof exists only in this App process. A cold candidate instead needs
        // the shell's independent PID/startticks/boot-gone check before any heartbeat arm.
        val heartbeat = frozen.snapshot().bytes(DaemonDomainRole.HEARTBEAT)
        val needsHandoff = stop == null && heartbeat?.let(HeartbeatSnapshotCodec::decode)?.configured == true &&
            io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime.migrationEndpointClosureAttested()
        if (stop == null) check(unsavedStop.current() == null && pointerStore!!.readStopIntent() == null)
        applyStaged(intent, frozen.sha256, staged, frozen.sha256.takeIf { needsHandoff },
            heartbeat?.let(DaemonProtectionRootSnapshot::digest).takeIf { needsHandoff })
        refreshLocked()
    }

    fun stop(context: Context) = launchCommand(context, DaemonWire.MASTER_OFF)
    fun enableMaster(context: Context) = launchCommand(context, DaemonWire.MASTER_ON)
    fun enableTarget(context: Context, identity: SelectedTargetIdentity) = launchCommand(context, DaemonWire.TARGET_ON, identity)
    fun disableTarget(context: Context, identity: SelectedTargetIdentity) = launchCommand(context, DaemonWire.TARGET_OFF, identity)
    fun enableFcm(context: Context): Deferred<DaemonFcmCommandResult> {
        val completion = DaemonFcmCommandCompletion(UUID.randomUUID().toString())
        // This scope belongs to the App. Cancelling a screen's await never cancels remote work.
        completion.bound(scope)
        try { launchCommand(context, DaemonWire.FCM_ON, completion = completion) }
        catch (_: Exception) { completion.finish(DaemonFcmCommandOutcome.FAILED, DaemonFcmCommandReason.PREPARATION) }
        return completion.result
    }
    fun disableFcm(context: Context) = launchCommand(context, DaemonWire.FCM_OFF)

    private fun launchCommand(context: Context, command: Int, target: SelectedTargetIdentity? = null,
        completion: DaemonFcmCommandCompletion? = null) {
        initialize(context)
        if (!isRemoteOwned(context)) {
            completion?.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.NOT_REMOTE)
            return
        }
        if (completion?.result?.isCompleted == true) return
        val first = DaemonAppCommand(command, target)
        // Before waiting for the migration/control mutex, remember an OFF against its INTENT.
        // This is local durable negative intent only; it is never reported as a remote OFF ACK.
        val offer = synchronized(commandAdmission) {
            commandAdmission.offer(first).also { offered ->
                if (offered == DaemonAppCommandAdmission.Offer.START && completion != null) fcmConfirmation = completion
                if (first.disabling && offered != DaemonAppCommandAdmission.Offer.REJECTED)
                    fcmConfirmation?.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.STOP_REQUESTED)
            }
        }
        if (first.disabling && offer != DaemonAppCommandAdmission.Offer.REJECTED) retainMigrationStop(first)
        if (offer != DaemonAppCommandAdmission.Offer.START) {
            completion?.finish(DaemonFcmCommandOutcome.REJECTED, DaemonFcmCommandReason.BUSY)
            return
        }
        publishBusy(true)
        scope.launch {
            var next: DaemonAppCommand? = first
            var reporting = completion
            while (next != null) {
                val request = next
                if (reporting != null) runFcmCommand(request, reporting)
                else runCatching { withTimeout(90_000) { control.withLock { executeCommandLocked(request) } } }.onFailure {
                    // One bounded reconciliation, not an indefinite retry or old executor fallback.
                    if (runCatching { withTimeout(60_000) { control.withLock { restoreLocked() } } }.isFailure) unavailable()
                }
                next = synchronized(commandAdmission) {
                    if (fcmConfirmation === reporting) fcmConfirmation = null
                    commandAdmission.finishAndNext()
                }
                reporting = null
            }
            publishBusy(false)
        }
    }

    private suspend fun runFcmCommand(request: DaemonAppCommand, completion: DaemonFcmCommandCompletion) {
        try {
            // Reserve time for one reconciliation of this exact request after a lost reply.
            withTimeout(65_000) { control.withLock { executeCommandLocked(request, completion) } }
        } catch (_: Exception) {
            if (!completion.hasExpectation()) {
                unavailable()
                completion.finish(DaemonFcmCommandOutcome.FAILED, DaemonFcmCommandReason.PREPARATION)
            } else try {
                withTimeout(20_000) { control.withLock {
                    restoreLocked()
                    refreshLocked { root, status, ui -> synchronized(commandAdmission) {
                        completion.observed(root, status, ui)
                    } }
                } }
            } catch (_: Exception) { unavailable() }
        } finally {
            completion.finish(DaemonFcmCommandOutcome.UNKNOWN, DaemonFcmCommandReason.UNAVAILABLE)
        }
    }

    private suspend fun executeCommandLocked(request: DaemonAppCommand, completion: DaemonFcmCommandCompletion? = null) {
        if (request.disabling) {
            unsavedStop.persist(pointerStore!!::saveStopIntent)?.let { resolveStopLocked(it) }
            pointerStore!!.readStopIntent()?.let { resolveStopLocked(it) }
            val saved = pointerStore!!.read()
            val intent = when (saved) {
                is DaemonMigrationRead.Remote -> saved.pointer.intent
                is DaemonMigrationRead.Migrating -> saved.intent
                else -> error("Migration pending")
            }
            val stop = DaemonAppStopIntent(UUID.randomUUID().toString(), intent, request)
            unsavedStop.retain(stop)
            resolveStopLocked(checkNotNull(unsavedStop.persist(pointerStore!!::saveStopIntent)))
            return
        }
        restoreLocked()
        val saved = pointerStore!!.read() as? DaemonMigrationRead.Remote ?: error("Migration pending")
        val expected = checkNotNull(mutable.value).control
        // An accepted OFF/deadline may already have settled this request while it waited for control.
        if (completion != null && !synchronized(commandAdmission) { completion.expect(expected) }) return
        val staged = stage(expected, request.kind, request.target, completion?.requestId ?: UUID.randomUUID().toString())
        if (completion != null) {
            val stagedRequest = DaemonWire.request(DaemonWire.nested(staged, "request"))
            val challenge = DaemonWire.challenge(DaemonWire.nested(staged, "challenge"))
            completion.staged(stagedRequest, challenge.pointer.record, challenge.patchDigest)
        }
        applyStaged(saved.pointer.intent, saved.pointer.importDigest, staged)
        refreshLocked { root, status, ui -> if (completion != null) synchronized(commandAdmission) {
            completion.observed(root, status, ui)
        } }
    }

    private fun retainMigrationStop(command: DaemonAppCommand) {
        val intent = (pointerStore!!.read() as? DaemonMigrationRead.Migrating)?.intent ?: adopting ?: return
        runCatching {
            val existing = unsavedStop.current() ?: pointerStore!!.readStopIntent()
            if (existing != null) { check(existing.intent == intent && existing.command == command); return }
            unsavedStop.retain(DaemonAppStopIntent(UUID.randomUUID().toString(), intent, command))
            unsavedStop.persist(pointerStore!!::saveStopIntent)
        }.onFailure { unavailable() }
    }

    private fun publishBusy(value: Boolean) {
        AutomaticProtectionRuntime.publishDaemonState(AutomaticProtectionRuntime.state.value.copy(busy = value))
        FcmProtectionRuntime.publishDaemonState(FcmProtectionRuntime.state.value.copy(busy = value))
        projected.value?.let { prior -> projected.value = prior.copy(automatic = prior.automatic.copy(busy = value),
            fcm = prior.fcm.copy(busy = value)) }
    }

    private suspend fun stage(expected: DaemonControlRecord, command: Int, target: SelectedTargetIdentity? = null,
        id: String = UUID.randomUUID().toString()): Bundle =
        client!!.call(DaemonWire.STAGE, Bundle().apply {
            putBundle("expected", DaemonWire.record(expected)); putString("id", id); putInt("command", command)
            target?.let { putString("target", DaemonWire.target(it)); putString("package", it.packageName) }
        })

    private suspend fun applyStaged(intent: DaemonMigrationIntent, digest: String, staged: Bundle,
        drainedImport: String? = null, drainedHeartbeat: String? = null): DaemonControlReceipt {
        val challenge = DaemonWire.challenge(DaemonWire.nested(staged, "challenge"))
        val request = DaemonWire.request(DaemonWire.nested(staged, "request"))
        check(challenge.purpose == DaemonPointerPurpose.TRANSITION)
        synchronized(commandAdmission) {
            fcmConfirmation?.takeIf { it.requestId == request.requestId }
                ?.staged(request, challenge.pointer.record, challenge.patchDigest)
        }
        checkStopAdmission(request)
        checkNotNull(pointerStore!!.commit(intent, digest, challenge))
        checkStopAdmission(request)
        val reply = client!!.call(DaemonWire.ATTEST_APPLY, Bundle().apply {
            putBundle("challenge", DaemonWire.challenge(challenge)); putBundle("request", DaemonWire.request(request))
            if (drainedImport != null) {
                putString("drainedImport", drainedImport); putString("drainedHeartbeat", checkNotNull(drainedHeartbeat))
            }
        })
        DaemonWire.keys(reply, "ok", "request", "record", "currentRevision")
        val receipt = DaemonControlReceipt(DaemonWire.request(DaemonWire.nested(reply, "request")),
            DaemonWire.record(DaemonWire.nested(reply, "record")))
        check(receipt.request == request && receipt.committed == challenge.pointer.record &&
            DaemonWire.long(reply, "currentRevision") >= receipt.committed.revision)
        return receipt
    }

    private fun checkStopAdmission(request: DaemonControlRequest) {
        val stop = unsavedStop.current() ?: pointerStore!!.readStopIntent()
        check(stop == null || stop.id == request.requestId) { "A different OFF is pending" }
    }

    private suspend fun restoreLocked() {
        unsavedStop.persist(pointerStore!!::saveStopIntent)?.let { resolveStopLocked(it) }
        // This durable intent precedes the disabling RPC. Even a cold/event-driven reconnect
        // cannot reconcile an older ACTIVE record after an unacknowledged explicit OFF.
        pointerStore!!.readStopIntent()?.let { resolveStopLocked(it) }
        if (pointerStore!!.read() == DaemonMigrationRead.Blocked) recoverPendingLocked()
        (pointerStore!!.read() as? DaemonMigrationRead.Migrating)?.let {
            resumeMigrationLocked(it.intent)
            return
        }
        val saved = pointerStore!!.read() as? DaemonMigrationRead.Remote ?: error("Migration not complete")
        val value = saved.pointer
        val status = client!!.bootstrap(value.intent)
        val current = DaemonWire.record(DaemonWire.nested(status, "record"))
        val host = DaemonWire.appStatus(status)
        if (current == value.committed.record && !host.needsReconciliation &&
            host.status in setOf(DaemonOwnerStatus.ACTIVE, DaemonOwnerStatus.OFF)) {
            // A living reconciled host must not be quiesced just because a screen reconnects.
            refreshLocked()
            return
        }
        if (current == value.committed.record) {
            val aggregate = client!!.snapshot()
            val proposal = aggregate.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.let(DaemonControlProposalCodec::decode)
            if (proposal != null) {
                // Stage may have committed before its reply reached this App. Finish that exact
                // trusted proposal; never regenerate its request/consent IDs or use an older ON.
                check(aggregate.control == current && proposal.expected == current)
                val reply = client!!.call(DaemonWire.RESUME_STAGED, Bundle().apply { putBundle("record", DaemonWire.record(proposal.next)) })
                applyStaged(value.intent, value.importDigest, reply)
                refreshLocked()
                return
            }
            // A fresh challenge is necessary after host restart; no saved ACTIVE proof is replayed.
            val reply = client!!.call(DaemonWire.CURRENT_POINTER)
            val challenge = DaemonWire.challenge(DaemonWire.nested(reply, "challenge"))
            check(challenge.purpose == DaemonPointerPurpose.RECONCILE && challenge.pointer.record == current)
            checkNotNull(pointerStore!!.commit(value.intent, value.importDigest, challenge))
            try {
                client!!.call(DaemonWire.RECONCILE, Bundle().apply {
                    putBundle("challenge", DaemonWire.challenge(challenge)); putString("id", UUID.randomUUID().toString())
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // A first native ARM can time out without publishing a session. Never use this
                // path for a heartbeat send/UNKNOWN: the daemon verifies the exact clean frozen
                // import delta plus actual old-owner closure before settling ONLY that ARM.
                if (!reconcileInitialArmLocked(value)) throw failure
                val retry = client!!.call(DaemonWire.CURRENT_POINTER)
                val fresh = DaemonWire.challenge(DaemonWire.nested(retry, "challenge"))
                check(fresh.purpose == DaemonPointerPurpose.RECONCILE && fresh.pointer.record == current)
                checkNotNull(pointerStore!!.commit(value.intent, value.importDigest, fresh))
                client!!.call(DaemonWire.RECONCILE, Bundle().apply {
                    putBundle("challenge", DaemonWire.challenge(fresh)); putString("id", UUID.randomUUID().toString())
                })
            }
        } else {
            // The pointer was saved before a lost control ACK; resume only its exact durable plan.
            val reply = client!!.call(DaemonWire.RESUME_STAGED, Bundle().apply { putBundle("record", DaemonWire.record(value.committed.record)) })
            applyStaged(value.intent, value.importDigest, reply)
        }
        refreshLocked()
    }

    private suspend fun reconcileInitialArmLocked(value: DaemonRemotePointer): Boolean {
        check(unsavedStop.current() == null && pointerStore!!.readStopIntent() == null)
        val current = client!!.snapshot()
        if (current.control != value.committed.record || current.control.mode != DaemonControlMode.ACTIVE ||
            current.control.revision != 2L) return false
        val heartbeat = current.bytes(DaemonDomainRole.HEARTBEAT)?.let(HeartbeatSnapshotCodec::decode) ?: return false
        if (heartbeat.pause != HeartbeatPause.RECONFIRM || heartbeat.resumeBlock != HeartbeatResumeBlock.UNCERTAIN_OWNER ||
            heartbeat.pendingArm == null || heartbeat.pendingAttempt != null || heartbeat.remoteSessionId != null) return false
        val frozen = exportStore!!.read(value.intent) ?: return false
        if (frozen.sha256 != value.importDigest) return false
        val bytes = frozen.bytes()
        // This first-migration compatibility RPC is deliberately one small bounded message.
        // Larger/unknown exports remain pending; no raw reconstruction or generic clear exists.
        if (bytes.size > DaemonImportProtocol.MAX_CHUNK_BYTES) return false
        check(unsavedStop.current() == null && pointerStore!!.readStopIntent() == null)
        client!!.call(DaemonWire.RECONCILE_INITIAL_ARM, Bundle().apply {
            putBundle("expected", DaemonWire.record(current.control)); putLong("dataRevision", current.dataRevision)
            putByteArray("frozen", bytes); putString("sha256", value.importDigest)
        })
        return true
    }

    private suspend fun resolveStopLocked(stop: DaemonAppStopIntent) {
        (pointerStore!!.read() as? DaemonMigrationRead.Migrating)?.let {
            check(it.intent == stop.intent && stop.command.kind == DaemonWire.MASTER_OFF)
            resumeMigrationLocked(it.intent, stop)
        }
        client!!.bootstrap(stop.intent)
        var aggregate = client!!.snapshot()
        if (pointerStore!!.read() == DaemonMigrationRead.Blocked) {
            val pending = pointerStore!!.inspectForRecovery() as? DaemonMigrationRecoveryRead.Pending
                ?: error("Stop pointer is uncertain")
            check(pending.intent == stop.intent)
            val knownCommitted = aggregate.control == pending.candidate.committed.record &&
                aggregate.bytes(DaemonDomainRole.CONTROL_PROPOSAL) == null && daemonStopSatisfied(aggregate, stop)
            if (!knownCommitted) check(daemonStopProposal(aggregate, stop)?.next == pending.candidate.committed.record)
            recoverPendingLocked() // Only the exact stopping effect proved above is allowed.
            aggregate = client!!.snapshot()
        }
        val saved = pointerStore!!.read() as? DaemonMigrationRead.Remote ?: error("Stop migration is incomplete")
        check(saved.pointer.intent == stop.intent)
        val proposal = daemonStopProposal(aggregate, stop) // A different ON proposal remains blocked.
        if (proposal != null) {
            val staged = client!!.call(DaemonWire.RESUME_STAGED, Bundle().apply { putBundle("record", DaemonWire.record(proposal.next)) })
            applyStaged(stop.intent, saved.pointer.importDigest, staged)
        } else if (!daemonStopSatisfied(aggregate, stop)) {
            check(aggregate.control == saved.pointer.committed.record)
            applyStaged(stop.intent, saved.pointer.importDigest,
                stage(aggregate.control, stop.command.kind, stop.command.target, stop.id))
        }
        val after = client!!.snapshot()
        check(after.bytes(DaemonDomainRole.CONTROL_PROPOSAL) == null && daemonStopSatisfied(after, stop))
        check(pointerStore!!.clearStopIntent(stop))
        refreshLocked()
    }

    private suspend fun recoverPendingLocked() {
        val pending = pointerStore!!.inspectForRecovery() as? DaemonMigrationRecoveryRead.Pending
            ?: error("Interrupted migration cannot be reconciled")
        val status = client!!.bootstrap(pending.intent)
        val current = DaemonWire.record(DaemonWire.nested(status, "record"))
        val reply = if (current == pending.candidate.committed.record) client!!.call(DaemonWire.CURRENT_POINTER)
            else client!!.call(DaemonWire.RESUME_STAGED, Bundle().apply {
                putBundle("record", DaemonWire.record(pending.candidate.committed.record))
            })
        val challenge = DaemonWire.challenge(DaemonWire.nested(reply, "challenge"))
        checkNotNull(pointerStore!!.recoverPending(pending, challenge))
        if (challenge.purpose == DaemonPointerPurpose.RECONCILE) {
            client!!.call(DaemonWire.RECONCILE, Bundle().apply {
                putBundle("challenge", DaemonWire.challenge(challenge)); putString("id", UUID.randomUUID().toString())
            })
        } else {
            client!!.call(DaemonWire.ATTEST_APPLY, Bundle().apply {
                putBundle("challenge", DaemonWire.challenge(challenge)); putBundle("request", DaemonWire.nested(reply, "request"))
            })
        }
    }

    /** A report-only read: never restore/migrate, flush storage or change protection readiness. */
    suspend fun diagnosticSnapshot(@Suppress("UNUSED_PARAMETER") context: Context): DaemonDiagnosticsSnapshot {
        if (remoteOwned != true) return DaemonDiagnosticsSnapshot()
        return try {
            withTimeoutOrNull(5_000) {
                DaemonWire.diagnostics(checkNotNull(client).readDiagnosticsExisting())
            } ?: DaemonDiagnosticsSnapshot(availability = DaemonDiagnosticsAvailability.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DaemonDiagnosticsSnapshot(availability = DaemonDiagnosticsAvailability.UNAVAILABLE) }
    }

    suspend fun refresh(context: Context): DaemonProtectionRootSnapshot? {
        initialize(context)
        if (!isRemoteOwned(context)) return null
        return daemonRefreshOrUnavailable({ control.withLock { refreshLocked() } }, ::unavailable)
    }

    suspend fun readGmsConnection(context: Context): GmsConnectionObservation {
        initialize(context)
        val id = readIds.incrementAndGet()
        return runCatching {
            check(isRemoteOwned(context))
            val saved = pointerStore!!.read() as? DaemonMigrationRead.Remote ?: error("Migration pending")
            client!!.bootstrap(saved.pointer.intent)
            val result = client!!.call(DaemonWire.READ_GMS, Bundle().apply { putLong("id", id) })
            GmsObservationCodec.decode(DaemonWire.text(result, "gms", 12_000), id)
        }.getOrElse { GmsConnectionObservation.Unknown(id, GmsUnknown.BRIDGE_UNAVAILABLE) }
    }

    private suspend fun refreshLocked(observed: ((DaemonProtectionRootSnapshot, DaemonAppStatus, DaemonUiSnapshot) -> Unit)? = null): DaemonProtectionRootSnapshot {
        var snapshot = client!!.snapshot()
        var status = DaemonWire.appStatus(client!!.call(DaemonWire.STATUS))
        if (status.record != snapshot.control || status.dataRevision != snapshot.dataRevision) {
            snapshot = client!!.snapshot()
            status = DaemonWire.appStatus(client!!.call(DaemonWire.STATUS))
        }
        val raw = DaemonUiProjection.project(snapshot, status, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        val result = raw.copy(automatic = raw.automatic.copy(busy = commandAdmission.busy()),
            fcm = raw.fcm.copy(busy = commandAdmission.busy()))
        mutable.value = snapshot
        projected.value = result
        AutomaticProtectionRuntime.publishDaemonState(result.automatic)
        FcmProtectionRuntime.publishDaemonState(result.fcm)
        observed?.invoke(snapshot, status, result)
        return snapshot
    }

    private fun unavailable() {
        // Retain the last configuration in UI; never turn a failed RPC into saved OFF/empty choices.
        val stopping = unsavedStop.current() != null || runCatching { pointerStore!!.readStopIntent() != null }.getOrDefault(true)
        val automatic = AutomaticProtectionRuntime.state.value.copy(enabled = false,
            busy = false, waitingForPrivilege = false, waitingForService = true, coreSessionStartedAtElapsedMillis = null,
            waitingForReconciliation = stopping, revocationSaveFailed = stopping,
            detail = if (stopping) "关闭结果待确认" else "保护服务暂不可用")
        val fcm = FcmProtectionRuntime.state.value.copy(busy = false,
            maintenance = io.github.originosfcmguardian.fcmprotection.FcmMaintenanceState.WAITING_HOST,
            recoveryReady = false, detail = "等待保护服务")
        AutomaticProtectionRuntime.publishDaemonState(automatic)
        FcmProtectionRuntime.publishDaemonState(fcm)
        projected.value?.let { prior -> projected.value = prior.copy(automatic = automatic, fcm = fcm, hostReady = false,
            heartbeatUi = prior.heartbeatUi.copy(active = false, waitingForHost = true, resumeAuthorized = false,
                busy = false, manualBlocked = true, detail = "等待保护服务"),
            recoveryUi = prior.recoveryUi.copy(detail = "等待保护服务")) }
    }
}
