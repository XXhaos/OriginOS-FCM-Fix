package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.BudgetClock
import io.github.originosfcmguardian.autoprotection.BudgetSnapshot
import io.github.originosfcmguardian.autoprotection.ProtectionAuthorityCodec
import io.github.originosfcmguardian.autoprotection.ProtectionAuthoritySnapshot
import io.github.originosfcmguardian.autoprotection.ProtectionConsentMode
import io.github.originosfcmguardian.autoprotection.ProtectionConsentStore
import io.github.originosfcmguardian.autoprotection.RecipientConsentToken
import io.github.originosfcmguardian.autoprotection.TargetConsent
import io.github.originosfcmguardian.autoprotection.TargetConsentLease
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.RecoveryJournalCodec
import io.github.originosfcmguardian.recovery.RecoveryJournalState
import io.github.originosfcmguardian.recovery.StoppedRecoveryJournal
import java.lang.management.ManagementFactory
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt in with GUARDIAN_RUN_READ_BENCHMARK=1 (or guardian.runReadBenchmark=1).
 * Host-only synthetic read-path scaling: real root/domain/consent/journal implementations,
 * cloned in-memory storage, no PackageManager, physical disk, device or battery measurements.
 * Times are descriptive, never correctness thresholds. Each repetition is one full target pass.
 */
class DaemonReadScalingBenchmarkTest {
    private class CountingStorage : DaemonProtectionRootStorage {
        private val backing = MemoryDaemonRootStorage()
        var reads = 0L
        var readBytes = 0L
        var writes = 0L
        override fun read(): ByteArray? = backing.read().also {
            reads++
            readBytes += it?.size ?: 0
        }
        override fun inspect(): ByteArray? = backing.inspect()
        override fun write(bytes: ByteArray) { writes++; backing.write(bytes) }
        override fun <T> withOwnerLease(action: () -> T): T = backing.withOwnerLease(action)
        fun reset() { reads = 0; readBytes = 0; writes = 0 }
    }

    private class Fixture(count: Int) {
        val leases = (1..count).map { index ->
            val identity = SelectedTargetIdentity("example.recipient%04d".format(Locale.ROOT, index),
                10_000 + index, 100, listOf("a".repeat(64)))
            TargetConsentLease(1, TargetConsent.Recipient(RecipientConsentToken(
                "12345678-1234-1234-1234-%012d".format(Locale.ROOT, index),
                1, 1_000, identity, VERIFIED_AUTOSTART_FINGERPRINT)))
        }
        private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1_000), emptyList(), null),
            targetConsents = leases.map { it.consent }, schemaVersion = 3, masterEnabledAtMillis = 1_000)
        val authorityBytes = ProtectionAuthorityCodec.encode(authority)
        private val snapshot = RootFixtures.snapshot(authorityBytes, mapOf(
            // Deliberately opaque: this benchmark never invokes the recovery-counts codec/store.
            DaemonDomainRole.RECOVERY_COUNTS to ByteArray(32 * 1024) { (it % 251).toByte() },
            DaemonDomainRole.STOPPED_JOURNAL to RecoveryJournalCodec.encode(RecoveryJournalState()),
        )).let { DaemonProtectionRootSnapshot(it.control.copy(mode = DaemonControlMode.ACTIVE), it.domains()) }
        val rootBytes = DaemonProtectionRootCodec.encode(snapshot).size
        val digest = snapshot.control.authorityDigest
        val storage = CountingStorage()
        val root = DaemonProtectionRoot(storage).also { it.initializeImported(snapshot) }
        private val domains = DaemonDomainStores(root)
        val consent = ProtectionConsentStore(domains.authority)
        val journal = StoppedRecoveryJournal(domains.recoveryJournal)

        fun cycle() {
            for (lease in leases) {
                assertEquals(digest, root.authorityDigest())
                assertTrue(consent.verifyAuthorized(lease))
                assertEquals(RecoveryJournalState(), journal.load())
            }
        }
    }

    private data class Sample(val wallNanos: Long, val cpuNanos: Long?, val allocatedBytes: Long?,
        val reads: Long, val readBytes: Long, val writes: Long)

    @Test fun syntheticReadPathScaling() {
        assumeTrue("Opt-in host benchmark", System.getenv("GUARDIAN_RUN_READ_BENCHMARK") == "1" ||
            System.getProperty("guardian.runReadBenchmark") == "1")
        val thread = ManagementFactory.getThreadMXBean()
        val cpuAvailable = thread.isCurrentThreadCpuTimeSupported && runCatching {
            if (!thread.isThreadCpuTimeEnabled) thread.isThreadCpuTimeEnabled = true
            thread.isThreadCpuTimeEnabled
        }.getOrDefault(false)
        val allocations = (thread as? com.sun.management.ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported && runCatching {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it.isThreadAllocatedMemoryEnabled
            }.getOrDefault(false)
        }
        val threadId = Thread.currentThread().id
        fun cpu() = if (cpuAvailable) thread.currentThreadCpuTime.takeIf { it >= 0 } else null
        fun allocated() = allocations?.getThreadAllocatedBytes(threadId)?.takeIf { it >= 0 }
        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        fun nullableMedian(values: List<Long?>) = values.filterNotNull().takeIf { it.size == values.size }?.let(::median)
        fun millis(nanos: Long?) = nanos?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "unavailable"

        println("READ_SCALING host=in_memory excludes=PM,physical_disk,phone_battery warmupCycles=3 measuredCycles=5 countsPayloadBytes=32768")
        for (count in listOf(24, 55, 207, 500)) {
            val fixture = Fixture(count)
            repeat(3) { fixture.cycle() }
            val samples = List(5) {
                fixture.storage.reset()
                val allocatedBefore = allocated()
                val cpuBefore = cpu()
                val wallBefore = System.nanoTime()
                fixture.cycle()
                val wallElapsed = System.nanoTime() - wallBefore
                val cpuAfter = cpu()
                val allocatedAfter = allocated()
                assertEquals("Reads must never persist anything", 0L, fixture.storage.writes)
                Sample(wallElapsed, cpuBefore?.let { before -> cpuAfter?.minus(before) },
                    allocatedBefore?.let { before -> allocatedAfter?.minus(before) },
                    fixture.storage.reads, fixture.storage.readBytes, fixture.storage.writes)
            }
            println("READ_SCALING targets=$count authorityBytes=${fixture.authorityBytes.size} rootBytes=${fixture.rootBytes} " +
                "medianWallMs=${millis(median(samples.map { it.wallNanos }))} " +
                "medianCpuMs=${millis(nullableMedian(samples.map { it.cpuNanos }))} " +
                "medianAllocatedBytes=${nullableMedian(samples.map { it.allocatedBytes }) ?: "unavailable"} " +
                "medianFreshReads=${median(samples.map { it.reads })} medianReadBytes=${median(samples.map { it.readBytes })} " +
                "medianStorageWrites=${median(samples.map { it.writes })}")
        }
    }
}
