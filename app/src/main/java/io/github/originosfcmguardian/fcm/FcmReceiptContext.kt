package io.github.originosfcmguardian.fcm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager

internal object FcmReceiptContext {
    fun capture(context: Context): Triple<ObservedBoolean, ObservedBoolean, NetworkAvailability> {
        val power = runCatching {
            context.getSystemService(PowerManager::class.java)
        }.getOrNull()
        val interactive = power?.let {
            runCatching { if (it.isInteractive) ObservedBoolean.YES else ObservedBoolean.NO }.getOrNull()
        } ?: ObservedBoolean.UNKNOWN
        val idle = power?.let {
            runCatching { if (it.isDeviceIdleMode) ObservedBoolean.YES else ObservedBoolean.NO }.getOrNull()
        } ?: ObservedBoolean.UNKNOWN
        val network = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching NetworkAvailability.UNKNOWN
            val active = manager.activeNetwork ?: return@runCatching NetworkAvailability.UNAVAILABLE
            val capabilities = manager.getNetworkCapabilities(active)
                ?: return@runCatching NetworkAvailability.UNKNOWN
            classifyCallbackNetwork(
                hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }.getOrDefault(NetworkAvailability.UNKNOWN)
        return Triple(interactive, idle, network)
    }
}

/** Validation is system connectivity evidence, not proof that a specific Firebase endpoint works. */
internal fun classifyCallbackNetwork(
    hasInternetCapability: Boolean,
    isValidated: Boolean,
): NetworkAvailability = when {
    !hasInternetCapability -> NetworkAvailability.UNAVAILABLE
    isValidated -> NetworkAvailability.AVAILABLE
    else -> NetworkAvailability.UNKNOWN
}
