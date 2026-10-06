package io.github.originosfcmguardian.protection

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ManualProtectionSubmissionResult(val message: String) {
    ACCEPTED("只读巡检请求已提交，系统可能延后执行；提交成功不等于巡检完成，请以报告的检查时间为准。"),
    FAILED("巡检请求提交失败，尚未确认入队；可以重试。原巡检记录保留。"),
    UNCONFIRMED("等待提交确认超时，任务可能仍会执行；未自动重试。请先查看报告的检查时间，必要时重新提交。"),
}

/** Wait only for enqueue acknowledgement, never for the worker or its report. */
internal suspend fun submitManualProtection(
    timeoutMillis: Long = 10_000,
    enqueue: () -> ListenableFuture<*>,
): ManualProtectionSubmissionResult {
    require(timeoutMillis > 0)
    return try {
        withTimeoutOrNull(timeoutMillis) {
            val outcome = suspendCancellableCoroutine<Result<Unit>> { continuation ->
                val future = enqueue()
                future.addListener(
                    {
                        // A cancelled enqueue Future is a submission failure, not cancellation of
                        // the ViewModel coroutine. Timeout/cancellation must not cancel queued work.
                        continuation.resume(runCatching { future.get(); Unit })
                    },
                    Executor { it.run() },
                )
            }
            if (outcome.isSuccess) ManualProtectionSubmissionResult.ACCEPTED
            else ManualProtectionSubmissionResult.FAILED
        } ?: ManualProtectionSubmissionResult.UNCONFIRMED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ManualProtectionSubmissionResult.FAILED
    }
}
