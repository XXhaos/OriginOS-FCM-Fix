package io.github.originosfcmguardian.core.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTraceParserTest {
    private val target = ProcessTraceTarget("org.telegram.messenger", 10_434)

    @Test
    fun `accepts only exact package process starts and direct target uid events`() {
        val raw = """
            1790590802.356  100  200 I am_proc_start: [0,31181,10434,org.telegram.messenger,broadcast,{cmp}]
            1790590802.357  100  200 I am_proc_start: [0,31182,10434,org.telegram.messenger:push,service,{cmp}]
            1790590802.358  100  200 I am_uid_active: 10434
            1790590802.359  100  200 I am_uid_idle: [10434]
        """.trimIndent()

        assertEquals(
            listOf(
                ProcessTraceEvent(1_790_590_802_356, target, ProcessTraceKind.PROCESS_START, 31_181),
                ProcessTraceEvent(1_790_590_802_357, target, ProcessTraceKind.PROCESS_START, 31_182),
                ProcessTraceEvent(1_790_590_802_358, target, ProcessTraceKind.UID_ACTIVE),
                ProcessTraceEvent(1_790_590_802_359, target, ProcessTraceKind.UID_IDLE),
            ),
            ProcessTraceParser.parse(raw, target),
        )
    }

    @Test
    fun `rejects other uid package prefix collisions indirect tags and malformed records`() {
        val raw = """
            1790590802.356  100  200 I am_proc_start: [0,31181,10435,org.telegram.messenger,broadcast,{cmp}]
            1790590802.357  100  200 I am_proc_start: [0,31182,10434,org.telegram.messenger.evil,broadcast,{cmp}]
            1790590802.358  100  200 I am_proc_start: [10,31183,10434,org.telegram.messenger,broadcast,{cmp}]
            1790590802.359  100  200 I am_proc_start: [0,31184,10434,org.telegram.messenger:push/evil,service,{cmp}]
            1790590802.360  100  200 I am_uid_active: 10435
            1790590802.361  100  200 I am_uid_idle: [10434,reason]
            1790590802.362  100  200 I am_proc_died: [0,31181,org.telegram.messenger]
            1790590802.363  100  200 I cached_app_freezer: [10434,31181]
            raw private application log
        """.trimIndent()

        assertTrue(ProcessTraceParser.parse(raw, target).isEmpty())
    }

    @Test
    fun `oversized or over-line input fails closed`() {
        val valid = "1790590802.356  100  200 I am_uid_active: 10434"
        assertTrue(ProcessTraceParser.parse("x".repeat(128 * 1024 + 1), target).isEmpty())
        assertTrue(ProcessTraceParser.parse(List(261) { valid }.joinToString("\n"), target).isEmpty())
    }
}
