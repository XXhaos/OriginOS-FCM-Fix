package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.trace.ProcessTraceCodec
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuSafetyTest {
    @Test
    fun rootUserServiceIsRejected() {
        assertFalse(isAcceptedServiceUid(0))
        assertFalse(isAcceptedServiceUid(1_000))
        assertTrue(isAcceptedServiceUid(2_000))
    }

    @Test
    fun statusDistinguishesUnavailablePermissionShellAndRoot() {
        assertEquals(
            ShizukuStatus.NOT_INSTALLED,
            classifyShizukuStatus(false, false, null, false),
        )
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            classifyShizukuStatus(true, false, null, false),
        )
        assertEquals(
            ShizukuStatus.PERMISSION_REQUIRED,
            classifyShizukuStatus(true, true, 2_000, false),
        )
        assertEquals(
            ShizukuStatus.READY_SHELL,
            classifyShizukuStatus(true, true, 2_000, true),
        )
        assertEquals(
            ShizukuStatus.READY_ROOT,
            classifyShizukuStatus(true, true, 0, true),
        )
        assertEquals(
            ShizukuStatus.ERROR,
            classifyShizukuStatus(true, true, 10_000, true),
        )
    }

    @Test
    fun executableMappingUsesOnlyFixedSystemPaths() {
        assertEquals(
            listOf("/system/bin/cmd", "appops", "get"),
            fixedSystemExecutable(listOf("cmd", "appops", "get")),
        )
        assertEquals(null, fixedSystemExecutable(listOf("sh", "-c", "id")))
        assertEquals(null, fixedSystemExecutable(emptyList()))
        assertEquals(
            listOf("/system/bin/content", "query", "--user", "0"),
            fixedSystemExecutable(listOf("content", "query", "--user", "0")),
        )
        assertEquals(
            listOf("/system/bin/logcat", "-b", "events", "-d"),
            fixedSystemExecutable(listOf("logcat", "-b", "events", "-d")),
        )
    }

    @Test
    fun exactGmsUidValidationRejectsOtherOrAmbiguousPackages() {
        assertEquals(
            10_208,
            verifiedExactGmsUid(
                "package:com.google.android.gms.policy_sidecar_aps uid:10181\n" +
                    "package:com.google.android.gms.location.history uid:10185\n" +
                    "package:com.google.android.gms uid:10208\n",
            ),
        )
        assertEquals(null, verifiedExactGmsUid("package:com.google.android.gsf uid:10208\n"))
        assertEquals(
            null,
            verifiedExactGmsUid(
                "package:com.google.android.gms uid:10208\n" +
                    "package:com.google.android.gms uid:10209\n",
            ),
        )
        assertEquals(null, verifiedExactGmsUid("package:com.google.android.gms uid:10208\nmalformed"))
        assertEquals(
            null,
            verifiedExactGmsUid(
                "package:com.google.android.gms uid:10208\n" +
                    "package:com.google.android.gms.supervision uid:110402\n",
            ),
        )
    }

    @Test
    fun exactGuardianUidValidationRejectsMissingAmbiguousAndCrossUserMatches() {
        assertEquals(
            10_362,
            verifiedExactGuardianUid("package:io.github.originosfcmguardian uid:10362\n"),
        )
        assertEquals(
            null,
            verifiedExactGuardianUid("package:io.github.originosfcmguardian.test uid:10363\n"),
        )
        assertEquals(
            null,
            verifiedExactGuardianUid(
                "package:io.github.originosfcmguardian uid:10362\n" +
                    "package:io.github.originosfcmguardian uid:10363\n",
            ),
        )
        assertEquals(
            null,
            verifiedExactGuardianUid("package:io.github.originosfcmguardian uid:110362\n"),
        )
    }

    @Test
    fun selectedTargetUidValidationRequiresOneExactSafeUserZeroPackage() {
        assertEquals(
            10_434,
            verifiedExactTargetUid(
                "package:org.telegram.messenger.web uid:10433\n" +
                    "package:org.telegram.messenger uid:10434\n",
                "org.telegram.messenger",
            ),
        )
        assertEquals(
            null,
            verifiedExactTargetUid(
                "package:org.telegram.messenger uid:10434\n" +
                    "package:org.telegram.messenger uid:10435\n",
                "org.telegram.messenger",
            ),
        )
        assertEquals(
            null,
            verifiedExactTargetUid(
                "package:org.telegram.messenger uid:110434\n",
                "org.telegram.messenger",
            ),
        )
        assertEquals(
            null,
            verifiedExactTargetUid(
                "package:org.telegram.messenger uid:9999\n",
                "org.telegram.messenger",
            ),
        )
    }

    @Test
    fun exclusiveTargetUidOwnerRejectsSharedMalformedMismatchedAndCrossUserResults() {
        assertTrue(
            verifiedExclusiveTargetUidOwner(
                "package:org.telegram.messenger uid:10434\n",
                "org.telegram.messenger",
                10_434,
            ),
        )
        assertFalse(
            verifiedExclusiveTargetUidOwner(
                "package:org.telegram.messenger uid:10434\n" +
                    "package:org.telegram.messenger.web uid:10434\n",
                "org.telegram.messenger",
                10_434,
            ),
        )
        assertFalse(
            verifiedExclusiveTargetUidOwner(
                "package:org.telegram.messenger uid:10435\n",
                "org.telegram.messenger",
                10_434,
            ),
        )
        assertFalse(
            verifiedExclusiveTargetUidOwner(
                "package:org.telegram.messenger uid:110434\n",
                "org.telegram.messenger",
                110_434,
            ),
        )
        assertFalse(
            verifiedExclusiveTargetUidOwner(
                "warning\npackage:org.telegram.messenger uid:10434\n",
                "org.telegram.messenger",
                10_434,
            ),
        )
    }

    @Test
    fun traceIpcUsesBothTypedFieldsWithoutChangingGenericCalls() {
        assertEquals(
            IpcArguments(10_434, "org.telegram.messenger"),
            ipcArguments(SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger")),
        )
        assertEquals(
            IpcArguments(IpcContract.NO_UID_ARGUMENT, null),
            ipcArguments(SafeCommand.GetDeviceIdleWhitelist),
        )
    }

    @Test
    fun processTraceIsParsedRemotelyAndOnlyStructuralFieldsRemain() {
        val command = SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger")
        val result = structureTargetProcessTrace(
            command,
            RemoteRunResult(
                exitCode = 0,
                stdout = "1790590802.356  100  200 I am_uid_active: 10434\n" +
                    "private unrelated application log\n",
                stderr = "",
                timedOut = false,
                outputTruncated = false,
            ),
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.stdout.contains("private"))
        val decoded = ProcessTraceCodec.decode(result.stdout)
        assertEquals(1, decoded?.size)
        assertEquals(ProcessTraceKind.UID_ACTIVE, decoded?.single()?.kind)
        assertEquals("", result.stderr)
    }

    @Test
    fun processTraceTransportFailuresNeverReturnRawOutputOrStderr() {
        val command = SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger")
        val failures = listOf(
            RemoteRunResult(1, "raw-private", "raw-stderr-secret", false, false),
            RemoteRunResult(0, "raw-private", "", false, true),
            RemoteRunResult(IpcContract.EXIT_TIMED_OUT, "raw-private", "raw-timeout", true, false),
        )

        failures.forEach { failure ->
            val result = structureTargetProcessTrace(command, failure)
            assertEquals("", result.stdout)
            assertFalse(result.stderr.contains("raw"))
            assertFalse(result.outputTruncated)
            assertNull(ProcessTraceCodec.decode(result.stderr))
        }
    }

    @Test
    fun clientRejectsMalformedOrFailedTracePayloadWithoutExposingRemoteText() {
        val command = SafeCommand.GetTargetProcessEvents(10_434, "org.telegram.messenger")
        val malformed = sanitizeProcessTraceIpcResult(
            CommandResult(command, 0, "raw-private-log", ""),
        )
        val failed = sanitizeProcessTraceIpcResult(
            CommandResult(command, 1, "raw-private-log", "raw-stderr-secret"),
        )

        listOf(malformed, failed).forEach { result ->
            assertEquals(IpcContract.EXIT_BRIDGE_ERROR, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("Process trace unavailable", result.stderr)
            assertFalse(result.outputTruncated)
        }
    }

    @Test
    fun outputLimitIsReportedAndStillReturnsBoundedOutput() {
        val result = RemoteCommandRunner(maxCombinedOutputBytes = 4).run(
            argv = listOf("/usr/bin/printf", "abcdef"),
            wasCancelled = { false },
            onProcessStarted = {},
        )

        assertEquals(0, result.exitCode)
        assertEquals("abcd", result.stdout)
        assertTrue(result.outputTruncated)
    }

    @Test
    fun cancelledRemoteCommandKeepsCancelledExitCode() {
        val result = RemoteCommandRunner().run(
            argv = listOf("/usr/bin/true"),
            wasCancelled = { true },
            onProcessStarted = {},
        )

        assertEquals(IpcContract.EXIT_CANCELLED, result.exitCode)
        assertEquals("Command cancelled", result.stderr)
        assertFalse(result.timedOut)
    }
}
