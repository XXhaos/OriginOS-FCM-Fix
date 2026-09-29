package io.github.originosfcmguardian.notification

import io.github.originosfcmguardian.core.isSafeAndroidPackageName

enum class NotificationFactKind {
    POSTED_OR_UPDATED,
    REMOVED,
}

/** NotificationListenerService directly observed this notification event. */
enum class NotificationFactEvidenceLevel {
    CONFIRMED,
}

/** A confirmed system notification callback. It does not imply an FCM cause. */
data class NotificationFact(
    val packageName: String,
    val observedAtEpochMillis: Long,
    val kind: NotificationFactKind,
    val evidenceLevel: NotificationFactEvidenceLevel = NotificationFactEvidenceLevel.CONFIRMED,
) {
    init {
        require(isSafeAndroidPackageName(packageName))
        require(observedAtEpochMillis > 0)
        require(evidenceLevel == NotificationFactEvidenceLevel.CONFIRMED)
    }
}

data class NotificationFactsUiState(
    val enabled: Boolean = false,
    val accessGranted: Boolean = false,
    val connected: Boolean = false,
    val targetCount: Int = 0,
    val events: List<NotificationFact> = emptyList(),
    val validTargetPackages: List<String> = emptyList(),
    val historyCorrupt: Boolean = false,
    val droppedEvents: Int = 0,
    val stopPersistenceUncertain: Boolean = false,
)

internal sealed interface NotificationFactsSnapshot {
    data object Empty : NotificationFactsSnapshot
    data class Available(val events: List<NotificationFact>) : NotificationFactsSnapshot
    data object Corrupt : NotificationFactsSnapshot
}

internal enum class RecordNotificationFactResult {
    STORED,
    CORRUPT,
    FAILED,
}
