package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autoprotection.StartupExitReason
import io.github.originosfcmguardian.daemon.DaemonRequestedExitDetail
import io.github.originosfcmguardian.daemon.daemonStartupExitRecords
import org.junit.Assert.*
import org.junit.Test

class SystemDaemonOwnerExitClassificationTest {
    private val platform = mapOf("SUBREASON_KILL_BACKGROUND" to 24,
        "SUBREASON_REMOVE_TASK" to 1001, "SUBREASON_FORCE_STOP" to 1002, "SUBREASON_STOP_APP" to 1003)

    @Test fun realUserRequestedBackgroundKillPairRetainsExistingMasterConsent() {
        val exit = daemonGuardianExit(10, true, 800) { daemonRequestedExitDetail(24, platform::get) }
        assertEquals(StartupExitReason.USER_REQUESTED, exit.reason)
        assertEquals(DaemonRequestedExitDetail.KILL_BACKGROUND, exit.requestedDetail)
        assertEquals(StartupExitReason.OTHER, requireNotNull(daemonStartupExitRecords(listOf(exit), 500)).single().reason)
    }

    @Test fun symbolicRuntimeValueIsUsedWithoutAssumingOnePlatformNumber() {
        assertEquals(DaemonRequestedExitDetail.KILL_BACKGROUND,
            daemonRequestedExitDetail(2000) { if (it == "SUBREASON_KILL_BACKGROUND") 2000 else null })
        assertEquals(DaemonRequestedExitDetail.UNKNOWN,
            daemonRequestedExitDetail(24) { if (it == "SUBREASON_KILL_BACKGROUND") 2000 else null })
    }

    @Test fun forceStopStopAppAndUnknownMetadataKeepTheirBoundaries() {
        for (name in listOf("SUBREASON_FORCE_STOP", "SUBREASON_STOP_APP")) {
            val exit = daemonGuardianExit(10, true, 800) {
                daemonRequestedExitDetail(platform.getValue(name), platform::get)
            }
            assertEquals(DaemonRequestedExitDetail.EXPLICIT_STOP, exit.requestedDetail)
            assertEquals(StartupExitReason.USER_REQUESTED,
                requireNotNull(daemonStartupExitRecords(listOf(exit), 500)).single().reason)
        }
        for (subReason in listOf(null, -1, 0, 23, 25)) {
            assertEquals(DaemonRequestedExitDetail.UNKNOWN, daemonRequestedExitDetail(subReason, platform::get))
        }
        assertEquals(DaemonRequestedExitDetail.UNKNOWN, daemonRequestedExitDetail(24) { null })
        val unknown = daemonGuardianExit(10, true, 800) { daemonRequestedExitDetail(25, platform::get) }
        assertNull(daemonStartupExitRecords(listOf(unknown), 500))
    }

    @Test fun backgroundDetailCannotWeakenASeparateUserStoppedReason() {
        val exit = daemonGuardianExit(11, true, 800) { error("Only USER_REQUESTED consults its detail") }
        assertEquals(StartupExitReason.USER_STOPPED, exit.reason)
        assertEquals(DaemonRequestedExitDetail.UNKNOWN, exit.requestedDetail)
        assertEquals(StartupExitReason.USER_STOPPED,
            requireNotNull(daemonStartupExitRecords(listOf(exit), 500)).single().reason)
    }
}
