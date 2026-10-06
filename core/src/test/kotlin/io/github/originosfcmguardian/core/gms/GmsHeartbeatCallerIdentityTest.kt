package io.github.originosfcmguardian.core.gms

import kotlin.test.*

class GmsHeartbeatCallerIdentityTest {
    private val identity = GmsHeartbeatCallerIdentity(10333, 1234, 987654321L, "11111111-2222-3333-4444-555555555555")
    @Test fun `identity wire is canonical bounded and rejects malformed identity`() {
        val encoded = GmsHeartbeatCallerCodec.encodeIdentity(identity)
        assertEquals(identity, GmsHeartbeatCallerCodec.decodeIdentity(encoded))
        listOf(null, "", "x".repeat(97), "$encoded,extra", encoded.replace("10333", "010333"),
            encoded.replace("10333", "110333"), encoded.replace("1234,", "0,"),
            encoded.replace("987654321", "9223372036854775808"), encoded.replace("987654321", "-1"),
            encoded.replace("555555555555", "AAAAAAAAAAAA"), "$encoded\n", encoded.replace("1234,", "4194305,")
        ).forEach { assertNull(GmsHeartbeatCallerCodec.decodeIdentity(it), it) }
    }
    @Test fun `qualification response matches exact request and requires current identity for positive reason`() {
        GmsHeartbeatCallerReason.entries.forEach { reason ->
            val value = GmsHeartbeatCallerQualification(identity, reason)
            assertEquals(value, GmsHeartbeatCallerCodec.decode(GmsHeartbeatCallerCodec.encode(7, value), 7))
        }
        val unknown = GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN)
        assertEquals(unknown, GmsHeartbeatCallerCodec.decode(GmsHeartbeatCallerCodec.encode(7, unknown), 7))
        listOf(null, "x".repeat(193), "1|7|OLD_INSTANCE_GONE|-", "1|07|UNKNOWN|-", "1|8|UNKNOWN|-",
            "1|7|UNKNOWN|-|extra", "1|7|not-a-reason|-", "2|7|UNKNOWN|-").forEach {
            assertEquals(unknown, GmsHeartbeatCallerCodec.decode(it, 7))
        }
    }
    private fun stat(comm: String = "guardian", ticks: String = "987654321", fields: Int = 50): String {
        val tail = MutableList(fields) { "0" }; tail[0] = "S"; tail[19] = ticks
        return "1234 ($comm) ${tail.joinToString(" ")}\n"
    }
    @Test fun `starttime handles spaces and closing parentheses in comm without shifting field 22`() {
        assertEquals(identity.startTicks, GmsHeartbeatCallerStatParser.startTicks(stat("guardian ) worker )"), 1234))
        assertEquals(identity.startTicks, GmsHeartbeatCallerStatParser.startTicks(stat(), 1234))
        assertEquals(Long.MAX_VALUE, GmsHeartbeatCallerStatParser.startTicks(stat(ticks = Long.MAX_VALUE.toString()), 1234))
    }
    @Test fun `truncated denied malformed or wrong pid stat never supplies starttime`() {
        listOf(stat().dropLast(1), stat(fields = 49), stat(fields = 20), stat(ticks = "0"),
            stat(ticks = "-1"), stat(ticks = "9223372036854775808"), stat().replace(" S ", " ? "),
            stat().replace(" S ", " S  "), stat().replace(" (", "("), stat("bad\nname"),
            "x".repeat(4097) + "\n", stat().replace(" 0 ", " invalid ")).forEach {
            assertNull(GmsHeartbeatCallerStatParser.startTicks(it, 1234), it.take(100))
        }
        assertNull(GmsHeartbeatCallerStatParser.startTicks(stat(), 1235))
    }
}
