package io.github.originosfcmguardian.doze.broker

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit qualification of one sleep child; no GMS/Doze mutation or production integration. */
@LargeTest
class GuardianBrokerQualificationDeviceTest {
    @Test(timeout = 50_000)
    fun offRevocationDuringRealAwaitRetainsBusyUntilExactResultRelease() {
        val arguments = InstrumentationRegistry.getArguments()
        val optedIn = arguments.getString("guardianBrokerQualification") == "true"
        if (!optedIn) status("code=SKIPPED;stage=OPT_IN_REQUIRED;gmsWrite=false;productionAdmission=NOT_QUALIFIED")
        assumeTrue("Explicit guardianBrokerQualification=true is required", optedIn)
        assumeTrue("Exact debug fingerprint and user0 are required", BuildConfig.DEBUG &&
            Build.FINGERPRINT == BROKER_FINGERPRINT && Process.myUid() in 10_000..99_999)
        val expectedUid = arguments.getString("expectedGuardianAppUID")?.toIntOrNull()
        assertTrue("Fresh expectedGuardianAppUID is required", expectedUid != null && expectedUid in 10_000..99_999)
        // Instrumentation recreates the App; external provider delivery is asynchronous. A bounded
        // initial-environment wait is not a retry of the qualification or a privilege grant.
        val received = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { received.countDown() }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
            if (!Shizuku.pingBinder()) received.await(3, TimeUnit.SECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        val binderReady = Shizuku.pingBinder()
        if (!binderReady) status("code=NOT_RUN;stage=SHIZUKU_BINDER_NOT_READY;gmsWrite=false;productionAdmission=NOT_QUALIFIED")
        assumeTrue("Initial external Shizuku Binder delivery is required", binderReady)
        val result = GuardianBrokerQualificationClient(
            InstrumentationRegistry.getInstrumentation().targetContext).run(requireNotNull(expectedUid))
        status("code=${result.code};stage=${result.stage};elapsedMillis=${result.elapsedMillis};" +
            "ackWhileChildAlive=${result.ackWhileChildAlive};cleanupFactsComplete=${result.cleanupFactsComplete};" +
            "resultReleased=${result.resultReleased};serviceDeathObserved=${result.serviceDeathObserved};" +
            "gmsWrite=false;productionAdmission=NOT_QUALIFIED;restartRecovery=NOT_IMPLEMENTED;fcmEffect=NOT_VERIFIED")
        assertEquals("PASS_SLEEP_ONLY", result.code)
        assertTrue(result.ackWhileChildAlive && result.cleanupFactsComplete && result.resultReleased)
        assertTrue("Own service requires genuine linked Binder death", result.serviceDeathObserved)
        assertTrue(result.elapsedMillis in 0..45_000)
    }

    private fun status(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("guardian.broker.qualification", value) })
}
