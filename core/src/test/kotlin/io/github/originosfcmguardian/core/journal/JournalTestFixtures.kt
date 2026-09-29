package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.core.PrivilegedExecutor
import io.github.originosfcmguardian.core.ReadOnlyDetector
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.fixture
import io.github.originosfcmguardian.core.result

internal const val TEST_FINGERPRINT = "vivo/PD2609/PD2609:17/build/user/release-keys"

internal suspend fun completeSnapshot(
    capturedAt: Long = 1_000,
    appOpOverride: String? = null,
    wakeLockOutput: String? = null,
    dozeSuffix: String = "",
): BaselineSnapshot {
    val recorder = RecordingPrivilegedExecutor(
        JournalFixtureExecutor(appOpOverride, wakeLockOutput, dozeSuffix),
    )
    val report = ReadOnlyDetector(recorder).detect()
    val evidence = recorder.snapshotEvidence()
    return BaselineSnapshot(
        metadata = CaptureMetadata(
            capturedAtEpochMillis = capturedAt,
            captureSource = CaptureSource.TEST_FIXTURE,
            buildFingerprint = TEST_FINGERPRINT,
            buildFingerprintSource = MetadataFieldSource.SHELL_REPORT,
            androidApiFingerprint = TEST_FINGERPRINT,
            buildId = "BUILD_ID",
            buildDisplay = "DISPLAY",
            sdkInt = 37,
            buildFieldsSource = MetadataFieldSource.ANDROID_BUILD_API,
            userId = 0,
            userIdSource = MetadataFieldSource.SHELL_REPORT,
            executorUid = 2_000,
            executorUidSource = MetadataFieldSource.EXECUTOR,
            gmsAppId = 10_208,
        ),
        report = report,
        commandEvidence = evidence.results,
        evidenceOverflowed = evidence.overflowed,
    )
}

internal fun appOpObservation(snapshot: BaselineSnapshot, operation: AppOp): PolicyObservation.AppOps {
    val check = snapshot.report.appOps.getValue(operation)
    return PolicyObservation.AppOps(
        operation,
        AppOpPolicyValue(
            check.packageState.scope,
            check.packageState.operationRecordPresence,
            check.packageState.reportedMode,
            check.packageState.reportedDefault,
            check.packageState.overrideProvenance,
        ),
        AppOpPolicyValue(
            check.uidState.scope,
            check.uidState.operationRecordPresence,
            check.uidState.reportedMode,
            check.uidState.reportedDefault,
            check.uidState.overrideProvenance,
        ),
    )
}

private class JournalFixtureExecutor(
    private val appOpOverride: String?,
    private val wakeLockOutput: String?,
    private val dozeSuffix: String,
) : PrivilegedExecutor {
    override suspend fun isAvailable(): Boolean = true
    override suspend fun getUid(): Int = 2_000

    override suspend fun exec(command: SafeCommand): CommandResult = when (command) {
        SafeCommand.GetCurrentUser -> result(command, "0\n")
        SafeCommand.GetBuildFingerprint -> result(command, "$TEST_FINGERPRINT\n")
        SafeCommand.GetGmsPackage,
        SafeCommand.GetGmsEnabledPackage,
        -> result(command, "package:$GMS_PACKAGE_NAME uid:10208\n")
        is SafeCommand.GetGmsAppOpByPackage -> result(command, appOpOutput(command.operation))
        is SafeCommand.GetAppOpByUid -> result(command, appOpOutput(command.operation))
        SafeCommand.GetDeviceIdleWhitelist -> result(
            command,
            fixture("deviceidle-cmd-whitelist.gms-excerpt.txt") + dozeSuffix,
        )
        SafeCommand.GetVivoPemList -> result(command, fixture("pem-setting.stdout.txt"))
        SafeCommand.GetVivoPemPackagePath -> result(command, fixture("pem-path.stdout.txt"))
        SafeCommand.GetCallingUid -> error("Detector uses getUid")
        SafeCommand.GetGuardianPackage,
        SafeCommand.GetGuardianAutostartRow,
        is SafeCommand.GetTargetPackage,
        is SafeCommand.GetTargetAutostartRow,
        is SafeCommand.GetTargetProcessEvents,
        -> error("Immutable original-record fixture excludes separate autostart evidence")
    }

    private fun appOpOutput(operation: AppOp): String = when (operation) {
        AppOp.RUN_IN_BACKGROUND -> appOpOverride ?: fixture("appop-run-in-background.stdout.txt")
        AppOp.RUN_ANY_IN_BACKGROUND -> fixture("appop-run-any-in-background.stdout.txt")
        AppOp.WAKE_LOCK -> wakeLockOutput ?: fixture("appop-wake-lock.stdout.txt")
    }
}
