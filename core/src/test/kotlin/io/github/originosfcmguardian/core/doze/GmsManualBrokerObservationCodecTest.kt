package io.github.originosfcmguardian.core.doze

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class GmsManualBrokerObservationCodecTest {
    @Test fun roundTripRetainsRawTextAndImmutableInstallations() {
        val value = observation()
        val encoded = GmsManualBrokerObservationCodec.encode(value, "firmware")
        val decoded = GmsManualBrokerObservationCodec.decode(encoded, "firmware")
        assertEquals(value, decoded)
        encoded.fill(0)
        assertEquals(value, decoded)
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded.identity.gms.signerSha256 as MutableList<String>).clear()
        }
    }

    @Test fun corruptionUnknownVersionAndTrailingBytesAreRejected() {
        val encoded = GmsManualBrokerObservationCodec.encode(observation(), "firmware")
        assertThrows(IllegalArgumentException::class.java) {
            GmsManualBrokerObservationCodec.decode(encoded.clone().apply { this[0] = 0 }, "firmware")
        }
        val body = encoded.copyOfRange(0, encoded.size - 32)
        for (invalid in listOf(body.clone().apply { this[7] = 2 }, body + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) {
                GmsManualBrokerObservationCodec.decode(invalid + MessageDigest.getInstance("SHA-256").digest(invalid), "firmware")
            }
        }
    }

    @Test fun unknownMappingWrongEnvironmentAndConflictingGmsRowsAreRejected() {
        val value = observation()
        for (wrong in listOf(value.copy(identity = value.identity.copy(owners = GmsGlobalOwnerEvidence.Unknown)),
            value.copy(identity = value.identity.copy(environment = value.identity.environment.copy(executionUid = 0))),
            value.copy(rawWhitelist = "user,com.google.android.gms,10209\n"))) {
            assertThrows(Exception::class.java) {
                GmsManualBrokerObservationCodec.encode(wrong, "firmware")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmsManualBrokerObservationCodec.decode(GmsManualBrokerObservationCodec.encode(value, "firmware"), "other-firmware")
        }
    }

    @Test fun oversizedFieldsAndUnpairedTextAreRejected() {
        val value = observation()
        for (raw in listOf("", "x".repeat(128 * 1024 + 1), "system,ordinary.pkg,1000\n\uD800")) {
            assertThrows(Exception::class.java) { GmsManualBrokerObservationCodec.encode(value.copy(rawWhitelist = raw), "firmware") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmsManualBrokerObservationCodec.decode(ByteArray(GmsManualBrokerObservationCodec.MAX_BYTES + 1), "firmware")
        }
    }

    private fun observation() = GmsGlobalDozeObservation(GmsGlobalDozeIdentity(
        GmsGlobalInstallation(10208, 37, 1, 2, listOf("a".repeat(64))),
        GmsGlobalGuardianBinding(0, GmsGlobalInstallation(10362, 57, 1, 2, listOf("b".repeat(64)))),
        GmsGlobalEnvironment("firmware", 2000, 0),
        GmsGlobalOwnerEvidence.Qualified(1, "c".repeat(64), 10208),
    ), "system,ordinary.pkg,1000\n")
}
