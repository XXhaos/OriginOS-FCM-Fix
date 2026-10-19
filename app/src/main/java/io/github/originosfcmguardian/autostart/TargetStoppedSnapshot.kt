package io.github.originosfcmguardian.autostart

import kotlinx.coroutines.CancellationException

enum class TargetStoppedStatus { NOT_CHECKED, STOPPED, NOT_STOPPED, UNKNOWN, IDENTITY_CHANGED }
enum class TargetStoppedReadFailure { TIMED_OUT, BUSY, FAILED }

data class TargetStoppedSnapshot(
    val status: TargetStoppedStatus = TargetStoppedStatus.NOT_CHECKED,
    val checkedAtEpochMillis: Long? = null,
    val readFailure: TargetStoppedReadFailure? = null,
) {
    val detail: String get() = when (status) {
        TargetStoppedStatus.NOT_CHECKED -> "尚未读取应用停止标记；点击复检读取"
        TargetStoppedStatus.STOPPED -> "读取时设置了停止标记，可能限制后台接收。若希望恢复接收，可使用下方已支持的恢复入口；不支持时可自行从桌面打开应用，再返回复检。若是主动停止，请保持原选择。解除标记不保证推送恢复，也不要求常驻或定期打开。"
        TargetStoppedStatus.NOT_STOPPED -> "读取时未设置停止标记；不代表应用有后台进程或推送正常"
        TargetStoppedStatus.UNKNOWN -> "停止标记未知：应用不可见、读取失败或身份无法验证；不能视为未停止"
        TargetStoppedStatus.IDENTITY_CHANGED -> "应用身份已变化；停止标记结果已丢弃，请重新选择"
    }
}

internal data class TargetPackageSnapshot(
    val identity: SelectedTargetIdentity,
    val stopped: Boolean,
    val signing: TargetSigningEvidence? = null,
)

/** Both reads bind the flag to the full installation identity. This is not continuous observation. */
internal fun readTargetStoppedSnapshot(
    expected: SelectedTargetIdentity,
    read: () -> TargetPackageSnapshot?,
    now: () -> Long = System::currentTimeMillis,
): TargetStoppedSnapshot {
    fun unavailable() = TargetStoppedSnapshot(TargetStoppedStatus.UNKNOWN, now())
    return try {
        val first = read() ?: return unavailable()
        val second = read() ?: return unavailable()
        // Compare raw current identities before projecting either read to an older baseline.
        if (first.identity != second.identity || first.identityFor(expected) != expected ||
            second.identityFor(expected) != expected) {
            return TargetStoppedSnapshot(TargetStoppedStatus.IDENTITY_CHANGED, now())
        }
        TargetStoppedSnapshot(
            if (second.stopped) TargetStoppedStatus.STOPPED else TargetStoppedStatus.NOT_STOPPED,
            now(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable()
    }
}

/** Never attach a late result to a replacement installation or a removed selection. */
internal fun List<SelectedTargetUiState>.withStoppedSnapshot(
    expected: SelectedTargetIdentity,
    snapshot: TargetStoppedSnapshot,
): List<SelectedTargetUiState> = map { target ->
    if (target.identity == expected) target.copy(stoppedSnapshot = snapshot) else target
}
