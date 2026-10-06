package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.privileged.ShizukuStatus
import io.github.originosfcmguardian.privileged.ShizukuStatusSnapshot
import io.github.originosfcmguardian.privileged.ShizukuUserAction
import io.github.originosfcmguardian.privileged.shizukuUserAction

internal const val CURRENT_RECOVERY_BACKEND_LABEL = "工作方式：外置 Shizuku"
internal const val SHIZUKU_REBOOT_NOTICE = "手机重启后，需要先重新启动外部 Shizuku；Guardian 会等待服务就绪。"

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
            ShizukuStatus.READY_SHELL -> "已授权 · shell 就绪"
            ShizukuStatus.READY_ROOT -> "Root 模式（不支持）"
            ShizukuStatus.ERROR -> "状态异常"
        },
        summary = when (status) {
            ShizukuStatus.NOT_INSTALLED -> "先安装外部 Shizuku，再启动并授权 Guardian。"
            ShizukuStatus.NOT_RUNNING -> "请先在 Shizuku 中启动服务，再返回授权。"
            ShizukuStatus.PERMISSION_REQUIRED -> snapshot.detail
            ShizukuStatus.READY_SHELL -> "服务已连接且 Guardian 已获授权。保护仍需单独开启。"
            ShizukuStatus.READY_ROOT -> "仅支持 ADB/shell 模式，不使用 Root。请在 Shizuku 中改用非 Root 启动。"
            ShizukuStatus.ERROR -> "服务状态异常，请刷新或打开 Shizuku 检查。"
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
