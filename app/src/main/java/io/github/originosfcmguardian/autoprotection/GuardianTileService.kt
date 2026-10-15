package io.github.originosfcmguardian.autoprotection

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.originosfcmguardian.MainActivity
import io.github.originosfcmguardian.R
import io.github.originosfcmguardian.daemon.DaemonAppRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** A normal, non-active tile: panel listening is a bounded recovery opportunity, never a residency guarantee. */
class GuardianTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var observation: Job? = null
    private var remoteRefresh: Job? = null
    private var remoteListening = false
    private val controller by lazy {
        GuardianTileController(opportunity, SystemClock::elapsedRealtime,
            AutomaticProtectionRuntime::hasSession,
            AutomaticProtectionRuntime::captureRecoveryRevision,
            probe = { complete ->
                val app = applicationContext
                probeExecutor.execute {
                    // Existing readiness check reads current authority without changing retained records; never
                    // reserves an attempt, grants consent or writes to a target app/system setting.
                    val result = runCatching {
                        ProtectionStartupProbe(app, ProtectionConsentStore(AtomicProtectionAuthorityStorage(app))).read()
                    }.getOrElse { StartupProbeResult(null, StartupEligibilityStatus.BLOCKED_STORAGE) }
                    main.post { complete(result) }
                }
            },
            restore = { expectedRevision, mayLaunch, finished ->
                AutomaticProtectionRuntime.launchSavedFromTrigger(applicationContext, mayLaunch, finished,
                    expectedRevision = expectedRevision)
            },
            publish = ::publish,
            record = { ProtectionLifecycleDiagnostics.record(it) },
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        if (DaemonAppRuntime.isRemoteOwned(this)) {
            remoteListening = true
            // Same persisted-intent semantics as GKD's listening recovery: reconnect, never ON.
            if (remoteReconnectThrottle.acquire(SystemClock.elapsedRealtime()))
                DaemonAppRuntime.restore(applicationContext)
            observation?.cancel()
            observation = scope.launch { DaemonAppRuntime.state.collect { snapshot ->
                publishPresentation(guardianRemoteTilePresentation(snapshot))
            } }
            remoteRefresh?.cancel()
            remoteRefresh = scope.launch {
                // Covers the first ordinary daemon poll; no task survives panel listening.
                withTimeout(45_000) {
                    refreshRemoteGuardianTile({ remoteListening }, {
                        runCatching { Shizuku.pingBinder() && Shizuku.getUid() == 2000 &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                    }, {
                        DaemonAppRuntime.refresh(applicationContext)
                        DaemonAppRuntime.state.value
                    })
                }
            }
            return
        }
        remoteRefresh?.cancel()
        remoteRefresh = null
        controller.startListening()
        observation?.cancel()
        observation = scope.launch { AutomaticProtectionRuntime.state.collect { controller.refresh() } }
    }

    override fun onStopListening() {
        // Observation ends here; the process-wide qualified recovery keeps its original deadline/revision.
        if (!remoteListening) controller.stopListening()
        remoteListening = false
        remoteRefresh?.cancel()
        remoteRefresh = null
        observation?.cancel()
        observation = null
        super.onStopListening()
    }

    override fun onTileRemoved() {
        if (!remoteListening) controller.stopListening()
        remoteListening = false
        remoteRefresh?.cancel()
        remoteRefresh = null
        observation?.cancel()
        observation = null
        super.onTileRemoved()
    }

    override fun onDestroy() {
        if (!remoteListening) controller.stopListening()
        remoteListening = false
        remoteRefresh?.cancel()
        remoteRefresh = null
        scope.cancel()
        super.onDestroy()
    }

    /** The same ordinary activity entry applies when locked. Clicking never creates consent. */
    // PendingIntent overload exists only on API34+. The legacy call is unreachable there.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else startActivityAndCollapse(intent)
        }
    }

    private fun publish(startupStatus: StartupEligibilityStatus?) {
        val presentation = if (DaemonAppRuntime.isRemoteOwned(this)) guardianRemoteTilePresentation(DaemonAppRuntime.state.value)
            else guardianTilePresentation(AutomaticProtectionRuntime.state.value,
                AutomaticProtectionRuntime.isActuallyProtecting(), startupStatus)
        publishPresentation(presentation)
    }

    private fun publishPresentation(presentation: GuardianTilePresentation) {
        qsTile?.apply {
            icon = Icon.createWithResource(this@GuardianTileService, R.drawable.ic_guardian_tile)
            label = GUARDIAN_TILE_LABEL
            subtitle = presentation.subtitle
            contentDescription = "$GUARDIAN_TILE_LABEL，${presentation.subtitle}"
            state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        private val opportunity = GuardianTileRecoveryOpportunity()
        private val remoteReconnectThrottle = GuardianTileThrottle()
        // A stuck Binder/read must not accumulate a queue on repeated panel openings.
        private val probeExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "guardian-tile-probe").apply { isDaemon = true }
        }
    }
}
