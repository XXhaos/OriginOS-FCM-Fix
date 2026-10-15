package io.github.originosfcmguardian.gmsheartbeat

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionGrant
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.privileged.GmsManualHeartbeatEvents
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No Activity/ViewModel lifetime owns an automatic heartbeat capability. */
object GmsAutoHeartbeatRuntime {
    @Volatile internal var bundledAdmission: (GmsConnectionObservation.Sample?) -> Boolean = { false }
    @Volatile internal var bundledEpochAdmission: (String) -> Boolean = { false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(GmsAutoHeartbeatUiState())
    val state: StateFlow<GmsAutoHeartbeatUiState> = mutable.asStateFlow()
    private var controller: GmsAutoHeartbeatController? = null
    private var hostId: String? = null
    private var hostGrant: AutomaticProtectionGrant? = null
    @Volatile private var migrationPrepared = false
    @Volatile private var migrationWorkersDrained = false
    private val collectors = mutableListOf<Job>()
    @Volatile private var forwardedUnknownSerial = GmsManualHeartbeatEvents.unknownSerial.value

    @Synchronized internal fun prepareForDaemonMigration() {
        migrationPrepared = true
        migrationWorkersDrained = false
        hostId = null; hostGrant = null
        controller?.prepareForDaemonMigration()
        collectors.forEach { it.cancel() }
    }

    internal suspend fun awaitDaemonMigrationDrain(context: Context): Boolean {
        // If never initialized, inspect the historical root inertly; never reconstruct or arm here.
        val engine = synchronized(this) { controller } ?: GmsAutoHeartbeatController(
            GmsAutoHeartbeatStore(AtomicHeartbeatStorage(context.applicationContext)), scope,
            SystemClock::elapsedRealtime).also { it.prepareForDaemonMigration() }
        if (GmsManualHeartbeatEvents.unknownSerial.value > forwardedUnknownSerial) engine.manualUnknown()
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
        return engine.awaitDaemonMigrationDrain()
    }

    /** Called only after original endpoint RPCs confirm actual worker/child settlement. */
    @Synchronized internal fun markMigrationWorkersDrained() { migrationWorkersDrained = true }

    @Synchronized internal fun migrationEndpointClosureAttested(): Boolean = migrationPrepared &&
        migrationWorkersDrained && controller?.migrationEndpointClosureAttested() == true

    @Synchronized private fun controller(context: Context): GmsAutoHeartbeatController {
        controller?.let { return it }
        return GmsAutoHeartbeatController(GmsAutoHeartbeatStore(AtomicHeartbeatStorage(context.applicationContext)),
            scope, SystemClock::elapsedRealtime).also { engine ->
            controller = engine
            collectors += scope.launch { engine.state.collect { mutable.value = it } }
            val initialManualSerial = GmsManualHeartbeatEvents.serial.value
            collectors += scope.launch { GmsManualHeartbeatEvents.serial.collect { if (it > initialManualSerial) engine.manualHeartbeatChanged() } }
            val initialUnknownSerial = GmsManualHeartbeatEvents.unknownSerial.value
            forwardedUnknownSerial = initialUnknownSerial
            collectors += scope.launch { GmsManualHeartbeatEvents.unknownSerial.collect { if (it > initialUnknownSerial) {
                forwardedUnknownSerial = it; engine.manualUnknown()
            } } }
        }
    }

    fun initialize(context: Context) { if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return; val engine = controller(context); scope.launch { engine.initialize() } }
    suspend fun confirm(context: Context): Boolean = withContext(Dispatchers.IO) { !migrationPrepared && AutomaticProtectionRuntime.localExecutionAllowed(context) && controller(context).confirm() }
    suspend fun confirmDurable(context: Context): Boolean = withContext(Dispatchers.IO) { !migrationPrepared && AutomaticProtectionRuntime.localExecutionAllowed(context) && controller(context).confirmDurable() }
    internal suspend fun provisionBundled(context: Context, epoch: String) = withContext(Dispatchers.IO) {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context) || !bundledAdmission(null)) BundledHeartbeatProvision.DEFERRED else controller(context).provisionBundled(epoch)
    }
    fun disable(context: Context) { if (!migrationPrepared && AutomaticProtectionRuntime.localExecutionAllowed(context)) controller(context).disable() }
    suspend fun manualHeartbeatAllowed(context: Context): Boolean = withContext(Dispatchers.IO) {
        !migrationPrepared && AutomaticProtectionRuntime.localExecutionAllowed(context) && controller(context).manualHeartbeatAllowed()
    }
    fun manualHeartbeatAllowedNow(): Boolean = !migrationPrepared && synchronized(this) { controller }?.manualHeartbeatAllowedNow() == true

    internal fun attachHost(context: Context, grant: AutomaticProtectionGrant) {
        if (migrationPrepared || !AutomaticProtectionRuntime.localExecutionAllowed(context)) return
        val engine = controller(context)
        engine.attachHost(newHost(context, grant))
        synchronized(this) { hostId = grant.id; hostGrant = grant }
        scope.launch { engine.initialize() }
    }

    private fun newHost(context: Context, grant: AutomaticProtectionGrant) = HeartbeatHost(grant.id, {
            bundledAdmission(null) && AutomaticProtectionRuntime.authorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting() &&
                AutomaticProtectionRuntime.consentsAuthorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting()
        }, SessionBackend(context.applicationContext))

    internal fun hostEligible(id: String) { synchronized(this) { controller }?.hostEligible(id) }
    /** Readiness forwarding alone never starts old-consent resume or a fresh read while bundled OFF. */
    internal fun updateHostReadiness(id: String): Boolean = synchronized(this) { hostId == id && hostGrant != null }

    /** Debug receiver gets SUBMITTED only; all durable/fresh/revoke work stays on the normal owner scope. */
    internal suspend fun debugReattachHostForAcceptance(context: Context): Boolean {
        val captured = synchronized(this) { controller to hostGrant }
        val engine = captured.first ?: return false
        val grant = captured.second ?: return false
        if (!AutomaticProtectionRuntime.authorized(grant) || !AutomaticProtectionRuntime.isActuallyProtecting()) return false
        return engine.reattachForAcceptance(newHost(context, grant))
    }

    internal fun hostEnded(id: String) {
        val engine = synchronized(this) {
            if (hostId == id) { hostId = null; hostGrant = null }
            controller
        }
        engine?.endHost(id)
    }
    internal fun privilegeLost(id: String) { synchronized(this) { controller }?.privilegeLost(id) }
    internal fun securityLost(id: String) { synchronized(this) { controller }?.securityLost(id) }

    private class SessionBackend(context: Context) : AutomaticHeartbeatBackend {
        @Volatile private var admittedEpoch: String? = null
        override val bundledEpochRequired = true
        override fun bundleAuthorized(epoch: String?): Boolean {
            if (epoch == null || !bundledEpochAdmission(epoch)) return false
            admittedEpoch = epoch; return true
        }
        private fun epochAuthorized() = admittedEpoch?.let(bundledEpochAdmission) == true
        private val executor = ShizukuExecutor.backgroundObservation(context)
        private var lifetime: Binder? = null
        @Volatile override var lastBindingRejection: GmsHeartbeatReason? = null
            private set
        override val callerQualificationRequired = true
        override val manualUnknownSerial: Long get() = GmsManualHeartbeatEvents.unknownSerial.value
        override suspend fun qualifyCaller(previous: GmsHeartbeatCallerIdentity?): GmsHeartbeatCallerQualification =
            ReadSessionCoordinator.withSession {
                withHeartbeatReadCleanup({ executor.retireObservationSession() }) {
                    executor.openSession()
                    executor.qualifyHeartbeatCaller(previous)
                }
            }
        override suspend fun freshBindingDigest(): String? = ReadSessionCoordinator.withSession {
            withHeartbeatReadCleanup({ executor.retireObservationSession() }) {
                executor.openSession()
                val observation = executor.readGmsConnection()
                lastBindingRejection = GmsHeartbeatPolicy.rejection(observation, SystemClock.elapsedRealtime())
                if (observation is GmsConnectionObservation.Sample && !bundledAdmission(observation))
                    lastBindingRejection = GmsHeartbeatReason.IDENTITY_CHANGED
                if (lastBindingRejection != null) null
                else gmsHeartbeatBindingDigest(observation as GmsConnectionObservation.Sample)
            }
        }
        override suspend fun arm(generation: Long, digest: String): GmsAutoArmResult {
            if (!epochAuthorized() || !bundledAdmission(null)) return GmsAutoArmResult(null, GmsAutoArmReason.NOT_ELIGIBLE)
            val nextLifetime = Binder()
            lifetime = nextLifetime
            return executor.armAutomaticGmsHeartbeat(generation, digest, nextLifetime)
        }
        override suspend fun request(session: GmsAutoSession, attempt: String): GmsHeartbeatResult =
            if (epochAuthorized() && bundledAdmission(null)) executor.requestAutomaticGmsHeartbeat(session, attempt)
            else GmsHeartbeatResult.notSent(1, GmsHeartbeatReason.CANCELLED)
        override suspend fun revoke(session: GmsAutoSession): Boolean = executor.revokeAutomaticGmsHeartbeat(session)
        // This non-owner executor's observation binding is short-lived and retired above.
        // All write APIs delegate to the separate shared heartbeat transport: never clean it up
        // here, and retain the lifetime until original-endpoint revoke/death is acknowledged.
    }
}
