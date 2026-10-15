package io.github.originosfcmguardian.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** At most one worker and one coalesced event. No timer runs while waiting for a future event. */
internal class DaemonReconnectRequests {
    private var active = false
    private var pending = false
    private var passes = 0

    @Synchronized fun offer(): Boolean {
        if (active) {
            if (passes < 2) pending = true
            return false
        }
        pending = true
        passes = 0
        active = true
        return true
    }

    @Synchronized fun takePending(): Boolean {
        if (!pending || passes >= 2) { active = false; pending = false; return false }
        pending = false
        passes++
        return true
    }
}

/** A released/replaced host can take time to rebind; each attempt repeats the same safe restore. */
internal suspend fun reconnectDaemonWhileReady(
    ready: () -> Boolean,
    reconnect: suspend () -> Unit,
    failed: () -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    for (pause in longArrayOf(0, 1_000, 4_000, 30_000)) {
        if (!ready()) return false
        if (pause > 0) wait(pause)
        if (!ready()) return false
        try {
            reconnect()
            return true
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed() }
    }
    return false
}
