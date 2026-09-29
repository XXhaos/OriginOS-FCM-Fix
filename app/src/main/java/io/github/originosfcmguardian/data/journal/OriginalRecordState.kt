package io.github.originosfcmguardian.data.journal

enum class OriginalRecordStatus {
    LOADING,
    NOT_SAVED,
    SAVED,
    INCOMPLETE,
    DAMAGED,
}

data class OriginalRecordState(
    val status: OriginalRecordStatus = OriginalRecordStatus.LOADING,
    val capturedAtEpochMillis: Long? = null,
    val matchesCurrentBuild: Boolean? = null,
    val latestCaptureIncomplete: Boolean = false,
    val detail: String = "正在检查已保存的原始记录",
)

internal fun applyInitialRecordIfStillLoading(
    current: OriginalRecordState,
    loaded: OriginalRecordState,
): OriginalRecordState = if (current.status == OriginalRecordStatus.LOADING) loaded else current
