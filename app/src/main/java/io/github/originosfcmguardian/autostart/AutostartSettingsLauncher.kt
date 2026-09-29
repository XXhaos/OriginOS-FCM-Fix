package io.github.originosfcmguardian.autostart

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

const val VIVO_AUTOSTART_SETTINGS_ACTION = "com.iqoo.secure.BGSTARTUPMANAGER"
const val VIVO_AUTOSTART_SETTINGS_PACKAGE = "com.vivo.permissionmanager"
const val VIVO_AUTOSTART_SETTINGS_CLASS =
    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"

sealed interface AutostartSettingsLaunchResult {
    data object Launched : AutostartSettingsLaunchResult
    data object Unavailable : AutostartSettingsLaunchResult
    data class Failed(val detail: String) : AutostartSettingsLaunchResult
}

object AutostartSettingsLauncher {
    @Suppress("DEPRECATION")
    fun open(activity: Activity): AutostartSettingsLaunchResult {
        val implicitIntent = Intent(VIVO_AUTOSTART_SETTINGS_ACTION)
        val resolved = runCatching {
            activity.packageManager.resolveActivity(
                implicitIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrNull()?.activityInfo
        if (!isExpectedAutostartSettingsComponent(resolved?.packageName, resolved?.name)) {
            return AutostartSettingsLaunchResult.Unavailable
        }

        val explicitIntent = Intent(VIVO_AUTOSTART_SETTINGS_ACTION).apply {
            component = ComponentName(VIVO_AUTOSTART_SETTINGS_PACKAGE, VIVO_AUTOSTART_SETTINGS_CLASS)
        }
        return runCatching {
            activity.startActivity(explicitIntent)
            AutostartSettingsLaunchResult.Launched
        }.getOrElse { error ->
            AutostartSettingsLaunchResult.Failed(
                error.message?.take(120) ?: error.javaClass.simpleName,
            )
        }
    }
}

internal fun isExpectedAutostartSettingsComponent(packageName: String?, className: String?): Boolean =
    packageName == VIVO_AUTOSTART_SETTINGS_PACKAGE && className == VIVO_AUTOSTART_SETTINGS_CLASS
