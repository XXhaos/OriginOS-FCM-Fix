package io.github.originosfcmguardian.autostart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartSettingsLauncherTest {
    @Test
    fun onlyExactVerifiedComponentIsAccepted() {
        assertTrue(
            isExpectedAutostartSettingsComponent(
                VIVO_AUTOSTART_SETTINGS_PACKAGE,
                VIVO_AUTOSTART_SETTINGS_CLASS,
            ),
        )
        assertFalse(
            isExpectedAutostartSettingsComponent(
                "com.iqoo.secure",
                VIVO_AUTOSTART_SETTINGS_CLASS,
            ),
        )
        assertFalse(
            isExpectedAutostartSettingsComponent(
                VIVO_AUTOSTART_SETTINGS_PACKAGE,
                "com.vivo.permissionmanager.activity.OtherActivity",
            ),
        )
        assertFalse(isExpectedAutostartSettingsComponent(null, null))
    }
}
