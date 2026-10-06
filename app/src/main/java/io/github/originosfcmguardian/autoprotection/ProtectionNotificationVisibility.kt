package io.github.originosfcmguardian.autoprotection

/** System permission/channel settings only; this does not report actual drawer presence. */
enum class ProtectionNotificationVisibility { ALLOWED, BLOCKED, UNKNOWN }

internal inline fun readProtectionNotificationVisibility(readAllowed: () -> Boolean): ProtectionNotificationVisibility =
    try {
        if (readAllowed()) ProtectionNotificationVisibility.ALLOWED else ProtectionNotificationVisibility.BLOCKED
    } catch (_: Exception) {
        ProtectionNotificationVisibility.UNKNOWN
    }
