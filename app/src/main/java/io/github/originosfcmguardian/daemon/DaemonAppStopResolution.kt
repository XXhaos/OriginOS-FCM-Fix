package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.autoprotection.ProtectionConsentMode
import io.github.originosfcmguardian.autoprotection.consents
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import io.github.originosfcmguardian.fcmprotection.FcmProtectionCodec

/** Read-only predicates. A local STOP intent is never a remote OFF acknowledgement. */
internal fun daemonStopSatisfied(root: DaemonProtectionRootSnapshot, stop: DaemonAppStopIntent): Boolean {
    if (root.control.owner != stop.intent.owner || root.control.migrationId != stop.intent.migrationId ||
        root.halt != null || root.bytes(DaemonDomainRole.INTERRUPTED_ARTIFACTS) != null) return false
    return when (stop.command.kind) {
        DaemonWire.MASTER_OFF -> root.control.mode == DaemonControlMode.OFF &&
            root.bytes(DaemonDomainRole.AUTHORITY)?.let(ProtectionAuthorityCodec::decode)?.mode == ProtectionConsentMode.USER_PAUSED
        DaemonWire.FCM_OFF -> root.bytes(DaemonDomainRole.FCM_PROTECTION)?.let(FcmProtectionCodec::decode)?.configured == false &&
            root.bytes(DaemonDomainRole.FCM_PROTECTION_HALT)?.contentEquals(byteArrayOf(1)) == true
        DaemonWire.TARGET_OFF -> {
            val target = requireNotNull(stop.command.target)
            val authority = ProtectionAuthorityCodec.decode(requireNotNull(root.bytes(DaemonDomainRole.AUTHORITY)))
            val selected = root.bytes(DaemonDomainRole.SELECTION)?.let {
                (TargetSelectionCodec.decode(it.toString(Charsets.UTF_8)) as? TargetSelectionDecodeResult.Valid)?.values
                    ?: error("Unknown selection")
            }.orEmpty()
            authority.consents().none { it.identity == target } && target !in selected
        }
        else -> false
    }
}

internal fun daemonStopProposal(root: DaemonProtectionRootSnapshot, stop: DaemonAppStopIntent): DaemonControlTransitionPlan? {
    val plan = root.bytes(DaemonDomainRole.CONTROL_PROPOSAL)?.let(DaemonControlProposalCodec::decode) ?: return null
    check(plan.requestId == stop.id && plan.expected == root.control &&
        plan.transition == DaemonControlTransition.SET_AUTHORITY && daemonStopSatisfied(projectDaemonControl(root, plan), stop)) {
        "An unresolved different proposal cannot be replayed after explicit OFF"
    }
    return plan
}
