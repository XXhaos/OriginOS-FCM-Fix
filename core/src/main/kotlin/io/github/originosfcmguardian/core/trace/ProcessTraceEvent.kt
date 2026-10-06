package io.github.originosfcmguardian.core.trace

import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import io.github.originosfcmguardian.core.isUserZeroTargetAppUid

data class ProcessTraceTarget(val packageName: String, val uid: Int) {
    init {
        require(isSafeAndroidPackageName(packageName)) { "Invalid target package" }
        require(isUserZeroTargetAppUid(uid)) { "Invalid target UID" }
    }
}

enum class ProcessTraceKind { PROCESS_START, UID_ACTIVE, UID_IDLE, UID_RUNNING, UID_STOPPED }

data class ProcessTraceEvent(
    val timestampEpochMillis: Long,
    val target: ProcessTraceTarget,
    val kind: ProcessTraceKind,
    val pid: Int? = null,
) {
    init {
        require(timestampEpochMillis > 0) { "Invalid timestamp" }
        require(pid == null || pid > 0) { "Invalid PID" }
        require((kind == ProcessTraceKind.PROCESS_START) == (pid != null)) { "PID does not match event kind" }
    }
}
