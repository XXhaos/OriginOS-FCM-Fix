package io.github.originosfcmguardian.autoprotection

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.originosfcmguardian.MainActivity
import io.github.originosfcmguardian.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A normal, non-active tile: panel listening is an opportunity, never a residency guarantee. */
class GuardianTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var observation: Job? = null
    private val controller by lazy {
        GuardianTileController(throttle, SystemClock::elapsedRealtime,
            AutomaticProtectionRuntime::hasSession,
            probe = { complete ->
                val app = applicationContext
                if (probeInFlight.compareAndSet(false, true)) probeExecutor.execute {
                    // Existing readiness check reads current authority without changing retained records; never
                    // reserves an attempt, grants consent or writes to a target app/system setting.
                    try {
                        val result = runCatching {
                            ProtectionStartupProbe(app, ProtectionConsentStore(AtomicProtectionAuthorityStorage(app))).read()
                        }.getOrElse { StartupProbeResult(null, StartupEligibilityStatus.BLOCKED_STORAGE) }
                        main.post { complete(result) }
                    } finally { probeInFlight.set(false) }
                }
            },
            restore = { mayLaunch, finished ->
                AutomaticProtectionRuntime.launchSavedFromTrigger(applicationContext, mayLaunch, finished)
            },
            publish = ::publish,
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        controller.startListening()
        observation?.cancel()
        observation = scope.launch { AutomaticProtectionRuntime.state.collect { controller.refresh() } }
    }

    override fun onStopListening() {
        controller.stopListening()
        observation?.cancel()
        observation = null
        super.onStopListening()
    }

    override fun onTileRemoved() {
        controller.stopListening()
        observation?.cancel()
        observation = null
        super.onTileRemoved()
    }

    override fun onDestroy() {
        controller.stopListening()
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
        val presentation = guardianTilePresentation(AutomaticProtectionRuntime.state.value,
            AutomaticProtectionRuntime.isActuallyProtecting(), startupStatus)
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
        private val throttle = GuardianTileThrottle()
        // A stuck Binder/read must not accumulate a queue on repeated panel openings.
        private val probeInFlight = AtomicBoolean(false)
        private val probeExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "guardian-tile-probe").apply { isDaemon = true }
        }
    }
}
