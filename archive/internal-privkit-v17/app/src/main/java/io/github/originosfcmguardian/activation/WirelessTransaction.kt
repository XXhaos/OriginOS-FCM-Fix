package io.github.originosfcmguardian.activation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

internal interface WirelessPort {
    fun preflight(): ActivationPreflight
    fun granted(): Boolean
    fun value(): Int
    fun write(value: Int): Boolean
    fun boot(): Int
    fun elapsed(): Long
}

internal interface WirelessLeaseStore {
    /** Corrupt/unknown records throw; they must never be treated as absent. */
    fun read(): WirelessLease?
    fun save(lease: WirelessLease?)
}

/** Only the live process which verified its own OFF→ON write may clean it up. */
internal class WirelessTransaction(
    private val port: WirelessPort,
    private val store: WirelessLeaseStore,
    private val owner: String = UUID.randomUUID().toString(),
) {
    private var owned: WirelessLease? = null
    fun hasPending(): Boolean = store.read() != null
    fun preflightFailure(wirelessRequired: Boolean = true) = ActivationPolicy.preflightFailure(port.preflight(), wirelessRequired)
    fun begin() {
        check(preflightFailure() == null) { "内置启动前置条件未满足" }
        check(!hasPending()) { "无线事务待处理：请在系统设置关闭无线调试，再点击确认已处理" }
        check(port.granted()) { "缺少 WRITE_SECURE_SETTINGS，请先完成一次性电脑授权" }
        when (port.value()) {
            1 -> return // Preserve a user's baseline ON, including pairing through Settings.
            0 -> Unit
            else -> error("无线调试原值未知，未执行写入")
        }
        val boot = port.boot()
        check(boot >= 0) { "无法确定当前启动代次，未执行写入" }
        val prepared = WirelessLease(owner, boot, port.elapsed(), false)
        store.save(prepared) // Durable BEFORE the only enabling write.
        check(port.write(1) && port.value() == 1) { "开启无线调试未确认；请手动关闭并确认" }
        owned = prepared.copy(confirmed = true)
        store.save(owned)
    }
    fun cleanup(): Boolean {
        val lease = owned ?: return !hasPending()
        val recorded = store.read()
        check(recorded == lease) { "无线事务记录不一致，请手动处理" }
        check(ActivationPolicy.cleanupAllowed(lease, owner, port.boot(), port.elapsed())) {
            "无线事务已过期，请手动关闭无线调试并确认"
        }
        check(port.granted()) { "无线清理权限丢失，请手动处理" }
        when (port.value()) {
            0 -> Unit // Someone already disabled it; do not re-enable.
            1 -> check(port.write(0) && port.value() == 0) { "无线清理未确认，请手动处理" }
            else -> error("无线值未知，请手动处理")
        }
        store.save(null)
        owned = null
        return true
    }
    fun acknowledgeManualCleanup() {
        check(owned == null) { "当前无线事务仍在执行" }
        check(port.value() == 0) { "请先在系统设置关闭无线调试" }
        store.save(null)
    }
    fun abandonUncertainOwnership() { owned = null }
}

internal class AndroidWirelessPort(private val context: Context) : WirelessPort {
    override fun preflight(): ActivationPreflight {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return ActivationPreflight(Build.FINGERPRINT, Process.myUid() / 100_000,
            context.getSystemService(UserManager::class.java).isUserUnlocked,
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, -1),
            // A VPN can own the default network while the actual Wi-Fi transport remains
            // available; no SSID, location or VPN-disabling step is needed for loopback ADB.
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            })
    }
    override fun granted() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    override fun value() = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
    override fun write(value: Int) = Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", value)
    override fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    override fun elapsed() = SystemClock.elapsedRealtime()
}

internal class AtomicWirelessLeaseStore(context: Context) : WirelessLeaseStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "internal-activation-wireless-v1.bin"))
    override fun read(): WirelessLease? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return DataInputStream(file.openRead()).use {
            check(it.readInt() == 1) { "无线事务版本未知" }
            val lease = if (it.readBoolean()) WirelessLease(it.readUTF(), it.readInt(), it.readLong(), it.readBoolean()) else null
            check(it.read() == -1 && (lease == null || (lease.owner.length in 1..64 && lease.boot >= 0 && lease.started >= 0)))
            lease
        }
    }
    override fun save(lease: WirelessLease?) {
        val stream = file.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeInt(1); data.writeBoolean(lease != null)
            lease?.let { data.writeUTF(it.owner); data.writeInt(it.boot); data.writeLong(it.started); data.writeBoolean(it.confirmed) }
            data.flush()
            file.finishWrite(stream)
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
