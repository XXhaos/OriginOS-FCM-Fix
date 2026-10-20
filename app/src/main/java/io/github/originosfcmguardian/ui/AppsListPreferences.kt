package io.github.originosfcmguardian.ui

import android.content.Context

/** List presentation only; this preference never changes protection selection or authority. */
internal object AppsListPreferences {
    private const val FILE = "apps_list_preferences_v1"
    private const val SHOW_SYSTEM_APPS = "show_system_apps"

    fun showSystemApps(context: Context): Boolean = try {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_SYSTEM_APPS, false)
    } catch (_: RuntimeException) {
        false
    }

    fun setShowSystemApps(context: Context, show: Boolean): Boolean = try {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(SHOW_SYSTEM_APPS, show).commit()
    } catch (_: RuntimeException) {
        false
    }
}
