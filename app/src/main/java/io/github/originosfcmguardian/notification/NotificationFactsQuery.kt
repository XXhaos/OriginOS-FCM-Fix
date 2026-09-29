package io.github.originosfcmguardian.notification

import io.github.originosfcmguardian.core.isSafeAndroidPackageName

/** Inclusive time bounds. A package must still be in the current valid target selection. */
data class NotificationFactsQuery(
    val packageName: String,
    val fromEpochMillis: Long? = null,
    val throughEpochMillis: Long? = null,
    val kind: NotificationFactKind? = null,
) {
    init {
        require(isSafeAndroidPackageName(packageName))
        require(fromEpochMillis == null || fromEpochMillis > 0)
        require(throughEpochMillis == null || throughEpochMillis > 0)
        require(fromEpochMillis == null || throughEpochMillis == null || fromEpochMillis <= throughEpochMillis)
    }
}

/** Preserves the store's newest-first order, including equal timestamps. */
fun queryNotificationFacts(events: List<NotificationFact>, query: NotificationFactsQuery): List<NotificationFact> =
    events.filter { event ->
        event.packageName == query.packageName &&
            (query.fromEpochMillis == null || event.observedAtEpochMillis >= query.fromEpochMillis) &&
            (query.throughEpochMillis == null || event.observedAtEpochMillis <= query.throughEpochMillis) &&
            (query.kind == null || event.kind == query.kind)
    }

/** A deliberately small, fixed schema containing only fields already present in history. */
fun encodeNotificationFactsExport(events: List<NotificationFact>): ByteArray {
    require(events.size <= NotificationFactsStore.MAX_EVENTS)
    val json = buildString {
        append("{\"schema\":\"guardian.notification-facts.v1\",")
        append("\"statement\":\"Notification publication or removal is not proof of FCM delivery or app wake. ")
        append("This is package-name history; installation continuity was not recorded and prior installations may be included.\",")
        append("\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append("{\"packageName\":\"").append(event.packageName)
            append("\",\"observedAtEpochMillis\":").append(event.observedAtEpochMillis)
            append(",\"kind\":\"").append(event.kind.name)
            append("\",\"evidenceLevel\":\"").append(event.evidenceLevel.name)
            append("\"}")
        }
        append("]}")
    }
    return json.toByteArray(Charsets.UTF_8)
}
