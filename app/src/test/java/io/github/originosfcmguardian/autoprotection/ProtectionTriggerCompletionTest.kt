package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.daemon.DaemonRestoreCompletion
import org.junit.Assert.*
import org.junit.Test

class ProtectionTriggerCompletionTest {
    @Test fun successOrErrorFinishesOnceAndRemovesDeadline() {
        var finishes = 0
        val cancelled = mutableListOf<Runnable>()
        val completion = ProtectionTriggerCompletion({ finishes++ }, { cancelled += it })
        assertTrue(completion.isLive())
        completion.finish()
        completion.deadline.run()
        completion.finish()
        assertFalse(completion.isLive())
        assertEquals(1, finishes)
        assertEquals(listOf(completion.deadline), cancelled)
    }

    @Test fun deadlineDoesNotCancelMergedRemoteRestoreAndLateCompletionCannotFinishTwice() {
        val coordinator = DaemonRestoreCompletion()
        val existing = coordinator.offer()
        assertTrue(coordinator.takePending(existing))
        val merged = coordinator.offer()
        var finishes = 0
        val completion = ProtectionTriggerCompletion({ finishes++ }, {})
        merged.completion.invokeOnCompletion { completion.finish() }
        assertEquals(0, finishes)
        completion.deadline.run()
        assertEquals(1, finishes)
        assertFalse(merged.completion.isCompleted)
        assertFalse(merged.completion.isCancelled)
        assertTrue(coordinator.takePending(existing))
        assertFalse(coordinator.takePending(existing))
        assertTrue(merged.completion.isCompleted)
        assertFalse(merged.completion.isCancelled)
        assertEquals(1, finishes)
    }
}
