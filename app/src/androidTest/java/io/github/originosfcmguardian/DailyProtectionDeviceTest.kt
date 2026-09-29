package io.github.originosfcmguardian

import android.os.Build
import android.os.SystemClock
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.protection.DailyProtectionScheduler
import io.github.originosfcmguardian.protection.DailyProtectionStore
import io.github.originosfcmguardian.protection.ProtectionCheckReason
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Explicitly opted-in real WorkManager execution. All production operations are read-only. */
@LargeTest
class DailyProtectionDeviceTest {
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun actualOneTimeWorkerPersistsBoundedReadOnlyResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabled = InstrumentationRegistry.getArguments()
            .getString(RUN_ARGUMENT)
            ?.toBooleanStrictOrNull() == true
        assumeTrue("Explicit opt-in is required", enabled)
        assumeTrue("Autostart semantics are exact-build gated", Build.FINGERPRINT == VERIFIED_AUTOSTART_FINGERPRINT)

        val context = instrumentation.targetContext
        val before = System.currentTimeMillis()
        DailyProtectionScheduler.enqueueManual(context)
            .result
            .get(10, TimeUnit.SECONDS)
        val manager = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + WORK_WAIT_MILLIS
        var finished = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val infos = manager.getWorkInfosForUniqueWork(DailyProtectionScheduler.UNIQUE_EVENT_WORK)
                .get(5, TimeUnit.SECONDS)
            if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                val report = DailyProtectionStore(context).load()
                if (report != null && report.checkedAtEpochMillis >= before) {
                    assertTrue(
                        "At least one execution must succeed without retry",
                        infos.any { it.state == WorkInfo.State.SUCCEEDED },
                    )
                    finished = true
                    break
                }
            }
            SystemClock.sleep(250)
        }
        assertTrue("One-time Daily Protection worker did not finish", finished)

        val report = DailyProtectionStore(context).load()
        assertNotNull(report)
        requireNotNull(report)
        assertTrue(report.checkedAtEpochMillis >= before)
        assertTrue(report.reason == ProtectionCheckReason.MANUAL)
        assertTrue(report.summary.length <= 800)
        assertTrue(report.summary.contains("Google Play 服务"))
    }

    private companion object {
        const val RUN_ARGUMENT = "guardianRunDailyProtection"
        const val WORK_WAIT_MILLIS = 70_000L
        const val TEST_TIMEOUT_MILLIS = 90_000L
    }
}
