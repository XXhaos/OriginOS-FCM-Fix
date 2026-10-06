package io.github.originosfcmguardian.core.gms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GmsHeartbeatTest {
    private fun sample(state: GcmConnectionState = GcmConnectionState.REPORTED_CONNECTED,
                       completeness: SourceCompleteness = SourceCompleteness.COMPLETE) =
        GmsConnectionObservation.Sample(1, 10208, 263436035, 100, 200, "a".repeat(64),
            "11111111-2222-3333-4444-555555555555", 1000, 500,
            SocketSource(SourceCompleteness.UNAVAILABLE, false), SocketSource(SourceCompleteness.UNAVAILABLE, false),
            false, emptyList(), GcmServiceSource(state, completeness, 1100, 600, 0))

    @Test fun `only fresh complete reported connected is eligible independently of TCP`() {
        assertNull(GmsHeartbeatPolicy.rejection(sample(), 600))
        assertEquals(GmsHeartbeatReason.REPORTED_DISCONNECTED,
            GmsHeartbeatPolicy.rejection(sample(GcmConnectionState.REPORTED_DISCONNECTED), 600))
        assertEquals(GmsHeartbeatReason.OBSERVATION_UNKNOWN,
            GmsHeartbeatPolicy.rejection(sample(GcmConnectionState.UNKNOWN, SourceCompleteness.TIMED_OUT), 600))
        assertEquals(GmsHeartbeatReason.STALE_OBSERVATION, GmsHeartbeatPolicy.rejection(sample(), 599))
        assertEquals(GmsHeartbeatReason.STALE_OBSERVATION, GmsHeartbeatPolicy.rejection(sample(), 30601))
        assertEquals(GmsHeartbeatReason.OBSERVATION_UNKNOWN,
            GmsHeartbeatPolicy.rejection(sample().copy(gcmService = GcmServiceSource.unavailable()), 600))
    }
    @Test fun `identity user and cancellation rejection remain unsent prerequisites`() {
        listOf(GmsUnknown.WRONG_USER to GmsHeartbeatReason.WRONG_USER,
            GmsUnknown.IDENTITY_CHANGED to GmsHeartbeatReason.IDENTITY_CHANGED,
            GmsUnknown.SHARED_UID to GmsHeartbeatReason.SHARED_UID,
            GmsUnknown.CANCELLED to GmsHeartbeatReason.CANCELLED).forEach { (unknown, reason) ->
            assertEquals(reason, GmsHeartbeatPolicy.rejection(GmsConnectionObservation.Unknown(1, unknown), 600))
        }
    }
    @Test fun `small codec carries only structural outcome and rejects malformed or wrong request`() {
        listOf(GmsHeartbeatResult.notSent(7, GmsHeartbeatReason.BUSY),
            GmsHeartbeatResult(7, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED),
            GmsHeartbeatResult.unknown(7, GmsHeartbeatReason.TIMED_OUT)).forEach {
            assertEquals(it, GmsHeartbeatCodec.decode(GmsHeartbeatCodec.encode(it), 7))
        }
        listOf(null, "1|8|REQUESTED|COMMAND_ACCEPTED", "1|7|REQUESTED|COMMAND_FAILED",
            "1|7|NOT_SENT|COMMAND_ACCEPTED", "1|7|UNKNOWN|private address", "x".repeat(161)).forEach {
            assertEquals(GmsHeartbeatResult.unknown(7, GmsHeartbeatReason.MALFORMED_RESPONSE), GmsHeartbeatCodec.decode(it, 7))
        }
    }
}
