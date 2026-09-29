package io.github.originosfcmguardian.protection

import android.content.Context
import android.os.Build
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetIdentityStatus
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.autostart.identityStatus
import io.github.originosfcmguardian.core.AutostartReadStatus
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.GuardianAutostartRead
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.ReadOnlyDetector
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.TargetAutostartParser
import io.github.originosfcmguardian.device.NormalDeviceProbe
import io.github.originosfcmguardian.privileged.ShizukuRuntime

internal class ShizukuProtectionReadPort(context: Context) : ProtectionReadPort {
    private val runtime = ShizukuRuntime(context)

    init {
        runtime.start()
    }

    override suspend fun detectCore(): DetectionReport = ReadOnlyDetector(runtime.executor).detect()

    override suspend fun readTarget(identity: SelectedTargetIdentity): GuardianAutostartRead {
        val result = runtime.executor.exec(SafeCommand.GetTargetAutostartRow(identity.packageName))
        return TargetAutostartParser.parse(result, identity.packageName, identity.uid)
    }

    override fun close() = runtime.close()
}

class DailyProtectionChecker(private val context: Context) {
    private val store = DailyProtectionStore(context)
    private val targetStore = TargetSelectionStore(context)
    private val targetProbe = TargetPackageProbe(context)

    suspend fun check(reason: ProtectionCheckReason): DailyProtectionReport {
        val now = System.currentTimeMillis()
        val fingerprint = Build.FINGERPRINT.take(512)
        val gms = NormalDeviceProbe(context).readGmsPackage()
        if (!isVerifiedProtectionBuild(fingerprint)) {
            return DailyProtectionReport(
                checkedAtEpochMillis = now,
                reason = reason,
                fingerprint = fingerprint,
                status = ProtectionCheckStatus.BUILD_CHANGED,
                summary = "构建语义未验证；GMS=${gms.summary.take(160)}；未执行特权读取",
            ).also(store::save)
        }

        val selected = targetStore.load().take(DailyProtectionReport.MAX_TARGETS)
        return ReadSessionCoordinator.withSession {
            val port = ShizukuProtectionReadPort(context)
            runProtectionSession(port) {
                executeCheck(now, reason, fingerprint, gms.summary, selected, targetProbe::identity, port)
                    .also(store::save)
            }
        }
    }
}

internal fun isVerifiedProtectionBuild(fingerprint: String): Boolean =
    fingerprint == VERIFIED_AUTOSTART_FINGERPRINT

internal suspend fun <T> runProtectionSession(
    port: ProtectionReadPort,
    block: suspend () -> T,
): T = try {
    block()
} finally {
    port.close()
}

internal suspend fun executeCheck(
    now: Long,
    reason: ProtectionCheckReason,
    fingerprint: String,
    normalGmsSummary: String,
    selected: List<SelectedTargetIdentity>,
    identityProbe: (String) -> SelectedTargetIdentity?,
    port: ProtectionReadPort,
): DailyProtectionReport {
    val core = port.detectCore()
    val targetResults = selected.take(DailyProtectionReport.MAX_TARGETS).map { stored ->
        when (identityStatus(stored, identityProbe(stored.packageName))) {
            TargetIdentityStatus.MISSING -> TargetCheckResult(stored.packageName, TargetCheckStatus.MISSING)
            TargetIdentityStatus.CHANGED -> TargetCheckResult(stored.packageName, TargetCheckStatus.IDENTITY_CHANGED)
            TargetIdentityStatus.MATCH -> {
                if (core.privilegedAccess != PrivilegedAccessState.READY_SHELL) {
                    TargetCheckResult(stored.packageName, TargetCheckStatus.UNAVAILABLE)
                } else {
                    val read = port.readTarget(stored)
                    val postStatus = identityStatus(stored, identityProbe(stored.packageName))
                    if (postStatus != TargetIdentityStatus.MATCH) {
                        TargetCheckResult(stored.packageName, TargetCheckStatus.IDENTITY_CHANGED)
                    } else {
                        TargetCheckResult(stored.packageName, read.toTargetStatus())
                    }
                }
            }
        }
    }
    val status = classify(core, targetResults, fingerprint)
    val summary = userSummary(core, normalGmsSummary, targetResults, status)
    return DailyProtectionReport(now, reason, fingerprint, status, summary, targetResults)
}

private fun userSummary(
    core: DetectionReport,
    normalGmsSummary: String,
    targets: List<TargetCheckResult>,
    status: ProtectionCheckStatus,
): String = buildList {
    add("Google Play 服务：${normalGmsSummary.take(160)}")
    add(
        when {
            core.privilegedAccess != PrivilegedAccessState.READY_SHELL -> "Shizuku 只读检测不可用"
            core.appOps.isNotEmpty() && core.appOps.values.all { it.status == EvidenceStatus.CONFORMING } ->
                "已检查的后台权限符合预期"
            core.appOps.values.any { it.status == EvidenceStatus.ANOMALOUS } -> "后台权限需要处理"
            else -> "后台权限状态未知"
        },
    )
    add(
        when (core.doze.status) {
            EvidenceStatus.CONFORMING -> "待机白名单符合预期"
            EvidenceStatus.ANOMALOUS -> "待机白名单需要处理"
            EvidenceStatus.UNKNOWN -> "待机白名单状态未知"
        },
    )
    add("vivo 后台策略语义未知（只读）")
    if (targets.isNotEmpty()) {
        val actionCount = targets.count {
            it.status in setOf(
                TargetCheckStatus.NEEDS_USER_ACTION,
                TargetCheckStatus.IDENTITY_CHANGED,
                TargetCheckStatus.MISSING,
            )
        }
        val unknownCount = targets.count {
            it.status in setOf(TargetCheckStatus.UNKNOWN, TargetCheckStatus.UNAVAILABLE)
        }
        add("目标应用：${targets.size} 项已检查，${actionCount} 项需手动处理，${unknownCount} 项未知")
    }
    if (status == ProtectionCheckStatus.HEALTHY_READ_ONLY) {
        add("已检查的关键设置符合预期；这不代表 FCM 已连接或消息已送达")
    }
}.joinToString("；").take(DailyProtectionReport.MAX_SUMMARY_CHARS)

private fun GuardianAutostartRead.toTargetStatus(): TargetCheckStatus = when {
    status != AutostartReadStatus.READ_ONLY_VALUE_PRESENT -> TargetCheckStatus.UNKNOWN
    currentState == 0 -> TargetCheckStatus.ALLOWED
    currentState == 1 -> TargetCheckStatus.NEEDS_USER_ACTION
    else -> TargetCheckStatus.UNKNOWN
}

private fun classify(
    core: DetectionReport,
    targets: List<TargetCheckResult>,
    expectedFingerprint: String,
): ProtectionCheckStatus {
    if (core.privilegedAccess != PrivilegedAccessState.READY_SHELL ||
        core.currentUser != 0 || core.buildFingerprint != expectedFingerprint ||
        core.gms.status == EvidenceStatus.UNKNOWN ||
        core.appOps.keys != io.github.originosfcmguardian.core.AppOp.entries.toSet() ||
        core.appOps.values.any { it.status == EvidenceStatus.UNKNOWN } ||
        core.doze.status == EvidenceStatus.UNKNOWN ||
        targets.any { it.status in setOf(TargetCheckStatus.UNKNOWN, TargetCheckStatus.UNAVAILABLE) }
    ) return ProtectionCheckStatus.UNKNOWN
    if (core.gms.status == EvidenceStatus.ANOMALOUS ||
        core.appOps.values.any { it.status == EvidenceStatus.ANOMALOUS } ||
        core.doze.status == EvidenceStatus.ANOMALOUS ||
        targets.any {
            it.status in setOf(
                TargetCheckStatus.NEEDS_USER_ACTION,
                TargetCheckStatus.IDENTITY_CHANGED,
                TargetCheckStatus.MISSING,
            )
        }
    ) return ProtectionCheckStatus.NEEDS_USER_ACTION
    return ProtectionCheckStatus.HEALTHY_READ_ONLY
}
