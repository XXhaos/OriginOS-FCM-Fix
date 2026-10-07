package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autostart.*
import org.junit.Assert.*
import org.junit.Test

class PortableRuntimeReadTest {
    class OldPm { fun read(name: String, flags: Int, user: Int) = "$name:$flags:$user" }
    class NewPm { fun read(name: String, flags: Long, user: Int) = "$name:$flags:$user" }
    @Test fun flagsReadResolvesActualInterfaceAndRejectsNarrowing() {
        for (pm in listOf(OldPm(), NewPm())) {
            assertEquals("pkg:64:0", invokePackageFlagsRead(pm.javaClass, pm, "read",
                arrayOf(String::class.java), arrayOf("pkg"), 64L, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            invokePackageFlagsRead(OldPm::class.java, OldPm(), "read", arrayOf(String::class.java),
                arrayOf("pkg"), Int.MAX_VALUE.toLong() + 1, 0)
        }
    }
    @Test fun unknownFirmwareRemainsRealObservationAndWireBinding() {
        val fingerprint = "vivo/other/device:16/build/id:user/release-keys"
        val fields = StoppedPackageFields("example.recipient", "example.recipient", 10345, 1000,
            listOf(byteArrayOf(1, 2)), true, RecoveryTargetQualifications(true, true, false, false, false))
        val expected = requireNotNull(validatedStoppedPackage(fields, "example.recipient")).first
        val port = object : StoppedIdentityReadPort {
            override fun environment() = StoppedProbeEnvironment(fingerprint, 0, 2000)
            override fun telegramPackage(): StoppedPackageFields? = null
            override fun targetPackage(packageName: String) = fields
            override fun packagesForUid(uid: Int) = listOf("example.recipient")
        }
        val known = observeStoppedTarget(1, expected, port, nowMillis = { 2000 }) as StoppedTargetObservation.Known
        assertEquals(fingerprint, known.fingerprint)
        assertEquals(known, StoppedObservationCodec.decode(StoppedObservationCodec.encode(known), 1, expected))
    }
}
