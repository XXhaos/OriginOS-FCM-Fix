package io.github.originosfcmguardian.monitoring

/** Main-thread-only, one-shot handoff to the document picker. Never restored after process death. */
internal class PreparedMonitorSessionExport {
    data class Ticket(val sequence: Long, val sessionId: String)

    private var sequence = 0L
    private var active: Ticket? = null
    private var valid = false
    private var bytes: ByteArray? = null

    fun begin(sessionId: String): Ticket? {
        if (active != null) return null
        return Ticket(++sequence, sessionId).also {
            active = it
            valid = true
        }
    }

    fun complete(ticket: Ticket, snapshot: ByteArray): Boolean {
        if (active != ticket || !valid) return false
        bytes = snapshot.copyOf()
        return true
    }

    /** Keep the old picker reserved until its callback, so it cannot consume a newer export. */
    fun invalidate() {
        valid = false
        bytes = null
    }

    fun cancel(ticket: Ticket) {
        if (active == ticket) {
            active = null
            invalidate()
        }
    }

    fun consume(documentSelected: Boolean, currentSessionId: String?): MonitorExportResult {
        val snapshot = bytes.takeIf { valid && active?.sessionId == currentSessionId }
        active = null
        invalidate()
        return when {
            !documentSelected -> MonitorExportResult.Cancelled
            snapshot == null -> MonitorExportResult.Missing
            else -> MonitorExportResult.Ready(snapshot)
        }
    }
}

internal sealed interface MonitorExportResult {
    data object Cancelled : MonitorExportResult
    data object Missing : MonitorExportResult
    data class Ready(val bytes: ByteArray) : MonitorExportResult
}
