package io.github.originosfcmguardian.update

internal enum class UpdateInstallLaunch { NONE, SOURCE_PERMISSION, INSTALLER }
internal data class UpdateInstallDecision(val phase: AppUpdatePhase, val launch: UpdateInstallLaunch,
    val message: String? = null)

/** Returning from Settings is not consent: check its current result without reopening on denial. */
internal fun updateInstallDecision(apkValid: Boolean, permissionGranted: Boolean,
    allowPermissionRequest: Boolean): UpdateInstallDecision = when {
    !apkValid -> UpdateInstallDecision(AppUpdatePhase.AVAILABLE, UpdateInstallLaunch.NONE,
        "更新校验失败，请重新下载")
    permissionGranted -> UpdateInstallDecision(AppUpdatePhase.INSTALLING, UpdateInstallLaunch.INSTALLER)
    allowPermissionRequest -> UpdateInstallDecision(AppUpdatePhase.READY, UpdateInstallLaunch.SOURCE_PERMISSION,
        "请允许安装更新")
    else -> UpdateInstallDecision(AppUpdatePhase.READY, UpdateInstallLaunch.NONE, "请允许安装更新")
}

/** Cancellation can be retried. A delayed installer result cannot reset a newer check/download. */
internal fun updateInstallerReturned(state: AppUpdateUiState): AppUpdateUiState =
    if (state.phase == AppUpdatePhase.INSTALLING) state.copy(phase = AppUpdatePhase.READY,
        message = "若未安装，可重试", launch = null) else state
