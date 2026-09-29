package io.github.originosfcmguardian.device

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

data class GmsPackageSnapshot(
    val installed: Boolean?,
    val enabled: Boolean?,
    val versionName: String?,
    val versionCode: Long?,
) {
    val summary: String
        get() = when {
            installed == false -> "未安装"
            enabled == false -> "已安装，但当前被停用"
            enabled == true -> buildString {
                append("已安装并启用")
                versionName?.let { append(" · $it") }
                versionCode?.let { append(" ($it)") }
            }
            installed == true -> "已安装，启用状态未知"
            else -> "读取失败，状态未知"
        }
}

class NormalDeviceProbe(private val context: Context) {
    fun readGmsPackage(): GmsPackageSnapshot = try {
        val info = packageInfo(GMS_PACKAGE)
        GmsPackageSnapshot(
            installed = true,
            enabled = info.applicationInfo?.enabled,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        GmsPackageSnapshot(false, null, null, null)
    } catch (_: RuntimeException) {
        GmsPackageSnapshot(null, null, null, null)
    }

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
    }
}
