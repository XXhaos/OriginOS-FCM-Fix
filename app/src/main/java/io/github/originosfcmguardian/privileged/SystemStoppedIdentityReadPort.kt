package io.github.originosfcmguardian.privileged

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.system.Os
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import io.github.originosfcmguardian.autostart.RecoveryTargetQualifications
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.autostart.qualifiedRecoveryLauncher

/**
 * Shizuku UserService is a shell process, not an application Context. Resolve only these
 * system read methods; unavailable/OEM-incompatible hidden APIs fail closed in the probe.
 * No ApplicationPackageManager, signature history, shell parsing or caller-supplied target.
 */
internal class SystemStoppedIdentityReadPort : StoppedIdentityReadPort {
    private val packageManager by lazy {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        check(Modifier.isStatic(getService.modifiers) && getService.returnType == IBinder::class.java)
        val binder = getService.invoke(null, "package") as? IBinder ?: error("Package service unavailable")
        val api = Class.forName("android.content.pm.IPackageManager")
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        check(Modifier.isStatic(asInterface.modifiers) && asInterface.returnType == api)
        val remote = asInterface.invoke(null, binder)
        check(api.isInstance(remote))
        SystemPackageReads(
            requireNotNull(remote),
            api.getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .also { check(it.returnType == PackageInfo::class.java) },
            api.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
                .also { check(it.returnType == Array<String>::class.java) },
            api,
        )
    }

    override fun environment(): StoppedProbeEnvironment {
        val getCurrentUser = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser")
        check(Modifier.isStatic(getCurrentUser.modifiers) && getCurrentUser.returnType == Int::class.javaPrimitiveType)
        return StoppedProbeEnvironment(Build.FINGERPRINT, getCurrentUser.invoke(null) as Int, Os.getuid())
    }

    override fun telegramPackage(): StoppedPackageFields? = readPackage(STOPPED_TARGET_PACKAGE, qualify = false)

    override fun targetPackage(packageName: String): StoppedPackageFields? {
        require(isRecoveryTargetPackage(packageName))
        return readPackage(packageName, qualify = true)
    }

    private fun readPackage(packageName: String, qualify: Boolean): StoppedPackageFields? {
        val reads = packageManager
        val info = reads.getPackageInfo.invoke(
            reads.remote, packageName, PackageManager.GET_SIGNING_CERTIFICATES.toLong(), 0,
        ) as? PackageInfo ?: return null
        val applicationInfo = info.applicationInfo ?: return null
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (signers.size !in 1..MAX_CURRENT_SIGNERS) return null
        return StoppedPackageFields(
            info.packageName, applicationInfo.packageName, applicationInfo.uid, info.firstInstallTime,
            signers.map { it.toByteArray() }, applicationInfo.flags and ApplicationInfo.FLAG_STOPPED != 0,
            if (qualify) RecoveryTargetQualifications(
                applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED != 0, applicationInfo.enabled,
                applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                applicationInfo.flags and ApplicationInfo.FLAG_PERSISTENT != 0,
                hasEnabledExportedLauncher(packageName, applicationInfo.uid),
            ) else null,
        )
    }

    override fun packagesForUid(uid: Int): List<String>? {
        val reads = packageManager
        val owners = reads.getPackagesForUid.invoke(reads.remote, uid) as? Array<*> ?: return null
        if (owners.size !in 1..64 || owners.any { it !is String }) return null
        return owners.map { it as String }
    }

    private fun hasEnabledExportedLauncher(packageName: String, uid: Int): Boolean {
        val reads = packageManager
        val query = reads.api.getMethod("queryIntentActivities", Intent::class.java, String::class.java,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        check(query.returnType.name == "android.content.pm.ParceledListSlice")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val slice = requireNotNull(query.invoke(reads.remote, intent, null, 0L, 0))
        val results = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: error("Launcher evidence unavailable")
        require(results.size <= 1024 && results.all { it is ResolveInfo })
        return results.any { result ->
            val activity = (result as ResolveInfo).activityInfo
            activity != null && qualifiedRecoveryLauncher(packageName, uid, activity.packageName,
                activity.applicationInfo?.uid, activity.enabled, activity.exported, activity.applicationInfo?.enabled) {
                val componentState = reads.api.getMethod("getComponentEnabledSetting", ComponentName::class.java, Int::class.javaPrimitiveType)
                check(componentState.returnType == Int::class.javaPrimitiveType)
                componentState.invoke(reads.remote, ComponentName(activity.packageName, activity.name), 0) as? Int
            }
        }
    }

    private data class SystemPackageReads(val remote: Any, val getPackageInfo: Method, val getPackagesForUid: Method, val api: Class<*>)
}
