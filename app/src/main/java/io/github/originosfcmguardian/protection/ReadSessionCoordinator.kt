package io.github.originosfcmguardian.protection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes a complete privileged read lifecycle, including the caller's cleanup. */
object ReadSessionCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withSession(block: suspend () -> T): T = mutex.withLock { block() }
}
