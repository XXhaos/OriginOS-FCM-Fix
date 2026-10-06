package io.github.originosfcmguardian.fcm

import android.content.ComponentName
import android.content.Context
import android.content.Intent

internal const val GMS_DIAGNOSTICS_PACKAGE = "com.google.android.gms"
internal const val GMS_DIAGNOSTICS_ACTIVITY = "com.google.android.gms.gcm.GcmDiagnostics"
internal const val GMS_DIAGNOSTICS_UNAVAILABLE = "无法打开 FCM Diagnostics：Google Play 服务未提供此页面或不允许访问。"

/** One explicit Activity attempt only: no shell fallback, broadcast or reconnection. */
internal fun attemptGmsDiagnostics(start: (String, String) -> Unit): Boolean = try {
    start(GMS_DIAGNOSTICS_PACKAGE, GMS_DIAGNOSTICS_ACTIVITY)
    true
} catch (_: RuntimeException) {
    // Includes ActivityNotFoundException and SecurityException; never expose exception details.
    false
}

internal fun openGmsDiagnostics(context: Context): Boolean = attemptGmsDiagnostics { packageName, className ->
    context.startActivity(Intent().setComponent(ComponentName(packageName, className)))
}
