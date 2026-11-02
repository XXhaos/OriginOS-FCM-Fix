package io.github.originosfcmguardian.core.gms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FcmReportedConnectionPolicyTest {
    private val source = GcmServiceSource(GcmConnectionState.REPORTED_CONNECTED,
        SourceCompleteness.COMPLETE, 1_000, 100, 0)
    private val sample = GmsConnectionObservation.Sample(1, 10208, 99, 100, 900,
        FcmRecoveryPolicy.SIGNER, "12345678-1234-1234-1234-123456789abc", 1_000, 100,
        SocketSource(SourceCompleteness.UNAVAILABLE, false), SocketSource(SourceCompleteness.UNAVAILABLE, false),
        true, emptyList(), source)

    @Test fun exactServiceConnectionDoesNotInventSocketEvidenceOrDisconnectedAdmission() {
        assertTrue(FcmRecoveryPolicy.reportedConnected(sample, 100))
        assertNull(GmsHeartbeatPolicy.rejection(sample, 100))
        assertFalse(FcmRecoveryPolicy.connected(sample, 100))
        assertEquals(SocketObservation.UNKNOWN, sample.observation)
        val disconnected = sample.copy(gcmService = source.copy(state = GcmConnectionState.REPORTED_DISCONNECTED))
        assertFalse(FcmRecoveryPolicy.reportedConnected(disconnected, 100))
        assertTrue(FcmRecoveryPolicy.reportedDisconnected(disconnected, 100))
        assertTrue(FcmRecoveryPolicy.mcsRequestEligible(disconnected, 100))
        assertFalse(FcmRecoveryPolicy.disconnected(disconnected, 100))
        assertTrue(FcmRecoveryPolicy.disconnected(disconnected.copy(
            tcp = SocketSource(SourceCompleteness.COMPLETE, false),
            tcp6 = SocketSource(SourceCompleteness.COMPLETE, false)), 100))
    }

    @Test fun boundedMcsUsesDirectCompleteDisconnectedAuthorityButAnyPositiveSocketVetoesIt() {
        val disconnected = sample.copy(gcmService = source.copy(state = GcmConnectionState.REPORTED_DISCONNECTED))
        for (status in SourceCompleteness.entries) {
            val unknownSocket = disconnected.copy(tcp = SocketSource(status, false), tcp6 = SocketSource(status, false))
            assertTrue(FcmRecoveryPolicy.mcsRequestEligible(unknownSocket, 100))
            assertEquals(if (status == SourceCompleteness.COMPLETE) SocketObservation.NO_MATCH else SocketObservation.UNKNOWN,
                unknownSocket.observation)
        }
        for (status in listOf(SourceCompleteness.COMPLETE, SourceCompleteness.PARTIAL)) {
            assertFalse(FcmRecoveryPolicy.mcsRequestEligible(disconnected.copy(tcp = SocketSource(status, true)), 100))
            assertFalse(FcmRecoveryPolicy.mcsRequestEligible(disconnected.copy(tcp6 = SocketSource(status, true)), 100))
        }
        for (unsafe in listOf(sample, disconnected.copy(signerSha256 = "a".repeat(64)),
            disconnected.copy(gcmService = GcmServiceSource.unavailable()),
            disconnected.copy(gcmService = source.copy(state = GcmConnectionState.SERVICE_ABSENT)),
            disconnected.copy(gcmService = source.copy(state = GcmConnectionState.UNKNOWN,
                completeness = SourceCompleteness.PARTIAL)),
            disconnected.copy(gcmService = disconnected.gcmService.copy(elapsedRealtimeMillis = 101)))) {
            assertFalse(FcmRecoveryPolicy.mcsRequestEligible(unsafe, 100))
        }
        assertFalse(FcmRecoveryPolicy.mcsRequestEligible(disconnected, 30_101))
    }

    @Test fun serviceConnectionRequiresOfficialAuthorityAndBothCurrentCompleteTimestamps() {
        for (unsafe in listOf(sample.copy(signerSha256 = "a".repeat(64)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.SERVICE_ABSENT)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.UNKNOWN)),
            sample.copy(gcmService = source.copy(state = GcmConnectionState.UNKNOWN,
                completeness = SourceCompleteness.PARTIAL)),
            sample.copy(gcmService = source.copy(elapsedRealtimeMillis = 101)),
            sample.copy(elapsedRealtimeMillis = 101), sample.copy(gcmService = GcmServiceSource.unavailable()))) {
            assertFalse(FcmRecoveryPolicy.reportedConnected(unsafe, 100))
        }
        assertFalse(FcmRecoveryPolicy.reportedConnected(sample, 30_101))
        assertFalse(FcmRecoveryPolicy.reportedConnected(sample.copy(elapsedRealtimeMillis = 30_100), 30_101))
    }
}
