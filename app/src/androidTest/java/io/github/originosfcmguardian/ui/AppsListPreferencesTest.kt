package io.github.originosfcmguardian.ui

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses isolated preferences in the test package; never changes installed protection settings. */
class AppsListPreferencesTest {
    @Test fun defaultsOffAndPersistsBothChoicesAcrossContextRecreation() {
        val base = InstrumentationRegistry.getInstrumentation().context
        val suffix = "-fixture-${UUID.randomUUID()}"
        val files = mutableSetOf<String>()
        fun freshContext() = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(name + suffix, mode).also { files += name + suffix }
        }
        try {
            assertFalse(AppsListPreferences.showSystemApps(freshContext()))
            assertTrue(AppsListPreferences.setShowSystemApps(freshContext(), true))
            assertTrue(AppsListPreferences.showSystemApps(freshContext()))
            assertTrue(AppsListPreferences.setShowSystemApps(freshContext(), false))
            assertFalse(AppsListPreferences.showSystemApps(freshContext()))
            assertTrue(freshContext().getSharedPreferences("apps_list_preferences_v1", 0).edit()
                .putString("show_system_apps", "invalid").commit())
            assertFalse(AppsListPreferences.showSystemApps(freshContext()))
            assertTrue(AppsListPreferences.setShowSystemApps(freshContext(), true))
            assertTrue(AppsListPreferences.showSystemApps(freshContext()))
        } finally {
            files.forEach { base.deleteSharedPreferences(it) }
        }
    }

    @Test fun inaccessiblePreferencesFallBackOffAndRejectSave() {
        val base = InstrumentationRegistry.getInstrumentation().context
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("Synthetic inaccessible preferences")
        }
        assertFalse(AppsListPreferences.showSystemApps(context))
        assertFalse(AppsListPreferences.setShowSystemApps(context, true))
    }

    @Test fun failedOrThrowingCommitDoesNotReportSuccessfulSave() {
        val base = InstrumentationRegistry.getInstrumentation().context
        val name = "apps-list-commit-fixture-${UUID.randomUUID()}"
        val preferences = base.getSharedPreferences(name, 0)
        try {
            for (throws in listOf(false, true)) {
                val failing = object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            // Keep the wrapper through the fluent setter; never dispatch a real commit.
                            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                                this.also { editor.putBoolean(key, value) }
                            override fun commit(): Boolean {
                                if (throws) throw IllegalStateException("Synthetic failed commit")
                                return false
                            }
                        }
                    }
                }
                val context = object : ContextWrapper(base) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failing
                }
                assertFalse(AppsListPreferences.setShowSystemApps(context, true))
                assertFalse(AppsListPreferences.showSystemApps(context))
            }
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
