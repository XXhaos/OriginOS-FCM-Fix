package io.github.originosfcmguardian.core.gms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GmsHeartbeatAutomaticTest {
    private val sample = GmsConnectionObservation.Sample(1, 10208, 99, 100, 200, "a".repeat(64),
        "11111111-2222-3333-4444-555555555555", 1000, 900,
        SocketSource(SourceCompleteness.UNAVAILABLE, false), SocketSource(SourceCompleteness.UNAVAILABLE, false), false, emptyList())
    @Test fun `binding digest covers installation and signer but is independent of observation timestamp`() {
        val digest = gmsHeartbeatBindingDigest(sample)
        assertEquals(64, digest.length)
        assertEquals(digest, gmsHeartbeatBindingDigest(sample.copy(observedAtMillis = 2000, requestId = 2)))
        listOf(sample.copy(uid = 10209), sample.copy(versionCode = 100), sample.copy(firstInstallTime = 101),
            sample.copy(lastUpdateTime = 201), sample.copy(signerSha256 = "b".repeat(64))).forEach {
            assertNotEquals(digest, gmsHeartbeatBindingDigest(it))
        }
    }
    @Test fun `arm codec is bounded and only roundtrips typed session authority`() {
        val session = GmsAutoSession("11111111-2222-3333-4444-555555555555", 7, gmsHeartbeatBindingDigest(sample))
        val armed = GmsAutoArmResult(session, GmsAutoArmReason.ARMED)
        assertEquals(armed, GmsAutoArmCodec.decode(GmsAutoArmCodec.encode(1, armed), 1))
        val rejected = GmsAutoArmResult(null, GmsAutoArmReason.IDENTITY_CHANGED)
        assertEquals(rejected, GmsAutoArmCodec.decode(GmsAutoArmCodec.encode(1, rejected), 1))
        listOf(null, "x".repeat(257), GmsAutoArmCodec.encode(2, armed), "1|1|BUSY|unexpected|||",
            "1|1|ARMED|bad-id|7|${session.bindingDigest}").forEach {
            assertEquals(GmsAutoArmResult(null, GmsAutoArmReason.MALFORMED_RESPONSE), GmsAutoArmCodec.decode(it, 1))
        }
        assertEquals(900_000L, GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS)
    }
}
