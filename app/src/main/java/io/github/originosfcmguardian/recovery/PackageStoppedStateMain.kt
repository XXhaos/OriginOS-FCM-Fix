package io.github.originosfcmguardian.recovery

import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.autostart.validRuntimeFingerprint
import io.github.originosfcmguardian.privileged.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

internal const val PACKAGE_STOPPED_STATE_MAIN = "io.github.originosfcmguardian.recovery.PackageStoppedStateMain"
internal const val PACKAGE_STOPPED_STATE_PROBE = "GUARDIAN_UNSTOP_BINDER_V1"

/** Fixed shell child only. It has no manifest/IPC entry, arbitrary service, method or user input. */
@Keep
object PackageStoppedStateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val succeeded = runCatching {
            check(Os.getuid() == 2000)
            fixedPackageStoppedState(args, SystemPackageStoppedStatePort())
        }.getOrDefault(false)
        if (!succeeded) exitProcess(1)
        if (args.contentEquals(arrayOf("probe"))) println(PACKAGE_STOPPED_STATE_PROBE)
    }
}

internal interface PackageStoppedStatePort {
    val reads: StoppedIdentityReadPort
    fun supported(): Boolean
    fun permissionGranted(): Boolean
    fun clear(packageName: String)
}

/** Exact declared Binder shape only; resolving it never invokes the writer. */
internal fun packageStoppedStateMethod(api: Class<*>): Method = api.getMethod("setPackageStoppedState",
    String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType).also {
    check(it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers))
}

internal fun clearPackageStoppedState(method: Method, remote: Any, packageName: String) {
    method.invoke(remote, packageName, false, 0)
}

/** Probe performs zero writes. A clear repeats the existing full raw identity/UID qualification. */
internal fun fixedPackageStoppedState(args: Array<String>, port: PackageStoppedStatePort): Boolean {
    require(args.size in 1..2)
    val probe = args.size == 1 && args[0] == "probe"
    require(probe || args.size == 2 && args[0] in setOf("clear-legacy", "clear-recipient"))
    val targetAware = !probe && args[0] == "clear-recipient"
    val expected = if (probe) null else decodeRecoveryTarget(args[1], targetAware)
    val before = port.reads.environment()
    check(before.serviceUid == 2000 && before.currentUser == 0 && validRuntimeFingerprint(before.fingerprint))
    check(port.supported() && port.permissionGranted())
    if (probe) { check(port.reads.environment() == before); return true }
    val identity = checkNotNull(expected)
    val observed = if (targetAware) observeStoppedTarget(1, identity, port.reads)
        else observeStoppedTelegram(1, port.reads)
    val known = observed as? StoppedTargetObservation.Known ?: error("Target evidence unavailable")
    check(known.identity == identity && known.hasVerifiedPackageUid && (!targetAware || known.qualifiedRecipient))
    check(port.reads.environment() == before && port.supported() && port.permissionGranted())
    if (known.stopped) port.clear(identity.packageName)
    return true
}

private class SystemPackageStoppedStatePort : PackageStoppedStatePort {
    override val reads: StoppedIdentityReadPort = SystemStoppedIdentityReadPort()
    private val api = Class.forName("android.content.pm.IPackageManager")
    private val remote by lazy { service("package", "android.content.pm.IPackageManager") }
    private val method by lazy { packageStoppedStateMethod(api) }
    override fun supported() = runCatching { method; check(api.isInstance(remote)); true }.getOrDefault(false)
    override fun permissionGranted(): Boolean {
        if (Os.getuid() != 2000) return false
        val activityApi = Class.forName("android.app.IActivityManager")
        return activityApi.getMethod("checkPermission", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service("activity", "android.app.IActivityManager"),
                "android.permission.CHANGE_COMPONENT_ENABLED_STATE", -1, 2000) as? Int == 0
    }
    override fun clear(packageName: String) { clearPackageStoppedState(method, remote, packageName) }
    private fun service(name: String, apiName: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder ?: error("Service unavailable")
        return checkNotNull(Class.forName("$apiName\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder))
    }
}
