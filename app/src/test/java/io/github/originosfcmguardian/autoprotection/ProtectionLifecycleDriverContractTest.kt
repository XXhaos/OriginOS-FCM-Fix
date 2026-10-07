package io.github.originosfcmguardian.autoprotection

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProtectionLifecycleDriverContractTest {
    private fun source(relative: String): String = File("src/$relative").let { local ->
        (if (local.isFile) local else File("app/src/$relative")).readText()
    }
    @Test fun `test driver has fixed debug DUMP boundary and never writes or starts service`() {
        val manifest = source("debug/AndroidManifest.xml")
        val main = source("main/AndroidManifest.xml")
        val driver = source("debug/java/io/github/originosfcmguardian/autoprotection/DebugProtectionLifecycleReceiver.kt")
        assertTrue(manifest.contains("DebugProtectionLifecycleReceiver"))
        assertTrue(manifest.contains("android.permission.DUMP"))
        assertFalse(main.contains("DebugProtectionLifecycleReceiver"))
        assertTrue(driver.contains("intent.extras?.isEmpty == false"))
        assertTrue(driver.contains("captureRecoveryRevision() == revision"))
        assertTrue(driver.contains("StartupEligibilityStatus.READY_TO_START"))
        assertTrue(driver.contains("app.stopService(Intent(app, AutomaticProtectionService::class.java))"))
        assertFalse(driver.contains("enableMaster("))
        assertFalse(driver.contains("startForegroundService("))
        assertFalse(driver.contains("suspendLease("))
    }
}
