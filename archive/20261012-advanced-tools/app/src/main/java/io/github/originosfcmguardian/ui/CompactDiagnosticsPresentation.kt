package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.privileged.ShizukuStatus

internal enum class DiagnosticTool(val label: String) {
    DAILY("定期巡检"), NOTIFICATIONS("通知记录"),
    MONITOR("深度监控"), TIMELINE("监控时间线"), SELF_TEST("Guardian 推送自测"),
}

internal fun gmsConnectionSummary(state: GmsConnectionUiState, nowElapsed: Long): String = when {
    state.busy -> "单次只读观察中…"
    state.observation is GmsConnectionObservation.Sample -> if (state.observation.gcmService.userId != null)
        gmsServiceHeadline(state.observation, nowElapsed, state.networkChanged || !state.networkTrackingAvailable)
        else gmsSampleHeadline(state.observation, nowElapsed, state.networkChanged || !state.networkTrackingAvailable)
    state.observation is GmsConnectionObservation.Unknown -> "来源不可用；当前连接未知"
    else -> "尚未取样；当前连接未知"
}

/** Fixed issue categories only; no raw fields, tokens or private event payloads in the overview. */
internal fun diagnosticIssues(state: HomeUiState): List<String> = buildList {
    if (state.shizuku.status != ShizukuStatus.READY_SHELL) add("Shizuku 未就绪，请检查工作模式。")
    if (state.targetSelectionStoreCorrupt) add("关注应用记录损坏，请在应用页处理。")
    if (state.stoppedRecovery.needsReconcile) add("上次恢复结果未确认，需要只读核对。")
    if (state.stoppedRecovery.storageBlocked) add("恢复记录存储不可用，已阻止新的恢复。")
    if (state.automaticProtection.revocationSaveFailed) add("关闭保护未可靠保存，请重试关闭。")
    if (state.dailyProtectionReport?.status == io.github.originosfcmguardian.protection.ProtectionCheckStatus.BUILD_CHANGED) add("系统版本已变化或记录来自其他版本，需要重新只读检查。")
    if (state.notificationFacts.stopPersistenceUncertain) add("通知记录停止未可靠保存，请重试停止或撤销系统通知使用权。")
    if (state.notificationFacts.historyCorrupt || state.notificationFacts.droppedEvents > 0) add("通知记录损坏或不完整，请查看通知记录。")
    if (state.notificationFacts.enabled && (!state.notificationFacts.accessGranted || !state.notificationFacts.connected)) add("通知记录已开启，但监听尚未就绪。")
    if (state.deepMonitoring.historyCorrupt) add("监控记录损坏，请查看深度监控。")
    if (state.fcm.evidenceCorrupt) add("Guardian 推送自测证据损坏，已停止覆盖。")
    if (state.dailyProtectionReport?.containsRetiredAutostartEvidence == false &&
        state.dailyProtectionReport.status == io.github.originosfcmguardian.protection.ProtectionCheckStatus.NEEDS_USER_ACTION)
        add("最近巡检有待处理项，请查看定期巡检。")
    if (listOf(state.targetSelectionLoadState, state.dailyProtectionLoadState,
            state.notificationFactsLoadState, state.deepMonitoringLoadState, state.fcmEvidenceLoadState).any { it == HomeSourceLoadState.FAILED })
        add("部分诊断记录读取失败；旧结果不能代表本次读取成功。")
}

internal fun diagnosticSummary(state: HomeUiState): String = when {
    diagnosticIssues(state).isNotEmpty() -> "有需要处理或核对的问题"
    !state.stoppedRecovery.loaded || listOf(state.targetSelectionLoadState, state.dailyProtectionLoadState,
            state.notificationFactsLoadState, state.deepMonitoringLoadState, state.fcmEvidenceLoadState).any { it == HomeSourceLoadState.LOADING } ->
        "部分诊断记录尚未载入，暂不能判断"
    else -> "暂无需要处理的问题"
}

internal fun recentDiagnosticActions(state: HomeUiState): List<String> = buildList {
    state.automaticProtection.lastOutcome?.let { add("最近自动恢复：${recoveryOutcomeSummary(it)}") }
    state.stoppedRecovery.receiptOutcome?.let { add("最近恢复记录：${recoveryOutcomeSummary(it.name)}（历史，不代表当前状态）") }
    state.dailyProtectionReport?.let { add("最近只读巡检：${if (it.containsRetiredAutostartEvidence) "旧范围记录，不代表当前状态" else when (it.status) {
        io.github.originosfcmguardian.protection.ProtectionCheckStatus.HEALTHY_READ_ONLY -> "安装身份未变化，不代表已保护"
        io.github.originosfcmguardian.protection.ProtectionCheckStatus.NEEDS_USER_ACTION -> "有待处理项"
        io.github.originosfcmguardian.protection.ProtectionCheckStatus.BUILD_CHANGED -> "系统版本变化，需重新检查"
        io.github.originosfcmguardian.protection.ProtectionCheckStatus.UNKNOWN -> "结果未知"
    }}") }
}.take(3)

private fun recoveryOutcomeSummary(outcome: String): String = when (outcome) {
    "FLAG_CLEARED" -> "已确认解除停止限制"
    "NO_WRITE_NEEDED" -> "无需恢复"
    "STILL_STOPPED" -> "停止限制仍存在"
    "UNCONFIRMED" -> "结果待确认"
    else -> "未完成，请检查问题摘要"
}
