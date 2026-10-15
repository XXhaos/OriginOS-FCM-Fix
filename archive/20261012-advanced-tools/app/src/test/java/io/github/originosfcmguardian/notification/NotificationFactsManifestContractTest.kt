package io.github.originosfcmguardian.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactsManifestContractTest {
    @Test
    fun listenerIsProtectedBySystemSignaturePermissionAndOfficialAction() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val service = Regex(
            "GuardianNotificationListenerService[\\s\\S]{0,400}</service>",
        ).find(manifest)?.value.orEmpty()

        assertTrue(service.contains("android:exported=\"true\""))
        assertTrue(service.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(service.contains("android.service.notification.NotificationListenerService"))
    }

    private fun projectFile(relative: String): File {
        val local = File(relative)
        if (local.isFile) return local
        return File("app", relative).also { require(it.isFile) { "Missing test input: $relative" } }
    }
}
