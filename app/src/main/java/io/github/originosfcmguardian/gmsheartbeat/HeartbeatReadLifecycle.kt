package io.github.originosfcmguardian.gmsheartbeat

/** Retires only the dedicated observation binding, on every read exit, including cancellation. */
internal suspend fun <T> withHeartbeatReadCleanup(retireReader: () -> Unit, read: suspend () -> T): T =
    try { read() } finally { retireReader() }
