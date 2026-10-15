package io.github.originosfcmguardian.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MonitorServiceLifetimeTest {
    @Test fun notificationStopRetiresTheLatestAndroidStartRequest() {
        val lifetime = MonitorServiceLifetime()
        var androidLatestStart = 41
        lifetime.onCommand(androidLatestStart) // Explicit START starts the collector.
        androidLatestStart = 42
        lifetime.onCommand(androidLatestStart) // Notification STOP arrives during collection.
        assertFalse(41 == androidLatestStart) // Android ignores stopSelf(original id).
        assertEquals(androidLatestStart, lifetime.stopId()) // Collector finally retires STOP too.
    }

    @Test fun duplicateOrStaleIntentsDuringCleanupCannotLeaveAStartedService() {
        val lifetime = MonitorServiceLifetime()
        listOf(17, 18, 19).forEach(lifetime::onCommand)
        assertEquals(19, lifetime.stopId())
    }
}
