package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.io.FileNotFoundException
import java.nio.file.Files

class ProtectionResourceJournalTest {
    private fun sample(at: Long = 10) = ProtectionResourceSample("00000000-0000-0000-0000-000000000001", 100,
        1, 1, at, at)
    private fun exit(at: Long = 10, pid: Int = 100) = ProtectionResourceExit(pid, at, 3, 0, 200, 123, null)

    @Test fun `startup skips identical resource history but persists exit coverage and counter changes`() {
        val prior = mergeProtectionResourceExits(ProtectionResourceSnapshot(), listOf(exit()))
        val written = mutableListOf<ProtectionResourceSnapshot>()
        persistChangedProtectionResources(prior, prior.copy(), written::add)
        assertTrue(written.isEmpty())
        val newExit = mergeProtectionResourceExits(prior, listOf(exit(), exit(at = 11)))
        persistChangedProtectionResources(prior, newExit, written::add)
        val unknownCoverage = mergeProtectionResourceExits(newExit, null)
        persistChangedProtectionResources(newExit, unknownCoverage, written::add)
        val failed = unknownCoverage.copy(failed = 1)
        persistChangedProtectionResources(unknownCoverage, failed, written::add)
        assertEquals(listOf(newExit, unknownCoverage, failed), written)
    }

    @Test fun `both rings retain bounded evidence and exact eviction counters`() {
        var value = ProtectionResourceSnapshot()
        repeat(1030) { value = appendProtectionResource(value, sample(it + 10L)) }
        val exits = List(64) { exit(it + 1L) }
        value = mergeProtectionResourceExits(value, exits)
        assertEquals(1024, value.samples.size); assertEquals(32, value.exits.size)
        assertEquals(6L, value.evictedSamples); assertEquals(32L, value.evictedExits)
        assertEquals(value, mergeProtectionResourceExits(value, exits))
        assertEquals(value, ProtectionResourceCodec.decode(ProtectionResourceCodec.encode(value)))
    }

    @Test fun `equal time exits remain distinct and missing history is explicitly unavailable`() {
        val first = mergeProtectionResourceExits(ProtectionResourceSnapshot(), listOf(exit()))
        val second = mergeProtectionResourceExits(first, listOf(exit(), exit(pid = 101), exit(at = 5)))
        assertEquals(2, second.exits.size)
        assertEquals(second, mergeProtectionResourceExits(second, listOf(exit(), exit(pid = 101))))
        assertFalse(mergeProtectionResourceExits(second, null).exitHistoryAvailable)
        assertThrows(IllegalArgumentException::class.java) { exit().copy(rssKiB = 0) }
    }

    @Test fun `evicted equal watermark tuples do not inflate counts on repeated history`() {
        val exits = List(64) { exit(100, it + 1) }
        val first = mergeProtectionResourceExits(ProtectionResourceSnapshot(), exits)
        assertEquals(32L, first.evictedExits)
        assertEquals(64, first.exitHistoryBoundary.size)
        assertEquals(first, mergeProtectionResourceExits(first, exits))
        assertEquals(first, ProtectionResourceCodec.decode(ProtectionResourceCodec.encode(first)))
    }

    @Test fun `all maximum allowed fields fit the independent byte cap`() {
        val name = "A".repeat(32)
        val full = sample(Long.MAX_VALUE).copy(versionCode = Long.MAX_VALUE, rssKiB = Long.MAX_VALUE,
            threads = Long.MAX_VALUE, heapUsedBytes = Long.MAX_VALUE, heapMaxBytes = Long.MAX_VALUE,
            cpuTimeMillis = Long.MAX_VALUE, nativeAllocatedBytes = Long.MAX_VALUE, systemAvailableBytes = Long.MAX_VALUE,
            systemThresholdBytes = Long.MAX_VALUE, automaticLastCheckedAtMillis = Long.MAX_VALUE,
            fcmObservationAtMillis = Long.MAX_VALUE, fcmObservationElapsedMillis = Long.MAX_VALUE,
            recoveryActionAtMillis = Long.MAX_VALUE, selectedCount = 1024, readerActive = 8, readerPoolSize = 8,
            pollIterations = Long.MAX_VALUE, readerTimeouts = Long.MAX_VALUE, readerRejections = Long.MAX_VALUE,
            systemLowMemory = true, interactive = true, automaticBusy = true, fcmConfigured = true, recoveryReady = true,
            heartbeatConfigured = true, heartbeatActive = true, heartbeatPaused = true, heartbeatStopPending = true,
            heartbeatResumeBlocked = true, heartbeatRequiresConfirmation = true, fcmService = name,
            fcmCompleteness = name, fcmSocket = name, maintenance = name, heartbeatLastStatus = name, recoveryAction = name)
        val value = ProtectionResourceSnapshot(ProtectionResourceAvailability.AVAILABLE, List(1024) { full }, List(32) { exit() },
            exitHistoryBoundary = List(64) { ProtectionResourceExitKey(it + 1, 3, Int.MAX_VALUE) })
        val bytes = ProtectionResourceCodec.encode(value)
        assertTrue(bytes.size <= 524_288)
        assertEquals(value, ProtectionResourceCodec.decode(bytes))
    }

    @Test fun `empty old installation and unavailable fields roundtrip without invented zeros`() {
        val empty = ProtectionResourceCodec.decode(ProtectionResourceCodec.encode(ProtectionResourceSnapshot()))
        assertEquals(ProtectionResourceAvailability.EMPTY, empty.availability)
        val value = appendProtectionResource(empty, sample())
        assertEquals(value, ProtectionResourceCodec.decode(ProtectionResourceCodec.encode(value)))
        assertNull(value.samples.single().rssKiB); assertNull(value.samples.single().systemLowMemory)
        assertNull(value.samples.single().fcmObservationAtMillis)
        assertThrows(IllegalArgumentException::class.java) { sample().copy(fcmObservationAtMillis = 1) }
    }

    @Test fun `corrupt checksum oversized and rejected diagnostic states cannot serialize as healthy`() {
        val bytes = ProtectionResourceCodec.encode(appendProtectionResource(ProtectionResourceSnapshot(), sample()))
        val bad = bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        val originalBad = bad.copyOf()
        assertThrows(IllegalArgumentException::class.java) { ProtectionResourceCodec.decode(bad) }
        assertArrayEquals(originalBad, bad)
        assertThrows(IllegalArgumentException::class.java) { ProtectionResourceCodec.decode(ByteArray(524_289)) }
        assertThrows(IllegalArgumentException::class.java) { ProtectionResourceCodec.encode(ProtectionResourceSnapshot(ProtectionResourceAvailability.CORRUPT)) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(fcmService = "arbitrary raw data") }
    }

    @Test fun `shared storage reader returns empty only for missing artifacts and preserves corrupt bytes`() {
        assertEquals(ProtectionResourceSnapshot(), readProtectionResourceStorage({ throw FileNotFoundException() }, { false }))
        assertThrows(IllegalStateException::class.java) { readProtectionResourceStorage({ throw FileNotFoundException() }, { true }) }
        val file = Files.createTempFile("guardian-resource-corrupt", ".bin").toFile()
        try {
            val bad = byteArrayOf(1, 2, 3, 4)
            file.writeBytes(bad)
            assertThrows(ProtectionResourceCorruption::class.java) { readProtectionResourceStorage({ file.inputStream() }, { file.exists() }) }
            var writes = 0
            var captures = 0
            assertThrows(ProtectionResourceCorruption::class.java) {
                updateProtectionResourceStorage({ readProtectionResourceStorage({ file.inputStream() }, { file.exists() }) },
                    { writes++ }, { captures++; sample() }, 0, 0)
            }
            assertEquals(0, writes); assertEquals(0, captures)
            assertArrayEquals(bad, file.readBytes())
        } finally { assertTrue(file.delete()) }
    }

    @Test fun `admission has monotonic cadence one pending and backlog skips with no catchup`() {
        val admission = ProtectionResourceAdmission()
        assertFalse(admission.offer(1, backlog = true))
        assertTrue(admission.offer(1, backlog = false))
        assertFalse(admission.offer(300_001, backlog = false)) // Still pending.
        admission.finished()
        assertFalse(admission.offer(0, backlog = false))
        assertFalse(admission.offer(300_000, backlog = false))
        assertFalse(admission.offer(300_001, backlog = true))
        assertTrue(admission.offer(900_001, backlog = false)) // One sample, no replay.
        admission.finished()
        assertFalse(admission.offer(900_002, backlog = false))
        assertTrue(admission.offer(Long.MAX_VALUE, backlog = false))
        admission.finished()
        assertFalse(admission.offer(Long.MAX_VALUE, backlog = false))
    }

    @Test fun `diagnostic counters saturate without arithmetic failures`() {
        assertEquals(Long.MAX_VALUE, saturatedDiagnosticAdd(Long.MAX_VALUE - 1, 2))
        val counter = AtomicLong(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, counter.incrementDiagnostic())
    }

    @Test fun `proc self status exact fields reject unknown duplicate malformed and overflow`() {
        assertEquals(ProtectionProcStatus(2345, 24), parseProtectionProcStatus("Name:\tguardian\nVmRSS:\t2345 kB\nThreads:\t24\n"))
        assertEquals(ProtectionProcStatus(), parseProtectionProcStatus("VmRSS: 0 kB\nThreads: 0\n"))
        assertNull(parseProtectionProcStatus("VmRSS: 23 MB\n").rssKiB)
        assertNull(parseProtectionProcStatus("Threads: 4\nThreads: 5\n").threads)
        assertNull(parseProtectionProcStatus("VmRSS: 9999999999999999999 kB\n").rssKiB)
        assertThrows(IllegalArgumentException::class.java) { parseProtectionProcStatus("x".repeat(16_385)) }
    }
}
