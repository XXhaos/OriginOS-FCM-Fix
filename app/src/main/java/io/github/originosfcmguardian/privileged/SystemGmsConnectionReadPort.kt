package io.github.originosfcmguardian.privileged

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import io.github.originosfcmguardian.core.gms.*
import io.github.originosfcmguardian.autostart.effectiveRecoveryComponentEnabled
import java.io.FileInputStream
import java.io.FileNotFoundException

/** Fixed shell-side sources only. No raw network table, address or exception crosses IPC. */
internal class SystemGmsConnectionReadPort : GmsConnectionReadPort {
    private val api = Class.forName("android.content.pm.IPackageManager")
    private val remote by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "package") as IBinder
        requireNotNull(Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)).also { check(api.isInstance(it)) }
    }
    override fun environment(): GmsProbeEnvironment {
        val user = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        val boot = FileInputStream("/proc/sys/kernel/random/boot_id").use { input ->
            val bytes = ByteArray(38)
            var count = 0
            while (count < bytes.size) { val next = input.read(); if (next < 0) break; bytes[count++] = next.toByte() }
            check(count in 36..37 && input.read() == -1)
            String(bytes, 0, count, Charsets.US_ASCII).trim()
        }
        check(boot.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        return GmsProbeEnvironment(user, Os.getuid(), boot)
    }
    override fun gmsPackage(): GmsPackageIdentity? {
        val info = invokePackageFlagsRead(api, remote, "getPackageInfo", arrayOf(String::class.java),
            arrayOf(GMS_OBSERVATION_PACKAGE), PackageManager.GET_SIGNING_CERTIFICATES.toLong(), 0) as? PackageInfo ?: return null
        val app = info.applicationInfo ?: return null
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        return GmsPackageIdentity(info.packageName, app.packageName, app.uid, info.longVersionCode,
            info.firstInstallTime, info.lastUpdateTime, gmsSignerDigest(signers.map { it.toByteArray() }))
    }
    override fun packagesForUid(uid: Int): List<String>? {
        val owners = api.getMethod("getPackagesForUid", Int::class.javaPrimitiveType).invoke(remote, uid) as? Array<*> ?: return null
        if (owners.size !in 1..64 || owners.any { it !is String }) return null
        return owners.map { it as String }
    }
    override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean): SocketSource = try {
        FileInputStream(if (ipv6) "/proc/net/tcp6" else "/proc/net/tcp").buffered(4096).use {
            GmsSocketParser.parse(it, ipv6, uid, interrupted)
        }
    } catch (_: SecurityException) { SocketSource(SourceCompleteness.DENIED, false) }
    catch (_: FileNotFoundException) { SocketSource(SourceCompleteness.UNAVAILABLE, false) }
    catch (_: java.io.IOException) { SocketSource(SourceCompleteness.UNAVAILABLE, false) }

    override fun gcmService(interrupted: () -> Boolean): GcmServiceSource {
        if (interrupted() || Thread.currentThread().isInterrupted) return GcmServiceParser.unknown(SourceCompleteness.CANCELLED)
        // The verified shell UID is inherited by this fixed child; this is not an in-process Binder dump.
        return try {
            val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "activity", "service", GcmServiceParser.COMPONENT).start()
            GcmServiceProcessReader.read(process, interrupted)
        } catch (_: SecurityException) { GcmServiceParser.unknown(SourceCompleteness.DENIED) }
        catch (_: java.io.IOException) { GcmServiceSource.unavailable() }
    }

    override fun reconnectReceivers(): GmsReceiverInspection {
        val intent = Intent(GMS_RECONNECT_ACTION).setPackage(GMS_OBSERVATION_PACKAGE)
        val flags = (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE).toLong()
        val slice = invokePackageFlagsRead(api, remote, "queryIntentReceivers", arrayOf(Intent::class.java, String::class.java),
            arrayOf(intent, null), flags, 0)
            ?: return GmsReceiverInspection(false, emptyList())
        val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return GmsReceiverInspection(false, emptyList())
        if (list.size > 16) return GmsReceiverInspection(false, emptyList())
        val receivers = list.map {
            val info = (it as ResolveInfo).activityInfo
            check(info.packageName == GMS_OBSERVATION_PACKAGE)
            ReconnectReceiver(info.name, info.exported, info.enabled && info.applicationInfo.enabled, info.permission)
        }
        return GmsReceiverInspection(true, receivers)
    }
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    override fun recoverySenderQualified(): Boolean {
        if (Os.getuid() != 2000) return false
        val component = ComponentName(GMS_OBSERVATION_PACKAGE, FcmRecoveryPolicy.COMPONENT)
        val receiver = invokePackageFlagsRead(api, remote, "getReceiverInfo", arrayOf(ComponentName::class.java),
            arrayOf(component), PackageManager.MATCH_DISABLED_COMPONENTS.toLong(), 0) as? ActivityInfo ?: return false
        val app = receiver.applicationInfo ?: return false
        val identity = gmsPackage() ?: return false
        val componentState = api.getMethod("getComponentEnabledSetting", ComponentName::class.java,
            Int::class.javaPrimitiveType).invoke(remote, component, 0) as? Int ?: return false
        val applicationState = api.getMethod("getApplicationEnabledSetting", String::class.java,
            Int::class.javaPrimitiveType).invoke(remote, GMS_OBSERVATION_PACKAGE, 0) as? Int ?: return false
        return qualifiedFcmRecoverySender(
            receiver.packageName, receiver.name, app.packageName, app.uid, identity.uid,
            receiver.exported, effectiveRecoveryComponentEnabled(receiver.enabled, componentState),
            effectiveRecoveryComponentEnabled(app.enabled, applicationState),
            app.flags and ApplicationInfo.FLAG_INSTALLED != 0, app.flags and ApplicationInfo.FLAG_STOPPED != 0,
            receiver.permission,
            permissionGrantedToShell = { permission ->
                api.getMethod("checkUidPermission", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(remote, permission, 2000) as? Int == PackageManager.PERMISSION_GRANTED
            },
            actionIsProtected = {
                api.getMethod("isProtectedBroadcast", String::class.java)
                    .invoke(remote, FcmRecoveryPolicy.ACTION) as? Boolean
            },
        ) && FcmPackageStateReader.read()
    }
}

/** An unread or denied sender capability only disables this recovery operation. It never gates
 * the shared GMS connection observation or the independently qualified heartbeat operation. */
internal fun qualifiedFcmRecoverySender(packageName: String?, name: String?, applicationPackage: String?,
    uid: Int?, expectedUid: Int, exported: Boolean, componentEnabled: Boolean, applicationEnabled: Boolean,
    installed: Boolean, stopped: Boolean, permission: String?, permissionGrantedToShell: (String) -> Boolean,
    actionIsProtected: () -> Boolean?): Boolean {
    if (packageName != GMS_OBSERVATION_PACKAGE || name != FcmRecoveryPolicy.COMPONENT ||
        applicationPackage != GMS_OBSERVATION_PACKAGE || uid != expectedUid || expectedUid !in 10_000..99_999 ||
        !exported || !componentEnabled || !applicationEnabled || !installed || stopped) return false
    if (runCatching(actionIsProtected).getOrNull() != false) return false
    return permission == null || permission.isNotBlank() &&
        runCatching { permissionGrantedToShell(permission) }.getOrDefault(false)
}
