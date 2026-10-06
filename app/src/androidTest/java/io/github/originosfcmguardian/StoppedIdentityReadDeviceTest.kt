package io.github.originosfcmguardian

import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, read-only parity. Does not invoke any recovery controller or endpoint. */
@LargeTest
class StoppedIdentityReadDeviceTest {
    @Test(timeout = 60_000)
    fun serviceObservationMatchesCurrentInstallation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guardianRunStoppedIdentityRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = TargetPackageProbe(context)
        val before = probe.packageSnapshot("org.telegram.messenger")
        assertNotNull("Current app-side identity must be visible", before)
        val runtime = ShizukuRuntime(context)
        runtime.start()
        try {
            val observation = runBlocking { runtime.executor.readTelegramStoppedIdentity() }
            assertTrue("Expected known read-only observation, got $observation", observation is StoppedTargetObservation.Known)
            observation as StoppedTargetObservation.Known
            val after = probe.packageSnapshot("org.telegram.messenger")
            assertNotNull(after)
            assertEquals(before!!.identity, after!!.identity)
            assertEquals(after.identity, observation.identity)
            assertEquals(after.stopped, observation.stopped)
            assertTrue(observation.exclusiveUidOwner)
            assertEquals(2000, observation.serviceUid)
            assertEquals(0, observation.currentUser)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("guardian.stopped.read", "identityMatch=true;exclusiveUid=true;stopped=${observation.stopped};serviceUid=2000;user=0;write=false")
            })
        } finally {
            runtime.close()
        }
    }
}
