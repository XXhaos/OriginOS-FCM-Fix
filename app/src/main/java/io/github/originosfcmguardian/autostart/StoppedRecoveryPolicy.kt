package io.github.originosfcmguardian.autostart

/** Domain values only. No command, IPC, persistence or device mutation is reachable here. */
internal data class StoppedRecoveryRequest(
    val requestId: Long,
    val expectedIdentity: SelectedTargetIdentity,
)

/** The future executor must collect these observations afresh, not from a cached UI snapshot. */
internal data class StoppedRecoveryProbe(
    val userConfirmedRecovery: Boolean,
    val selectedIdentity: SelectedTargetIdentity?,
    val fingerprint: String?,
    val currentUser: Int?,
    val authorizedShell: Boolean,
    val serviceUid: Int?,
    val observedIdentity: SelectedTargetIdentity?,
    val exclusiveUidOwner: Boolean?,
    val stopped: Boolean?,
    val qualifiedRecipient: Boolean = false,
)

internal enum class RecoveryBlockReason {
    INVALID_REQUEST, USER_ACTION_REQUIRED, TARGET_NOT_SELECTED, UNSUPPORTED_TARGET,
    UNSUPPORTED_BUILD, WRONG_USER, SHELL_UNAVAILABLE, IDENTITY_UNKNOWN, IDENTITY_CHANGED,
    UID_OWNERSHIP_UNKNOWN, UID_NOT_EXCLUSIVE, STOPPED_UNKNOWN,
}

internal sealed interface StoppedRecoveryPreflight {
    /** Still requires durable intent, service-side revalidation and request de-duplication. */
    data object ReadyForServerRecheck : StoppedRecoveryPreflight
    data object NoWriteNeeded : StoppedRecoveryPreflight
    data class Blocked(val reason: RecoveryBlockReason) : StoppedRecoveryPreflight
}

internal fun preflightStoppedRecovery(
    request: StoppedRecoveryRequest,
    probe: StoppedRecoveryProbe,
): StoppedRecoveryPreflight {
    fun blocked(reason: RecoveryBlockReason) = StoppedRecoveryPreflight.Blocked(reason)
    if (request.requestId <= 0) return blocked(RecoveryBlockReason.INVALID_REQUEST)
    if (!probe.userConfirmedRecovery) return blocked(RecoveryBlockReason.USER_ACTION_REQUIRED)
    if (probe.selectedIdentity != request.expectedIdentity) return blocked(RecoveryBlockReason.TARGET_NOT_SELECTED)
    // A failed qualified read carries no identity; do not mislabel it as known unsupported scope.
    if (probe.observedIdentity == null) return blocked(RecoveryBlockReason.IDENTITY_UNKNOWN)
    if (request.expectedIdentity.packageName != "org.telegram.messenger" && !probe.qualifiedRecipient ||
        probe.qualifiedRecipient && !isRecoveryTargetPackage(request.expectedIdentity.packageName)) {
        return blocked(RecoveryBlockReason.UNSUPPORTED_TARGET)
    }
    if (probe.fingerprint != VERIFIED_AUTOSTART_FINGERPRINT) return blocked(RecoveryBlockReason.UNSUPPORTED_BUILD)
    if (probe.currentUser != 0) return blocked(RecoveryBlockReason.WRONG_USER)
    if (!probe.authorizedShell || probe.serviceUid != 2000) return blocked(RecoveryBlockReason.SHELL_UNAVAILABLE)
    if (probe.observedIdentity != request.expectedIdentity) return blocked(RecoveryBlockReason.IDENTITY_CHANGED)
    if (probe.exclusiveUidOwner == null) return blocked(RecoveryBlockReason.UID_OWNERSHIP_UNKNOWN)
    if (!probe.exclusiveUidOwner) return blocked(RecoveryBlockReason.UID_NOT_EXCLUSIVE)
    return when (probe.stopped) {
        null -> blocked(RecoveryBlockReason.STOPPED_UNKNOWN)
        false -> StoppedRecoveryPreflight.NoWriteNeeded
        true -> StoppedRecoveryPreflight.ReadyForServerRecheck
    }
}

internal enum class StoppedRecoveryReadback {
    FLAG_CLEARED,
    STILL_STOPPED,
    UNCONFIRMED,
}

/** A fresh readback describes only the standard flag, never notification delivery or causation. */
internal fun classifyStoppedRecoveryReadback(
    request: StoppedRecoveryRequest,
    observedIdentity: SelectedTargetIdentity?,
    stopped: Boolean?,
): StoppedRecoveryReadback {
    if (observedIdentity != request.expectedIdentity || stopped == null) {
        return StoppedRecoveryReadback.UNCONFIRMED
    }
    return if (stopped) StoppedRecoveryReadback.STILL_STOPPED else StoppedRecoveryReadback.FLAG_CLEARED
}
