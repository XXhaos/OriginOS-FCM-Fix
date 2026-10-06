package io.github.originosfcmguardian.core.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTraceParserTest {
    private val target = ProcessTraceTarget("org.telegram.messenger", 10_434)

    @Test
    fun `replays observed OriginOS Firebase receiver startup without inferring FCM causality`() {
        // PD2609-20260923, 2026-09-29 trial03. Transcribed from the observed event fields;
        // this is host replay, not a claim that Guardian captured the original device session.
        val toolbox = ProcessTraceTarget("fr.smarquis.fcm", 10_353)
        val raw = """
            1790677514.045  2089  2190 I am_proc_start: [0,2679,10353,fr.smarquis.fcm,broadcast,{fr.smarquis.fcm/com.google.firebase.iid.FirebaseInstanceIdReceiver}]
            1790677514.176  2089  3571 I am_uid_active: 10353
        """.trimIndent()

        assertEquals(
            listOf(
                ProcessTraceEvent(1_790_677_514_045, toolbox, ProcessTraceKind.PROCESS_START, 2_679),
                ProcessTraceEvent(1_790_677_514_176, toolbox, ProcessTraceKind.UID_ACTIVE),
            ),
            ProcessTraceParser.parse(raw, toolbox),
        )
        assertTrue(ProcessTraceParser.parse(raw, target).isEmpty())
        assertTrue(ProcessTraceParser.parse(raw, toolbox.copy(uid = 10_354)).isEmpty())
        assertTrue(ProcessTraceParser.parse(raw, toolbox.copy(packageName = "fr.smarquis")).none {
            it.kind == ProcessTraceKind.PROCESS_START
        })
    }

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
    fun `uid lifecycle requires exact direct uid and preserves event distinction`() {
        val raw = """
            1790590802.356  100  200 I am_uid_running: 10434
            1790590802.357  100  200 I am_uid_stopped: [10434]
            1790590802.358  100  200 I am_uid_running: 10435
            1790590802.359  100  200 I am_uid_stopped: 1010434
            1790590802.360  100  200 I am_uid_running: [10434,reason]
            1790590802.361  100  200 I am_uid_stopped: 10434 extra
            1790590802.362  100  200 I am_proc_died: [0,31181,org.telegram.messenger]
        """.trimIndent()
        assertEquals(
            listOf(
                ProcessTraceEvent(1_790_590_802_356, target, ProcessTraceKind.UID_RUNNING),
                ProcessTraceEvent(1_790_590_802_357, target, ProcessTraceKind.UID_STOPPED),
            ),
            ProcessTraceParser.parse(raw, target),
        )
    }

    @Test
    fun `oversized or over-line input fails closed`() {
        val valid = "1790590802.356  100  200 I am_uid_active: 10434"
        assertTrue(ProcessTraceParser.parse("x".repeat(128 * 1024 + 1), target).isEmpty())
        assertTrue(ProcessTraceParser.parse(List(261) { valid }.joinToString("\n"), target).isEmpty())
    }
}
