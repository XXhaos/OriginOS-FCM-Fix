package io.github.originosfcmguardian.core.gms

import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FcmBindingRepresentationTest {
    private val sample = GmsConnectionObservation.Sample(1, 10208, 264131035, 100, 200,
        FcmRecoveryPolicy.SIGNER, "12345678-1234-1234-1234-123456789abc", 1000, 500,
        SocketSource(SourceCompleteness.COMPLETE, true), SocketSource(SourceCompleteness.COMPLETE, false),
        true, emptyList())

    /** Frozen pre-v98 encoding: an existing on-disk binding must remain usable. */
    private fun legacy(value: GmsConnectionObservation.Sample): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(value.uid, value.versionCode, value.firstInstallTime, value.lastUpdateTime,
            value.signerSha256, value.bootId).joinToString("|").toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun `existing binding golden value and every identity field remain exact`() {
        assertEquals("8c2abb01880b6c98a21e9b65bbd0c399bce95a929a940f108dfb0282da41be2c",
            FcmRecoveryPolicy.binding(sample))
        val changes = listOf(sample.copy(uid = 99999), sample.copy(versionCode = Long.MAX_VALUE),
            sample.copy(firstInstallTime = Long.MAX_VALUE), sample.copy(lastUpdateTime = Long.MAX_VALUE),
            sample.copy(signerSha256 = "0".repeat(64)),
            sample.copy(bootId = "abcdef01-4321-4321-4321-cba987654321"))
        for (changed in changes) {
            assertEquals(legacy(changed), FcmRecoveryPolicy.binding(changed))
            assertNotEquals(FcmRecoveryPolicy.binding(sample), FcmRecoveryPolicy.binding(changed))
        }
    }

    @Test fun `transient observation changes do not alter persistent binding`() {
        val changed = sample.copy(requestId = 99, observedAtMillis = 9999, elapsedRealtimeMillis = 999,
            tcp = SocketSource(SourceCompleteness.UNAVAILABLE, false), receiverInspectionComplete = false)
        assertEquals(FcmRecoveryPolicy.binding(sample), FcmRecoveryPolicy.binding(changed))
        for (index in 0..255) {
            val value = sample.copy(uid = 10000 + index, versionCode = index.toLong(),
                firstInstallTime = index + 1L, lastUpdateTime = Long.MAX_VALUE - index,
                signerSha256 = index.toString(16).padStart(2, '0').repeat(32))
            assertEquals(legacy(value), FcmRecoveryPolicy.binding(value))
        }
    }

    @Test fun `optional binding allocation timing sample`() {
        if (System.getenv("GUARDIAN_BINDING_BENCHMARK") != "1") return
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true) bean.isThreadAllocatedMemoryEnabled = true
        val values = (0..63).map { sample.copy(versionCode = sample.versionCode + it) }
        repeat(1000) { legacy(values[it % values.size]); FcmRecoveryPolicy.binding(values[it % values.size]) }
        val iterations = 2000
        for ((name, encoder) in listOf("legacy" to ::legacy, "current" to FcmRecoveryPolicy::binding)) {
            val allocatedBefore = bean?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1
            val started = System.nanoTime()
            var checksum = 0
            repeat(iterations) { checksum += encoder(values[it % values.size])[0].code }
            val elapsed = System.nanoTime() - started
            val allocated = bean?.getThreadAllocatedBytes(Thread.currentThread().id)?.minus(allocatedBefore) ?: -1
            println("binding-benchmark encoder=$name iterations=$iterations checksum=$checksum " +
                "nanosPerBinding=${elapsed / iterations} bytesPerBinding=${if (allocated < 0) -1 else allocated / iterations}")
        }
    }
}
