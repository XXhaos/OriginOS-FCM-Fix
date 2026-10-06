package io.github.originosfcmguardian.autoprotection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Normal credential-protected boot delivery is after first unlock; no direct-boot writes. */
class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val live = AtomicBoolean(true)
        val main = Handler(Looper.getMainLooper())
        val finish = Runnable { if (live.compareAndSet(true, false)) pending.finish() }
        main.postDelayed(finish, 8_000)
        AutomaticProtectionRuntime.launchSavedFromTrigger(context, { live.get() }) {
            main.removeCallbacks(finish)
            finish.run()
        }
    }
}
