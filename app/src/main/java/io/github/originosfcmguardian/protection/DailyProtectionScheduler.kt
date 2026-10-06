package io.github.originosfcmguardian.protection

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.originosfcmguardian.BuildConfig
import java.util.concurrent.TimeUnit

object DailyProtectionScheduler {
    const val UNIQUE_DAILY_WORK = "guardian_daily_read_only_protection_v1"
    const val UNIQUE_EVENT_WORK = "guardian_event_read_only_protection_v1"

    fun ensureDaily(context: Context): Operation {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<DailyProtectionWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            // The app-start/event worker performs the initial check; avoid two immediate full scans.
            .setInitialDelay(24, TimeUnit.HOURS)
            .setInputData(reasonData(ProtectionCheckReason.DAILY_FALLBACK))
            .build()
        return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_DAILY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueEvent(
        context: Context,
        reason: ProtectionCheckReason,
        replaceRunning: Boolean = false,
    ): Operation {
        val request = OneTimeWorkRequestBuilder<DailyProtectionWorker>()
            .setInputData(reasonData(reason))
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_EVENT_WORK,
            if (replaceRunning) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Explicit user action supersedes a possibly stale/unavailable in-flight event check. */
    fun enqueueManual(context: Context): Operation =
        enqueueEvent(context, ProtectionCheckReason.MANUAL, replaceRunning = true)

    private fun reasonData(reason: ProtectionCheckReason): Data =
        Data.Builder().putString(DailyProtectionWorker.KEY_REASON, reason.name).build()
}

class DailyProtectionEvents(private val context: Context) {
    private val store = DailyProtectionStore(context)

    fun onAppStart() {
        DailyProtectionScheduler.ensureDaily(context)
        val old = store.eventStamps()
        val current = currentStamps(old.shizukuAvailable)
        val changes = decideEvents(old, current).toMutableList()
        val persistedReportFingerprint = store.load()?.fingerprint
        if (
            persistedReportFingerprint != null &&
            persistedReportFingerprint != current.fingerprint &&
            ProtectionCheckReason.BUILD_CHANGED !in changes
        ) {
            changes.add(0, ProtectionCheckReason.BUILD_CHANGED)
        }
        if (ProtectionCheckReason.BUILD_CHANGED in changes) {
            store.invalidateForBuildChange(System.currentTimeMillis(), current.fingerprint)
        }
        store.saveEventStamps(current)
        val reason = changes.firstOrNull() ?: ProtectionCheckReason.APP_START
        DailyProtectionScheduler.enqueueEvent(
            context,
            reason,
            replaceRunning = changes.isNotEmpty(),
        )
    }

    fun onShizukuAvailableTransition(available: Boolean) {
        val old = store.eventStamps()
        val current = old.copy(shizukuAvailable = available)
        store.saveEventStamps(current)
        if (isShizukuAvailableTransition(old.shizukuAvailable, available)) {
            // Do not let an already-running unavailable check permanently consume this wake-up.
            DailyProtectionScheduler.enqueueEvent(
                context,
                ProtectionCheckReason.SHIZUKU_AVAILABLE,
                replaceRunning = true,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun currentStamps(shizukuAvailable: Boolean?): ProtectionEventStamps {
        return ProtectionEventStamps(
            fingerprint = Build.FINGERPRINT,
            guardianVersion = BuildConfig.VERSION_CODE.toLong(),
            gmsVersion = -1L, // Historical field retained; GMS version is no longer probed.
            shizukuAvailable = shizukuAvailable,
        )
    }
}

internal fun decideEvents(
    old: ProtectionEventStamps,
    current: ProtectionEventStamps,
): List<ProtectionCheckReason> = buildList {
    if (old.fingerprint.isNotEmpty() && old.fingerprint != current.fingerprint) {
        add(ProtectionCheckReason.BUILD_CHANGED)
    }
    if (old.fingerprint.isNotEmpty() && old.guardianVersion != current.guardianVersion) {
        add(ProtectionCheckReason.GUARDIAN_VERSION_CHANGED)
    }

}

internal fun isShizukuAvailableTransition(old: Boolean?, current: Boolean): Boolean =
    old != true && current
