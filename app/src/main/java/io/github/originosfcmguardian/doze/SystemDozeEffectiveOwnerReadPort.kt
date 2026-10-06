package io.github.originosfcmguardian.doze

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.system.Os
import java.security.MessageDigest
import java.util.concurrent.ExecutionException

/** Shell-side fixed getter sweep. Names come only from the trusted bounded inventory in this process. */
internal class SystemDozeEffectiveOwnerReadPort(
    private val readInventory: () -> DozeOwnerCandidates,
    private val requireActive: () -> Unit,
    private val remainingMillis: () -> Long,
    private val onUncertainExit: () -> Unit,
) : DozeEffectiveOwnerReadPort, AutoCloseable {
    private val getters = DozeEffectiveOwnerGetterCalls(requireActive, remainingMillis, onUncertainExit)
    val uncertainBinderExit: Boolean get() = getters.uncertainExit
    private val api = Class.forName("android.content.pm.IPackageManager")
    private val remote by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder).also { check(api.isInstance(it)) }
    }
    private var allowedNames: Set<String> = emptySet()

    override fun inventory(): DozeOwnerCandidates {
        requireActive()
        val value = readInventory()
        if (allowedNames.isEmpty()) allowedNames = value.records.mapTo(linkedSetOf()) { it.packageName }
        requireActive()
        return value
    }
    override fun environment(): DozeEffectiveEnvironment = bounded(DozeEffectiveOwnerRejection.ENVIRONMENT_READ) {
        val user = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        DozeEffectiveEnvironment(Build.FINGERPRINT, Os.getuid(), user)
    }
    override fun packageInfo(canonicalName: String): DozeEffectivePackage? {
        check(canonicalName in allowedNames)
        return bounded(DozeEffectiveOwnerRejection.PACKAGE_READ) {
            val info = getPackage(canonicalName, PACKAGE_FLAGS) ?: return@bounded null
            DozeEffectivePackage(info.packageName, PackageInfo::class.java.getField("isApex").getBoolean(info),
                info.longVersionCode, info.applicationInfo?.uid)
        }
    }
    override fun applicationInfo(canonicalName: String): DozeEffectiveApplication? {
        check(canonicalName in allowedNames)
        return bounded(DozeEffectiveOwnerRejection.APPLICATION_READ) {
            val info = api.getMethod("getApplicationInfo", String::class.java, Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(remote, canonicalName, MATCH_ANY_USER, 0) as? ApplicationInfo
                ?: return@bounded null
            val pcc = try { ApplicationInfo::class.java.getField("pccUid").getInt(info) }
                catch (_: NoSuchFieldException) { throw DozeEffectiveOwnerReadException(DozeEffectiveOwnerRejection.PCC_FIELD_UNAVAILABLE) }
            DozeEffectiveApplication(info.packageName, info.uid, pcc)
        }
    }
    override fun gmsInstallation(): DozeEffectiveGmsInstallation? = bounded(DozeEffectiveOwnerRejection.GMS_READ) {
        val info = getPackage(EFFECTIVE_OWNER_GMS, PACKAGE_FLAGS or PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            ?: return@bounded null
        val app = info.applicationInfo ?: return@bounded null
        val signatures = info.signingInfo?.apkContentsSigners ?: return@bounded null
        check(signatures.size in 1..8 && !PackageInfo::class.java.getField("isApex").getBoolean(info))
        val hashes = signatures.map { signature ->
            val bytes = signature.toByteArray()
            check(bytes.size in 1..32_768)
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }.distinct().sorted()
        DozeEffectiveGmsInstallation(info.packageName, app.packageName, app.uid, info.longVersionCode,
            info.firstInstallTime, info.lastUpdateTime, hashes)
    }
    private fun getPackage(name: String, flags: Long): PackageInfo? =
        api.getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(remote, name, flags, 0) as? PackageInfo

    private fun <T> bounded(rejection: DozeEffectiveOwnerRejection, action: () -> T): T {
        try {
            return getters.read(action)
        } catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is DozeEffectiveOwnerReadException) throw cause
            if (cause is NoSuchMethodException || cause is NoSuchFieldException || cause is ClassNotFoundException ||
                cause is IllegalAccessException) throw DozeEffectiveOwnerReadException(DozeEffectiveOwnerRejection.REFLECTION_UNAVAILABLE)
            throw DozeEffectiveOwnerReadException(rejection)
        }
    }
    override fun close() = getters.close()
    private companion object {
        const val MATCH_ANY_USER = 0x00400000L
        const val MATCH_APEX = 0x40000000L
        // MATCH_ANY_USER | MATCH_APEX; the application getter uses only MATCH_ANY_USER.
        const val PACKAGE_FLAGS = 0x40400000L
    }
}
