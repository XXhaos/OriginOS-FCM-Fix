package io.github.originosfcmguardian.pushactivation

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionRuntime
import io.github.originosfcmguardian.daemon.DaemonAppRuntime
import io.github.originosfcmguardian.daemon.DaemonFcmCommandOutcome
import io.github.originosfcmguardian.fcmprotection.FcmProtectionRuntime
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryRuntime
import io.github.originosfcmguardian.gmsheartbeat.GmsAutoHeartbeatRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import rikka.shizuku.Shizuku

/** Explicit ON survives screen exit and process restart; it does not replace any authority store. */
internal object PushActivationRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Any()
    private val mutable = MutableStateFlow(PushActivationUiState())
    val state = mutable.asStateFlow()
    @Volatile private var controller: PushActivationController? = null

    fun initialize(context: Context) = synchronized(initialization) {
        if (controller != null) return@synchronized
        val app = context.applicationContext
        val port = AndroidPort(app)
        val created = PushActivationController(PushActivationStore(AndroidPushActivationStorage(app)), port, scope)
        controller = created
        scope.launch { created.state.collect { mutable.value = it } }
        scope.launch {
            merge(AutomaticProtectionRuntime.state.map { Unit }, FcmProtectionRuntime.state.map { Unit },
                DaemonAppRuntime.state.map { Unit }, GmsAutoHeartbeatRuntime.state.map { Unit },
                FcmRecoveryRuntime.state.map { Unit }).collect { created.changed() }
        }
        Shizuku.addBinderReceivedListenerSticky { created.changed() }
        Shizuku.addBinderDeadListener { created.changed() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) { port.permissionGranted(); created.onForeground() }
            else if (port.hadPermission()) created.privilegeRevoked() else created.changed()
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = created.changed()
                override fun onLost(network: Network) = created.changed()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = created.changed()
            })
        }
        created.initialize()
    }

    /** Awaiting this result is optional; the save and continuation belong to the App process. */
    suspend fun confirmExplicit(context: Context): Boolean {
        initialize(context)
        return checkNotNull(controller).confirmExplicitAsync().await()
    }

    /** Caller also invokes its existing master/FCM OFF path; this fence never waits on IO. */
    fun cancelExplicit(context: Context) {
        initialize(context)
        checkNotNull(controller).cancelExplicit()
        // Present OFF immediately, independently of the collector's IO dispatcher scheduling.
        mutable.value = checkNotNull(controller).state.value
    }

    fun cancellationToken(context: Context): Long {
        initialize(context)
        return checkNotNull(controller).cancellationToken()
    }

    fun cancelIfCurrent(context: Context, token: Long, admit: () -> Boolean = { true }): Boolean {
        initialize(context)
        val active = checkNotNull(controller)
        if (!active.cancelExplicit(token, admit)) return false
        mutable.value = active.state.value
        return true
    }

    fun onForeground(context: Context) { initialize(context); controller?.onForeground() }

    private data class Epoch(val remote: Boolean, val owner: Any?, val session: Long?, val binder: Any?,
        val network: Network?, val networkReady: Boolean)

    private class AndroidPort(private val app: Context) : PushActivationPort {
        @Volatile private var granted = false
        @Volatile private var lastAttempt: PushActivationAttemptDiagnostic? = null
        override fun attemptDiagnostic() = lastAttempt
        fun permissionGranted() { granted = true }
        fun hadPermission() = granted
        override fun confirmed() {
            lastAttempt = null
            granted = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        }
        override fun installation() = readPushActivationInstallation(app)

        override fun readiness(): PushActivationReadiness {
            val remote = DaemonAppRuntime.isRemoteOwned(app)
            val projected = DaemonAppRuntime.state.value.takeIf { remote }
            val automatic = if (remote) projected?.automatic ?: AutomaticProtectionRuntime.state.value else AutomaticProtectionRuntime.state.value
            val fcm = if (remote) projected?.fcm ?: FcmProtectionRuntime.state.value else FcmProtectionRuntime.state.value
            val heartbeat = if (remote) projected?.heartbeatUi ?: GmsAutoHeartbeatRuntime.state.value else GmsAutoHeartbeatRuntime.state.value
            val recovery = if (remote) projected?.recoveryUi ?: FcmRecoveryRuntime.state.value else FcmRecoveryRuntime.state.value
            val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val permission = if (alive) runCatching { Shizuku.checkSelfPermission() }.getOrNull() else null
            if (permission == PackageManager.PERMISSION_GRANTED) granted = true
            val shell = alive && permission == PackageManager.PERMISSION_GRANTED && runCatching { Shizuku.getUid() == 2000 }.getOrDefault(false)
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val network = runCatching { cm?.activeNetwork }.getOrNull()
            val networkReady = runCatching {
                val capabilities = network?.let { cm?.getNetworkCapabilities(it) }
                capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.getOrDefault(false)
            val epoch = Epoch(remote, DaemonAppRuntime.snapshots.value?.control?.owner.takeIf { remote },
                automatic.coreSessionStartedAtElapsedMillis, runCatching { Shizuku.getBinder() }.getOrNull(), network, networkReady)
            if (remote && projected == null) return PushActivationReadiness(epoch,
                block = if (granted && permission == PackageManager.PERMISSION_DENIED) PushActivationReason.PRIVILEGE_REVOKED else null,
                masterKnown = false)
            val block = if (granted && permission == PackageManager.PERMISSION_DENIED) PushActivationReason.PRIVILEGE_REVOKED
                else pushActivationBlockReason(automatic, fcm, heartbeat, recovery,
                    DaemonAppRuntime.snapshots.value.takeIf { remote })
            val hostReady = automatic.configurationAvailable && automatic.masterEnabled && automatic.enabled && !automatic.waitingForPrivilege &&
                !automatic.waitingForService && automatic.coreSessionStartedAtElapsedMillis != null &&
                (!remote || projected?.hostReady == true)
            val idle = !automatic.busy && !fcm.busy && !heartbeat.busy && !recovery.busy
            return PushActivationReadiness(epoch, masterOn = automatic.masterEnabled,
                ready = block == null && hostReady && idle && shell && networkReady,
                configured = block == null && hostReady && fcm.configured && !fcm.requiresConfirmation,
                canStartMaster = block == null && automatic.configurationAvailable && !automatic.masterEnabled && idle && shell,
                block = block, masterKnown = automatic.configurationAvailable)
        }

        override fun startMaster() {
            if (DaemonAppRuntime.isRemoteOwned(app)) DaemonAppRuntime.enableMaster(app)
            else AutomaticProtectionRuntime.enableMaster(app)
        }

        override suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch = coroutineScope {
            if (DaemonAppRuntime.isRemoteOwned(app)) {
                var command: Deferred<io.github.originosfcmguardian.daemon.DaemonFcmCommandResult>? = null
                if (!admit { command = DaemonAppRuntime.enableFcm(app) }) return@coroutineScope PushActivationDispatch.UNKNOWN
                val result = checkNotNull(command).await()
                lastAttempt = PushActivationAttemptDiagnostic(result.outcome.name, result.reason.name,
                    result.preparationReason?.name, result.readUnknown?.name)
                remoteActivationResult(result.outcome, current() && readiness().configured)
            } else {
                lastAttempt = null
                var command: Deferred<Boolean>? = null
                var authorityWriteStarted = false
                if (!admit { command = async(start = CoroutineStart.LAZY) {
                    currentCoroutineContext().ensureActive()
                    if (!current()) false else FcmProtectionRuntime.confirm(app) { authorityWriteStarted = true }
                } }) return@coroutineScope PushActivationDispatch.UNKNOWN
                val configured = checkNotNull(command).await()
                val fcm = FcmProtectionRuntime.state.value
                val heartbeat = GmsAutoHeartbeatRuntime.state.value
                val recovery = FcmRecoveryRuntime.state.value
                localActivationResult(configured && current() && readiness().configured, authorityWriteStarted,
                    storageBlocked = fcm.storageBlocked || recovery.storageBlocked,
                    noAuthority = !fcm.configured && !fcm.busy && !heartbeat.configured && !heartbeat.stopPending &&
                        !heartbeat.busy && !recovery.configured && !recovery.busy)
            }
        }
    }

}
