package io.github.originosfcmguardian.autoprotection

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.recovery.AndroidRecoveryJournal
import rikka.shizuku.Shizuku

internal data class StartupProbeResult(val leases: List<TargetConsentLease>, val status: StartupEligibilityStatus,
    val invalidTargets: List<TargetConsentLease> = emptyList()) {
    /** Source compatibility for legacy test fixtures; runtime always consumes the full lease list. */
    constructor(token: DurableConsentToken?, status: StartupEligibilityStatus) : this(
        token?.let { listOf(TargetConsentLease(it.generation, TargetConsent.Legacy(it))) }.orEmpty(), status)
}

/** IO only. No target write, permission grant, consent upgrade or attempt reservation. */
internal class ProtectionStartupProbe(private val context: Context, private val store: ProtectionConsentStore) {
    fun read(expected: List<TargetConsentLease>? = null): StartupProbeResult {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            return StartupProbeResult(null, StartupEligibilityStatus.WAITING_UNLOCK)
        }
        val state = runCatching { store.readSnapshot() }
        val snapshot = state.getOrNull()
        val leases = snapshot?.let { root -> root.runnableConsents()
            .map { TargetConsentLease(root.generation, it) } }.orEmpty()
        if (expected != null && expected != leases) return StartupProbeResult(leases, StartupEligibilityStatus.BLOCKED_STORAGE)
        if (leases.isEmpty()) {
            return StartupProbeResult(leases, startupEligibility(StartupEligibilityInputs(
                authorityReadSuccessful = state.isSuccess, authority = state.getOrNull(), userUnlocked = true)))
        }
        val history = historySince(snapshot?.masterEnabledAtMillis ?: leases.minOf { it.consent.consentedAtMillis })
        val selectionRead = runCatching { TargetSelectionStore(context).load() }
        val selection = selectionRead.getOrNull()
        val journal = runCatching { AndroidRecoveryJournal.open(context).load().latest }
        val journalStatus = when {
            journal.isFailure -> RestartJournalStatus.CORRUPT
            journal.getOrNull()?.requiresReconcile == true -> RestartJournalStatus.PENDING
            else -> RestartJournalStatus.CLEAR
        }
        val authorization = store.checkRecoveryAuthorization(leases.first())
        val privilege = privilegeEvidence()
        val packageProbe = TargetPackageProbe(context)
        // Capture only PM inputs; storage, history, selection and privilege stay on this caller.
        val packageInputs = leases.map { lease -> StartupPackageInput(
            lease.consent.identity.copy(signerSha256 = lease.consent.identity.signerSha256.toList()),
            lease.consent !is TargetConsent.Legacy) }
        val packageRead = StartupTargetReader.read(packageInputs) { input ->
            StartupPackageEvidence(packageProbe.identity(input.identity.packageName),
                !input.requiresQualification || packageProbe.isRecoveryCandidate(input.identity))
        }
        val evidence = (packageRead as? StartupTargetReadResult.Complete)?.values
        // Unavailable batches expose no partial identity facts, including retirement candidates.
        val targets = leases.mapIndexed { index, lease -> StartupTargetEvidence(lease,
            evidence?.get(index)?.identity, selection?.singleOrNull { it == lease.consent.identity },
            evidence?.get(index)?.qualified ?: false) }
        val inputs = StartupEligibilityInputs(
            authorityReadSuccessful = state.isSuccess, authority = state.getOrNull(), userUnlocked = true,
            exitHistory = history, fingerprint = Build.FINGERPRINT, currentUser = Process.myUid() / 100_000,
            targets = targets, journal = journalStatus,
            budget = authorization, privilege = privilege,
            targetReadUnavailable = packageRead is StartupTargetReadResult.Unavailable,
        )
        return StartupProbeResult(leases, startupEligibility(inputs),
            confirmedInvalidTargetLeases(inputs, selectionRead.isSuccess))
    }

    internal fun historySince(masterEnabledAtMillis: Long): StartupExitHistoryStatus {
        val records = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, STARTUP_EXIT_HISTORY_LIMIT)
                .map { record ->
                    val own = record.packageUid == Process.myUid() &&
                        (record.processName == context.packageName || record.processName.startsWith(context.packageName + ":"))
                    val main = own && record.processName == context.packageName
                    val reason = when {
                        own && record.reason == ApplicationExitInfo.REASON_USER_REQUESTED -> StartupExitReason.USER_REQUESTED
                        own && record.reason == ApplicationExitInfo.REASON_USER_STOPPED -> StartupExitReason.USER_STOPPED
                        main && record.reason == ApplicationExitInfo.REASON_CRASH -> StartupExitReason.CRASH
                        main && record.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> StartupExitReason.CRASH_NATIVE
                        main && record.reason == ApplicationExitInfo.REASON_ANR -> StartupExitReason.ANR
                        main && record.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> StartupExitReason.INITIALIZATION_FAILURE
                        else -> StartupExitReason.OTHER
                    }
                    // Keep irrelevant entries as OTHER to retain the bounded-query saturation evidence.
                    StartupExitRecord(reason, record.timestamp)
                }
        }.getOrNull()
        return startupExitHistoryDecision(masterEnabledAtMillis, System.currentTimeMillis(), records)
    }

    companion object {
        fun privilegeEvidence(): RestartPrivilegeEvidence = runCatching {
            val alive = Shizuku.pingBinder()
            RestartPrivilegeEvidence(alive,
                if (alive) Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED else false,
                if (alive) Shizuku.getUid() else null, Process.myUid() / 100_000, true)
        }.getOrDefault(RestartPrivilegeEvidence())
    }
}

internal fun StartupEligibilityStatus.mayCreateWaitingSession(): Boolean = this in setOf(
    StartupEligibilityStatus.READY_TO_START, StartupEligibilityStatus.WAITING_BUDGET,
    StartupEligibilityStatus.WAITING_PRIVILEGE,
    StartupEligibilityStatus.WAITING_RECONCILIATION,
    StartupEligibilityStatus.WAITING_TARGET_READ,
)

internal fun StartupEligibilityStatus.description(): String = when (this) {
    StartupEligibilityStatus.OFF -> "自动保护未开启"
    StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT -> "请重新确认开启；旧授权不包含开机恢复"
    StartupEligibilityStatus.WAITING_UNLOCK -> "等待首次解锁，当前未执行保护"
    StartupEligibilityStatus.WAITING_PRIVILEGE -> "等待 Shizuku 启动并授权，当前未执行保护"
    StartupEligibilityStatus.STOPPED_BY_USER -> "系统记录到主动停止，请在应用内重新确认"
    StartupEligibilityStatus.BLOCKED_CRASH_LOOP -> "检测到重复崩溃，已暂停；请检查后重新确认"
    StartupEligibilityStatus.BLOCKED_IDENTITY -> "安装身份、应用选择或系统版本变化，请重新确认"
    StartupEligibilityStatus.BLOCKED_RECONCILIATION -> "恢复记录待确认或无法读取，请先只读复检"
    StartupEligibilityStatus.BLOCKED_BUDGET -> "当前持久授权无法确认，请检查后重新确认"
    StartupEligibilityStatus.BLOCKED_STORAGE -> "授权或停止记录无法确认，未启动保护"
    StartupEligibilityStatus.WAITING_BUDGET -> "授权已保留，等待重新核对恢复条件；消息接收尚未验证"
    StartupEligibilityStatus.WAITING_RECONCILIATION -> "授权已保留，上次恢复待只读复检；全部自动恢复写入已阻止"
    StartupEligibilityStatus.WAITING_TARGET_READ -> "应用身份读取暂时超时或繁忙；授权已保留，等待下次完整核验，当前未执行保护"
    StartupEligibilityStatus.READY_TO_START -> "授权及运行条件已核对，正在启动检测"
}
