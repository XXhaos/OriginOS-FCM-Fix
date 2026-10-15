package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertNull(fixedSystemExecutable(listOf("content", "query", "--user", "0")))
        assertNull(fixedSystemExecutable(listOf("logcat", "-b", "events", "-d")))
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
    fun outputLimitIsReportedAndStillReturnsBoundedOutput() {
        val result = RemoteCommandRunner(maxCombinedOutputBytes = 4).run(
            argv = fixtureCommand("output"),
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
            argv = fixtureCommand("silent"),
            wasCancelled = { true },
            onProcessStarted = {},
        )

        assertEquals(IpcContract.EXIT_CANCELLED, result.exitCode)
        assertEquals("Command cancelled", result.stderr)
        assertFalse(result.timedOut)
    }

    private fun fixtureCommand(action: String): List<String> = listOf(
        File(System.getProperty("java.home"), "bin/java${if (isWindows()) ".exe" else ""}").path,
        "-cp",
        File(Class.forName(FIXTURE_CLASS).protectionDomain.codeSource.location.toURI()).path,
        FIXTURE_CLASS,
        action,
    )

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private companion object {
        const val FIXTURE_CLASS = "io.github.originosfcmguardian.privileged.RemoteCommandRunnerFixture"
    }
}
