package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.TargetRecoveryCounts

data class VisibleProtectionApp(
    val packageName: String,
    val label: String,
    val identity: SelectedTargetIdentity? = null,
    val supported: Boolean = false,
    /** Process-local read only; never a saved selection or protection authorization. */
    val stoppedSnapshot: TargetStoppedSnapshot = TargetStoppedSnapshot(),
)

internal data class ProtectionAppRow(val app: VisibleProtectionApp, val currentInstallation: Boolean)
internal data class ProtectionAppGroups(val protected: List<ProtectionAppRow>, val all: List<ProtectionAppRow>)

/** An unavailable record is not evidence that no applications were configured. */
internal fun protectedAppsEmptyLabel(configurationAvailable: Boolean, query: String): String = when {
    !configurationAvailable -> "应用选择记录暂不可用"
    query.isBlank() -> "尚未选择保护应用"
    else -> "没有匹配的已选择应用"
}

/** A compact count belongs only to the currently installed, explicitly authorized target. */
internal fun protectionAppRecoveryCounts(state: HomeUiState, row: ProtectionAppRow): TargetRecoveryCounts? {
    val identity = row.app.identity ?: return null
    if (!row.currentInstallation || !state.recoveryCounts.available ||
        !targetAutomaticProtectionState(state.automaticProtection, identity).savedAuthorization) return null
    return state.recoveryCounts.byIdentity[identity]
}

/** A prior installation or historical receipt never promotes the current installation. */
internal fun protectionAppGroups(state: HomeUiState, query: String): ProtectionAppGroups {
    val current = state.visibleApps.filterNot { it.packageName in state.hiddenPreinstalledPackages }
        .map { ProtectionAppRow(it, true) }
    val oldIdentities = (state.selectedTargets.map { it.identity } + state.automaticProtection.configuredIdentities).distinct()
        .filter { identity -> identity.packageName !in state.hiddenPreinstalledPackages &&
            current.none { it.app.identity == identity } }
    val unavailable = oldIdentities.map { identity ->
        val selected = state.selectedTargets.firstOrNull { it.identity == identity }
        ProtectionAppRow(VisibleProtectionApp(identity.packageName, selected?.label ?: identity.packageName, identity), false)
    }
    val search = query.trim()
    val rows = (current + unavailable).filter {
        it.app.label.contains(search, true) || it.app.packageName.contains(search, true)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.app.label })
    return ProtectionAppGroups(
        rows.filter { it.app.identity in state.automaticProtection.configuredIdentities },
        rows.filterNot { it.app.identity in state.automaticProtection.configuredIdentities },
    )
}

internal fun canPrepareAppProtection(state: HomeUiState, app: VisibleProtectionApp): Boolean =
    state.visibleAppsLoadState == HomeSourceLoadState.LOADED &&
        state.automaticPreparationPackage == null && state.automaticConfirmationIdentity == null &&
        appProtectionPrerequisites(state, app)

internal fun appProtectionPrerequisites(state: HomeUiState, app: VisibleProtectionApp): Boolean =
    app.supported && app.identity != null && isRecoveryTargetPackage(app.packageName) &&
        !state.automaticProtection.busy && !state.automaticProtection.waitingForPrivilege &&
        !state.automaticProtection.revocationSaveFailed && state.recoveryFirmwareSupported &&
        state.shizuku.status == ShizukuStatus.READY_SHELL &&
        state.targetSelectionLoadState == HomeSourceLoadState.LOADED && !state.targetSelectionStoreCorrupt &&
        state.stoppedRecovery.loaded && !state.stoppedRecovery.storageBlocked &&
        !state.stoppedRecovery.needsReconcile && !state.stoppedRecovery.busy && !state.detecting &&
        app.identity !in state.targetReadInFlight &&
        state.automaticProtection.configuredIdentities.none { it.packageName == app.packageName }

internal fun protectionAppStatus(state: HomeUiState, row: ProtectionAppRow): String {
    val identity = row.app.identity
    val protection = identity?.let { targetAutomaticProtectionState(state.automaticProtection, it) }
    val configured = identity in state.automaticProtection.configuredIdentities
    val binding = state.automaticProtection.targets.firstOrNull { it.identity == identity }
    if (!row.currentInstallation) return if (configured)
        "应用已变化，请重新选择" else "应用已卸载或更新，请刷新"
    if (identity == null) return "无法读取应用，请刷新"
    if (state.automaticPreparationPackage == row.app.packageName) return "正在核对应用…"
    if (binding?.needsReconfirmation == true) return "请重新选择此应用"
    if (configured && state.selectedTargets.any { it.identity == identity && it.status == TargetAutostartStatus.IDENTITY_CHANGED })
        return "应用已变化，请重新选择"
    if (configured && !state.automaticProtection.masterEnabled) return "已选择 · 保护未开启"
    if (protection?.busy == true) return "启动或恢复中…"
    if (protection?.waitingForReconciliation == true) return "等待复检"
    if (protection?.waitingForPrivilege == true) return "等待 Shizuku"
    if (protection?.enabled == true) return "保护已开启"
    if (protection?.savedAuthorization == true) return "等待恢复"
    if (state.stoppedRecovery.recordIdentity == identity) when (state.stoppedRecovery.receiptOutcome) {
        StoppedRecoveryOutcome.STILL_STOPPED -> return "上次恢复未完成，请复检"
        StoppedRecoveryOutcome.UNCONFIRMED -> return "上次恢复待确认，请复检"
        else -> Unit
    }
    val selected = state.selectedTargets.firstOrNull { it.identity == identity }
    if (selected?.status == TargetAutostartStatus.IDENTITY_CHANGED) return "应用已变化，请刷新"
    if (!row.app.supported) return "此应用不支持自动保护"
    if (state.automaticProtection.configuredIdentities.any { it.packageName == row.app.packageName })
        return "请重新选择此应用"
    if (state.shizuku.status != ShizukuStatus.READY_SHELL) return "需连接 Shizuku 后开启"
    return "保护未开启"
}

/** Token spans asynchronous enrollment and confirmation. Cancel/background invalidates both phases. */
internal class AppProtectionRequestGate {
    private var generation = 0L
    private var active: Long? = null
    fun begin(): Long? {
        if (active != null) return null
        return (++generation).also { active = it }
    }
    fun current(token: Long): Boolean = active == token
    fun cancel() { generation++; active = null }
}

/** Request phase reads only. Cancellation cannot leave a newly selected application behind. */
internal suspend fun probeAppProtectionConfirmation(
    expected: SelectedTargetIdentity,
    identity: suspend () -> SelectedTargetIdentity?,
    qualified: suspend (SelectedTargetIdentity) -> Boolean,
    current: () -> Boolean,
): SelectedTargetIdentity? {
    if (!current() || identity() != expected || !current() || !qualified(expected)) return null
    return expected.takeIf { current() }
}

/** Called only after explicit confirmation. The captured identity must survive every await. */
internal suspend fun prepareAppProtectionIdentity(
    packageName: String,
    expected: SelectedTargetIdentity,
    identity: suspend () -> SelectedTargetIdentity?,
    qualified: suspend (SelectedTargetIdentity) -> Boolean,
    selected: suspend () -> List<SelectedTargetIdentity>,
    authorized: () -> List<SelectedTargetIdentity>,
    enroll: suspend (SelectedTargetIdentity) -> Boolean,
    current: () -> Boolean,
): SelectedTargetIdentity? {
    if (expected.packageName != packageName || probeAppProtectionConfirmation(expected, identity, qualified, current) == null) return null
    if (!current() || authorized().any { it.packageName == packageName }) return null
    val saved = selected()
    if (!current()) return null
    if (expected !in saved && !enroll(expected)) return null
    if (!current() || identity() != expected || !qualified(expected) || expected !in selected()) return null
    return expected.takeIf { current() && authorized().none { it.packageName == packageName } }
}
