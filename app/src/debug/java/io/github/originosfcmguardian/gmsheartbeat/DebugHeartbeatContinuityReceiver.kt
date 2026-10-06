package io.github.originosfcmguardian.gmsheartbeat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Debug APK only/DUMP protected. One submission per process, no clock/backend/consent bypass. */
class DebugHeartbeatContinuityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !isOrderedBroadcast) return
        if (intent.extras?.isEmpty == false || !submitted.compareAndSet(false, true)) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "REFUSED"
            return
        }
        val pending = goAsync()
        scope.launch {
            val accepted = runCatching {
                // goAsync does not extend the broadcast lifetime; finishing has priority.
                withTimeoutOrNull(8_000) {
                    GmsAutoHeartbeatRuntime.debugReattachHostForAcceptance(context.applicationContext)
                } == true
            }.getOrDefault(false)
            try {
                pending.setResultCode(if (accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                // Submission is not proof of a new capability: production state must be read back.
                pending.setResultData(if (accepted) "SUBMITTED_PRODUCTION_REATTACH" else "NOT_CONFIRMED")
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "io.github.originosfcmguardian.DEBUG_HEARTBEAT_REATTACH"
        private val submitted = AtomicBoolean(false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
