package io.github.originosfcmguardian.gmsconfig

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Orders one checker's metadata and complete check reads before its shared RPC. */
internal class GmsConfigurationReadGate(private val metadataBudgetMillis: Long = 11_000) {
    init { require(metadataBudgetMillis in 1..11_000) }
    private val mutex = Mutex()

    // Admission and RPC completion share the original metadata deadline.
    suspend fun <T> metadata(read: suspend () -> T): T = withTimeout(metadataBudgetMillis) {
        mutex.withLock { read() }
    }

    // Called only inside the existing 45-second check coordinator budget. Publication
    // happens after this returns, so preference listeners cannot contend with its reads.
    suspend fun <T> checkRead(read: suspend () -> T): T = mutex.withLock { read() }
}
