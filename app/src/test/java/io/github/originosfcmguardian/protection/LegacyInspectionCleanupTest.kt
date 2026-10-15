package io.github.originosfcmguardian.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyInspectionCleanupTest {
    @Test fun upgradeCancelsOnlyBothRetiredInspectionWorkNames() {
        val cancelled = mutableListOf<String>()
        cancelRetiredInspectionWork { cancelled.add(it) }
        assertEquals(listOf("guardian_daily_read_only_protection_v1",
            "guardian_event_read_only_protection_v1"), cancelled)
    }
}
