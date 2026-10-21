package io.github.originosfcmguardian.autoprotection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** A broadcast deadline releases only its PendingResult; it never cancels a shared restore. */
internal class ProtectionTriggerCompletion(private val finishPending: () -> Unit,
    private val cancelDeadline: (Runnable) -> Unit) {
    private val live = AtomicBoolean(true)
    val deadline = Runnable { finish() }
    fun isLive(): Boolean = live.get()
    fun finish() {
        if (live.compareAndSet(true, false)) {
            cancelDeadline(deadline)
            finishPending()
        }
    }
}

/** Normal credential-protected boot delivery is after first unlock; no direct-boot writes. */
class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val completion = ProtectionTriggerCompletion({ pending.finish() }, { main.removeCallbacks(it) })
        main.postDelayed(completion.deadline, 8_000)
        try {
            if (io.github.originosfcmguardian.daemon.DaemonAppRuntime.isRemoteOwned(context)) {
                val restore = io.github.originosfcmguardian.daemon.DaemonAppRuntime.restore(context)
                if (restore == null) completion.finish()
                else restore.invokeOnCompletion { completion.finish() }
            } else AutomaticProtectionRuntime.launchSavedFromTrigger(context, completion::isLive,
                finished = completion::finish)
        } catch (_: Exception) { completion.finish() }
    }
}
