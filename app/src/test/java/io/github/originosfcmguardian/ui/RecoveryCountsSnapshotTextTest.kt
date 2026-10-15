package io.github.originosfcmguardian.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryCountsSnapshotTextTest {
    @Test fun countsRetainTheCalculationDateAndZoneAcrossMidnight() {
        val savedAt = java.time.Instant.parse("2026-10-06T15:59:00Z").toEpochMilli()
        assertEquals("更新于 10-06 23:59", recoveryCountsSnapshotText(savedAt, "Asia/Shanghai"))
        assertEquals("更新于 10-06 15:59", recoveryCountsSnapshotText(savedAt, "UTC"))
    }

    @Test fun missingOrInvalidCalculationTimeNeverLooksCurrent() {
        assertEquals("更新时间未知", recoveryCountsSnapshotText(null, "UTC"))
        assertEquals("更新时间未知", recoveryCountsSnapshotText(1_000, null))
        assertEquals("更新时间未知", recoveryCountsSnapshotText(1_000, "invalid-zone"))
    }
}
