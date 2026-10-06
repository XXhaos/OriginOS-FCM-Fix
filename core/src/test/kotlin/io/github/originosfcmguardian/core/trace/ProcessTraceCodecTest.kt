package io.github.originosfcmguardian.core.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProcessTraceCodecTest {
    private val target = ProcessTraceTarget("com.example.target", 10_123)

    @Test
    fun `round trip contains only bounded structural fields`() {
        val events = listOf(
            ProcessTraceEvent(1_790_590_802_356, target, ProcessTraceKind.PROCESS_START, 321),
            ProcessTraceEvent(1_790_590_802_357, target, ProcessTraceKind.UID_ACTIVE),
            ProcessTraceEvent(1_790_590_802_358, target, ProcessTraceKind.UID_IDLE),
        )

        val encoded = ProcessTraceCodec.encode(events)

        assertEquals(events, ProcessTraceCodec.decode(encoded))
        assertEquals(
            "PT1\n1790590802356\tcom.example.target\t10123\tPROCESS_START\t321\n" +
                "1790590802357\tcom.example.target\t10123\tUID_ACTIVE\t-\n" +
                "1790590802358\tcom.example.target\t10123\tUID_IDLE\t-",
            encoded,
        )
    }

    @Test
    fun `uid lifecycle round trips without changing legacy wire format`() {
        val events = listOf(ProcessTraceKind.UID_RUNNING, ProcessTraceKind.UID_STOPPED).map {
            ProcessTraceEvent(1_790_590_802_356, target, it)
        }
        assertEquals(events, ProcessTraceCodec.decode(ProcessTraceCodec.encode(events)))
        assertNull(ProcessTraceCodec.decode("PT1\n1\tcom.example.target\t10123\tUID_STOPPED\t9"))
    }

    @Test
    fun `malformed and oversized payloads fail closed`() {
        assertNull(ProcessTraceCodec.decode("not-a-trace\nprivate raw log"))
        assertNull(ProcessTraceCodec.decode("PT1\n1\tcom.example.target\t10123\tUID_ACTIVE\t9"))
        assertNull(ProcessTraceCodec.decode("PT1\n1\tcom.example.target\t10123\tPROCESS_START\t-"))
        assertNull(ProcessTraceCodec.decode("PT1\n1\tcom.example.target\t10123\tUNKNOWN\t-"))
        assertNull(ProcessTraceCodec.decode("PT1\n" + "x".repeat(64 * 1024)))
        assertNull(
            ProcessTraceCodec.decode(
                "PT1\n" + List(257) {
                    "1790590802357\tcom.example.target\t10123\tUID_ACTIVE\t-"
                }.joinToString("\n"),
            ),
        )
    }

    @Test
    fun `encoder rejects more than bounded event count`() {
        val event = ProcessTraceEvent(1, target, ProcessTraceKind.UID_ACTIVE)
        assertFailsWith<IllegalArgumentException> { ProcessTraceCodec.encode(List(257) { event }) }
    }
}
