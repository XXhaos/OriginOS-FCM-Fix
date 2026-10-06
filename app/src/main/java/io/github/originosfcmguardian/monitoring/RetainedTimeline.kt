package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.notification.NotificationFact
import io.github.originosfcmguardian.notification.NotificationFactKind

enum class RetainedTimelineSource {
    NOTIFICATION,
    PROCESS_SESSION,
}

enum class RetainedTimelineKind {
    NOTIFICATION_POSTED_OR_UPDATED,
    NOTIFICATION_REMOVED,
    PROCESS_START,
    UID_ACTIVE,
    UID_IDLE,
    UID_RUNNING,
    UID_STOPPED,
}

enum class RetainedTimelineTimestampMeaning {
    NOTIFICATION_OBSERVED_AT,
    SYSTEM_EVENT_TIME,
}

/** The two retained sources make different installation-continuity claims. */
enum class RetainedTimelineInstallationScope {
    PACKAGE_ONLY_UNKNOWN,
    PINNED_SESSION_CURRENT_UNKNOWN,
}

enum class RetainedTimelineEvidenceLevel {
    CONFIRMED,
}

/** An observed fact only; the combined view intentionally has no FCM or causal outcome field. */
data class RetainedTimelineItem(
    val packageName: String,
    val currentSelectedAppLabel: String?,
    val timestampEpochMillis: Long,
    val timestampMeaning: RetainedTimelineTimestampMeaning,
    val source: RetainedTimelineSource,
    val kind: RetainedTimelineKind,
    val sessionId: String?,
    val installationScope: RetainedTimelineInstallationScope,
    val evidenceLevel: RetainedTimelineEvidenceLevel = RetainedTimelineEvidenceLevel.CONFIRMED,
) {
    init {
        require(isSafeAndroidPackageName(packageName))
        require(timestampEpochMillis > 0)
        require(currentSelectedAppLabel == null || currentSelectedAppLabel.isNotBlank())
        require((source == RetainedTimelineSource.PROCESS_SESSION) == (sessionId != null))
        require(
            when (source) {
                RetainedTimelineSource.NOTIFICATION ->
                    timestampMeaning == RetainedTimelineTimestampMeaning.NOTIFICATION_OBSERVED_AT &&
                        installationScope == RetainedTimelineInstallationScope.PACKAGE_ONLY_UNKNOWN &&
                        kind in NOTIFICATION_KINDS
                RetainedTimelineSource.PROCESS_SESSION ->
                    timestampMeaning == RetainedTimelineTimestampMeaning.SYSTEM_EVENT_TIME &&
                        installationScope == RetainedTimelineInstallationScope.PINNED_SESSION_CURRENT_UNKNOWN &&
                        kind in PROCESS_KINDS
            },
        ) { "Timeline item fields do not match its source" }
    }
}

enum class RetainedTimelineWarning {
    NOTIFICATION_SOURCE_OMITTED,
    PROCESS_SOURCE_OMITTED,
}

data class RetainedTimelineSnapshot(
    val items: List<RetainedTimelineItem>,
    val warnings: Set<RetainedTimelineWarning>,
) {
    init {
        require(items.size <= MAX_RETAINED_TIMELINE_ITEMS)
    }
}

data class RetainedTimelineTimeWindow(
    val fromEpochMillis: Long,
    val throughEpochMillis: Long,
) {
    init {
        require(fromEpochMillis > 0)
        require(throughEpochMillis >= fromEpochMillis)
    }

    companion object {
        const val RECENT_24_HOURS_MILLIS = 24L * 60L * 60L * 1_000L

        /** Captures both inclusive boundaries so recomposition or later events cannot move the query. */
        fun recent24Hours(capturedAtEpochMillis: Long): RetainedTimelineTimeWindow {
            require(capturedAtEpochMillis > 0)
            return RetainedTimelineTimeWindow(
                fromEpochMillis = (capturedAtEpochMillis - RECENT_24_HOURS_MILLIS).coerceAtLeast(1L),
                throughEpochMillis = capturedAtEpochMillis,
            )
        }
    }
}

data class RetainedTimelineQuery(
    val searchText: String = "",
    val packageName: String? = null,
    val timeWindow: RetainedTimelineTimeWindow? = null,
    val source: RetainedTimelineSource? = null,
    val kind: RetainedTimelineKind? = null,
    val limit: Int = MAX_RETAINED_TIMELINE_ITEMS,
) {
    init {
        require(searchText.length <= MAX_RETAINED_TIMELINE_SEARCH_LENGTH)
        require(packageName == null || isSafeAndroidPackageName(packageName))
        require(limit in 1..MAX_RETAINED_TIMELINE_ITEMS)
        require(
            source == null || kind == null ||
                source == RetainedTimelineSource.NOTIFICATION && kind in NOTIFICATION_KINDS ||
                source == RetainedTimelineSource.PROCESS_SESSION && kind in PROCESS_KINDS,
        ) { "Timeline kind does not belong to the selected source" }
    }
}

/**
 * Combines only already-retained facts. Notification labels are current selected-target metadata,
 * never historical identity evidence. A corrupt source is omitted independently of the other.
 */
fun combineRetainedTimeline(
    notificationEvents: List<NotificationFact>,
    notificationHistoryCorrupt: Boolean,
    processSession: MonitorSession?,
    processHistoryCorrupt: Boolean,
    currentSelectedAppLabels: Map<String, String> = emptyMap(),
): RetainedTimelineSnapshot {
    val warnings = linkedSetOf<RetainedTimelineWarning>()
    val indexed = ArrayList<IndexedTimelineItem>(MAX_RETAINED_TIMELINE_ITEMS)

    if (notificationHistoryCorrupt || notificationEvents.size > MAX_NOTIFICATION_ITEMS) {
        warnings += RetainedTimelineWarning.NOTIFICATION_SOURCE_OMITTED
    } else {
        notificationEvents.forEachIndexed { index, fact ->
            indexed += IndexedTimelineItem(
                sourceIndex = index,
                item = RetainedTimelineItem(
                    packageName = fact.packageName,
                    currentSelectedAppLabel = currentLabel(fact.packageName, currentSelectedAppLabels),
                    timestampEpochMillis = fact.observedAtEpochMillis,
                    timestampMeaning = RetainedTimelineTimestampMeaning.NOTIFICATION_OBSERVED_AT,
                    source = RetainedTimelineSource.NOTIFICATION,
                    kind = fact.kind.toTimelineKind(),
                    sessionId = null,
                    installationScope = RetainedTimelineInstallationScope.PACKAGE_ONLY_UNKNOWN,
                ),
            )
        }
    }

    if (processHistoryCorrupt || (processSession?.events?.size ?: 0) > MAX_PROCESS_ITEMS) {
        warnings += RetainedTimelineWarning.PROCESS_SOURCE_OMITTED
    } else {
        processSession?.events?.forEachIndexed { index, event ->
            indexed += IndexedTimelineItem(
                sourceIndex = index,
                item = RetainedTimelineItem(
                    packageName = event.target.packageName,
                    currentSelectedAppLabel = currentLabel(event.target.packageName, currentSelectedAppLabels),
                    timestampEpochMillis = event.timestampEpochMillis,
                    timestampMeaning = RetainedTimelineTimestampMeaning.SYSTEM_EVENT_TIME,
                    source = RetainedTimelineSource.PROCESS_SESSION,
                    kind = event.kind.toTimelineKind(),
                    sessionId = processSession.id,
                    installationScope = RetainedTimelineInstallationScope.PINNED_SESSION_CURRENT_UNKNOWN,
                ),
            )
        }
    }

    val items = indexed
        .sortedWith(
            compareByDescending<IndexedTimelineItem> { it.item.timestampEpochMillis }
                .thenBy { it.item.source.ordinal }
                .thenBy { it.sourceIndex },
        )
        .map(IndexedTimelineItem::item)
    return RetainedTimelineSnapshot(items, warnings)
}

fun queryRetainedTimeline(
    snapshot: RetainedTimelineSnapshot,
    query: RetainedTimelineQuery,
): List<RetainedTimelineItem> {
    val search = query.searchText.trim()
    return snapshot.items.asSequence()
        .filter { item -> query.packageName == null || item.packageName == query.packageName }
        .filter { item ->
            query.timeWindow == null ||
                item.timestampEpochMillis in
                query.timeWindow.fromEpochMillis..query.timeWindow.throughEpochMillis
        }
        .filter { item -> query.source == null || item.source == query.source }
        .filter { item -> query.kind == null || item.kind == query.kind }
        .filter { item ->
            search.isEmpty() ||
                item.packageName.contains(search, ignoreCase = true) ||
                item.currentSelectedAppLabel?.contains(search, ignoreCase = true) == true
        }
        .take(query.limit)
        .toList()
}

private data class IndexedTimelineItem(
    val sourceIndex: Int,
    val item: RetainedTimelineItem,
)

private fun currentLabel(packageName: String, labels: Map<String, String>): String? =
    labels[packageName]?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_CURRENT_LABEL_LENGTH)

private fun NotificationFactKind.toTimelineKind(): RetainedTimelineKind = when (this) {
    NotificationFactKind.POSTED_OR_UPDATED -> RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED
    NotificationFactKind.REMOVED -> RetainedTimelineKind.NOTIFICATION_REMOVED
}

private fun ProcessTraceKind.toTimelineKind(): RetainedTimelineKind = when (this) {
    ProcessTraceKind.PROCESS_START -> RetainedTimelineKind.PROCESS_START
    ProcessTraceKind.UID_ACTIVE -> RetainedTimelineKind.UID_ACTIVE
    ProcessTraceKind.UID_IDLE -> RetainedTimelineKind.UID_IDLE
    ProcessTraceKind.UID_RUNNING -> RetainedTimelineKind.UID_RUNNING
    ProcessTraceKind.UID_STOPPED -> RetainedTimelineKind.UID_STOPPED
}

const val MAX_RETAINED_TIMELINE_ITEMS = 700
const val MAX_RETAINED_TIMELINE_SEARCH_LENGTH = 128
private const val MAX_NOTIFICATION_ITEMS = 200
private const val MAX_PROCESS_ITEMS = 500
private const val MAX_CURRENT_LABEL_LENGTH = 80

private val NOTIFICATION_KINDS = setOf(
    RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED,
    RetainedTimelineKind.NOTIFICATION_REMOVED,
)
private val PROCESS_KINDS = setOf(
    RetainedTimelineKind.PROCESS_START,
    RetainedTimelineKind.UID_ACTIVE,
    RetainedTimelineKind.UID_IDLE,
    RetainedTimelineKind.UID_RUNNING,
    RetainedTimelineKind.UID_STOPPED,
)
