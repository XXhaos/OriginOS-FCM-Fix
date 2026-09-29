package io.github.originosfcmguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GmsPackageParserTest {
    private val installed = SafeCommand.GetGmsPackage
    private val enabled = SafeCommand.GetGmsEnabledPackage

    @Test
    fun `synthetic exact user-zero package and enabled rows are conforming`() {
        val line = "package:$GMS_PACKAGE_NAME uid:10208\n"
        val state = GmsPackageParser.parse(result(installed, line), result(enabled, line))

        assertEquals(Presence.PRESENT, state.presence)
        assertEquals(EnabledState.ENABLED, state.enabled)
        assertEquals(10_208, state.appId)
        assertEquals(EvidenceStatus.CONFORMING, state.status)
    }

    @Test
    fun `synthetic installed exact row absent from enabled query means disabled`() {
        val state = GmsPackageParser.parse(
            result(installed, "package:$GMS_PACKAGE_NAME uid:10208\n"),
            result(enabled, ""),
        )

        assertEquals(Presence.PRESENT, state.presence)
        assertEquals(EnabledState.DISABLED, state.enabled)
        assertEquals(EvidenceStatus.ANOMALOUS, state.status)
    }

    @Test
    fun `synthetic exit-zero cross-user permission error is semantic failure`() {
        val error = "Error: Shell does not have permission to access user 666\n"
        val errorResult = result(installed, error)
        val state = GmsPackageParser.parse(errorResult, result(enabled, ""))

        assertEquals(false, errorResult.completedCleanly)
        assertEquals(Presence.UNKNOWN, state.presence)
        assertEquals(EvidenceStatus.UNKNOWN, state.status)
        assertTrue(state.problems.any { it.contains("semantic error") })
    }

    @Test
    fun `synthetic substring package is not accepted as GMS`() {
        val other = "package:com.google.android.gms.unstable uid:10209\n"
        val state = GmsPackageParser.parse(result(installed, other), result(enabled, ""))

        assertEquals(Presence.ABSENT, state.presence)
        assertEquals(EvidenceStatus.ANOMALOUS, state.status)
    }

    @Test
    fun `synthetic duplicates malformed cross-user uid and truncation fail closed`() {
        val exact = "package:$GMS_PACKAGE_NAME uid:10208\n"
        val cases = listOf(
            result(installed, exact + exact) to result(enabled, exact),
            result(installed, "package:$GMS_PACKAGE_NAME\n") to result(enabled, exact),
            result(installed, "package:$GMS_PACKAGE_NAME uid:99910208\n") to result(enabled, exact),
            result(installed, exact, outputTruncated = true) to result(enabled, exact),
        )
        cases.forEach { (installedResult, enabledResult) ->
            val state = GmsPackageParser.parse(installedResult, enabledResult)
            assertEquals(EvidenceStatus.UNKNOWN, state.status)
        }
    }
}
