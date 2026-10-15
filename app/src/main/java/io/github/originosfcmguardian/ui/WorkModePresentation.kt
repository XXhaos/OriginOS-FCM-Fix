package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.privileged.ShizukuUserAction
import io.github.originosfcmguardian.privileged.shizukuUserAction

internal const val CURRENT_RECOVERY_BACKEND_LABEL = "Shizuku"
internal const val SHIZUKU_REBOOT_NOTICE = "重启手机后，请重新启动 Shizuku。"

internal data class ShizukuWorkModePresentation(val statusLabel: String, val summary: String,
    val actionLabel: String?, val showOpenManager: Boolean)

internal fun shizukuWorkModePresentation(snapshot: ShizukuStatusSnapshot): ShizukuWorkModePresentation {
    val action = shizukuUserAction(snapshot)
    val status = snapshot.status
    return ShizukuWorkModePresentation(
        statusLabel = when (status) {
            ShizukuStatus.NOT_INSTALLED -> "未安装"
            ShizukuStatus.NOT_RUNNING -> "未启动"
            ShizukuStatus.PERMISSION_REQUIRED -> "待授权"
            ShizukuStatus.READY_SHELL -> "已就绪"
            ShizukuStatus.READY_ROOT -> "启动方式不支持"
            ShizukuStatus.ERROR -> "状态异常"
        },
        summary = when (status) {
            ShizukuStatus.NOT_INSTALLED -> "安装并启动 Shizuku，再授权 Guardian。"
            ShizukuStatus.NOT_RUNNING -> "启动 Shizuku 后，返回授权 Guardian。"
            ShizukuStatus.PERMISSION_REQUIRED -> if (action == ShizukuUserAction.OPEN_MANAGER)
                "请在 Shizuku 中允许 Guardian。" else "请授权 Guardian 使用 Shizuku。"
            ShizukuStatus.READY_SHELL -> ""
            ShizukuStatus.READY_ROOT -> "请在 Shizuku 中改用非 Root 启动。"
            ShizukuStatus.ERROR -> "请刷新状态或打开 Shizuku 检查。"
        },
        actionLabel = if (status == ShizukuStatus.READY_SHELL) null else when (action) {
            ShizukuUserAction.DOWNLOAD -> "获取 Shizuku"
            ShizukuUserAction.OPEN_MANAGER -> "打开 Shizuku"
            ShizukuUserAction.REQUEST_PERMISSION -> "Shizuku 授权"
            ShizukuUserAction.REFRESH -> null
        },
        showOpenManager = action == ShizukuUserAction.REQUEST_PERMISSION || status == ShizukuStatus.READY_SHELL,
    )
}
