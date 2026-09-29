package io.github.originosfcmguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceIdleAndPemParserTest {
    @Test
    fun `real DeviceIdle excerpt preserves all GMS sources`() {
        val state = DeviceIdleParser.parse(
            result(
                SafeCommand.GetDeviceIdleWhitelist,
                fixture("deviceidle-cmd-whitelist.gms-excerpt.txt"),
            ),
        )

        assertEquals(EvidenceStatus.CONFORMING, state.status)
        assertEquals(
            setOf(DozeSource.SYSTEM_EXCIDLE, DozeSource.SYSTEM, DozeSource.USER),
            state.sources,
        )
        assertTrue(state.memberships.all { it.appId == 10_208 })
    }

    @Test
    fun `synthetic missing membership is anomalous while ambiguous and errors fail closed`() {
        val absent = DeviceIdleParser.parse(
            result(SafeCommand.GetDeviceIdleWhitelist, "system,com.android.phone,1001\n"),
        )
        assertEquals(EvidenceStatus.ANOMALOUS, absent.status)

        val empty = DeviceIdleParser.parse(result(SafeCommand.GetDeviceIdleWhitelist, ""))
        assertEquals(EvidenceStatus.UNKNOWN, empty.status)

        val unknownSource = DeviceIdleParser.parse(
            result(SafeCommand.GetDeviceIdleWhitelist, "vendor,$GMS_PACKAGE_NAME,10208\n"),
        )
        assertEquals(EvidenceStatus.UNKNOWN, unknownSource.status)

        val error = DeviceIdleParser.parse(
            result(SafeCommand.GetDeviceIdleWhitelist, "Permission denial\n"),
        )
        assertEquals(EvidenceStatus.UNKNOWN, error.status)

        val truncated = DeviceIdleParser.parse(
            result(
                SafeCommand.GetDeviceIdleWhitelist,
                "user,$GMS_PACKAGE_NAME,10208\n",
                outputTruncated = true,
            ),
        )
        assertEquals(EvidenceStatus.UNKNOWN, truncated.status)
    }

    @Test
    fun `real PEM null remains ambiguous and never claims protection`() {
        val state = PemParser.parse(
            listResult = result(SafeCommand.GetVivoPemList, fixture("pem-setting.stdout.txt")),
            pathResult = result(
                SafeCommand.GetVivoPemPackagePath,
                fixture("pem-path.stdout.txt"),
            ),
        )

        assertEquals(Presence.PRESENT, state.packagePresence)
        assertEquals(PemListState.NULL_OR_MISSING_AMBIGUOUS, state.listState)
        assertEquals(EvidenceStatus.UNKNOWN, state.status)
        assertFalse(state.entries.contains(GMS_PACKAGE_NAME))
    }
}
