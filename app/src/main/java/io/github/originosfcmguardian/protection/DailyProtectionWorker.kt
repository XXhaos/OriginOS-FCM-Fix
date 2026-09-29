package io.github.originosfcmguardian.protection

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.originosfcmguardian.autostart.CorruptTargetSelectionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class DailyProtectionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val reason = runCatching {
            ProtectionCheckReason.valueOf(inputData.getString(KEY_REASON).orEmpty())
        }.getOrDefault(ProtectionCheckReason.DAILY_FALLBACK)
        return try {
            withTimeout(WORK_BUDGET_MILLIS) { DailyProtectionChecker(applicationContext).check(reason) }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            saveUnknown(reason, "巡检在 45 秒预算内未完成；已安全清理，不自动重试")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val summary = if (error is CorruptTargetSelectionException) {
                "已保存的目标应用身份记录损坏；请在前台重新选择，未执行自动修复"
            } else {
                "巡检暂不可用；已安全清理，不自动重试"
            }
            saveUnknown(reason, summary)
            Result.success()
        }
    }

    private fun saveUnknown(reason: ProtectionCheckReason, summary: String) {
        DailyProtectionStore(applicationContext).save(
            DailyProtectionReport(
                checkedAtEpochMillis = System.currentTimeMillis(),
                reason = reason,
                fingerprint = Build.FINGERPRINT.take(512),
                status = ProtectionCheckStatus.UNKNOWN,
                summary = summary,
            ),
        )
    }

    companion object {
        const val KEY_REASON = "daily_protection_reason"
        const val WORK_BUDGET_MILLIS = 45_000L
    }
}
