package io.github.originosfcmguardian.privileged

import android.app.ApplicationExitInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import io.github.originosfcmguardian.autoprotection.STARTUP_EXIT_HISTORY_LIMIT
import io.github.originosfcmguardian.autoprotection.StartupExitReason
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.lowerHexDigest
import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.daemon.*
import java.security.MessageDigest

/** Shell-side system Binder reads; never uses app-context/cached Shizuku permission checks. */
internal class SystemDaemonOwnerEnvironmentReadPort : DaemonOwnerEnvironmentReadPort {
    private val environmentPort = SystemGmsConnectionReadPort()
    private val packageApi = Class.forName("android.content.pm.IPackageManager")
    private val packageRemote by lazy { systemService("package", "android.content.pm.IPackageManager") }
    private val activityApi = Class.forName("android.app.IActivityManager")
    private val activityRemote by lazy { systemService("activity", "android.app.IActivityManager") }

    override fun environment(): DaemonOwnerEnvironment {
        val env = environmentPort.environment()
        val userApi = Class.forName("android.os.IUserManager")
        val userRemote = systemService("user", "android.os.IUserManager")
        val unlocked = userApi.getMethod("isUserUnlocked", Int::class.javaPrimitiveType)
            .invoke(userRemote, 0) as? Boolean ?: error("Unknown unlock state")
        return DaemonOwnerEnvironment(env.serviceUid, env.currentUser, env.bootId, unlocked)
    }

    override fun guardianPackage(): DaemonGuardianPackage? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        val info = invokePackageFlagsRead(packageApi, packageRemote, "getPackageInfo",
            arrayOf(String::class.java), arrayOf(GUARDIAN_PACKAGE_NAME), flags.toLong(), 0)
            as? PackageInfo ?: return null
        val app = info.applicationInfo ?: return null
        check(info.packageName == GUARDIAN_PACKAGE_NAME && app.packageName == GUARDIAN_PACKAGE_NAME)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        check(signers.size in 1..16)
        val hashes = signers.map { lowerHexDigest(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
        check(hashes.distinct().size == hashes.size)
        return DaemonGuardianPackage(DaemonGuardianInstallation(SelectedTargetIdentity(
            GUARDIAN_PACKAGE_NAME, app.uid, info.firstInstallTime, hashes.sorted()), info.longVersionCode,
            info.lastUpdateTime), app.flags and ApplicationInfo.FLAG_INSTALLED != 0, app.enabled,
            app.flags and ApplicationInfo.FLAG_STOPPED != 0,
            info.requestedPermissions?.contains(SHIZUKU_RUNTIME_PERMISSION) == true)
    }

    override fun packagesForUid(uid: Int) = environmentPort.packagesForUid(uid)

    override fun shizukuRuntimePermission(uid: Int): Boolean? {
        require(uid in 10_000..99_999)
        val result = activityApi.getMethod("checkPermission", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(activityRemote, SHIZUKU_RUNTIME_PERMISSION, -1, uid) as? Int ?: return null
        return when (result) {
            PackageManager.PERMISSION_GRANTED -> true
            PackageManager.PERMISSION_DENIED -> false
            else -> null
        }
    }

    override fun guardianExitHistory(uid: Int): List<DaemonGuardianExit>? {
        require(uid in 10_000..99_999)
        val slice = activityApi.getMethod("getHistoricalProcessExitReasons", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(activityRemote, GUARDIAN_PACKAGE_NAME, 0, STARTUP_EXIT_HISTORY_LIMIT, 0) ?: return null
        val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return null
        if (list.size > STARTUP_EXIT_HISTORY_LIMIT || list.any { it !is ApplicationExitInfo }) return null
        return list.map { raw ->
            val exit = raw as ApplicationExitInfo
            // A response for another installation cannot be used to clear this owner's stop gate.
            check(exit.packageUid == uid)
            val own = exit.processName == GUARDIAN_PACKAGE_NAME ||
                exit.processName.startsWith("$GUARDIAN_PACKAGE_NAME:")
            check(own)
            val main = exit.processName == GUARDIAN_PACKAGE_NAME
            val reason = when {
                exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> StartupExitReason.USER_REQUESTED
                exit.reason == ApplicationExitInfo.REASON_USER_STOPPED -> StartupExitReason.USER_STOPPED
                main && exit.reason == ApplicationExitInfo.REASON_CRASH -> StartupExitReason.CRASH
                main && exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> StartupExitReason.CRASH_NATIVE
                main && exit.reason == ApplicationExitInfo.REASON_ANR -> StartupExitReason.ANR
                main && exit.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> StartupExitReason.INITIALIZATION_FAILURE
                else -> StartupExitReason.OTHER
            }
            DaemonGuardianExit(reason, exit.timestamp, if (reason == StartupExitReason.USER_REQUESTED)
                requestedExitDetail(exit) else DaemonRequestedExitDetail.UNKNOWN)
        }
    }

    private fun requestedExitDetail(exit: ApplicationExitInfo): DaemonRequestedExitDetail {
        // Symbolic platform values, not hardcoded OEM subreason numbers. Missing metadata is unknown.
        val subReason = runCatching { ApplicationExitInfo::class.java.getMethod("getSubReason")
            .invoke(exit) as? Int }.getOrNull() ?: return DaemonRequestedExitDetail.UNKNOWN
        fun matches(name: String) = runCatching {
            ApplicationExitInfo::class.java.getField(name).getInt(null) == subReason
        }.getOrDefault(false)
        return when {
            matches("SUBREASON_REMOVE_TASK") -> DaemonRequestedExitDetail.REMOVE_TASK
            matches("SUBREASON_FORCE_STOP") || matches("SUBREASON_STOP_APP") -> DaemonRequestedExitDetail.EXPLICIT_STOP
            else -> DaemonRequestedExitDetail.UNKNOWN
        }
    }

    override fun wallMillis() = System.currentTimeMillis()

    private fun systemService(name: String, apiName: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder ?: error("Service unavailable")
        val api = Class.forName(apiName)
        return requireNotNull(Class.forName("$apiName\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)).also { check(api.isInstance(it)) }
    }

    internal companion object {
        const val SHIZUKU_RUNTIME_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}
