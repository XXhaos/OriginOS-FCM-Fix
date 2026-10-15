package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.SelectedTargetUiState
import io.github.originosfcmguardian.autostart.TargetAutostartStatus
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import io.github.originosfcmguardian.autostart.isRecoveryTargetPackage
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage

enum class StoppedRecoveryPhase { IDLE, RECOVERING, SUCCESS, FAILED, UNCONFIRMED }

internal enum class RecoveryActionPresentation { HIDDEN, OFFER, RECOVERING, SUCCESS }
internal enum class TargetStatusTone { SUCCESS, FAILURE, UNCERTAIN, NEUTRAL }
internal data class TargetStatusPresentation(val text: String, val tone: TargetStatusTone)

/** Presentation only: eligibility never replaces the service's fresh write guards. */
data class StoppedRecoveryUiState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val storageBlocked: Boolean = false,
    val needsReconcile: Boolean = false,
    val recordIdentity: SelectedTargetIdentity? = null,
    val recordedAtMillis: Long? = null,
    val message: String? = null,
    val confirmationIdentity: SelectedTargetIdentity? = null,
    val phase: StoppedRecoveryPhase = StoppedRecoveryPhase.IDLE,
    val activeIdentity: SelectedTargetIdentity? = null,
    val receiptOutcome: StoppedRecoveryOutcome? = null,
    val showSuccessButton: Boolean = false,
    val recordSource: StoppedRecoverySource? = null,
    val recordStage: StoppedRecoveryStage? = null,
    /** Journal outcome only; receiptOutcome may describe a different, latest UI operation. */
    val recordOutcome: StoppedRecoveryOutcome? = null,
)

internal fun stoppedRecoveryActionPresentation(
    target: SelectedTargetUiState,
    recovery: StoppedRecoveryUiState,
    eligible: Boolean,
): RecoveryActionPresentation = when {
    recovery.phase == StoppedRecoveryPhase.RECOVERING && recovery.activeIdentity == target.identity ->
        RecoveryActionPresentation.RECOVERING
    recovery.showSuccessButton && recovery.phase == StoppedRecoveryPhase.SUCCESS &&
        recovery.recordStage != StoppedRecoveryStage.RECONCILED &&
        !recovery.needsReconcile &&
        recovery.recordIdentity == target.identity &&
        recovery.receiptOutcome == StoppedRecoveryOutcome.FLAG_CLEARED && target.hasKnownClearedFlag() ->
        RecoveryActionPresentation.SUCCESS
    eligible -> RecoveryActionPresentation.OFFER
    else -> RecoveryActionPresentation.HIDDEN
}

internal fun targetWakePresentation(target: SelectedTargetUiState, checking: Boolean = false): TargetStatusPresentation = when {
    checking -> TargetStatusPresentation("正在复检…", TargetStatusTone.NEUTRAL)
    target.status == TargetAutostartStatus.IDENTITY_CHANGED ->
        TargetStatusPresentation("应用已变化，请重新选择", TargetStatusTone.UNCERTAIN)
    else -> when (target.stoppedSnapshot.status) {
        TargetStoppedStatus.NOT_STOPPED -> TargetStatusPresentation("允许后台唤醒", TargetStatusTone.SUCCESS)
        TargetStoppedStatus.STOPPED -> TargetStatusPresentation("后台唤醒受限", TargetStatusTone.FAILURE)
        TargetStoppedStatus.IDENTITY_CHANGED -> TargetStatusPresentation("应用已变化，请重新选择", TargetStatusTone.UNCERTAIN)
        TargetStoppedStatus.NOT_CHECKED -> TargetStatusPresentation("尚未检查", TargetStatusTone.NEUTRAL)
        TargetStoppedStatus.UNKNOWN -> TargetStatusPresentation("状态未知，请复检", TargetStatusTone.UNCERTAIN)
    }
}

internal fun stoppedRecoveryReceiptPresentation(
    recovery: StoppedRecoveryUiState,
    target: SelectedTargetUiState?,
): TargetStatusPresentation? {
    if (recovery.phase == StoppedRecoveryPhase.RECOVERING) return null
    val sameIdentity = target != null && target.identity == recovery.recordIdentity &&
        target.status != TargetAutostartStatus.IDENTITY_CHANGED &&
        target.stoppedSnapshot.status != TargetStoppedStatus.IDENTITY_CHANGED
    return when {
        recovery.recordStage == StoppedRecoveryStage.RECONCILED && !recovery.needsReconcile && !recovery.storageBlocked -> {
            val observation = when (recovery.receiptOutcome) {
                StoppedRecoveryOutcome.STILL_STOPPED -> "上次复检：仍受限，恢复结果待确认"
                StoppedRecoveryOutcome.FLAG_CLEARED -> "上次复检：允许后台唤醒，恢复结果待确认"
                else -> "上次复检：恢复结果待确认"
            }
            TargetStatusPresentation(
                observation,
                TargetStatusTone.NEUTRAL,
            )
        }
        recovery.phase == StoppedRecoveryPhase.IDLE && !recovery.needsReconcile && !recovery.storageBlocked &&
            recovery.receiptOutcome != null && recovery.receiptOutcome != StoppedRecoveryOutcome.UNCONFIRMED -> {
            val history = when (recovery.receiptOutcome) {
                StoppedRecoveryOutcome.FLAG_CLEARED -> "上次恢复：已解除限制"
                StoppedRecoveryOutcome.STILL_STOPPED -> "上次恢复：仍受限"
                StoppedRecoveryOutcome.NO_WRITE_NEEDED -> "上次核对：无需恢复"
                else -> "上次恢复：未完成"
            }
            TargetStatusPresentation(
                history,
                TargetStatusTone.NEUTRAL,
            )
        }
        sameIdentity && recovery.phase == StoppedRecoveryPhase.SUCCESS && !recovery.needsReconcile &&
            target.hasKnownClearedFlag() &&
            recovery.receiptOutcome == StoppedRecoveryOutcome.FLAG_CLEARED ->
            TargetStatusPresentation("操作成功：已解除停止限制", TargetStatusTone.SUCCESS)
        sameIdentity && recovery.phase == StoppedRecoveryPhase.SUCCESS && !recovery.needsReconcile &&
            target.hasKnownClearedFlag() &&
            recovery.receiptOutcome == StoppedRecoveryOutcome.NO_WRITE_NEEDED ->
            TargetStatusPresentation("无需恢复：当前未被停止", TargetStatusTone.SUCCESS)
        sameIdentity && target.stoppedSnapshot.status == TargetStoppedStatus.STOPPED &&
            recovery.phase == StoppedRecoveryPhase.FAILED ->
            TargetStatusPresentation("恢复失败：停止限制仍存在", TargetStatusTone.FAILURE)
        recovery.receiptOutcome != null || recovery.needsReconcile || recovery.storageBlocked ||
            recovery.phase == StoppedRecoveryPhase.UNCONFIRMED ->
            TargetStatusPresentation("结果未确认：请复检或核对上次结果", TargetStatusTone.UNCERTAIN)
        else -> null
    }
}

private fun SelectedTargetUiState.hasKnownClearedFlag(): Boolean =
    status != TargetAutostartStatus.IDENTITY_CHANGED && stoppedSnapshot.status == TargetStoppedStatus.NOT_STOPPED

internal fun canOfferStoppedRecovery(
    target: SelectedTargetUiState,
    recovery: StoppedRecoveryUiState,
    selectionReady: Boolean,
    supportedFirmware: Boolean,
    shellReady: Boolean,
    otherReadBusy: Boolean,
): Boolean = isRecoveryTargetPackage(target.identity.packageName) &&
    target.status != TargetAutostartStatus.IDENTITY_CHANGED &&
    target.stoppedSnapshot.status == TargetStoppedStatus.STOPPED &&
    recovery.loaded && !recovery.busy && recovery.phase != StoppedRecoveryPhase.RECOVERING &&
    !recovery.storageBlocked && !recovery.needsReconcile &&
    selectionReady && supportedFirmware && shellReady && !otherReadBusy

internal fun recoveryConfirmationStillValid(
    confirmation: SelectedTargetIdentity?,
    targets: List<SelectedTargetUiState>,
    eligible: (SelectedTargetUiState) -> Boolean,
): Boolean = confirmation != null && targets.any { it.identity == confirmation && eligible(it) }
