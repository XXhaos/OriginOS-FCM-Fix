package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryEvaluatorTest {
    @Test
    fun `unknown journal schema and before unrelated to baseline fail closed`() = runBlocking {
        val baseline = completeSnapshot()
        val unknownVersion = RecoveryEvaluator.evaluate(
            JournalDocument(schemaVersion = 999, baseline = baseline),
            baseline,
        )
        assertEquals(RecoveryDisposition.FAIL_CLOSED, unknownVersion.disposition)
        assertTrue(RecoveryReason.UNKNOWN_JOURNAL_SCHEMA in unknownVersion.reasons)

        val drifted = completeSnapshot(appOpOverride = "No operations.\nDefault mode: ignore\n")
        val arbitraryBefore = appOpObservation(drifted, AppOp.RUN_IN_BACKGROUND)
        val unrelated = RecoveryEvaluator.evaluate(
            JournalDocument(
                baseline = baseline,
                operations = listOf(
                    JournalOperation(
                        "unrelated-before",
                        JournalComponent.APP_OPS,
                        arbitraryBefore,
                        arbitraryBefore,
                        WriteIntentState.NONE,
                        OperationVerification.NOT_ATTEMPTED,
                    ),
                ),
            ),
            drifted,
        )
        assertEquals(RecoveryDisposition.FAIL_CLOSED, unrelated.disposition)
        assertTrue(RecoveryReason.BASELINE_OPERATION_MISMATCH in unrelated.reasons)
    }

    @Test
    fun `identity change fails closed before operation inference`() = runBlocking {
        val baseline = completeSnapshot()
        val current = completeSnapshot().copy(
            metadata = completeSnapshot().metadata.copy(buildId = "OTHER_BUILD"),
        )
        val result = RecoveryEvaluator.evaluate(JournalDocument(baseline = baseline), current)

        assertEquals(RecoveryDisposition.FAIL_CLOSED, result.disposition)
        assertTrue(RecoveryReason.DEVICE_IDENTITY_CHANGED in result.reasons)
        assertFalse(result.canAutoRetry)
        assertFalse(result.canAutoRollback)
    }

    @Test
    fun `concurrent appops drift conflicts while telemetry-only history drift is ignored`() = runBlocking {
        val baseline = completeSnapshot()
        val before = appOpObservation(baseline, AppOp.RUN_IN_BACKGROUND)
        val operation = JournalOperation(
            "appop-before",
            JournalComponent.APP_OPS,
            before,
            before,
            WriteIntentState.NONE,
            OperationVerification.NOT_ATTEMPTED,
        )
        val drifted = completeSnapshot(appOpOverride = "No operations.\nDefault mode: ignore\n")
        val conflict = RecoveryEvaluator.evaluate(
            JournalDocument(baseline = baseline, operations = listOf(operation)),
            drifted,
        )
        assertEquals(RecoveryDisposition.CONFLICT, conflict.disposition)
        assertTrue(RecoveryReason.BEFORE_STATE_DRIFT in conflict.reasons)

        val wakeBefore = appOpObservation(baseline, AppOp.WAKE_LOCK)
        val historyOnly = completeSnapshot(
            wakeLockOutput = baseline.report.appOps.getValue(AppOp.WAKE_LOCK)
                .packageState.rawOutput.replace("time=+2m13s422ms", "time=+9m1s"),
        )
        assertTrue(
            baseline.report.appOps.getValue(AppOp.WAKE_LOCK).packageState.rawOutput !=
                historyOnly.report.appOps.getValue(AppOp.WAKE_LOCK).packageState.rawOutput,
        )
        val historyResult = RecoveryEvaluator.evaluate(
            JournalDocument(
                baseline = baseline,
                operations = listOf(
                    JournalOperation(
                        "wake-history",
                        JournalComponent.APP_OPS,
                        wakeBefore,
                        wakeBefore,
                        WriteIntentState.NONE,
                        OperationVerification.NOT_ATTEMPTED,
                    ),
                ),
            ),
            historyOnly,
        )
        assertEquals(RecoveryDisposition.CONSISTENT, historyResult.disposition)
    }

    @Test
    fun `unknown inverse and uncertain intent never become automatic success`() = runBlocking {
        val snapshot = completeSnapshot()
        val before = appOpObservation(snapshot, AppOp.WAKE_LOCK)
        val unknown = JournalOperation(
            "unknown-inverse",
            JournalComponent.APP_OPS,
            before,
            null,
            WriteIntentState.DISPATCHED,
            OperationVerification.UNCONFIRMED,
        )
        val unknownResult = RecoveryEvaluator.evaluate(
            JournalDocument(baseline = snapshot, operations = listOf(unknown)),
            snapshot,
        )
        assertEquals(RecoveryDisposition.FAIL_CLOSED, unknownResult.disposition)
        assertTrue(RecoveryReason.UNKNOWN_INVERSE in unknownResult.reasons)

        val uncertain = unknown.copy(
            operationId = "uncertain",
            expectedAfter = before,
            writeIntent = WriteIntentState.DISPATCH_UNCERTAIN,
        )
        val uncertainResult = RecoveryEvaluator.evaluate(
            JournalDocument(baseline = snapshot, operations = listOf(uncertain)),
            snapshot,
        )
        assertEquals(RecoveryDisposition.MANUAL_REVIEW, uncertainResult.disposition)
        assertTrue(RecoveryReason.WRITE_OUTCOME_UNCERTAIN in uncertainResult.reasons)
        assertFalse(uncertainResult.canAutoRetry)
        assertFalse(uncertainResult.canAutoRollback)
    }

    @Test
    fun `deviceidle complete raw drift conflicts even when parsed membership is unchanged`() = runBlocking {
        val baseline = completeSnapshot()
        val before = PolicyObservation.DeviceIdle(
            baseline.report.doze.memberships,
            baseline.report.doze.rawOutput,
            evidenceComplete = true,
        )
        val current = completeSnapshot(dozeSuffix = "\n")
        val result = RecoveryEvaluator.evaluate(
            JournalDocument(
                baseline = baseline,
                operations = listOf(
                    JournalOperation(
                        "doze",
                        JournalComponent.DEVICE_IDLE,
                        before,
                        before,
                        WriteIntentState.NONE,
                        OperationVerification.NOT_ATTEMPTED,
                    ),
                ),
            ),
            current,
        )
        assertEquals(RecoveryDisposition.CONFLICT, result.disposition)
    }
}
