package io.github.originosfcmguardian.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryCountsSnapshotTextTest {
    @Test fun countsRetainTheCalculationDateAndZoneAcrossMidnight() {
        val savedAt = java.time.Instant.parse("2026-10-06T15:59:00Z").toEpochMilli()
        assertEquals("统计截至 10-06 23:59（Asia/Shanghai）", recoveryCountsSnapshotText(savedAt, "Asia/Shanghai"))
        assertEquals("统计截至 10-06 15:59（UTC）", recoveryCountsSnapshotText(savedAt, "UTC"))
    }

    @Test fun missingOrInvalidCalculationTimeNeverLooksCurrent() {
        assertEquals("统计时间未知；请刷新", recoveryCountsSnapshotText(null, "UTC"))
        assertEquals("统计时间未知；请刷新", recoveryCountsSnapshotText(1_000, null))
        assertEquals("统计时间未知；请刷新", recoveryCountsSnapshotText(1_000, "invalid-zone"))
    }
}
