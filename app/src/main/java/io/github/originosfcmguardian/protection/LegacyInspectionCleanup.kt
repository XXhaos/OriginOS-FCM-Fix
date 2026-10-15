package io.github.originosfcmguardian.protection

import android.content.Context
import androidx.work.WorkManager

/** Upgrade cleanup only. Never creates work or reads historical inspection records. */
internal object LegacyInspectionCleanup {
    fun cancel(context: Context) {
        runCatching {
            val manager = WorkManager.getInstance(context)
            cancelRetiredInspectionWork { name -> manager.cancelUniqueWork(name) }
        }
    }
}

internal fun cancelRetiredInspectionWork(cancelUniqueWork: (String) -> Unit) {
    cancelUniqueWork("guardian_daily_read_only_protection_v1")
    cancelUniqueWork("guardian_event_read_only_protection_v1")
}
