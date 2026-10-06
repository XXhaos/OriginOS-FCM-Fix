package io.github.originosfcmguardian.protection

import android.content.Context
import android.os.Build
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.identityStatus

/** Daily fallback checks configured installation identities; it grants no recovery authority. */
class DailyProtectionChecker(private val context: Context) {
    private val store = DailyProtectionStore(context)
    private val targetStore = TargetSelectionStore(context)
    private val targetProbe = TargetPackageProbe(context)

    suspend fun check(reason: ProtectionCheckReason): DailyProtectionReport =
        executeCheck(System.currentTimeMillis(), reason, Build.FINGERPRINT.take(512),
            targetStore.load(), targetProbe::identity).also(store::save)
}

internal fun executeCheck(
    now: Long,
    reason: ProtectionCheckReason,
    fingerprint: String,
    selected: List<SelectedTargetIdentity>,
    identityProbe: (String) -> SelectedTargetIdentity?,
): DailyProtectionReport {
    val targets = selected.map { stored ->
        TargetCheckResult(stored.packageName, when (identityStatus(stored, identityProbe(stored.packageName))) {
            TargetIdentityStatus.MISSING -> TargetCheckStatus.MISSING
            TargetIdentityStatus.CHANGED -> TargetCheckStatus.IDENTITY_CHANGED
            TargetIdentityStatus.MATCH -> TargetCheckStatus.IDENTITY_MATCH
        })
    }
    val changed = targets.count { it.status != TargetCheckStatus.IDENTITY_MATCH }
    val status = when {
        targets.isEmpty() -> ProtectionCheckStatus.UNKNOWN
        changed > 0 -> ProtectionCheckStatus.NEEDS_USER_ACTION
        else -> ProtectionCheckStatus.HEALTHY_READ_ONLY
    }
    val summary = if (targets.isEmpty()) "尚未选择应用；未检查应用身份"
        else "应用安装身份：已检查 ${targets.size} 项，${changed} 项需要重新选择；不代表停止限制、FCM 连接或通知送达状态"
    return DailyProtectionReport(now, reason, fingerprint, status, summary, targets)
}
