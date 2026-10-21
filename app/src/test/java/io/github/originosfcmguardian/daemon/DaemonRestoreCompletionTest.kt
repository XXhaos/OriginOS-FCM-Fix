package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonRestoreCompletionTest {
    @Test fun mergedTriggerWaitsForBothExistingPasses() {
        val coordinator = DaemonRestoreCompletion()
        val first = coordinator.offer()
        assertTrue(first.startWorker)
        assertTrue(coordinator.takePending(first))
        val merged = coordinator.offer()
        assertFalse(merged.startWorker)
        assertSame(first.completion, merged.completion)
        var finished = 0
        merged.completion.invokeOnCompletion { finished++ }
        assertEquals(0, finished)
        assertTrue(coordinator.takePending(first))
        assertEquals(0, finished)
        // A trigger during the second pass joins completion, without adding a third pass.
        val last = coordinator.offer()
        assertFalse(last.startWorker)
        assertSame(first.completion, last.completion)
        assertFalse(coordinator.takePending(first))
        assertEquals(1, finished)
        assertTrue(last.completion.isCompleted)
        coordinator.finish(first)
        assertEquals(1, finished)
    }

    @Test fun exceptionalCompletionReleasesWaitersAndAllowsNextEvent() {
        val coordinator = DaemonRestoreCompletion()
        val failed = coordinator.offer()
        assertTrue(coordinator.takePending(failed))
        val merged = coordinator.offer()
        coordinator.finish(failed)
        assertTrue(merged.completion.isCompleted)
        val next = coordinator.offer()
        assertTrue(next.startWorker)
        assertNotSame(failed.completion, next.completion)
        assertFalse(next.completion.isCompleted)
        coordinator.finish(failed)
        assertFalse(next.completion.isCompleted)
        assertFalse(coordinator.takePending(failed))
        assertTrue(coordinator.takePending(next))
        assertFalse(coordinator.takePending(next))
        assertTrue(next.completion.isCompleted)
    }
}
