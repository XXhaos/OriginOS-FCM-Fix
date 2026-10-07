package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.StoppedRecoveryRequest
import io.github.originosfcmguardian.autostart.StoppedRecoveryProbe
import io.github.originosfcmguardian.autostart.StoppedRecoveryPreflight
import io.github.originosfcmguardian.autostart.StoppedRecoveryReadback
import io.github.originosfcmguardian.autostart.preflightStoppedRecovery
import io.github.originosfcmguardian.autostart.classifyStoppedRecoveryReadback
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

internal enum class RecoveryFailureStage { INITIAL_READ, CAPABILITY, CLAIM, FINAL_READ, UNSTOP, READBACK }
internal enum class RecoveryFailureKind { REJECTED_EXECUTION, TIMED_OUT, INTERRUPTED, UNKNOWN }

/** Only fixed exception categories are diagnostic evidence; never expose messages or stack traces. */
internal fun recoveryFailureKind(failure: Throwable): RecoveryFailureKind {
    var current: Throwable? = failure
    repeat(4) {
        when (current) {
            is RejectedExecutionException -> return RecoveryFailureKind.REJECTED_EXECUTION
            is TimeoutException -> return RecoveryFailureKind.TIMED_OUT
            is InterruptedException, is CancellationException -> return RecoveryFailureKind.INTERRUPTED
        }
        current = current?.cause
    }
    return RecoveryFailureKind.UNKNOWN
}

/** Service-owned fresh observations. The claim is the irreversible dispatch boundary, never replayed. */
internal class StoppedRecoveryEngine(
    private val observe: (Long) -> StoppedTargetObservation,
    private val claim: (Long) -> Boolean,
    private val active: () -> Boolean,
    private val unstop: () -> Int,
) {
    fun recover(id: Long, expected: SelectedTargetIdentity, capable: () -> Boolean = { true }): StoppedRecoveryResult {
        var claimed = false
        var commandSucceeded = false
        var stage = RecoveryFailureStage.INITIAL_READ
        fun result(outcome: StoppedRecoveryOutcome, detail: String) = StoppedRecoveryResult(outcome, detail,
            commandSucceeded = commandSucceeded)
        fun guard(observation: StoppedTargetObservation): StoppedRecoveryPreflight {
            if (observation.requestId != id) return StoppedRecoveryPreflight.Blocked(io.github.originosfcmguardian.autostart.RecoveryBlockReason.INVALID_REQUEST)
            val known = observation as? StoppedTargetObservation.Known
            return preflightStoppedRecovery(StoppedRecoveryRequest(id, expected), StoppedRecoveryProbe(
                true, expected, known?.fingerprint, known?.currentUser, known?.serviceUid == 2000,
                known?.serviceUid, known?.identity, known?.exclusiveUidOwner, known?.stopped,
                known?.qualifiedRecipient == true,
            ))
        }
        return try {
            check(active()) { "操作已中断" }
            when (val preflight = guard(observe(id))) {
                StoppedRecoveryPreflight.NoWriteNeeded -> return result(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "停止标记已解除，消息接收尚未验证")
                is StoppedRecoveryPreflight.Blocked -> return result(StoppedRecoveryOutcome.BLOCKED, "执行前阻止：${preflight.reason}")
                StoppedRecoveryPreflight.ReadyForServerRecheck -> Unit
            }
            stage = RecoveryFailureStage.CAPABILITY
            check(active()) { "操作已中断" }
            if (!capable()) return result(StoppedRecoveryOutcome.BLOCKED, "系统恢复接口暂不可用")
            stage = RecoveryFailureStage.CLAIM
            check(claim(id)) { "请求已使用或持久记录不可用" }
            claimed = true
            stage = RecoveryFailureStage.FINAL_READ
            check(active()) { "派发后操作已中断" }
            // Claim/callback IO may take time: independently repeat all guards immediately before write.
            when (val finalGuard = guard(observe(id))) {
                StoppedRecoveryPreflight.NoWriteNeeded -> return result(StoppedRecoveryOutcome.NO_WRITE_NEEDED, "停止标记已解除，消息接收尚未验证")
                is StoppedRecoveryPreflight.Blocked -> return result(StoppedRecoveryOutcome.UNCONFIRMED, "派发后校验不可用：${finalGuard.reason}，先只读复检")
                StoppedRecoveryPreflight.ReadyForServerRecheck -> Unit
            }
            stage = RecoveryFailureStage.UNSTOP
            check(active()) { "派发后操作已中断" }
            val exit = unstop()
            commandSucceeded = exit == 0
            stage = RecoveryFailureStage.READBACK
            check(active()) { "执行后操作已中断" }
            val after = (observe(id) as? StoppedTargetObservation.Known)?.takeIf { it.requestId == id && it.hasVerifiedPackageUid }
            check(active()) { "读回已中断" }
            when (classifyStoppedRecoveryReadback(StoppedRecoveryRequest(id, expected), after?.identity, after?.stopped)) {
                StoppedRecoveryReadback.FLAG_CLEARED -> result(StoppedRecoveryOutcome.FLAG_CLEARED, "已确认标准停止标记解除；消息接收尚未验证（exit=$exit）")
                StoppedRecoveryReadback.STILL_STOPPED -> result(StoppedRecoveryOutcome.STILL_STOPPED, "标准停止标记仍为停止；消息接收未验证（exit=$exit）")
                StoppedRecoveryReadback.UNCONFIRMED -> result(StoppedRecoveryOutcome.UNCONFIRMED, "执行结果待确认，先只读复检")
            }
        } catch (failure: Throwable) {
            result(if (claimed) StoppedRecoveryOutcome.UNCONFIRMED else StoppedRecoveryOutcome.BLOCKED,
                (if (claimed) "已派发操作结果待确认，先只读复检" else "未执行：校验、记录或连接不可用") +
                    " [stage=$stage error=${recoveryFailureKind(failure)}]")
        }
    }
}

internal object RecoveryResultCodec {
    fun encode(result: StoppedRecoveryResult) = result.outcome.name + "|" + result.detail.take(192) + "|" + if (result.commandSucceeded) "1" else "0"
    fun decode(raw: String?): StoppedRecoveryResult {
        return runCatching {
            require(raw != null && raw.length <= 256)
            val fields = raw.split('|', limit = 3); require(fields.size in 2..3 && fields[1].length <= 192)
            require(fields.size == 2 || fields[2] in setOf("0", "1"))
            StoppedRecoveryResult(StoppedRecoveryOutcome.valueOf(fields[0]), fields[1],
                commandSucceeded = fields.size == 3 && fields[2] == "1")
        }.getOrElse { StoppedRecoveryResult(StoppedRecoveryOutcome.UNCONFIRMED, "服务结果不可用，先只读复检") }
    }
}
