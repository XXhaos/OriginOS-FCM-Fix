package io.github.originosfcmguardian.gmsconfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Process-wide ordering for manual and automatic GMS configuration checks. */
internal object GmsConfigurationCheckCoordinator {
    private val mutex = Mutex()

    suspend fun check(
        budgetMillis: Long = GMS_CONFIG_BUDGET_MILLIS,
        read: suspend () -> GmsConfigSnapshot?,
        timeoutSnapshot: () -> GmsConfigSnapshot,
        failureSnapshot: () -> GmsConfigSnapshot,
        eligible: () -> Boolean,
        publish: (GmsConfigSnapshot) -> Unit,
    ) {
        val callerContext = currentCoroutineContext()
        // The budget includes admission wait. An unadmitted timeout cannot publish.
        withTimeoutOrNull(budgetMillis) {
            mutex.withLock {
                val result = try {
                    read()
                } catch (_: TimeoutCancellationException) {
                    callerContext.ensureActive()
                    timeoutSnapshot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failureSnapshot()
                }
                if (result != null) {
                    // The timeout child may be cancelled, but the original owner must
                    // still be active before any synchronous eligibility/commit work.
                    callerContext.ensureActive()
                    val allowed = eligible()
                    callerContext.ensureActive()
                    if (allowed) {
                        val finalResult = if (currentCoroutineContext().isActive) result else timeoutSnapshot()
                        publish(finalResult)
                    }
                }
            }
        }
        callerContext.ensureActive()
    }
}
