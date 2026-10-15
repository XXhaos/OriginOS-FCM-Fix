package io.github.originosfcmguardian.daemon

/** Endpoint selection for report-only reads has no connect, fallback or reconnect capability. */
internal suspend fun <Endpoint, Result> readExistingDaemonDiagnostics(
    cached: () -> Endpoint?,
    alive: (Endpoint) -> Boolean,
    read: suspend (Endpoint) -> Result,
): Result {
    val endpoint = cached()?.takeIf(alive) ?: error("Daemon not attached")
    return read(endpoint)
}
