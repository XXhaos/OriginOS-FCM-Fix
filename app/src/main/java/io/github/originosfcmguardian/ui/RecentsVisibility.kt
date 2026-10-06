package io.github.originosfcmguardian.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/** Changes only our own task presentation, not services, package state or OEM power policy. */
internal object RecentsVisibility {
    private const val FILE = "guardian_task_presentation"
    private const val KEY = "hide_recents"
    fun hidden(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun apply(activity: Activity): Boolean = runCatching {
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
        check(tasks.isNotEmpty())
        tasks.forEach { it.setExcludeFromRecents(hidden(activity)) }
        true
    }.getOrDefault(false)

    fun set(activity: Activity, hidden: Boolean): Boolean {
        val old = this.hidden(activity)
        return runCatching {
            val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
            check(tasks.isNotEmpty())
            tasks.forEach { it.setExcludeFromRecents(hidden) }
            check(activity.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, hidden).commit())
            true
        }.getOrElse {
            runCatching { activity.getSystemService(ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(old) } }
            false
        }
    }
}
