package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind

/** Inclusive filters for one exact retained monitoring session. */
data class MonitorSessionQuery(
    val sessionId: String,
    val fromEpochMillis: Long? = null,
    val throughEpochMillis: Long? = null,
    val kind: ProcessTraceKind? = null,
) {
    init {
        require(SAFE_SESSION_ID.matches(sessionId)) { "Invalid monitor session ID" }
        require(fromEpochMillis == null || fromEpochMillis > 0) { "Invalid query start" }
        require(throughEpochMillis == null || throughEpochMillis > 0) { "Invalid query end" }
        require(
            fromEpochMillis == null || throughEpochMillis == null ||
                fromEpochMillis <= throughEpochMillis,
        ) { "Invalid monitor query window" }
    }
}

/**
 * Returns a newest-first snapshot. Equal timestamps preserve their retained-session order even if
 * collection batches were stored in an unusual order.
 */
fun queryMonitorSession(
    session: MonitorSession,
    query: MonitorSessionQuery,
): List<ProcessTraceEvent> {
    require(query.sessionId == session.id) { "Monitor session ID does not match" }

    return session.events
        .withIndex()
        .filter { (_, event) ->
            (query.fromEpochMillis == null || event.timestampEpochMillis >= query.fromEpochMillis) &&
                (query.throughEpochMillis == null || event.timestampEpochMillis <= query.throughEpochMillis) &&
                (query.kind == null || event.kind == query.kind)
        }
        .sortedWith(
            compareByDescending<IndexedValue<ProcessTraceEvent>> { it.value.timestampEpochMillis }
                .thenBy { it.index },
        )
        .map(IndexedValue<ProcessTraceEvent>::value)
}

/** A fixed, privacy-minimized export of an in-memory session snapshot and its exact query. */
fun encodeMonitorSessionExport(
    session: MonitorSession,
    query: MonitorSessionQuery,
): ByteArray {
    val events = queryMonitorSession(session, query)
    val snapshotStatus = if (session.status == MonitorSessionStatus.RUNNING) {
        "RUNNING_PARTIAL"
    } else {
        "NOT_RUNNING"
    }
    val json = buildString {
        append("{\"schema\":\"guardian.monitor-session.v1\",")
        append("\"session\":{")
        append("\"id\":\"").append(session.id)
        append("\",\"packageName\":\"").append(session.identity.packageName)
        append("\",\"startedAtEpochMillis\":").append(session.startedAtEpochMillis)
        append(",\"deadlineEpochMillis\":").append(session.deadlineEpochMillis)
        append(",\"status\":\"").append(session.status.name)
        append("\",\"snapshotStatus\":\"").append(snapshotStatus)
        append("\",\"pollCount\":").append(session.pollCount)
        append(",\"retainedEventCount\":").append(session.events.size)
        append("},\"query\":{")
        append("\"sessionId\":\"").append(query.sessionId)
        append("\",\"fromEpochMillis\":").append(query.fromEpochMillis ?: "null")
        append(",\"throughEpochMillis\":").append(query.throughEpochMillis ?: "null")
        append(",\"kind\":")
        if (query.kind == null) append("null") else append('"').append(query.kind.name).append('"')
        append("},\"evidence\":{")
        append("\"captureScope\":\"BOUNDED\",")
        append("\"completeness\":\"UNKNOWN\",")
        append("\"systemEventFacts\":\"")
        append(if (events.isEmpty()) "UNKNOWN" else "CONFIRMED")
        append("\",")
        append("\"fcmDelivery\":\"UNKNOWN\",")
        append("\"fcmCause\":\"UNKNOWN\",")
        append("\"fcmConnection\":\"UNKNOWN\",")
        append("\"emptyResultMeaning\":\"UNKNOWN\",")
        append("\"installationIdentity\":\"PINNED_AT_COLLECTION\",")
        append("\"currentInstallationContinuity\":\"UNVERIFIED\"")
        append("},\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append("{\"timestampEpochMillis\":").append(event.timestampEpochMillis)
            append(",\"kind\":\"").append(event.kind.name).append('"')
            event.pid?.let { append(",\"pid\":").append(it) }
            append('}')
        }
        append("]}")
    }
    return json.toByteArray(Charsets.UTF_8)
}

private val SAFE_SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
