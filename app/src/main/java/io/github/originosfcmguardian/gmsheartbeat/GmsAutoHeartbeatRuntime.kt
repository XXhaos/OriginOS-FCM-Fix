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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(GmsAutoHeartbeatUiState())
    val state: StateFlow<GmsAutoHeartbeatUiState> = mutable.asStateFlow()
    private var controller: GmsAutoHeartbeatController? = null
    private var hostId: String? = null
    private var hostGrant: AutomaticProtectionGrant? = null

    @Synchronized private fun controller(context: Context): GmsAutoHeartbeatController {
        controller?.let { return it }
        return GmsAutoHeartbeatController(GmsAutoHeartbeatStore(AtomicHeartbeatStorage(context.applicationContext)),
            scope, SystemClock::elapsedRealtime).also { engine ->
            controller = engine
            scope.launch { engine.state.collect { mutable.value = it } }
            val initialManualSerial = GmsManualHeartbeatEvents.serial.value
            scope.launch { GmsManualHeartbeatEvents.serial.collect { if (it > initialManualSerial) engine.manualHeartbeatChanged() } }
            val initialUnknownSerial = GmsManualHeartbeatEvents.unknownSerial.value
            scope.launch { GmsManualHeartbeatEvents.unknownSerial.collect { if (it > initialUnknownSerial) engine.manualUnknown() } }
        }
    }

    fun initialize(context: Context) { val engine = controller(context); scope.launch { engine.initialize() } }
    suspend fun confirm(context: Context): Boolean = withContext(Dispatchers.IO) { controller(context).confirm() }
    suspend fun confirmDurable(context: Context): Boolean = withContext(Dispatchers.IO) { controller(context).confirmDurable() }
    fun disable(context: Context) { controller(context).disable() }
    suspend fun manualHeartbeatAllowed(context: Context): Boolean = withContext(Dispatchers.IO) {
        controller(context).manualHeartbeatAllowed()
    }
    fun manualHeartbeatAllowedNow(): Boolean = synchronized(this) { controller }?.manualHeartbeatAllowedNow() == true

    internal fun attachHost(context: Context, grant: AutomaticProtectionGrant) {
        val engine = controller(context)
        engine.attachHost(newHost(context, grant))
        synchronized(this) { hostId = grant.id; hostGrant = grant }
        scope.launch { engine.initialize() }
    }

    private fun newHost(context: Context, grant: AutomaticProtectionGrant) = HeartbeatHost(grant.id, {
            AutomaticProtectionRuntime.authorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting() &&
                AutomaticProtectionRuntime.consentsAuthorized(grant) && AutomaticProtectionRuntime.isActuallyProtecting()
        }, SessionBackend(context.applicationContext))

    internal fun hostEligible(id: String) { synchronized(this) { controller }?.hostEligible(id) }

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
        private val executor = ShizukuExecutor(context)
        private var lifetime: Binder? = null
        @Volatile override var lastBindingRejection: GmsHeartbeatReason? = null
            private set
        override val callerQualificationRequired = true
        override val manualUnknownSerial: Long get() = GmsManualHeartbeatEvents.unknownSerial.value
        override suspend fun qualifyCaller(previous: GmsHeartbeatCallerIdentity?): GmsHeartbeatCallerQualification =
            ReadSessionCoordinator.withSession {
                withHeartbeatReadCleanup({ executor.retireGmsConfiguration() }) {
                    executor.openSession()
                    executor.qualifyHeartbeatCaller(previous)
                }
            }
        override suspend fun freshBindingDigest(): String? = ReadSessionCoordinator.withSession {
            withHeartbeatReadCleanup({ executor.retireGmsConfiguration() }) {
                executor.openSession()
                val observation = executor.readGmsConnection()
                lastBindingRejection = GmsHeartbeatPolicy.rejection(observation, SystemClock.elapsedRealtime())
                if (lastBindingRejection != null) null
                else gmsHeartbeatBindingDigest(observation as GmsConnectionObservation.Sample)
            }
        }
        override suspend fun arm(generation: Long, digest: String): GmsAutoArmResult {
            val nextLifetime = Binder()
            lifetime = nextLifetime
            return executor.armAutomaticGmsHeartbeat(generation, digest, nextLifetime)
        }
        override suspend fun request(session: GmsAutoSession, attempt: String): GmsHeartbeatResult =
            executor.requestAutomaticGmsHeartbeat(session, attempt)
        override suspend fun revoke(session: GmsAutoSession): Boolean = executor.revokeAutomaticGmsHeartbeat(session)
        // This non-owner executor's observation binding is short-lived and retired above.
        // All write APIs delegate to the separate shared heartbeat transport: never clean it up
        // here, and retain the lifetime until original-endpoint revoke/death is acknowledged.
    }
}
