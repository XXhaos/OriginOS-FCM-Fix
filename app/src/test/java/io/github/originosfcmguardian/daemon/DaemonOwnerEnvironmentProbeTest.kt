package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.StartupExitReason
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import org.junit.Assert.*
import org.junit.Test

class DaemonOwnerEnvironmentProbeTest {
    private val boot = "11111111-2222-3333-4444-555555555555"
    private val installation = DaemonGuardianInstallation(SelectedTargetIdentity(
        GUARDIAN_PACKAGE_NAME, 10238, 100, listOf("a".repeat(64))), 79, 200)
    private inner class Port : DaemonOwnerEnvironmentReadPort {
        var env = DaemonOwnerEnvironment(2000, 0, boot, true)
        var pkg: DaemonGuardianPackage? = DaemonGuardianPackage(installation, true, true, false, true)
        var owners: List<String>? = listOf(GUARDIAN_PACKAGE_NAME)
        var permission: Boolean? = true
        var history: List<DaemonGuardianExit>? = emptyList()
        var permissionReads = 0
        var afterHistory: () -> Unit = {}
        val requestedUids = mutableListOf<Int>()
        override fun environment() = env
        override fun guardianPackage() = pkg
        override fun packagesForUid(uid: Int) = owners
        override fun shizukuRuntimePermission(uid: Int): Boolean? {
            permissionReads++; requestedUids += uid; return permission
        }
        override fun guardianExitHistory(uid: Int) = history.also { afterHistory() }
        override fun wallMillis() = 1000L
    }
    private fun read(port: Port, cancelled: () -> Boolean = { false }) =
        DaemonOwnerEnvironmentProbe(port).read(installation, boot, 500, cancelled)

    @Test fun lowMemoryAndMissingAppProcessDoNotRevokeAnInstallationPrincipal() {
        val port = Port().apply { history = listOf(DaemonGuardianExit(StartupExitReason.OTHER, 800)) }
        assertEquals(DaemonOwnerEnvironmentStatus.READY, read(port))
        // No PID/lifetime input or app callback is needed, and shell UID must not be queried.
        assertEquals(listOf(10238, 10238), port.requestedUids)
    }
    @Test fun currentRuntimeGrantIsNecessaryEvenWhenAnAppFlagOnceGrantedAccess() {
        for (value in listOf(false, null)) {
            val port = Port().apply { permission = value }
            assertEquals(DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE, read(port))
            assertEquals(1, port.permissionReads)
        }
        val flagOnly = Port().apply { pkg = pkg!!.copy(requestsRuntimePermission = false) }
        assertEquals(DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE, read(flagOnly))
        assertEquals(0, flagOnly.permissionReads)
    }
    @Test fun revokeStopUninstallOrInstallUpdateDuringReadCannotPublishReady() {
        val changes: List<Pair<(Port) -> Unit, DaemonOwnerEnvironmentStatus>> = listOf(
            ({ p: Port -> p.permission = false }) to DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE,
            ({ p: Port -> p.pkg = p.pkg!!.copy(stopped = true) }) to DaemonOwnerEnvironmentStatus.USER_STOPPED,
            ({ p: Port -> p.pkg = null }) to DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED,
            ({ p: Port -> p.pkg = p.pkg!!.copy(installation = installation.copy(versionCode = 80)) }) to DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED,
            ({ p: Port -> p.owners = listOf(GUARDIAN_PACKAGE_NAME, "other.app") }) to DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED,
            ({ p: Port -> p.env = p.env.copy(currentUser = 10) }) to DaemonOwnerEnvironmentStatus.ENVIRONMENT_CHANGED,
        )
        changes.forEach { (change, expected) ->
            val port = Port().apply { afterHistory = { change(this) } }
            assertEquals(expected, read(port))
        }
    }
    @Test fun explicitStopsAfterMasterOnBlockButOldStopsDoNot() {
        for (reason in listOf(StartupExitReason.USER_REQUESTED, StartupExitReason.USER_STOPPED)) {
            assertEquals(DaemonOwnerEnvironmentStatus.USER_STOPPED,
                read(Port().apply { history = listOf(DaemonGuardianExit(reason, 501, DaemonRequestedExitDetail.EXPLICIT_STOP)) }))
            assertEquals(DaemonOwnerEnvironmentStatus.READY,
                read(Port().apply { history = listOf(DaemonGuardianExit(reason, 499, DaemonRequestedExitDetail.EXPLICIT_STOP)) }))
        }
    }
    @Test fun ordinaryRemoveTaskIsNotOffAndUnclassifiedUserRequestIsNotPretendOff() {
        assertEquals(DaemonOwnerEnvironmentStatus.READY, read(Port().apply {
            history = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 800, DaemonRequestedExitDetail.REMOVE_TASK))
        }))
        assertEquals(DaemonOwnerEnvironmentStatus.EXIT_HISTORY_UNAVAILABLE, read(Port().apply {
            history = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 800))
        }))
        assertEquals(DaemonOwnerEnvironmentStatus.READY, read(Port().apply {
            history = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 499))
        }))
        assertEquals(DaemonOwnerEnvironmentStatus.USER_STOPPED, read(Port().apply {
            pkg = pkg!!.copy(stopped = true)
            history = listOf(DaemonGuardianExit(StartupExitReason.USER_REQUESTED, 800, DaemonRequestedExitDetail.REMOVE_TASK))
        }))
    }
    @Test fun unavailableSaturatedFutureOrRepeatedCrashHistoryDoesNotAuthorize() {
        for (history in listOf(null, List(64) { DaemonGuardianExit(StartupExitReason.OTHER, 600) },
                listOf(DaemonGuardianExit(StartupExitReason.OTHER, 1001)))) {
            assertEquals(DaemonOwnerEnvironmentStatus.EXIT_HISTORY_UNAVAILABLE, read(Port().apply { this.history = history }))
        }
        assertEquals(DaemonOwnerEnvironmentStatus.CRASH_LOOP, read(Port().apply {
            history = listOf(DaemonGuardianExit(StartupExitReason.CRASH, 600), DaemonGuardianExit(StartupExitReason.ANR, 700))
        }))
    }
    @Test fun rootWrongUserBootLockedAndCancellationFailClosed() {
        for (env in listOf(DaemonOwnerEnvironment(0, 0, boot, true),
                DaemonOwnerEnvironment(2000, 10, boot, true),
                DaemonOwnerEnvironment(2000, 0, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", true),
                DaemonOwnerEnvironment(2000, 0, boot, false))) {
            assertEquals(DaemonOwnerEnvironmentStatus.ENVIRONMENT_CHANGED, read(Port().apply { this.env = env }))
        }
        assertEquals(DaemonOwnerEnvironmentStatus.UNKNOWN, read(Port()) { true })
        var cancelled = false
        val port = Port().apply { afterHistory = { cancelled = true } }
        assertEquals(DaemonOwnerEnvironmentStatus.UNKNOWN, read(port) { cancelled })
    }
}
