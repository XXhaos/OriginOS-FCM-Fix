package io.github.originosfcmguardian.autoprotection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug + DUMP only. Stops one exact idle Guardian service; never edits authority or relaunches. */
class DebugProtectionLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || intent.extras?.isEmpty == false || !inFlight.compareAndSet(false, true)) {
            resultCode = 0
            return
        }
        val app = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val revision = AutomaticProtectionRuntime.captureRecoveryRevision()
        val pending = goAsync()
        val live = AtomicBoolean(true)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable { if (live.compareAndSet(true, false)) { pending.resultCode = 0; pending.finish() } }
        main.postDelayed(timeout, 8_000)
        executor.execute {
            val eligible = runCatching {
                val probe = ProtectionStartupProbe(app, ProtectionConsentStore(AtomicProtectionAuthorityStorage(app))).read()
                probe.status == StartupEligibilityStatus.READY_TO_START && probe.invalidTargets.isEmpty()
            }.getOrDefault(false)
            main.post {
                if (!live.compareAndSet(true, false)) { inFlight.set(false); return@post }
                main.removeCallbacks(timeout)
                try {
                    val idle = AutomaticProtectionRuntime.isActuallyProtecting() && !AutomaticProtectionRuntime.state.value.busy
                    val now = SystemClock.elapsedRealtime()
                    pending.resultCode = if (eligible && idle && now >= startedAt && now - startedAt < 8_000 &&
                        AutomaticProtectionRuntime.captureRecoveryRevision() == revision) {
                        ProtectionLifecycleDiagnostics.record(ProtectionLifecycleEventKind.TEST_SERVICE_STOP)
                        if (app.stopService(Intent(app, AutomaticProtectionService::class.java))) 1 else 0
                    } else 0
                } catch (_: Exception) { pending.resultCode = 0 }
                finally { inFlight.set(false); pending.finish() }
            }
        }
    }

    companion object {
        private const val ACTION = "io.github.originosfcmguardian.DEBUG_STOP_PROTECTION_SERVICE"
        private val inFlight = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "guardian-lifecycle-test").apply { isDaemon = true } }
    }
}
