package io.github.originosfcmguardian.recovery

import java.util.concurrent.atomic.AtomicBoolean

/** Process-local single flight. A refused operation never constructs a session or dispatches RPC. */
internal class RecoveryAdmission {
    private val busy = AtomicBoolean(false)

    fun isBusy(): Boolean = busy.get()

    suspend fun withAdmission(
        isClosed: () -> Boolean,
        unavailable: () -> StoppedRecoveryResult,
        operation: suspend () -> StoppedRecoveryResult,
    ): StoppedRecoveryResult {
        if (isClosed()) return unavailable()
        if (!busy.compareAndSet(false, true)) {
            // A closed owner remains unavailable even if another request also holds admission.
            if (isClosed()) return unavailable()
            // This caller has no session or receipt. Do not borrow the admitted request's journal.
            return StoppedRecoveryResult(
                StoppedRecoveryOutcome.BLOCKED,
                "已有恢复操作正在进行，尚未进入本次恢复",
                admissionBusy = true,
            )
        }
        return try { operation() } finally { busy.set(false) }
    }
}
