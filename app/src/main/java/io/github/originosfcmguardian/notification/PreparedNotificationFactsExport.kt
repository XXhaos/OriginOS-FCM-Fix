package io.github.originosfcmguardian.notification

/** Process-local SAF handoff. A recreated process deliberately cannot recover an old snapshot. */
internal class PreparedNotificationFactsExport {
    private var bytes: ByteArray? = null

    fun prepare(snapshot: ByteArray) {
        bytes = snapshot
    }

    fun invalidate() {
        bytes = null
    }

    fun consume(documentSelected: Boolean): PreparedExportResult {
        val snapshot = bytes
        bytes = null
        if (!documentSelected) return PreparedExportResult.Cancelled
        return if (snapshot == null) PreparedExportResult.Missing else PreparedExportResult.Ready(snapshot)
    }
}

internal sealed interface PreparedExportResult {
    data object Cancelled : PreparedExportResult
    data object Missing : PreparedExportResult
    data class Ready(val bytes: ByteArray) : PreparedExportResult
}
