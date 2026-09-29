package io.github.originosfcmguardian.autostart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartReturnTrackerTest {
    @Test
    fun returnRequiresSuccessfulLaunchAndActualDepartureAndIsConsumedOnce() {
        val tracker = AutostartReturnTracker()

        assertFalse(tracker.consumeReturn())
        tracker.markSuccessfulLaunch()
        assertFalse(tracker.consumeReturn())
        tracker.markDeparture()
        assertTrue(tracker.consumeReturn())
        assertFalse(tracker.consumeReturn())
    }

    @Test
    fun clearCancelsPendingReturn() {
        val tracker = AutostartReturnTracker()
        tracker.markSuccessfulLaunch()
        tracker.markDeparture()
        tracker.clear()

        assertFalse(tracker.consumeReturn())
    }
}
