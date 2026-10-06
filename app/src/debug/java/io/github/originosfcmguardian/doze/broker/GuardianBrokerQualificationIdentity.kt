package io.github.originosfcmguardian.doze.broker

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import io.github.originosfcmguardian.BuildConfig
import java.security.MessageDigest

internal const val BROKER_PACKAGE = "io.github.originosfcmguardian"
internal const val BROKER_FINGERPRINT =
    "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
private const val ORIGINAL_SIGNER = "d662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d"

/** Fresh framework metadata, matching the source-qualified OFD probe; no native or process call. */
internal object GuardianBrokerQualificationIdentity {
    fun app(context: Context, expectedUid: Int) {
        common(expectedUid)
        check(Process.myUid() == expectedUid && context.packageName == BROKER_PACKAGE)
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(BROKER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        validate(info, expectedUid)
        check(pm.getPackagesForUid(expectedUid)?.toList() == listOf(BROKER_PACKAGE))
    }

    fun shell(callerUid: Int) {
        common(callerUid)
        check(Process.myUid() == 2_000)
        check(Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) == 0)
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "package") as IBinder
        val api = Class.forName("android.content.pm.IPackageManager")
        val remote = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        check(api.isInstance(remote))
        val info = api.getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(remote, BROKER_PACKAGE,
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(), 0) as PackageInfo
        validate(info, callerUid)
        val owners = api.getMethod("getPackagesForUid", Int::class.javaPrimitiveType)
            .invoke(remote, callerUid) as Array<*>
        check(owners.toList() == listOf(BROKER_PACKAGE))
    }

    private fun common(expectedUid: Int) {
        check(BuildConfig.DEBUG && expectedUid in 10_000..99_999 && Build.FINGERPRINT == BROKER_FINGERPRINT)
    }

    private fun validate(info: PackageInfo, expectedUid: Int) {
        val app = requireNotNull(info.applicationInfo)
        check(info.packageName == BROKER_PACKAGE && app.packageName == BROKER_PACKAGE && app.uid == expectedUid)
        check(app.sourceDir.startsWith("/data/app/") && app.sourceDir.endsWith("/base.apk"))
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == ORIGINAL_SIGNER)
    }
}
