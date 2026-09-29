package io.github.originosfcmguardian.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianProbeParserTest {
    private val valid = mapOf(
        GuardianProbeParser.VERSION_KEY to "1",
        GuardianProbeParser.TEST_ID_KEY to "test_ABC-123",
        GuardianProbeParser.SENT_AT_KEY to "1720000000123",
    )

    @Test
    fun acceptsOnlyExactVersionOneContract() {
        assertEquals(GuardianProbe("test_ABC-123", 1_720_000_000_123), GuardianProbeParser.parse(valid))
        assertNull(GuardianProbeParser.parse(valid - GuardianProbeParser.TEST_ID_KEY))
        assertNull(GuardianProbeParser.parse(valid + ("extra" to "ignored-by-nobody")))
        assertNull(GuardianProbeParser.parse(valid + (GuardianProbeParser.VERSION_KEY to "2")))
    }

    @Test
    fun rejectsUnsafeIdsAndInvalidTimestamps() {
        listOf("", "space id", "../escape", "a".repeat(81), "中文").forEach { id ->
            assertNull(GuardianProbeParser.parse(valid + (GuardianProbeParser.TEST_ID_KEY to id)))
        }
        listOf("0", "-1", "+1", "01", "1.5", "9223372036854775808").forEach { timestamp ->
            assertNull(GuardianProbeParser.parse(valid + (GuardianProbeParser.SENT_AT_KEY to timestamp)))
        }
    }
}
