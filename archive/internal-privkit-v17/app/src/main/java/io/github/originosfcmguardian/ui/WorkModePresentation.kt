package io.github.originosfcmguardian.ui

internal const val INTERNAL_CONNECTION_TITLE = "内置服务（试用）"
internal const val CURRENT_RECOVERY_BACKEND_LABEL = "当前保护方式：外部 Shizuku"
internal const val INTERNAL_PROTECTION_NOTICE = "当前自动保护仍使用外部 Shizuku"

internal data class InternalConnectionPresentation(
    val headline: String,
    val protectionNotice: String = INTERNAL_PROTECTION_NOTICE,
    val canPair: Boolean,
    val canStart: Boolean,
)

/** Presentation only: internal readiness never selects or authorizes a protection backend. */
internal fun internalConnectionPresentation(
    busy: Boolean,
    ready: Boolean,
    needsManualCleanup: Boolean,
    pairingActive: Boolean = false,
): InternalConnectionPresentation {
    val available = !busy && !needsManualCleanup && !pairingActive
    return InternalConnectionPresentation(
        headline = when {
            needsManualCleanup -> "需要手动清理"
            busy || pairingActive -> "正在处理连接"
            ready -> "内置身份已核验"
            else -> "等待配对或启动"
        },
        canPair = available,
        canStart = available,
    )
}
