package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticServiceRetirementTest {
    @Test fun pendingRemovalThenEmptyOwnerCompletesPreviouslySkippedServiceStop() {
        var pending = true
        var active = true
        var stopped = false
        val retireIdle = { if (!active) stopped = true }
        // Old cancellation clears foreground ownership before durable removal finishes.
        active = false
        if (!pending) retireIdle()
        assertFalse(stopped)
        assertFalse(finishAutomaticIdleRetirement(9, 9, pending, retireIdle))
        pending = false
        assertTrue(finishAutomaticIdleRetirement(9, 9, pending, retireIdle))
        assertTrue(stopped)
    }

    @Test fun completionBeforeCancellationStillAllowsNormalRetirementIncludingFailure() {
        // Both failed revoke and successful removal to empty leave no live runtime owner.
        for (failedRemoval in listOf(false, true)) {
            var active = true
            var stopped = false
            var revocationSaveFailed = failedRemoval
            val retireIdle = { if (!active) stopped = true }
            assertTrue(finishAutomaticIdleRetirement(9, 9, false, retireIdle))
            assertFalse(stopped)
            active = false
            retireIdle() // Normal retire sees cleared pendingStart and stops the service.
            assertTrue(stopped)
            assertTrue(revocationSaveFailed == failedRemoval)
        }
    }

    @Test fun staleCompletionOrReservedReplacementCannotStopReplacementService() {
        var stopped = false
        val retireIdle = { stopped = true }
        assertFalse(finishAutomaticIdleRetirement(9, 10, false, retireIdle))
        assertFalse(finishAutomaticIdleRetirement(9, 9, true, retireIdle))
        assertFalse(finishAutomaticIdleRetirement(9, 10, true, retireIdle))
        assertFalse(stopped)
    }

    @Test fun successiveTargetRemovalsAndGlobalStopRetainExistingServiceCallback() {
        for (finalRevision in listOf(10L, 11L)) {
            val handoff = AutomaticServiceRetirementHandoff()
            var stopped = false
            handoff.attach { stopped = true }
            // First removal revoked the grant and its service retired while pendingStart existed.
            // A second target removal or global stop has no grant from which to copy the callback.
            assertFalse(handoff.finish(9, finalRevision, hasSession = true))
            assertFalse(handoff.finish(9, finalRevision, hasSession = false))
            assertFalse(stopped)
            assertTrue(handoff.finish(finalRevision, finalRevision, hasSession = false))
            assertTrue(stopped)
        }
    }

    @Test fun oldServiceDestructionCannotClearReplacementCallback() {
        val handoff = AutomaticServiceRetirementHandoff()
        var oldStopped = false
        var replacementStopped = false
        val old: () -> Unit = { oldStopped = true }
        val replacement: () -> Unit = { replacementStopped = true }
        handoff.attach(old)
        handoff.attach(replacement)
        handoff.detach(old)
        assertFalse(handoff.finish(9, 10, hasSession = false))
        assertFalse(handoff.finish(10, 10, hasSession = true))
        assertFalse(oldStopped)
        assertFalse(replacementStopped)
        assertTrue(handoff.finish(10, 10, hasSession = false))
        assertTrue(replacementStopped)
        replacementStopped = false
        handoff.detach(replacement)
        handoff.finish(10, 10, hasSession = false)
        assertFalse(replacementStopped)
    }
}
