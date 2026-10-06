package io.github.originosfcmguardian.privilegebridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Isolated probe APK: one opt-in caller-UID transaction, no shell identity/bridge/Activity/recovery. */
class GuardianWssDeviceTest {
    @Test(timeout = 45_000)
    fun actualGuardianUidCanToggleWirelessAndRestoreBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guardianRunWssProbe") == "true")
        assertEquals("wssProbe", BuildConfig.BUILD_TYPE)
        assertTrue(BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        assertEquals("io.github.originosfcmguardian", context.packageName)
        assertEquals(FINGERPRINT, Build.FINGERPRINT)
        assertEquals(10362, Process.myUid())
        assertEquals(Process.myUid(), context.applicationInfo.uid)
        assertEquals(0, Process.myUid() / 100_000)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
        val resolver = context.contentResolver
        fun setting(key: String) = Settings.Global.getString(resolver, key)
        assertEquals("1", setting("adb_enabled"))
        assertEquals("1", setting(WIRELESS))
        val expectedAuthority = requireNotNull(args.getString("guardianAuthoritySha256"))
        val expectedJournal = requireNotNull(args.getString("guardianJournalSha256"))
        require(expectedAuthority.matches(Regex("[0-9a-f]{64}")))
        require(expectedJournal.matches(Regex("[0-9a-f]{64}")))
        val authority = File(context.noBackupFilesDir, "automatic-protection-authority-v1.bin")
        val journal = File(context.noBackupFilesDir, "stopped-recovery-v1.bin")
        fun checkRecords() {
            assertEquals("Authority changed", expectedAuthority, digest(authority))
            assertEquals("Recovery journal changed", expectedJournal, digest(journal))
        }
        fun report(stage: String, accepted: Boolean? = null) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("guardian.wss.stage", stage)
                putInt("guardian.wss.callerUid", Process.myUid())
                putString("guardian.wss.wireless", setting(WIRELESS))
                putString("guardian.wss.usb", setting("adb_enabled"))
                accepted?.let { putBoolean("guardian.wss.apiAccepted", it) }
            })
        }
        fun waitFor(value: String): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (setting(WIRELESS) == value) return true
                SystemClock.sleep(100)
            }
            return setting(WIRELESS) == value
        }
        checkRecords()
        report("BASELINE")
        var offAttempted = false
        var restored = false
        try {
            // Mark before IPC: an exception/false return does not prove absence of side effects.
            offAttempted = true
            val accepted = Settings.Global.putInt(resolver, WIRELESS, 0)
            report("OFF_REQUESTED", accepted)
            assertTrue("OFF did not read back within 5s", waitFor("0"))
            assertEquals("1", setting("adb_enabled"))
            report("OFF_OBSERVED")
            // Bounded host observation window; no permanent polling or wake lock.
            val holdUntil = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < holdUntil) {
                assertEquals("Wireless changed during observation", "0", setting(WIRELESS))
                assertEquals("1", setting("adb_enabled"))
                SystemClock.sleep(200)
            }
            checkRecords()
        } finally {
            if (offAttempted) {
                when (setting(WIRELESS)) {
                    "0" -> {
                        val accepted = Settings.Global.putInt(resolver, WIRELESS, 1)
                        report("RESTORE_REQUESTED", accepted)
                        restored = waitFor("1")
                    }
                    "1" -> restored = true
                    else -> report("RESTORE_UNKNOWN_REQUIRES_USER")
                }
                report(if (restored) "BASELINE_RESTORED" else "RESTORE_FAILED_REQUIRES_USER")
            }
            assertEquals("1", setting("adb_enabled"))
            checkRecords()
            assertTrue("Restore failed: user system UI must restore wireless ON", restored)
        }
    }

    private fun digest(file: File): String {
        require(file.isFile && file.length() in 1..8192)
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val WIRELESS = "adb_wifi_enabled"
        const val FINGERPRINT = "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}
