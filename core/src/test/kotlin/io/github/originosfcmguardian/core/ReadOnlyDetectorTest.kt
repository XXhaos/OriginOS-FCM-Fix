package io.github.originosfcmguardian.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlyDetectorTest {
    @Test
    fun `full and configuration detection never query OEM autostart permissions`() = runBlocking {
        for (configurationOnly in listOf(false, true)) {
            val executor = FixtureExecutor()
            val detector = ReadOnlyDetector(executor)
            if (configurationOnly) detector.detectGmsConfiguration() else detector.detect()
            assertFalse(executor.commands.any {
                it == SafeCommand.GetGuardianAutostartRow || it is SafeCommand.GetTargetAutostartRow
            })
        }
    }

    @Test
    fun `GMS config reads fixed settings without PEM while full detection retains PEM`() = runBlocking {
        val executor = FixtureExecutor()
        val lean = ReadOnlyDetector(executor).detectGmsConfiguration()
        assertEquals(11, executor.execCount)
        assertFalse(executor.commands.any { it == SafeCommand.GetVivoPemList || it == SafeCommand.GetVivoPemPackagePath })
        assertEquals(EvidenceStatus.CONFORMING, lean.gms.status)
        assertEquals(EvidenceStatus.CONFORMING, lean.doze.status)
        assertTrue(lean.appOps.values.all { it.status == EvidenceStatus.CONFORMING })
        val full = FixtureExecutor()
        ReadOnlyDetector(full).detect()
        assertEquals(13, full.execCount)
        assertTrue(full.commands.containsAll(listOf(SafeCommand.GetVivoPemList, SafeCommand.GetVivoPemPackagePath)))
    }
    @Test
    fun `clean PD2609 null PEM snapshot plans no write without claiming FCM health`() = runBlocking {
        val executor = FixtureExecutor()
        val report = ReadOnlyDetector(executor).detect()
        val plan = RepairPlanner.plan(report)

        assertEquals(PrivilegedAccessState.READY_SHELL, report.privilegedAccess)
        assertEquals(0, report.currentUser)
        assertEquals(EvidenceStatus.CONFORMING, report.gms.status)
        assertTrue(report.appOps.values.all { it.status == EvidenceStatus.CONFORMING })
        assertEquals(EvidenceStatus.CONFORMING, report.doze.status)
        assertEquals(PemListState.NULL_OR_MISSING_AMBIGUOUS, report.pem.listState)
        assertTrue(report.pem.problems.isEmpty())
        assertEquals(FcmHealthConclusion.UNKNOWN, report.fcmHealth)
        assertEquals(AppOpsWriteCapability.SHELL_GUARD_WHILE_SHIZUKU_RUNNING, report.appOpsWriteAdvisory.capability)
        assertEquals(AdvisoryEvidence.HISTORICAL_NOT_LIVE, report.appOpsWriteAdvisory.evidence)
        assertEquals(13, executor.execCount)
        assertEquals(PlanDisposition.NO_WRITE, plan.disposition)
        assertTrue(plan.candidates.isEmpty())
        assertFalse(plan.hasExecutableWrites)
        assertTrue(plan.validationNotes.any { it.contains("does not establish FCM") })
        assertTrue(plan.validationNotes.any { it.contains("Historical build-specific evidence") })
    }

    @Test
    fun `other and missing fingerprints retain unknown write capability without extra reads`() = runBlocking {
        for (fingerprint in listOf(
            "vivo/PD2609/PD2609:17/other-build:user/release-keys\n",
            "\n",
        )) {
            val executor = FixtureExecutor(fingerprintOutput = fingerprint)
            val report = ReadOnlyDetector(executor).detect()
            val plan = RepairPlanner.plan(report)

            assertEquals(AppOpsWriteCapability.UNKNOWN, report.appOpsWriteAdvisory.capability)
            assertEquals(AdvisoryEvidence.NONE, report.appOpsWriteAdvisory.evidence)
            assertEquals(13, executor.execCount)
            assertTrue(plan.validationNotes.any { it.contains("capability is unknown") })
            assertFalse(plan.validationNotes.any { it.contains("Historical build-specific evidence") })
            assertTrue(plan.candidates.isEmpty())
            assertFalse(plan.hasExecutableWrites)
            assertEquals(
                if (report.buildFingerprint == null) PlanDisposition.NEEDS_VALIDATION else PlanDisposition.NO_WRITE,
                plan.disposition,
            )
        }
    }

    @Test
    fun `failed PEM list read requires validation while producing no write`() = runBlocking {
        val report = ReadOnlyDetector(FixtureExecutor(truncatePemList = true)).detect()
        val plan = RepairPlanner.plan(report)

        assertEquals(PemListState.UNKNOWN, report.pem.listState)
        assertTrue(report.pem.problems.isNotEmpty())
        assertEquals(PlanDisposition.NEEDS_VALIDATION, plan.disposition)
        assertTrue(plan.candidates.isEmpty())
        assertFalse(plan.hasExecutableWrites)
    }

    @Test
    fun `failed PEM package path read requires validation while producing no write`() = runBlocking {
        val report = ReadOnlyDetector(FixtureExecutor(failPemPath = true)).detect()
        val plan = RepairPlanner.plan(report)

        assertEquals(Presence.UNKNOWN, report.pem.packagePresence)
        assertTrue(report.pem.problems.isNotEmpty())
        assertEquals(PlanDisposition.NEEDS_VALIDATION, plan.disposition)
        assertTrue(plan.candidates.isEmpty())
        assertFalse(plan.hasExecutableWrites)
    }

    @Test
    fun `confirmed anomaly yields non-executable candidate requiring validation`() = runBlocking {
        val executor = FixtureExecutor(
            appOpOverride = "No operations.\nDefault mode: ignore\n",
        )
        val report = ReadOnlyDetector(executor).detect()
        val plan = RepairPlanner.plan(report)

        assertEquals(EvidenceStatus.ANOMALOUS, report.appOps.getValue(AppOp.RUN_IN_BACKGROUND).status)
        assertEquals(PlanDisposition.NEEDS_VALIDATION, plan.disposition)
        assertTrue(plan.candidates.isNotEmpty())
        assertTrue(plan.candidates.none { it.executable })
        assertFalse(plan.hasExecutableWrites)
    }

    @Test
    fun `root and non-user-zero execution are rejected before policy probes`() = runBlocking {
        val root = FixtureExecutor(uid = 0)
        val rootReport = ReadOnlyDetector(root).detect()
        assertEquals(PrivilegedAccessState.ROOT_REJECTED, rootReport.privilegedAccess)
        assertEquals(0, root.execCount)

        val otherUser = FixtureExecutor(currentUserOutput = "10\n")
        val otherUserReport = ReadOnlyDetector(otherUser).detect()
        assertEquals(10, otherUserReport.currentUser)
        assertEquals(1, otherUser.execCount)
        assertEquals(PlanDisposition.NEEDS_VALIDATION, RepairPlanner.plan(otherUserReport).disposition)
    }

    @Test
    fun `truncated package output propagates unknown and blocks planning`() = runBlocking {
        val report = ReadOnlyDetector(FixtureExecutor(truncatePackage = true)).detect()
        val plan = RepairPlanner.plan(report)

        assertEquals(EvidenceStatus.UNKNOWN, report.gms.status)
        assertEquals(PlanDisposition.NEEDS_VALIDATION, plan.disposition)
        assertFalse(plan.hasExecutableWrites)
    }

    @Test
    fun `coroutine cancellation is never converted to an unknown report`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                ReadOnlyDetector(CancellingExecutor).detect()
            }
        }
    }

    private class FixtureExecutor(
        private val uid: Int? = 2_000,
        private val currentUserOutput: String = "0\n",
        private val appOpOverride: String? = null,
        private val truncatePackage: Boolean = false,
        private val truncatePemList: Boolean = false,
        private val failPemPath: Boolean = false,
        private val fingerprintOutput: String =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys\n",
    ) : PrivilegedExecutor {
        var execCount: Int = 0
        val commands = mutableListOf<SafeCommand>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun getUid(): Int? = uid

        override suspend fun exec(command: SafeCommand): CommandResult {
            execCount += 1
            commands += command
            return when (command) {
                SafeCommand.GetCurrentUser -> result(command, currentUserOutput)
                SafeCommand.GetBuildFingerprint -> result(
                    command,
                    fingerprintOutput,
                )
                SafeCommand.GetGmsPackage,
                SafeCommand.GetGmsEnabledPackage,
                -> result(
                    command,
                    "package:$GMS_PACKAGE_NAME uid:10208\n",
                    outputTruncated = truncatePackage,
                )
                is SafeCommand.GetGmsAppOpByPackage -> {
                    val output = when (command.operation) {
                        AppOp.RUN_IN_BACKGROUND -> appOpOverride
                            ?: fixture("appop-run-in-background.stdout.txt")
                        AppOp.RUN_ANY_IN_BACKGROUND -> fixture("appop-run-any-in-background.stdout.txt")
                        AppOp.WAKE_LOCK -> fixture("appop-wake-lock.stdout.txt")
                    }
                    result(command, output)
                }
                is SafeCommand.GetAppOpByUid -> {
                    val name = when (command.operation) {
                        AppOp.RUN_IN_BACKGROUND -> "gms-uid-run-in-background.stdout.txt"
                        AppOp.RUN_ANY_IN_BACKGROUND -> "gms-uid-run-any-in-background.stdout.txt"
                        AppOp.WAKE_LOCK -> "gms-uid-wake-lock.stdout.txt"
                    }
                    val output = if (
                        command.operation == AppOp.RUN_IN_BACKGROUND && appOpOverride != null
                    ) {
                        appOpOverride
                    } else {
                        fixture(name)
                    }
                    result(command, output)
                }
                SafeCommand.GetDeviceIdleWhitelist -> result(
                    command,
                    fixture("deviceidle-cmd-whitelist.gms-excerpt.txt"),
                )
                SafeCommand.GetVivoPemList -> result(
                    command,
                    fixture("pem-setting.stdout.txt"),
                    outputTruncated = truncatePemList,
                )
                SafeCommand.GetVivoPemPackagePath -> if (failPemPath) {
                    result(command, "Error: package path unavailable\n")
                } else {
                    result(command, fixture("pem-path.stdout.txt"))
                }
                SafeCommand.GetCallingUid -> error("Detector uses getUid instead")
                SafeCommand.GetGuardianPackage,
                SafeCommand.GetGuardianAutostartRow,
                is SafeCommand.GetTargetPackage,
                is SafeCommand.GetTargetAutostartRow,
                is SafeCommand.GetTargetProcessEvents,
                -> error("Legacy 13-read detector must not include the separate autostart read")
            }
        }
    }

    private data object CancellingExecutor : PrivilegedExecutor {
        override suspend fun isAvailable(): Boolean = true
        override suspend fun getUid(): Int? = 2_000
        override suspend fun exec(command: SafeCommand): CommandResult {
            throw CancellationException("cancelled")
        }
    }
}
