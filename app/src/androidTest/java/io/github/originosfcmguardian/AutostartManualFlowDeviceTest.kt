package io.github.originosfcmguardian

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.AutostartUiStatus
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.AutostartReadStatus
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.GuardianAutostartRead
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.TargetAutostartParser
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import io.github.originosfcmguardian.ui.HomeUiState
import io.github.originosfcmguardian.ui.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exact-build opt-in protocol. It performs read-only provider queries and opens the verified OEM
 * settings Activity, but never toggles a switch or writes any system policy.
 */
@LargeTest
class AutostartManualFlowDeviceTest {
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun productionSelectionTargetReturnRecheckAndTypedReadParity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val enabled = InstrumentationRegistry.getArguments()
            .getString(RUN_ARGUMENT)
            ?.toBooleanStrictOrNull() == true
        assumeTrue("Explicit opt-in is required", enabled)
        assumeTrue("This protocol is build-specific", Build.FINGERPRINT == VERIFIED_AUTOSTART_FINGERPRINT)

        val targetContext = instrumentation.targetContext
        val probePackage = instrumentation.context.packageName
        val store = TargetSelectionStore(targetContext)
        assertTrue("Test setup must remove only its own monitoring selection", store.remove(probePackage))

        val activity = instrumentation.startActivitySync(
            android.content.Intent(targetContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val viewModel = ViewModelProvider(activity)[HomeViewModel::class.java]
        val runtime = ShizukuRuntime(targetContext)
        runtime.start()
        try {
            waitState(viewModel) { it.shizuku.status == ShizukuStatus.READY_SHELL }

            val guardianAttempt = viewModel.uiState.value.autostartCheckAttemptCount
            instrumentation.runOnMainSync { viewModel.checkGuardianAutostart() }
            val guardianState = waitState(viewModel) {
                it.autostartCheckAttemptCount > guardianAttempt &&
                    it.autostart.status != AutostartUiStatus.CHECKING
            }
            assertEquals(AutostartUiStatus.NEEDS_USER_ACTION, guardianState.autostart.status)
            assertNotNull(guardianState.autostart.evidence)
            val fixedGuardianRead = requireNotNull(guardianState.autostart.evidence)

            val typedGuardianRead = readTypedTargetRow(
                runtime = runtime,
                packageName = GUARDIAN_PACKAGE_NAME,
                expectedUid = activity.applicationInfo.uid,
            )
            assertEquals(AutostartReadStatus.READ_ONLY_VALUE_PRESENT, typedGuardianRead.status)
            assertEquals(fixedGuardianRead, typedGuardianRead)

            instrumentation.runOnMainSync { viewModel.showTargetSelection() }
            waitState(viewModel) { state ->
                !state.targetSelection.loading &&
                    state.targetSelection.candidates.any { it.packageName == probePackage }
            }
            instrumentation.runOnMainSync { viewModel.setTargetSelected(probePackage, true) }
            val selected = waitState(viewModel) { state ->
                state.selectedTargets.any { it.identity.packageName == probePackage }
            }.selectedTargets.single { it.identity.packageName == probePackage }
            assertEquals(selected.identity, store.load().single { it.packageName == probePackage })

            val independentBefore = readTypedTargetRow(
                runtime = runtime,
                packageName = probePackage,
                expectedUid = selected.identity.uid,
            )
            val firstAttempt = viewModel.uiState.value.targetReadAttemptCount
            instrumentation.runOnMainSync { viewModel.checkSelectedTarget(probePackage) }
            val firstRead = waitState(viewModel) {
                it.targetReadAttemptCount > firstAttempt &&
                    it.selectedTargets.single { target -> target.identity.packageName == probePackage }
                        .status != TargetAutostartStatus.CHECKING
            }
            assertEquals(
                expectedTargetStatus(independentBefore),
                firstRead.selectedTargets.single { it.identity.packageName == probePackage }.status,
            )
            assertEquals(
                independentBefore,
                firstRead.selectedTargets.single { it.identity.packageName == probePackage }.evidence,
            )

            val lifecycle = LifecycleProbe(activity)
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            try {
                val beforeReturnAttempt = viewModel.uiState.value.targetReadAttemptCount
                instrumentation.runOnMainSync {
                    activity.openSelectedTargetAutostartSettings(probePackage)
                }
                assertTrue("Production MainActivity must actually pause", lifecycle.paused.await(10, TimeUnit.SECONDS))
                lifecycle.watchResume = true
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                assertTrue("Production MainActivity must resume after Back", lifecycle.resumed.await(10, TimeUnit.SECONDS))

                val returned = waitState(viewModel) {
                    it.targetReadAttemptCount > beforeReturnAttempt &&
                        it.selectedTargets.single { target -> target.identity.packageName == probePackage }
                            .status != TargetAutostartStatus.CHECKING
                }
                val independentAfter = readTypedTargetRow(
                    runtime = runtime,
                    packageName = probePackage,
                    expectedUid = selected.identity.uid,
                )
                assertEquals(
                    "Opening and leaving the read-only settings page must not change the row",
                    independentBefore,
                    independentAfter,
                )
                assertEquals(
                    expectedTargetStatus(independentAfter),
                    returned.selectedTargets.single { it.identity.packageName == probePackage }.status,
                )
                assertEquals(
                    independentAfter,
                    returned.selectedTargets.single { it.identity.packageName == probePackage }.evidence,
                )
                runBlocking { delay(500) }
                assertEquals(
                    "Return-triggered production read must be one-shot",
                    returned.targetReadAttemptCount,
                    viewModel.uiState.value.targetReadAttemptCount,
                )
            } finally {
                activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            }

            instrumentation.runOnMainSync { viewModel.setTargetSelected(probePackage, false) }
            waitState(viewModel) { state ->
                state.selectedTargets.none { it.identity.packageName == probePackage }
            }
            assertTrue(store.load().none { it.packageName == probePackage })
        } finally {
            runtime.close()
            store.remove(probePackage)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun readTypedTargetRow(
        runtime: ShizukuRuntime,
        packageName: String,
        expectedUid: Int,
    ): GuardianAutostartRead = runBlocking {
        withTimeout(READ_TIMEOUT_MILLIS) {
            assertTrue("Shizuku shell must already be authorized", runtime.executor.isAvailable())
            val command = SafeCommand.GetTargetAutostartRow(packageName)
            val result = ReadSessionCoordinator.withSession {
                try {
                    runtime.executor.exec(command)
                } finally {
                    runtime.executor.cleanup()
                }
            }
            TargetAutostartParser.parse(result, packageName, expectedUid)
        }
    }

    private fun waitState(
        viewModel: HomeViewModel,
        predicate: (HomeUiState) -> Boolean,
    ): HomeUiState = runBlocking {
        withTimeout(STATE_TIMEOUT_MILLIS) { viewModel.uiState.first(predicate) }
    }

    private fun expectedTargetStatus(read: GuardianAutostartRead): TargetAutostartStatus = when {
        read.status != AutostartReadStatus.READ_ONLY_VALUE_PRESENT -> TargetAutostartStatus.UNKNOWN
        read.currentState == 0 -> TargetAutostartStatus.ALLOWED
        read.currentState == 1 -> TargetAutostartStatus.NEEDS_USER_ACTION
        else -> TargetAutostartStatus.UNKNOWN
    }

    private class LifecycleProbe(private val target: Activity) : Application.ActivityLifecycleCallbacks {
        val paused = CountDownLatch(1)
        val resumed = CountDownLatch(1)

        @Volatile
        var watchResume = false

        override fun onActivityPaused(activity: Activity) {
            if (activity === target) paused.countDown()
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity === target && watchResume) resumed.countDown()
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val RUN_ARGUMENT = "guardianRunAutostartManualFlow"
        const val READ_TIMEOUT_MILLIS = 20_000L
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val TEST_TIMEOUT_MILLIS = 120_000L
    }
}
