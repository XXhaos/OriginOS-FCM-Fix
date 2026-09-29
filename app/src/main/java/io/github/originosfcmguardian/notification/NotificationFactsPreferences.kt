package io.github.originosfcmguardian.notification

import android.content.Context
import android.content.SharedPreferences

internal interface NotificationFactsPreferences {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean): Boolean
    fun registerListener(listener: () -> Unit): AutoCloseable
}

internal class AndroidNotificationFactsPreferences(context: Context) : NotificationFactsPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()

    override fun registerListener(listener: () -> Unit): AutoCloseable {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_facts_preferences_v1"
        const val KEY_ENABLED = "enabled"
    }
}
