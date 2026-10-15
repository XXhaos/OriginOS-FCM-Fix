package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.privileged.ShizukuStatus

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
    if (state.targetSelectionStoreCorrupt) add("应用记录损坏，请在应用页处理。")
    if (state.stoppedRecovery.needsReconcile) add("上次恢复待核对，请复检。")
    if (state.stoppedRecovery.storageBlocked) add("记录存储异常，恢复已暂停。")
    if (state.automaticProtection.revocationSaveFailed) add("关闭失败，请重试。")
    if (state.targetSelectionLoadState == HomeSourceLoadState.FAILED)
        add("部分记录读取失败，请重试。")
}

internal fun diagnosticSummary(state: HomeUiState): String = when {
    diagnosticIssues(state).isNotEmpty() -> "需要处理"
    !state.stoppedRecovery.loaded || state.targetSelectionLoadState == HomeSourceLoadState.LOADING ->
        "正在读取记录…"
    else -> "暂无需要处理的问题"
}

internal fun recentDiagnosticActions(state: HomeUiState): List<String> = buildList {
    state.automaticProtection.lastOutcome?.let { add("最近自动恢复：${recoveryOutcomeSummary(it)}") }
    state.stoppedRecovery.receiptOutcome?.let { add("上次恢复：${recoveryOutcomeSummary(it.name)}") }
}.take(3)

private fun recoveryOutcomeSummary(outcome: String): String = when (outcome) {
    "FLAG_CLEARED" -> "已确认解除停止限制"
    "NO_WRITE_NEEDED" -> "无需恢复"
    "STILL_STOPPED" -> "停止限制仍存在"
    "UNCONFIRMED" -> "结果待确认"
    else -> "未完成，请检查问题摘要"
}
