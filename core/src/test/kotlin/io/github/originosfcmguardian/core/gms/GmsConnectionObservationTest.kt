package io.github.originosfcmguardian.core.gms

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GmsConnectionObservationTest {
    private val uid = 10208
    private fun header(ipv6: Boolean = false) = "  sl local_address ${if (ipv6) "remote_address" else "rem_address"} st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
    private fun row(ipv6: Boolean = false, owner: Int = uid, port: String = "146C", state: String = "01") =
        "  0: ${"0".repeat(if (ipv6) 32 else 8)}:1234 ${"A".repeat(if (ipv6) 32 else 8)}:$port $state 00000000:00000000 00:00000000 00000000 $owner 0 123 1 0000000000000000\n"
    private fun parse(text: String, ipv6: Boolean = false) = GmsSocketParser.parse(text.byteInputStream(), ipv6, uid)
    private fun sample(tcp: SocketSource, tcp6: SocketSource = SocketSource(SourceCompleteness.COMPLETE, false)) =
        GmsConnectionObservation.Sample(7, uid, 123, 100, 200, "a".repeat(64),
            "11111111-2222-3333-4444-555555555555", 1000, 500, tcp, tcp6, true, emptyList())

    @Test fun `ipv4 and ipv6 accept all three ports and only established GMS rows`() {
        for (ipv6 in listOf(false, true)) {
            for (port in listOf("146C", "146D", "146E", "146c")) {
                assertEquals(SocketSource(SourceCompleteness.COMPLETE, true), parse(header(ipv6) + row(ipv6, port = port), ipv6))
            }
            for (text in listOf(row(ipv6, owner = uid + 1), row(ipv6, port = "01BB"), row(ipv6, state = "02"))) {
                assertEquals(SocketSource(SourceCompleteness.COMPLETE, false), parse(header(ipv6) + text, ipv6))
            }
        }
    }

    @Test fun `empty complete tables are negative observations not proof of disconnection`() {
        val observation = sample(parse(header()), parse(header(true), true))
        assertEquals(SocketObservation.NO_MATCH, observation.observation)
        assertEquals(SocketObservation.UNKNOWN, sample(parse("")).observation)
    }

    @Test fun `unrecognized header cannot recover into a positive observation`() {
        val result = parse("unknown columns\n" + header() + row())
        assertEquals(SourceCompleteness.MALFORMED, result.completeness)
        assertFalse(result.matched)
        assertEquals(SourceCompleteness.MALFORMED, parse(header(true) + row()).completeness)
    }

    @Test fun `malformed or torn rows without match yield unknown`() {
        for (text in listOf(header() + "private malformed line\n", header() + row().trimEnd(), header() + row().replace(" 10208 ", " bad "))) {
            assertEquals(SocketObservation.UNKNOWN, sample(parse(text)).observation)
        }
    }

    @Test fun `valid positive survives incomplete source but never claims full coverage`() {
        for (suffix in listOf("bad\n", "torn", "x".repeat(GmsSocketParser.MAX_LINE_BYTES + 1))) {
            val result = parse(header() + row() + suffix)
            assertEquals(SocketSource(SourceCompleteness.PARTIAL, true), result)
            assertEquals(SocketObservation.OBSERVED, sample(result).observation)
        }
        assertEquals(SocketObservation.OBSERVED, sample(parse(header() + row()), SocketSource(SourceCompleteness.DENIED, false)).observation)
    }

    @Test fun `unreadable companion source prevents negative inference`() {
        for (status in SourceCompleteness.entries.filter { it != SourceCompleteness.COMPLETE }) {
            assertEquals(SocketObservation.UNKNOWN, sample(parse(header()), SocketSource(status, false)).observation)
        }
    }

    @Test fun `byte line and length budgets bound work during reading`() {
        val many = header() + row(owner = uid + 1).repeat(GmsSocketParser.MAX_LINES)
        assertEquals(SourceCompleteness.TRUNCATED, parse(many).completeness)
        assertEquals(SourceCompleteness.TRUNCATED, parse(header() + "x".repeat(GmsSocketParser.MAX_LINE_BYTES + 1)).completeness)
        val wide = header() + (row(owner = uid + 1).trimEnd() + " ".repeat(700) + "\n").repeat(400)
        var readBytes = 0
        val counted = object : ByteArrayInputStream(wide.toByteArray()) {
            override fun read(): Int { readBytes++; return super.read() }
        }
        assertEquals(SourceCompleteness.TRUNCATED, GmsSocketParser.parse(counted, false, uid).completeness)
        assertTrue(readBytes <= GmsSocketParser.MAX_BYTES + 1)
    }

    @Test fun `IO failures do not leak exceptions and interrupted source is incomplete`() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("private endpoint") }
        assertEquals(SocketSource(SourceCompleteness.UNAVAILABLE, false), GmsSocketParser.parse(broken, false, uid))
        val denied = object : InputStream() { override fun read(): Int = throw SecurityException("private path") }
        assertEquals(SocketSource(SourceCompleteness.DENIED, false), GmsSocketParser.parse(denied, false, uid))
        assertEquals(SocketSource(SourceCompleteness.PARTIAL, false), GmsSocketParser.parse((header() + row()).byteInputStream(), false, uid) { true })
    }

    @Test fun `strict bounded codec roundtrips only structural evidence`() {
        val result = sample(parse(header() + row())).copy(receivers = listOf(
            ReconnectReceiver("com.google.android.gms.TestReceiver", true, true, "com.google.android.c2dm.permission.SEND")))
        val encoded = GmsObservationCodec.encode(result)
        assertEquals(result, GmsObservationCodec.decode(encoded, 7))
        assertFalse(encoded.contains("AAAAAAAA"))
        assertFalse(encoded.contains("local_address"))
        assertFalse(encoded.contains("private endpoint"))
        for (reason in GmsUnknown.entries) {
            val unknown = GmsConnectionObservation.Unknown(7, reason)
            assertEquals(unknown, GmsObservationCodec.decode(GmsObservationCodec.encode(unknown), 7))
        }
    }

    @Test fun `malformed oversized wrong request and invalid flags fail closed`() {
        val good = GmsObservationCodec.encode(sample(parse(header())))
        for (raw in listOf(null, "x".repeat(12001), good.replaceFirst("2|7|", "2|8|"),
            good.replace("|COMPLETE|0|", "|COMPLETE|yes|"), good + "|extra", "1|7|U|private error")) {
            assertEquals(GmsConnectionObservation.Unknown(7, GmsUnknown.MALFORMED_RESPONSE), GmsObservationCodec.decode(raw, 7))
        }
    }

    @Test fun `current codec preserves independent timestamped report and legacy leaves it unavailable`() {
        val result = sample(parse(header())).copy(gcmService = GcmServiceSource(
            GcmConnectionState.REPORTED_DISCONNECTED, SourceCompleteness.COMPLETE, 1100, 600, 0))
        val current = GmsObservationCodec.encode(result)
        assertEquals(result, GmsObservationCodec.decode(current, 7))
        val legacy = current.split('|').take(17).joinToString("|").replaceFirst("2|", "1|")
        val old = GmsObservationCodec.decode(legacy, 7) as GmsConnectionObservation.Sample
        assertEquals(GcmServiceSource.unavailable(), old.gcmService)
        assertEquals(SocketObservation.NO_MATCH, old.observation)
        assertEquals(GmsConnectionObservation.Unknown(7, GmsUnknown.MALFORMED_RESPONSE),
            GmsObservationCodec.decode(current.replace("|600|0", "|600|10"), 7))
    }
    @Test fun `optional duration uses v3 without upgrading old v2 evidence or safety bindings`() {
        val base = sample(parse(header())).copy(gcmService = GcmServiceSource(
            GcmConnectionState.REPORTED_CONNECTED, SourceCompleteness.COMPLETE, 1100, 600, 0))
        val value = base.copy(gcmService = base.gcmService.copy(connectedDurationMillis = 3_287_000))
        val wire = GmsObservationCodec.encode(value)
        assertTrue(wire.startsWith("3|7|S|"))
        assertEquals(value, GmsObservationCodec.decode(wire, 7))
        assertEquals(gmsHeartbeatBindingDigest(base), gmsHeartbeatBindingDigest(value))
        assertEquals(GmsHeartbeatPolicy.rejection(base, 600), GmsHeartbeatPolicy.rejection(value, 600))
        val old = wire.substringBeforeLast('|').replaceFirst("3|", "2|")
        assertEquals(base, GmsObservationCodec.decode(old, 7))
        for (bad in listOf(wire.substringBeforeLast('|') + "|-1", wire.substringBeforeLast('|') + "|overflow",
            wire.replace("|REPORTED_CONNECTED|", "|REPORTED_DISCONNECTED|"))) {
            assertEquals(GmsConnectionObservation.Unknown(7, GmsUnknown.MALFORMED_RESPONSE), GmsObservationCodec.decode(bad, 7))
        }
    }

    @Test fun `sample freshness is bounded and cannot survive backward elapsed clock`() {
        val observation = sample(parse(header()))
        assertFalse(observation.isFresh(499))
        assertTrue(observation.isFresh(500))
        assertTrue(observation.isFresh(30500))
        assertFalse(observation.isFresh(30501))
    }
}
