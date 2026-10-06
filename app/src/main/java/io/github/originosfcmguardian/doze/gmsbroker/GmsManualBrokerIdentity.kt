package io.github.originosfcmguardian.doze.gmsbroker

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsGlobalInstallation
import java.security.MessageDigest

internal const val GMS_MANUAL_BROKER_PACKAGE = "io.github.originosfcmguardian"
internal const val GMS_MANUAL_BROKER_FINGERPRINT =
    "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
private const val ORIGINAL_SIGNER = "d662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d"

/** Fresh installed metadata. Shell callers must bound these framework calls outside admission. */
internal object GmsManualBrokerIdentity {
    fun app(context: Context, expectedUid: Int = Process.myUid()): GmsGlobalGuardianBinding {
        common(expectedUid)
        check(Process.myUid() == expectedUid && context.packageName == GMS_MANUAL_BROKER_PACKAGE)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(GMS_MANUAL_BROKER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        check(pm.getPackagesForUid(expectedUid)?.toList() == listOf(GMS_MANUAL_BROKER_PACKAGE))
        return validate(info, expectedUid)
    }

    fun shell(callerUid: Int): GmsGlobalGuardianBinding {
        common(callerUid)
        check(Process.myUid() == 2_000)
        check(Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) == 0)
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        val api = Class.forName("android.content.pm.IPackageManager")
        val remote = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        check(api.isInstance(remote))
        val info = api.getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(remote, GMS_MANUAL_BROKER_PACKAGE,
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(), 0) as PackageInfo
        val owners = api.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
            .invoke(remote, callerUid) as Array<*>
        check(owners.toList() == listOf(GMS_MANUAL_BROKER_PACKAGE))
        return validate(info, callerUid)
    }

    private fun common(expectedUid: Int) {
        check(expectedUid in 10_000..99_999 && Build.FINGERPRINT == GMS_MANUAL_BROKER_FINGERPRINT)
    }

    private fun validate(info: PackageInfo, expectedUid: Int): GmsGlobalGuardianBinding {
        val app = requireNotNull(info.applicationInfo)
        check(info.packageName == GMS_MANUAL_BROKER_PACKAGE && app.packageName == GMS_MANUAL_BROKER_PACKAGE &&
            app.uid == expectedUid && info.longVersionCode == BuildConfig.VERSION_CODE.toLong())
        check(app.sourceDir.startsWith("/data/app/") && app.sourceDir.endsWith("/base.apk"))
        check(info.firstInstallTime > 0 && info.lastUpdateTime >= info.firstInstallTime)
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1)
        val bytes = signers.single().toByteArray()
        check(bytes.size in 1..32_768)
        val signer = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(signer == ORIGINAL_SIGNER)
        return GmsGlobalGuardianBinding(0, GmsGlobalInstallation(expectedUid, info.longVersionCode,
            info.firstInstallTime, info.lastUpdateTime, listOf(signer)))
    }
}
