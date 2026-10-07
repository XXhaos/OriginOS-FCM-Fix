package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.privileged.StoppedTargetObservation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

class StoppedRecoveryFailureStageTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10234, 100, listOf("a".repeat(64)))
    private fun known() = StoppedTargetObservation.Known(1, identity, true, true, 100)

    @Test fun rejectedCapabilityReportsStageWithoutClaimWriteOrExceptionMessage() {
        val result = StoppedRecoveryEngine({ known() }, { error("claim") }, { true }, { error("write") })
            .recover(1, identity) { throw RejectedExecutionException("private secret") }
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertTrue(result.detail.contains("stage=CAPABILITY error=REJECTED_EXECUTION"))
        assertFalse(result.detail.contains("private secret"))
    }

    @Test fun claimCanConsumeIdAndThenThrowWithoutDispatchingWrite() {
        var consumed = false
        val result = StoppedRecoveryEngine({ known() }, {
            consumed = true
            throw TimeoutException("private claim response")
        }, { true }, { error("write") }).recover(1, identity)
        assertTrue(consumed)
        // This flag reflects callback return, not a claim that the durable ID was unconsumed.
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertTrue(result.detail.contains("stage=CLAIM error=TIMED_OUT"))
        assertFalse(result.detail.contains("private claim response"))
    }

    @Test fun failureAfterSuccessfulClaimIsUnconfirmedAndNeverWrites() {
        var reads = 0
        val result = StoppedRecoveryEngine({
            if (++reads == 1) known() else throw ExecutionException(InterruptedException("private interruption"))
        }, { true }, { true }, { error("write") }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
        assertTrue(result.detail.contains("stage=FINAL_READ error=INTERRUPTED"))
        assertFalse(result.detail.contains("private interruption"))
        assertTrue(result.detail.length <= 192)
    }
}
