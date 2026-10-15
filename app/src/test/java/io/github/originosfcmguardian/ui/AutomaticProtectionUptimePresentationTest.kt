package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticProtectionUptimePresentationTest {
    private val active = AutomaticProtectionState(masterEnabled = true, enabled = true,
        coreSessionStartedAtElapsedMillis = 10_000)

    @Test fun displaysOnlyOneCompactLine() {
        assertEquals("已开启时长 03:21:08", automaticProtectionUptimeText(active, 12_078_000))
        assertEquals("已开启时长 00:00:00", automaticProtectionUptimeText(active, 10_000))
    }

    @Test fun hoursDoNotWrapAtMidnight() {
        assertEquals("已开启时长 123:04:05",
            automaticProtectionUptimeText(active, 10_000 + (123 * 3_600L + 4 * 60 + 5) * 1_000))
    }

    @Test fun savedConfigurationAndUnreadyStatesDoNotClaimUptime() {
        listOf(AutomaticProtectionState(), active.copy(masterEnabled = false),
            active.copy(enabled = false, savedAuthorization = true),
            active.copy(waitingForPrivilege = true), active.copy(waitingForReconciliation = true),
            active.copy(revocationSaveFailed = true), active.copy(coreSessionStartedAtElapsedMillis = null)
        ).forEach { assertNull(automaticProtectionUptimeText(it, 20_000)) }
    }

    @Test fun ordinaryRecoveryBusyAndUnchangedSnapshotKeepTheSameOrigin() {
        assertEquals("已开启时长 00:00:10", automaticProtectionUptimeText(active.copy(busy = true), 20_000))
        assertEquals("已开启时长 00:00:20", automaticProtectionUptimeText(active.copy(), 30_000))
    }

    @Test fun sameCoreResumesItsClockButNewCoreStartsAgain() {
        val waiting = active.copy(enabled = false, waitingForPrivilege = true)
        assertNull(automaticProtectionUptimeText(waiting, 40_000))
        assertEquals("已开启时长 00:00:30", automaticProtectionUptimeText(
            waiting.copy(enabled = true, waitingForPrivilege = false), 40_000))
        assertEquals("已开启时长 00:00:00", automaticProtectionUptimeText(
            active.copy(coreSessionStartedAtElapsedMillis = 40_000), 40_000))
    }

    @Test fun invalidMonotonicClockNeverDisplaysFabricatedDuration() {
        assertNull(automaticProtectionUptimeText(active, 9_999))
        assertNull(automaticProtectionUptimeText(active.copy(coreSessionStartedAtElapsedMillis = -1), 20_000))
    }
}
