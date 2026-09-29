package io.github.originosfcmguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest qualification probe only. Receiving boot deliberately performs no work. */
class AutostartProbeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
