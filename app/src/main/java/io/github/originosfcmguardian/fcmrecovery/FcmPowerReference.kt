package io.github.originosfcmguardian.fcmrecovery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.UUID

enum class FcmChargingStatus { CHARGING, FULL, DISCHARGING, NOT_CHARGING, UNKNOWN }
enum class FcmPluggedSource { UNPLUGGED, AC, USB, WIRELESS, DOCK, OTHER, UNKNOWN }

/** Whole-phone battery and current Guardian main-process CPU reference, never attributed energy. */
data class FcmPowerReference(val batteryPercent: Int? = null, val chargingStatus: FcmChargingStatus? = null,
    val pluggedSource: FcmPluggedSource? = null, val interactive: Boolean? = null, val deviceIdle: Boolean? = null,
    val mainProcessCpuMillis: Long? = null, val processAgeMillis: Long? = null, val processInstanceId: String? = null) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100)
        require(mainProcessCpuMillis == null || mainProcessCpuMillis >= 0)
        require(processAgeMillis == null || processAgeMillis >= 0)
        require(processInstanceId == null || processInstanceId.matches(Regex("[a-f0-9-]{36}")))
    }
}
internal interface FcmPowerReadPort {
    fun batteryPercent(): Int?
    fun chargingStatus(): FcmChargingStatus?
    fun pluggedSource(): FcmPluggedSource?
    fun interactive(): Boolean?
    fun deviceIdle(): Boolean?
    fun mainProcessCpuMillis(): Long?
    fun processAgeMillis(): Long?
    fun processInstanceId(): String?
}

/** Optional reads are independent: failure of any field cannot abort a recovery cycle. */
internal fun captureFcmPowerReference(port: FcmPowerReadPort) = FcmPowerReference(
    runCatching { port.batteryPercent()?.takeIf { it in 0..100 } }.getOrNull(),
    runCatching { port.chargingStatus() }.getOrNull(), runCatching { port.pluggedSource() }.getOrNull(),
    runCatching { port.interactive() }.getOrNull(), runCatching { port.deviceIdle() }.getOrNull(),
    runCatching { port.mainProcessCpuMillis()?.takeIf { it >= 0 } }.getOrNull(),
    runCatching { port.processAgeMillis()?.takeIf { it >= 0 } }.getOrNull(),
    runCatching { port.processInstanceId()?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } }.getOrNull())

internal object FcmPowerReferenceReader {
    private val processInstance = UUID.randomUUID().toString()
    fun capture(context: Context): FcmPowerReference = captureFcmPowerReference(object : FcmPowerReadPort {
        // A sticky query only; no receiver is installed and no new wakeup source exists.
        private val battery by lazy { runCatching { context.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() }
        private val power by lazy { context.getSystemService(PowerManager::class.java) }
        override fun batteryPercent(): Int? {
            val sticky = battery ?: return null
            return fcmBatteryPercent(sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        }
        override fun chargingStatus(): FcmChargingStatus? = battery?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> FcmChargingStatus.CHARGING
                BatteryManager.BATTERY_STATUS_FULL -> FcmChargingStatus.FULL
                BatteryManager.BATTERY_STATUS_DISCHARGING -> FcmChargingStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> FcmChargingStatus.NOT_CHARGING
                else -> FcmChargingStatus.UNKNOWN
            }
        }
        override fun pluggedSource(): FcmPluggedSource? = battery?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                0 -> FcmPluggedSource.UNPLUGGED
                BatteryManager.BATTERY_PLUGGED_AC -> FcmPluggedSource.AC
                BatteryManager.BATTERY_PLUGGED_USB -> FcmPluggedSource.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> FcmPluggedSource.WIRELESS
                BatteryManager.BATTERY_PLUGGED_DOCK -> FcmPluggedSource.DOCK
                -1 -> FcmPluggedSource.UNKNOWN
                else -> FcmPluggedSource.OTHER
            }
        }
        override fun interactive() = power?.isInteractive
        override fun deviceIdle() = power?.isDeviceIdleMode
        override fun mainProcessCpuMillis() = Process.getElapsedCpuTime()
        override fun processAgeMillis(): Long? {
            val now = SystemClock.elapsedRealtime(); val start = Process.getStartElapsedRealtime()
            return if (start in 0..now) now - start else null
        }
        override fun processInstanceId() = processInstance
    })
}

internal fun fcmBatteryPercent(level: Int, scale: Int): Int? =
    if (scale <= 0 || level !in 0..scale) null else (level.toLong() * 100 / scale).toInt()
