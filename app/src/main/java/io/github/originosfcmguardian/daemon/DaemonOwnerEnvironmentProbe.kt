package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.StartupExitHistoryStatus
import io.github.originosfcmguardian.autoprotection.StartupExitReason
import io.github.originosfcmguardian.autoprotection.StartupExitRecord
import io.github.originosfcmguardian.autoprotection.startupExitHistoryDecision
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME

/** Installation principal, not an app PID, model, firmware or GMS-version allowlist. */
internal data class DaemonGuardianInstallation(
    val identity: SelectedTargetIdentity,
    val versionCode: Long,
    val lastUpdateTime: Long,
) {
    init {
        require(identity.packageName == GUARDIAN_PACKAGE_NAME)
        require(versionCode > 0 && lastUpdateTime >= identity.firstInstallTime)
    }
}

internal data class DaemonGuardianPackage(
    val installation: DaemonGuardianInstallation,
    val installed: Boolean,
    val enabled: Boolean,
    val stopped: Boolean,
    val requestsRuntimePermission: Boolean,
)

internal data class DaemonOwnerEnvironment(val shellUid: Int, val currentUser: Int,
    val bootId: String, val userUnlocked: Boolean)

internal enum class DaemonRequestedExitDetail { REMOVE_TASK, EXPLICIT_STOP, UNKNOWN }
internal data class DaemonGuardianExit(val reason: StartupExitReason, val timestampMillis: Long,
    val requestedDetail: DaemonRequestedExitDetail = DaemonRequestedExitDetail.UNKNOWN)

/** REMOVE TASK is ordinary UI departure, not consent revocation; ambiguous requests stay unknown. */
internal fun daemonStartupExitRecords(records: List<DaemonGuardianExit>?, masterEnabledAtMillis: Long): List<StartupExitRecord>? =
    records?.map { exit ->
        val reason = if (exit.reason != StartupExitReason.USER_REQUESTED) exit.reason else when (exit.requestedDetail) {
            DaemonRequestedExitDetail.REMOVE_TASK -> StartupExitReason.OTHER
            DaemonRequestedExitDetail.EXPLICIT_STOP -> StartupExitReason.USER_REQUESTED
            DaemonRequestedExitDetail.UNKNOWN -> if (exit.timestampMillis < masterEnabledAtMillis)
                StartupExitReason.OTHER else return null
        }
        StartupExitRecord(reason, exit.timestampMillis)
    }

/** Fixed Guardian-only reads. No process-alive requirement and no privileged write operation. */
internal interface DaemonOwnerEnvironmentReadPort {
    fun environment(): DaemonOwnerEnvironment
    fun guardianPackage(): DaemonGuardianPackage?
    fun packagesForUid(uid: Int): List<String>?
    /** Real system permission query for this fresh app UID; null means unavailable, not granted. */
    fun shizukuRuntimePermission(uid: Int): Boolean?
    fun guardianExitHistory(uid: Int): List<DaemonGuardianExit>?
    fun wallMillis(): Long
}

internal enum class DaemonOwnerEnvironmentStatus {
    READY, ENVIRONMENT_CHANGED, INSTALLATION_CHANGED, USER_STOPPED,
    PERMISSION_UNAVAILABLE, EXIT_HISTORY_UNAVAILABLE, CRASH_LOOP, UNKNOWN,
}

/**
 * Fresh pre-dispatch evidence for independent execution. Never grants consent or caches a grant.
 * A runtime permission is necessary even if an app-side Shizuku flag/cached check once succeeded.
 * Caller death/LOW_MEMORY alone does not revoke protection; explicit stops retain existing policy.
 */
internal class DaemonOwnerEnvironmentProbe(private val port: DaemonOwnerEnvironmentReadPort) {
    fun read(expected: DaemonGuardianInstallation, expectedBoot: String, masterEnabledAtMillis: Long,
        interrupted: () -> Boolean = { false }): DaemonOwnerEnvironmentStatus {
        fun stopped() = interrupted() || Thread.currentThread().isInterrupted
        fun packageStatus(value: DaemonGuardianPackage?): DaemonOwnerEnvironmentStatus? = when {
            value == null || value.installation != expected || !value.installed || !value.enabled ->
                DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED
            value.stopped -> DaemonOwnerEnvironmentStatus.USER_STOPPED
            !value.requestsRuntimePermission -> DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE
            else -> null
        }
        return try {
            if (stopped()) return DaemonOwnerEnvironmentStatus.UNKNOWN
            val before = port.environment()
            if (before.shellUid != 2000 || before.currentUser != 0 || !before.userUnlocked ||
                !BOOT_ID.matches(expectedBoot) || before.bootId != expectedBoot)
                return DaemonOwnerEnvironmentStatus.ENVIRONMENT_CHANGED
            packageStatus(port.guardianPackage())?.let { return it }
            val uid = expected.identity.uid
            if (port.packagesForUid(uid) != listOf(GUARDIAN_PACKAGE_NAME))
                return DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED
            if (stopped()) return DaemonOwnerEnvironmentStatus.UNKNOWN
            if (port.shizukuRuntimePermission(uid) != true)
                return DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE
            val history = startupExitHistoryDecision(masterEnabledAtMillis, port.wallMillis(),
                daemonStartupExitRecords(port.guardianExitHistory(uid), masterEnabledAtMillis))
            when (history) {
                StartupExitHistoryStatus.USER_STOPPED -> return DaemonOwnerEnvironmentStatus.USER_STOPPED
                StartupExitHistoryStatus.CRASH_LOOP -> return DaemonOwnerEnvironmentStatus.CRASH_LOOP
                StartupExitHistoryStatus.UNKNOWN -> return DaemonOwnerEnvironmentStatus.EXIT_HISTORY_UNAVAILABLE
                StartupExitHistoryStatus.CLEAR -> Unit
            }
            // Recheck the principal and permission after cross-service IO; unknown never authorizes.
            if (stopped()) return DaemonOwnerEnvironmentStatus.UNKNOWN
            packageStatus(port.guardianPackage())?.let { return it }
            if (port.packagesForUid(uid) != listOf(GUARDIAN_PACKAGE_NAME))
                return DaemonOwnerEnvironmentStatus.INSTALLATION_CHANGED
            if (port.shizukuRuntimePermission(uid) != true)
                return DaemonOwnerEnvironmentStatus.PERMISSION_UNAVAILABLE
            if (port.environment() != before) return DaemonOwnerEnvironmentStatus.ENVIRONMENT_CHANGED
            if (stopped()) DaemonOwnerEnvironmentStatus.UNKNOWN else DaemonOwnerEnvironmentStatus.READY
        } catch (_: Exception) {
            DaemonOwnerEnvironmentStatus.UNKNOWN
        }
    }

    private companion object {
        val BOOT_ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
