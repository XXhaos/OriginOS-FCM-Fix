package io.github.originosfcmguardian.protection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One interactive RPC lane: refreshes never queue ahead of an explicit confirmation. */
internal class InteractiveGmsReadCoordinator {
    private val admission = Any()
    private val mutex = Mutex()
    private var confirmations = 0

    suspend fun <T : Any> foreground(read: suspend () -> T): T? {
        val acquired = synchronized(admission) { confirmations == 0 && mutex.tryLock() }
        if (!acquired) return null // Keep the UI's previous observation; this is not a failed read.
        return try { read() } finally { mutex.unlock() }
    }

    /** Includes admission and the complete read lifecycle; cancellation always releases admission. */
    suspend fun <T : Any> confirmation(timeoutMillis: Long, read: suspend () -> T): T? {
        require(timeoutMillis > 0)
        synchronized(admission) { confirmations++ }
        return try { withTimeoutOrNull(timeoutMillis) { mutex.withLock { read() } } }
        finally { synchronized(admission) { confirmations-- } }
    }

    companion object { val process = InteractiveGmsReadCoordinator() }
}
