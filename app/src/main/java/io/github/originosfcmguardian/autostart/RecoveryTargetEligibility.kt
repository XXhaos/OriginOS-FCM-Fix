package io.github.originosfcmguardian.autostart

import io.github.originosfcmguardian.core.isSafeAndroidPackageName

/** Eligibility is evidence only. Selection and explicit consent are separate requirements. */
internal fun isRecoveryTargetPackage(packageName: String): Boolean =
    isSafeAndroidPackageName(packageName) && packageName !in setOf(
        "android", "io.github.originosfcmguardian", "io.github.originosfcmguardian.test",
        "io.github.originosfcmguardian.fcmtest", "com.google.android.gms", "com.android.vending",
        "moe.shizuku.privileged.api", "com.android.shell", "com.android.systemui",
    )

/** PackageManager component overrides take precedence over the manifest, including launcher aliases. */
internal fun effectiveRecoveryComponentEnabled(manifestEnabled: Boolean?, setting: Int?): Boolean = when (setting) {
    0 -> manifestEnabled == true // COMPONENT_ENABLED_STATE_DEFAULT
    1 -> true // COMPONENT_ENABLED_STATE_ENABLED
    else -> false // Disabled variants, unknown values and unavailable reads never qualify.
}

internal fun qualifiedRecoveryLauncher(expectedPackage: String, expectedUid: Int, packageName: String?, uid: Int?,
    manifestEnabled: Boolean?, exported: Boolean?, applicationEnabled: Boolean?, readSetting: () -> Int?): Boolean {
    if (packageName != expectedPackage || uid != expectedUid || exported != true || applicationEnabled != true) return false
    return effectiveRecoveryComponentEnabled(manifestEnabled, runCatching(readSetting).getOrNull())
}

internal data class RecoveryTargetQualifications(
    val installed: Boolean?, val enabled: Boolean?, val system: Boolean?,
    val updatedSystem: Boolean?, val persistent: Boolean?,
    /** Legacy observation only. Clearing a stopped flag does not launch an activity. */
    val enabledExportedLauncher: Boolean? = null,
) {
    val eligible: Boolean get() = installed == true && enabled == true && system == false &&
        updatedSystem == false && persistent == false
}
