package io.github.originosfcmguardian.doze

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only. One independent debug shell read; no writer or production session. */
@LargeTest
class DozeOwnerReadOnlyDeviceTest {
    @Test(timeout = 50_000)
    fun fixedSettingsReadReturnsSmallUnknownInventorySummary() {
        val optedIn = InstrumentationRegistry.getArguments().getString("guardianRunDozeOwnerReadOnly") == "true"
        if (!optedIn) status("SKIPPED;reason=OPT_IN_REQUIRED;inventory=UNKNOWN;write=false")
        assumeTrue("Explicit guardianRunDozeOwnerReadOnly=true is required", optedIn)
        assumeTrue("Matching debug build/user0 is required", BuildConfig.DEBUG && Process.myUid() in 10_000..99_999 &&
            Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
        val result = DozeOwnerReadOnlyClient(InstrumentationRegistry.getInstrumentation().targetContext).read()
        val summary = result.summary
        status("result=${summary.code};inventory=${summary.inventory};activeRecords=${summary.activeRecords};" +
            "hiddenSystemRecords=${summary.hiddenSystemRecords};retainedRecords=${summary.retainedRecords};" +
            "canonicalPackages=${summary.canonicalPackages};renameRows=${summary.renameRows};reportedUsers=${summary.reportedUsers};" +
            "normalizedSha256=${summary.normalizedSha256};requestedAtMillis=${summary.requestedAtMillis};" +
            "finishedAtMillis=${summary.finishedAtMillis};elapsedMillis=${summary.elapsedMillis};" +
            "cleanupCompleted=${result.cleanupCompleted};globalOwner=NOT_VERIFIED;fcmEffect=NOT_VERIFIED;write=false")
        assertTrue("Independent shell cleanup must complete", result.cleanupCompleted)
        assertEquals("UNKNOWN", summary.inventory)
        assertEquals(DozeOwnerReadOnlyCode.PARSED, summary.code)
        assertTrue(summary.parsedComplete && summary.isValidReadOnlySummary())
    }

    private fun status(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("guardian.doze.owner.readOnly", value) })
}
