package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent

enum class MonitorSessionStatus {
    RUNNING,
    STOPPED,
    DEADLINE,
    UNAVAILABLE,
    IDENTITY_CHANGED,
    LIMIT_REACHED,
    INTERRUPTED,
}

data class MonitorSession(
    val id: String,
    val identity: SelectedTargetIdentity,
    val startedAtEpochMillis: Long,
    val deadlineEpochMillis: Long,
    val status: MonitorSessionStatus,
    val events: List<ProcessTraceEvent>,
    val pollCount: Int = 0,
) {
    init {
        require(SESSION_ID.matches(id)) { "Invalid monitor session ID" }
        require(startedAtEpochMillis > 0) { "Invalid monitor session start" }
        require(deadlineEpochMillis > startedAtEpochMillis) { "Invalid monitor session deadline" }
        require(deadlineEpochMillis - startedAtEpochMillis <= MAX_DURATION_MILLIS) {
            "Monitor session exceeds its duration limit"
        }
        require(events.size <= MAX_EVENTS) { "Too many monitor session events" }
        require(events.distinct().size == events.size) { "Duplicate monitor session events" }
        require(events.all(::eventBelongsToSession)) { "Monitor session contains a cross-target event" }
        require(
            events.all {
                it.timestampEpochMillis in startedAtEpochMillis..deadlineEpochMillis
            },
        ) { "Monitor session event is outside the session window" }
        require(pollCount >= 0) { "Invalid monitor session poll count" }
    }

    private fun eventBelongsToSession(event: ProcessTraceEvent): Boolean =
        event.target.packageName == identity.packageName && event.target.uid == identity.uid

    companion object {
        const val MAX_EVENTS = 500
        const val MAX_DURATION_MILLIS = 30L * 60L * 1_000L

        private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/**
 * Applies one bounded collector result without interpreting an empty poll as a completed capture.
 * Calls outside a running session are inert. A poll at/after the deadline may persist events whose
 * own timestamps are still inside the session window, then closes the session as [MonitorSessionStatus.DEADLINE].
 */
fun MonitorSession.appendBatch(
    events: List<ProcessTraceEvent>,
    nowEpochMillis: Long,
): MonitorSession {
    require(nowEpochMillis > 0) { "Invalid monitor poll time" }
    if (status != MonitorSessionStatus.RUNNING || nowEpochMillis < startedAtEpochMillis) return this

    val existing = this.events.toHashSet()
    val additions = ArrayList<ProcessTraceEvent>()
    var reachedLimit = this.events.size == MonitorSession.MAX_EVENTS

    for (event in events) {
        if (event.target.packageName != identity.packageName || event.target.uid != identity.uid) continue
        if (event.timestampEpochMillis !in startedAtEpochMillis..deadlineEpochMillis) continue
        if (event.timestampEpochMillis > nowEpochMillis) continue
        if (event in existing || event in additions) continue
        if (this.events.size + additions.size == MonitorSession.MAX_EVENTS) {
            reachedLimit = true
            break
        }
        additions += event
        if (this.events.size + additions.size == MonitorSession.MAX_EVENTS) reachedLimit = true
    }

    val nextStatus = when {
        reachedLimit -> MonitorSessionStatus.LIMIT_REACHED
        nowEpochMillis >= deadlineEpochMillis -> MonitorSessionStatus.DEADLINE
        else -> MonitorSessionStatus.RUNNING
    }
    return copy(
        status = nextStatus,
        events = this.events + additions,
        pollCount = if (pollCount == Int.MAX_VALUE) pollCount else pollCount + 1,
    )
}
