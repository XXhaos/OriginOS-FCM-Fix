package io.github.originosfcmguardian.core

/**
 * Produces diagnostic candidates only. This milestone has no authorized write
 * profile, no write command, and no executable repair action.
 */
object RepairPlanner {
    fun plan(report: DetectionReport): RepairPlan {
        val candidates = mutableListOf<RepairCandidate>()
        val validation = mutableListOf<String>()
        var blockedByUnknown = false

        if (report.privilegedAccess != PrivilegedAccessState.READY_SHELL || report.currentUser != 0) {
            blockedByUnknown = true
            validation += "Shell UID 2000 and current user 0 must be established"
        }
        if (report.buildFingerprint == null) {
            blockedByUnknown = true
            validation += "Build fingerprint is required to scope future evidence"
        }

        when (report.gms.status) {
            EvidenceStatus.CONFORMING -> Unit
            EvidenceStatus.ANOMALOUS -> candidates += RepairCandidate(
                component = RepairComponent.GMS_PACKAGE,
                reason = "GMS is absent or disabled; no privileged repair is defined",
            )
            EvidenceStatus.UNKNOWN -> {
                blockedByUnknown = true
                validation += "GMS package/enabled state is unknown"
            }
        }

        AppOp.entries.forEach { operation ->
            when (val check = report.appOps[operation]) {
                null -> {
                    blockedByUnknown = true
                    validation += "${operation.shellName} package/UID evidence is unavailable"
                }
                else -> when (check.status) {
                    EvidenceStatus.CONFORMING -> Unit
                    EvidenceStatus.ANOMALOUS -> {
                        if (check.packageState.status == EvidenceStatus.ANOMALOUS) {
                            candidates += RepairCandidate(
                                component = RepairComponent.APP_OP_PACKAGE,
                                operation = operation,
                                reason = "Package scope reports a non-allow mode",
                            )
                        }
                        if (check.uidState.status == EvidenceStatus.ANOMALOUS) {
                            candidates += RepairCandidate(
                                component = RepairComponent.APP_OP_UID,
                                operation = operation,
                                reason = "UID scope reports a non-allow mode",
                            )
                        }
                    }
                    EvidenceStatus.UNKNOWN -> {
                        blockedByUnknown = true
                        validation += "${operation.shellName} evidence is unknown or conflicting"
                    }
                }
            }
        }

        when (report.doze.status) {
            EvidenceStatus.CONFORMING -> Unit
            EvidenceStatus.ANOMALOUS -> candidates += RepairCandidate(
                component = RepairComponent.DEVICE_IDLE,
                reason = "No GMS DeviceIdle membership was reported",
            )
            EvidenceStatus.UNKNOWN -> {
                blockedByUnknown = true
                validation += "DeviceIdle evidence is unknown"
            }
        }

        if (
            report.pem.problems.isNotEmpty() ||
            report.pem.listState == PemListState.UNKNOWN ||
            report.pem.packagePresence == Presence.UNKNOWN
        ) {
            blockedByUnknown = true
        }
        validation += when (report.pem.listState) {
            PemListState.NULL_OR_MISSING_AMBIGUOUS ->
                "PEM returned null: missing and empty remain ambiguous; no validity is claimed"
            PemListState.VALUE_REPORTED ->
                "PEM value is readable, but its OriginOS 7 semantics and write path are unverified"
            PemListState.UNKNOWN -> "PEM state is unknown and is not eligible for repair"
        }
        validation += "Observed configuration does not establish FCM delivery health"
        validation += when (report.appOpsWriteAdvisory.capability) {
            AppOpsWriteCapability.SHELL_GUARD_WHILE_SHIZUKU_RUNNING ->
                "Historical build-specific evidence: the target shell AppOps write guard was observed while official Shizuku ran; phone-side writes remain untested and automatic repair is withheld"
            AppOpsWriteCapability.UNKNOWN ->
                "AppOps write capability is unknown for this build; this detection did not test a write"
        }

        val disposition = if (candidates.isEmpty() && !blockedByUnknown) {
            PlanDisposition.NO_WRITE
        } else {
            PlanDisposition.NEEDS_VALIDATION
        }
        return RepairPlan(
            disposition = disposition,
            candidates = candidates,
            validationNotes = validation.distinct(),
        )
    }
}
