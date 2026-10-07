package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import java.util.concurrent.TimeUnit

/** Finite post-command observation only. This function cannot issue or retry a mutation. */
internal fun awaitStoppedRecoveryReadback(
    identity: SelectedTargetIdentity,
    initial: StoppedRecoveryResult,
    read: () -> StoppedTargetObservation,
    active: () -> Boolean,
    pause: () -> Unit = { Thread.sleep(1_000) },
    nowNanos: () -> Long = System::nanoTime,
): StoppedRecoveryResult {
    if (initial.outcome != StoppedRecoveryOutcome.STILL_STOPPED) return initial
    val deadline = nowNanos() + TimeUnit.SECONDS.toNanos(8)
    fun unknown() = StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "恢复结果待确认，请只读核对；不会重复执行")
    return try {
        repeat(8) {
            if (!active()) return unknown()
            pause()
            if (!active()) return unknown()
            if (nowNanos() > deadline) return initial
            val observation = read() as? StoppedTargetObservation.Known ?: return unknown()
            if (!active()) return unknown()
            if (observation.identity != identity || !observation.hasVerifiedPackageUid) return unknown()
            if (nowNanos() > deadline) return unknown()
            if (!observation.stopped) return StoppedRecoveryResult(
                StoppedRecoveryOutcome.FLAG_CLEARED, "操作成功：已解除停止限制，通知送达需另行确认",
                commandSucceeded = initial.commandSucceeded,
            )
        }
        StoppedRecoveryResult(StoppedRecoveryOutcome.STILL_STOPPED, "操作未成功：观察期内仍有停止限制",
            commandSucceeded = initial.commandSucceeded)
    } catch (_: Exception) { unknown() }
}
