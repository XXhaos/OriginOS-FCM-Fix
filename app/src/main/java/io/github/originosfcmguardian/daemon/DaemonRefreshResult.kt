package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException

/** Cancelling one UI reader is not evidence that the independent protection owner is unavailable. */
internal suspend fun <T> daemonRefreshOrUnavailable(read: suspend () -> T, unavailable: () -> Unit): T? =
    try { read() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { unavailable(); null }
