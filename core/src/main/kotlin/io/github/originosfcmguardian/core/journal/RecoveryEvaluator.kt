package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.Presence

enum class RecoveryDisposition {
    NO_PENDING_OPERATION,
    CONSISTENT,
    MANUAL_REVIEW,
    CONFLICT,
    FAIL_CLOSED,
}

enum class RecoveryReason {
    INCOMPLETE_BASELINE,
    INCOMPLETE_CURRENT,
    UNKNOWN_JOURNAL_SCHEMA,
    BASELINE_OPERATION_MISMATCH,
    DEVICE_IDENTITY_CHANGED,
    TARGET_UNAVAILABLE,
    UNKNOWN_INVERSE,
    BEFORE_STATE_DRIFT,
    EXPECTED_STATE_DRIFT,
    WRITE_OUTCOME_UNCERTAIN,
    FAILED_OPERATION,
    INCONSISTENT_OPERATION_STATE,
    VERIFIED_STATE_MATCHES,
    NO_WRITE_WAS_DISPATCHED,
}

data class OperationRecoveryAssessment(
    val operationId: String,
    val disposition: RecoveryDisposition,
    val reasons: Set<RecoveryReason>,
) {
    val canAutoRetry: Boolean = false
    val canAutoRollback: Boolean = false
}

data class RecoveryAssessment(
    val disposition: RecoveryDisposition,
    val reasons: Set<RecoveryReason>,
    val operations: List<OperationRecoveryAssessment>,
) {
    val canAutoRetry: Boolean = false
    val canAutoRollback: Boolean = false
    val executionCapable: Boolean = false
}

/**
 * Pure conflict/recovery classification. Equality never proves that Guardian performed a write,
 * and every result deliberately lacks an executable retry or rollback action.
 */
object RecoveryEvaluator {
    fun evaluate(document: JournalDocument, current: BaselineSnapshot): RecoveryAssessment {
        if (document.schemaVersion != JOURNAL_SCHEMA_VERSION) {
            return terminal(RecoveryDisposition.FAIL_CLOSED, RecoveryReason.UNKNOWN_JOURNAL_SCHEMA)
        }
        val baselineValidation = JournalSemanticValidator.validateBaseline(document.baseline)
        if (!baselineValidation.isValid) {
            return terminal(RecoveryDisposition.FAIL_CLOSED, RecoveryReason.INCOMPLETE_BASELINE)
        }
        val currentValidation = JournalSemanticValidator.validateBaseline(current)
        if (!currentValidation.isValid) {
            return terminal(RecoveryDisposition.FAIL_CLOSED, RecoveryReason.INCOMPLETE_CURRENT)
        }
        if (!sameDevice(document.baseline.metadata, current.metadata)) {
            return terminal(RecoveryDisposition.FAIL_CLOSED, RecoveryReason.DEVICE_IDENTITY_CHANGED)
        }
        if (document.operations.isEmpty()) {
            return RecoveryAssessment(
                RecoveryDisposition.NO_PENDING_OPERATION,
                emptySet(),
                emptyList(),
            )
        }

        val operations = document.operations.map { operation ->
            evaluateOperation(operation, document.baseline, current)
        }
        val disposition = operations.maxBy { severity(it.disposition) }.disposition
        return RecoveryAssessment(
            disposition = disposition,
            reasons = operations.flatMapTo(linkedSetOf()) { it.reasons },
            operations = operations,
        )
    }

    private fun evaluateOperation(
        operation: JournalOperation,
        baseline: BaselineSnapshot,
        current: BaselineSnapshot,
    ): OperationRecoveryAssessment {
        val baselineObserved = currentObservation(operation.before, baseline)
        if (baselineObserved == null || !policyEquals(operation.before, baselineObserved)) {
            return assessment(
                operation,
                RecoveryDisposition.FAIL_CLOSED,
                RecoveryReason.BASELINE_OPERATION_MISMATCH,
            )
        }
        val observed = currentObservation(operation.before, current)
            ?: return assessment(operation, RecoveryDisposition.FAIL_CLOSED, RecoveryReason.TARGET_UNAVAILABLE)
        return when (operation.writeIntent) {
            WriteIntentState.NONE,
            WriteIntentState.RECORDED_NOT_DISPATCHED,
            -> if (operation.verification != OperationVerification.NOT_ATTEMPTED) {
                assessment(
                    operation,
                    RecoveryDisposition.FAIL_CLOSED,
                    RecoveryReason.INCONSISTENT_OPERATION_STATE,
                )
            } else if (policyEquals(operation.before, observed)) {
                assessment(
                    operation,
                    RecoveryDisposition.CONSISTENT,
                    RecoveryReason.NO_WRITE_WAS_DISPATCHED,
                )
            } else {
                assessment(operation, RecoveryDisposition.CONFLICT, RecoveryReason.BEFORE_STATE_DRIFT)
            }

            WriteIntentState.DISPATCH_UNCERTAIN -> assessment(
                operation,
                RecoveryDisposition.MANUAL_REVIEW,
                RecoveryReason.WRITE_OUTCOME_UNCERTAIN,
            )

            WriteIntentState.DISPATCHED -> evaluateDispatched(operation, observed)
        }
    }

    private fun evaluateDispatched(
        operation: JournalOperation,
        observed: PolicyObservation,
    ): OperationRecoveryAssessment {
        val expected = operation.expectedAfter
            ?: return assessment(operation, RecoveryDisposition.FAIL_CLOSED, RecoveryReason.UNKNOWN_INVERSE)
        return when (operation.verification) {
            OperationVerification.VERIFIED_SUCCESS -> if (policyEquals(expected, observed)) {
                assessment(
                    operation,
                    RecoveryDisposition.CONSISTENT,
                    RecoveryReason.VERIFIED_STATE_MATCHES,
                )
            } else {
                assessment(operation, RecoveryDisposition.CONFLICT, RecoveryReason.EXPECTED_STATE_DRIFT)
            }
            OperationVerification.FAILED -> assessment(
                operation,
                RecoveryDisposition.MANUAL_REVIEW,
                RecoveryReason.FAILED_OPERATION,
            )
            OperationVerification.NOT_ATTEMPTED,
            OperationVerification.UNCONFIRMED,
            -> assessment(
                operation,
                RecoveryDisposition.MANUAL_REVIEW,
                RecoveryReason.WRITE_OUTCOME_UNCERTAIN,
            )
        }
    }

    private fun currentObservation(
        target: PolicyObservation,
        current: BaselineSnapshot,
    ): PolicyObservation? = when (target) {
        is PolicyObservation.AppOps -> {
            val check = current.report.appOps[target.operation] ?: return null
            PolicyObservation.AppOps(
                operation = target.operation,
                packageValue = AppOpPolicyValue(
                    scope = check.packageState.scope,
                    operationRecordPresence = check.packageState.operationRecordPresence,
                    reportedMode = check.packageState.reportedMode,
                    reportedDefault = check.packageState.reportedDefault,
                    overrideProvenance = check.packageState.overrideProvenance,
                ),
                uidValue = AppOpPolicyValue(
                    scope = check.uidState.scope,
                    operationRecordPresence = check.uidState.operationRecordPresence,
                    reportedMode = check.uidState.reportedMode,
                    reportedDefault = check.uidState.reportedDefault,
                    overrideProvenance = check.uidState.overrideProvenance,
                ),
            )
        }
        is PolicyObservation.DeviceIdle -> PolicyObservation.DeviceIdle(
            memberships = current.report.doze.memberships,
            rawOutput = current.report.doze.rawOutput,
            evidenceComplete = current.report.doze.status != EvidenceStatus.UNKNOWN &&
                current.report.doze.problems.isEmpty(),
        )
        is PolicyObservation.VivoPem -> PolicyObservation.VivoPem(
            packagePresence = current.report.pem.packagePresence,
            listState = current.report.pem.listState,
            entries = current.report.pem.entries,
            rawListOutput = current.report.pem.rawListOutput,
            evidenceComplete = current.report.pem.packagePresence != Presence.UNKNOWN &&
                current.report.pem.listState != PemListState.UNKNOWN &&
                current.report.pem.problems.isEmpty(),
        )
    }

    private fun policyEquals(expected: PolicyObservation, actual: PolicyObservation): Boolean = when {
        expected is PolicyObservation.AppOps && actual is PolicyObservation.AppOps ->
            expected.operation == actual.operation &&
                expected.packageValue == actual.packageValue &&
                expected.uidValue == actual.uidValue
        expected is PolicyObservation.DeviceIdle && actual is PolicyObservation.DeviceIdle ->
            expected.evidenceComplete && actual.evidenceComplete &&
                expected.memberships == actual.memberships && expected.rawOutput == actual.rawOutput
        expected is PolicyObservation.VivoPem && actual is PolicyObservation.VivoPem ->
            expected.evidenceComplete && actual.evidenceComplete &&
                expected.packagePresence == actual.packagePresence &&
                expected.listState == actual.listState && expected.entries == actual.entries &&
                expected.rawListOutput == actual.rawListOutput
        else -> false
    }

    private fun sameDevice(first: CaptureMetadata, second: CaptureMetadata): Boolean =
        first.buildFingerprint == second.buildFingerprint &&
            first.androidApiFingerprint == second.androidApiFingerprint &&
            first.buildId == second.buildId &&
            first.buildDisplay == second.buildDisplay &&
            first.sdkInt == second.sdkInt &&
            first.userId == second.userId &&
            first.executorUid == second.executorUid &&
            first.gmsAppId == second.gmsAppId

    private fun terminal(
        disposition: RecoveryDisposition,
        reason: RecoveryReason,
    ) = RecoveryAssessment(disposition, setOf(reason), emptyList())

    private fun assessment(
        operation: JournalOperation,
        disposition: RecoveryDisposition,
        reason: RecoveryReason,
    ) = OperationRecoveryAssessment(operation.operationId, disposition, setOf(reason))

    private fun severity(disposition: RecoveryDisposition): Int = when (disposition) {
        RecoveryDisposition.NO_PENDING_OPERATION -> 0
        RecoveryDisposition.CONSISTENT -> 1
        RecoveryDisposition.MANUAL_REVIEW -> 2
        RecoveryDisposition.CONFLICT -> 3
        RecoveryDisposition.FAIL_CLOSED -> 4
    }
}
