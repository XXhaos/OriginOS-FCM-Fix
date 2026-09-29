package io.github.originosfcmguardian.fcm

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmNotificationPublisherTest {
    @Test
    fun preAndroid13DoesNotConsultRuntimeNotificationPermission() {
        var checked = false

        assertTrue(notificationPermissionGranted(32) {
            checked = true
            PackageManager.PERMISSION_DENIED
        })
        assertFalse(checked)
    }

    @Test
    fun android13AndLaterRequireGrantedRuntimePermission() {
        assertFalse(notificationPermissionGranted(33) { PackageManager.PERMISSION_DENIED })
        assertTrue(notificationPermissionGranted(33) { PackageManager.PERMISSION_GRANTED })
    }
}
