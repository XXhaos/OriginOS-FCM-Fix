package io.github.originosfcmguardian

import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.FcmHealthConclusion
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.core.PlanDisposition
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.ReadOnlyDetector
import io.github.originosfcmguardian.core.RepairPlanner
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in integration check for the parent's known user-0 device baseline.
 *
 * This test uses the real Shizuku bridge. It has no privileged mocks and the
 * production command set contains reads only. Ordinary instrumentation runs
 * skip it unless guardianRunPrivilegedSmoke=true is supplied explicitly.
 */
@LargeTest
class ShizukuReadOnlyDeviceTest {
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun twoReadOnlyDetectionsAttemptCleanupAndFreshBind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabled = InstrumentationRegistry.getArguments()
            .getString(RUN_ARGUMENT)
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "Pass -e $RUN_ARGUMENT true only on the parent's authorized known-baseline device",
            enabled,
        )

        val runtime = ShizukuRuntime(instrumentation.targetContext)
        runtime.start()
        try {
            runBlocking {
                withTimeout(COROUTINE_TIMEOUT_MILLIS) {
                    assertTrue("Shizuku shell must already be authorized", runtime.executor.isAvailable())
                    assertEquals(2_000, runtime.executor.getUid())

                    repeat(2) { index ->
                        val report = try {
                            ReadOnlyDetector(runtime.executor).detect()
                        } finally {
                            runtime.executor.cleanup()
                        }

                        assertEquals(PrivilegedAccessState.READY_SHELL, report.privilegedAccess)
                        assertEquals(0, report.currentUser)
                        assertEquals(Presence.PRESENT, report.gms.presence)
                        assertEquals(EnabledState.ENABLED, report.gms.enabled)
                        assertNotNull("GMS appId must be read from the current device", report.gms.appId)

                        assertEquals(AppOp.entries.toSet(), report.appOps.keys)
                        AppOp.entries.forEach { operation ->
                            val check = requireNotNull(report.appOps[operation])
                            assertTrue(
                                "${operation.shellName} must not be UNKNOWN on the known baseline",
                                check.status != EvidenceStatus.UNKNOWN,
                            )
                        }

                        val gmsAppId = requireNotNull(report.gms.appId)
                        assertTrue(
                            "GMS must have at least one DeviceIdle membership on the known baseline",
                            report.doze.memberships.any { membership ->
                                membership.packageName == GMS_PACKAGE_NAME && membership.appId == gmsAppId
                            },
                        )
                        assertEquals(FcmHealthConclusion.UNKNOWN, report.fcmHealth)

                        val plan = RepairPlanner.plan(report)
                        assertEquals(PlanDisposition.NO_WRITE, plan.disposition)
                        assertTrue(plan.candidates.isEmpty())
                        assertTrue(!plan.hasExecutableWrites)

                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    STATUS_KEY,
                                    buildSummary(index + 1, report.appOps.mapValues { it.value.status }, report),
                                )
                            },
                        )

                        // Give the remove=true request a bounded interval to propagate
                        // before the next distinct-generation bind attempt. Host-side
                        // process observation is required to prove actual process exit.
                        delay(REBIND_SETTLE_MILLIS)
                    }
                }
            }
        } finally {
            runtime.close()
        }
    }

    private fun buildSummary(
        round: Int,
        appOps: Map<AppOp, EvidenceStatus>,
        report: io.github.originosfcmguardian.core.DetectionReport,
    ): String = buildString {
        append("round=").append(round)
        append(";uid=2000;user=").append(report.currentUser)
        append(";gms=").append(report.gms.presence).append('/').append(report.gms.enabled)
        append(";appOps=")
        append(AppOp.entries.joinToString(",") { operation ->
            "${operation.name}:${appOps[operation]}"
        })
        append(";doze=")
        append(report.doze.sources.joinToString(",") { it.wireName })
        append(";pem=").append(report.pem.listState)
        append(";fcm=").append(report.fcmHealth)
        append(";plan=").append(RepairPlanner.plan(report).disposition)
        append(";problems=").append(report.problems.size)
    }

    private companion object {
        const val RUN_ARGUMENT = "guardianRunPrivilegedSmoke"
        const val STATUS_KEY = "guardian.summary"
        const val REBIND_SETTLE_MILLIS = 300L
        const val COROUTINE_TIMEOUT_MILLIS = 220_000L
        const val TEST_TIMEOUT_MILLIS = 240_000L
    }
}
