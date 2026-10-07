package io.github.originosfcmguardian.core.gms

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GcmServiceObservationTest {
    private val header = "SERVICE com.google.android.gms/.gcm.GcmService f7b120a pid=13069 user=0\n"
    private fun dump(value: String) = header + "  Client:\n    Is client connected: $value\n"
    private fun parse(text: String) = GcmServiceParser.parse(text.toByteArray())
    private fun assertUnknown(text: String, status: SourceCompleteness = SourceCompleteness.MALFORMED) {
        assertEquals(GcmServiceParser.unknown(status), parse(text))
    }
    @Test fun `exact complete successful absence is distinct and heartbeat still refuses it`() {
        val absence = "No services match: ${GcmServiceParser.COMPONENT}\n"
        assertEquals(GcmServiceSource(GcmConnectionState.SERVICE_ABSENT, SourceCompleteness.COMPLETE), parse(absence))
        assertEquals(GcmConnectionState.SERVICE_ABSENT, parse(absence.replace("\n", "\r\n")).state)
        assertEquals(GcmConnectionState.SERVICE_ABSENT, parse(absence + "Use -h for help.\n").state)
        assertEquals(GcmConnectionState.SERVICE_ABSENT, parse((absence + "Use -h for help.\n").replace("\n", "\r\n")).state)
        listOf(absence.replace("GcmService", "OtherService"), "prefix " + absence, absence + absence,
            absence + "private extra\n", " " + absence,
            absence + "Use -h for help\n", absence + "Use -h for help.\r\n",
            absence.replace("\n", "\r\n") + "Use -h for help.\n",
            absence + "Use -h for help.\nextra\n").forEach { assertUnknown(it) }
        assertUnknown(absence + "Use -h for help.", SourceCompleteness.TRUNCATED)
        assertUnknown(absence + "Use -h for help.\nPermission Denial\n", SourceCompleteness.DENIED)
        assertUnknown(absence.trimEnd(), SourceCompleteness.TRUNCATED)
        assertEquals(GcmConnectionState.UNKNOWN, GcmServiceProcessReader.read(Child(absence, exit = 1), { false }).state)
        assertEquals(GcmConnectionState.UNKNOWN, GcmServiceProcessReader.read(Child(absence, errors = "denied"), { false }).state)
    }
    @Test fun `single complete self report distinguishes true and false without exposing dump`() {
        assertEquals(GcmConnectionState.REPORTED_CONNECTED, parse(dump("true")).state)
        assertEquals(GcmConnectionState.REPORTED_DISCONNECTED, parse(dump("false")).state)
        assertEquals(SourceCompleteness.COMPLETE, parse(dump("true")).completeness)
        assertFalse(parse(dump("true") + " account: private@example.invalid\n").toString().contains("private"))
    }
    @Test fun `current diagnostics clock is left column and precision is seconds`() {
        for ((text, millis) in listOf("54:47/00:00" to 3_287_000L,
            "00:00/12:34" to 0L, "1:02:03/59:59" to 3_723_000L,
            "49:01:01/2:03:04" to 176_461_000L)) {
            val source = parse(dump("true") + "    connectTime=$text\n")
            assertEquals(GcmConnectionState.REPORTED_CONNECTED, source.state)
            assertEquals(millis, source.connectedDurationMillis)
        }
    }
    @Test fun `optional duration cannot invent a session or invalidate existing connection qualification`() {
        val duration = "    connectTime=54:47/00:00\n"
        assertEquals(null, parse(dump("false") + duration).connectedDurationMillis)
        assertEquals(null, parse(dump("true")).connectedDurationMillis)
        for (bad in listOf("54:47/bad", "60:00/00:00", "12:60/00:00", "-1:00/00:00",
            "0:01/00:00", "0:00:01/00:00", "1:60:00/00:00", "9999999999999999:00:00/00:00",
            "54:47/60:00", "５４:４７/00:00", "54:47/00:00 extra")) {
            val source = parse(dump("true") + "    connectTime=$bad\n")
            assertEquals(GcmConnectionState.REPORTED_CONNECTED, source.state)
            assertEquals(SourceCompleteness.COMPLETE, source.completeness)
            assertEquals(null, source.connectedDurationMillis)
        }
        assertEquals(null, parse(dump("true") + duration + duration).connectedDurationMillis)
        assertEquals(null, parse(duration + dump("true")).connectedDurationMillis)
    }
    @Test fun `ambiguous duplicate conflicting malformed and absent fields fail closed`() {
        listOf(header, "", dump("true") + "    Is client connected: false\n",
            dump("true") + "    Is client connected: true\n", dump("true") + dump("false"),
            dump("TRUE"), dump("true extra"), header + "Is client connected: true\n",
            "    Is client connected: true\n" + header).forEach { assertUnknown(it) }
    }
    @Test fun `wrong component or user cannot borrow an otherwise valid boolean`() {
        listOf(dump("true").replace("user=0", "user=10"),
            dump("true").replace("/.gcm.GcmService", "/.gcm.OtherService"),
            dump("true").replace("/.gcm.GcmService", "/com.google.android.gms.gcm.GcmService"),
            dump("true").replace("pid=13069", "pid=0")).forEach { assertUnknown(it) }
    }
    @Test fun `truncation and errors discard a preceding positive field`() {
        assertUnknown(dump("true").trimEnd(), SourceCompleteness.TRUNCATED)
        assertUnknown(dump("true") + "Permission Denial: private detail\n", SourceCompleteness.DENIED)
        assertUnknown(dump("true") + "  Error dumping service: timed out\n", SourceCompleteness.UNAVAILABLE)
        assertUnknown(dump("true") + "*** SERVICE DUMP TIMEOUT EXPIRED ***\n", SourceCompleteness.UNAVAILABLE)
        assertUnknown(dump("true") + "*** DUMP TIMEOUT (2000ms) ***\n", SourceCompleteness.UNAVAILABLE)
        assertEquals(GcmConnectionState.REPORTED_CONNECTED, parse(dump("true") + "  heartbeat timeout configuration: 300\n").state)
        assertUnknown(dump("true") + "x".repeat(GcmServiceParser.MAX_LINE_BYTES + 1) + "\n", SourceCompleteness.TRUNCATED)
        assertUnknown(dump("true") + "\n".repeat(GcmServiceParser.MAX_LINES), SourceCompleteness.TRUNCATED)
        assertUnknown(dump("true") + "x".repeat(GcmServiceParser.MAX_BYTES) + "\n", SourceCompleteness.TRUNCATED)
    }
    private class Child(text: String = "", errors: String = "", private val exit: Int = 0,
                        private var alive: Boolean = false) : Process() {
        var destroyed = false
        var forced = false
        var outClosed = false
        var errClosed = false
        private val out = object : ByteArrayInputStream(text.toByteArray()) {
            override fun close() { outClosed = true; super.close() }
        }
        private val err = object : ByteArrayInputStream(errors.toByteArray()) {
            override fun close() { errClosed = true; super.close() }
        }
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue(): Int { check(!alive); return exit }
        override fun isAlive() = alive
        override fun waitFor(): Int { check(!alive); return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (alive) Thread.sleep(unit.toMillis(timeout))
            return !alive
        }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { forced = true; alive = false; return this }
    }
    @Test fun `finished child requires clean exit and both streams complete`() {
        val clean = Child(dump("true"))
        assertEquals(GcmConnectionState.REPORTED_CONNECTED, GcmServiceProcessReader.read(clean, { false }).state)
        assertTrue(clean.destroyed && clean.outClosed && clean.errClosed)
        listOf(Child(dump("true"), errors = "private error"), Child(dump("true"), exit = 1)).forEach {
            assertEquals(GcmServiceParser.unknown(SourceCompleteness.UNAVAILABLE), GcmServiceProcessReader.read(it, { false }))
            assertTrue(it.destroyed && it.outClosed && it.errClosed)
        }
    }
    @Test fun `timeout and cancellation destroy running child and close pipes without reader workers`() {
        val slow = Child(dump("true"), alive = true)
        assertEquals(GcmServiceParser.unknown(SourceCompleteness.TIMED_OUT), GcmServiceProcessReader.read(slow, { false }, 30))
        assertTrue(slow.destroyed && slow.forced && slow.outClosed && slow.errClosed)
        val cancelled = Child(dump("true"), alive = true)
        assertEquals(GcmServiceParser.unknown(SourceCompleteness.CANCELLED), GcmServiceProcessReader.read(cancelled, { true }))
        assertTrue(cancelled.destroyed && cancelled.forced && cancelled.outClosed && cancelled.errClosed)
    }
    @Test fun `shared byte bound includes stderr and discards any positive output`() {
        val child = Child(dump("true"), errors = "x".repeat(GcmServiceParser.MAX_BYTES), alive = true)
        assertEquals(GcmServiceParser.unknown(SourceCompleteness.TRUNCATED), GcmServiceProcessReader.read(child, { false }))
        assertTrue(child.destroyed && child.forced && child.outClosed && child.errClosed)
    }
}
