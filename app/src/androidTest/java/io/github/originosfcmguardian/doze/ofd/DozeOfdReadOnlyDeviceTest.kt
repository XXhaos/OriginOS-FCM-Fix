package io.github.originosfcmguardian.doze.ofd

import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** New distinct opt-in; never invokes the accepted 629 effective-owner getter event. */
@LargeTest
class DozeOfdReadOnlyDeviceTest {
    @Test(timeout = 50_000)
    fun independentAppShellOfdAndPfdCloseQualificationOnly() {
        val args = InstrumentationRegistry.getArguments()
        val optedIn = args.getString("guardianRunDozeOfdReadOnly") == "true"
        if (!optedIn) status("code=SKIPPED;reason=OPT_IN_REQUIRED;writerPermission=false;productionAdmission=NOT_QUALIFIED")
        assumeTrue("Explicit guardianRunDozeOfdReadOnly=true is required", optedIn)
        // Fresh PC baseline supplies the expected app UID; deriving it from ourselves is not qualification.
        val uid = args.getString("expectedGuardianAppUID")?.toIntOrNull()
        assertTrue("Fresh expectedGuardianAppUID argument is required", uid != null && uid in 10_000..99_999)
        // Instrumentation recreates the App; provider delivery is asynchronous. This is only
        // a bounded initial-environment wait, not a retry of the fixture or a privilege grant.
        val received = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { received.countDown() }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
            if (!Shizuku.pingBinder()) received.await(3, TimeUnit.SECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        val binderReady = Shizuku.pingBinder()
        if (!binderReady) status("code=NOT_RUN;reason=SHIZUKU_BINDER_NOT_READY;writerPermission=false;productionAdmission=NOT_QUALIFIED")
        assumeTrue("Initial Shizuku binder delivery is required", binderReady)
        val result = DozeOfdReadOnlyClient(InstrumentationRegistry.getInstrumentation().targetContext).run(requireNotNull(uid))
        status("code=${result.code};elapsedMillis=${result.elapsedMillis};cleanupConfirmed=${result.cleanupConfirmed};" +
            "failedStage=${result.diagnostic.stage};failureClass=${result.diagnostic.failureClass};" +
            "failureReason=${result.diagnostic.reason};nativeStatus=${result.diagnostic.nativeStatus?.name ?: "NOT_CALLED"};" +
            "nativeErrno=${result.diagnostic.nativeErrno};" +
            "shellProbeStage=${result.shellProbe.stage};shellProbeFailureClass=${result.shellProbe.failureClass};" +
            "shellBStatus=${result.shellProbe.bStatus?.name ?: "NOT_CALLED"};shellBErrno=${result.shellProbe.bErrno};" +
            "shellDupAStatus=${result.shellProbe.dupAStatus?.name ?: "NOT_CALLED"};shellDupAErrno=${result.shellProbe.dupAErrno};" +
            "appHeldShellBusy=${result.appHeldShellBusy};duplicateANonExclusive=${result.duplicateANonExclusive};" +
            "retainedBAfterExportClose=${result.retainedBAfterExportClose};shellHeldAppBusy=${result.shellHeldAppBusy};" +
            "inoMismatchRejected=${result.inoMismatchRejected};serviceDeathObserved=${result.serviceDeathObserved};" +
            "appReacquiredAfterRelease=${result.appReacquiredAfterRelease};postDeathAppLock=${result.postDeathAppLock};" +
            "inodeReplacement=${result.inodeReplacement};shellHeldAfterAppBClosed=${result.shellHeldAfterAppBClosed};" +
            "productionAdmission=${result.productionAdmission};childMatrix=NOT_RUN;writerPermission=false;fcmEffect=NOT_VERIFIED")
        assertEquals("PASS_BASIC_ONLY", result.code)
        assertTrue(result.cleanupConfirmed && result.serviceDeathObserved)
        assertTrue(result.appHeldShellBusy && result.duplicateANonExclusive && result.retainedBAfterExportClose)
        assertTrue(result.shellHeldAppBusy && result.appReacquiredAfterRelease && result.inoMismatchRejected)
        assertTrue(result.postDeathAppLock)
        assertTrue(result.shellHeldAfterAppBClosed)
        assertTrue(result.elapsedMillis in 0..40_000)
        assertEquals("NOT_RUN", result.inodeReplacement)
        assertEquals("NOT_QUALIFIED", result.productionAdmission)
        assertFalse(result.writerPermission)
    }

    private fun status(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("guardian.doze.ofd.readOnly", value) })
}
