package io.github.originosfcmguardian.monitoring

/** Main-thread ledger: stop buttons deliver a newer Android service start id than START. */
internal class MonitorServiceLifetime {
    private var latestStartId: Int? = null

    fun onCommand(startId: Int) {
        latestStartId = startId
    }

    fun stopId(): Int = checkNotNull(latestStartId)
}
