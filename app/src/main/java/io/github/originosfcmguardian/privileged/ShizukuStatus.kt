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
    val authorizationBlocked: Boolean = false,
)

internal enum class ShizukuUserAction { DOWNLOAD, OPEN_MANAGER, REQUEST_PERMISSION, REFRESH }

/** Only an explicit user action consumes this decision; it never enables protection consent. */
internal fun shizukuUserAction(snapshot: ShizukuStatusSnapshot): ShizukuUserAction = when (snapshot.status) {
    ShizukuStatus.NOT_INSTALLED -> ShizukuUserAction.DOWNLOAD
    ShizukuStatus.NOT_RUNNING, ShizukuStatus.READY_ROOT, ShizukuStatus.ERROR -> ShizukuUserAction.OPEN_MANAGER
    ShizukuStatus.PERMISSION_REQUIRED -> if (snapshot.authorizationBlocked) ShizukuUserAction.OPEN_MANAGER else ShizukuUserAction.REQUEST_PERMISSION
    ShizukuStatus.READY_SHELL -> ShizukuUserAction.REFRESH
}

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
