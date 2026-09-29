package io.github.originosfcmguardian.privileged

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY_SHELL,
    READY_ROOT,
    ERROR,
}

data class ShizukuStatusSnapshot(
    val status: ShizukuStatus,
    val detail: String,
)

internal fun classifyShizukuStatus(
    managerInstalled: Boolean,
    binderAlive: Boolean,
    serverUid: Int?,
    permissionGranted: Boolean,
): ShizukuStatus = when {
    !binderAlive && !managerInstalled -> ShizukuStatus.NOT_INSTALLED
    !binderAlive -> ShizukuStatus.NOT_RUNNING
    serverUid == 0 -> ShizukuStatus.READY_ROOT
    serverUid != 2_000 -> ShizukuStatus.ERROR
    !permissionGranted -> ShizukuStatus.PERMISSION_REQUIRED
    else -> ShizukuStatus.READY_SHELL
}
