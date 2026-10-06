package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerCandidateReaderTest {
    @Test fun fixedArgvSuccessfulExitAndEofReturnOnlyUnknownCandidates() {
        assertEquals(listOf("/system/bin/dumpsys", "package", "--include-apex", "packages"), DozeOwnerCandidateReader.FIXED_ARGV)
        val process = FakeProcess()
        val events = ArrayList<Process?>()
        val result = reader(process, onProcess = { events += it }).read(setOf(0))
        assertTrue(result.parsedComplete)
        assertFalse(result.inventoryQualified)
        assertFalse(result.matchingRawAppId(10_123).exclusiveOwner)
        assertEquals(listOf(process, null), events)
    }

    @Test fun nonzeroExitAndAnyStderrIncludingWhitespaceReject() {
        for (process in listOf(FakeProcess(exit = 1), FakeProcess(error = " "), FakeProcess(error = "denied"))) {
            assertThrows(Exception::class.java) { reader(process).read(setOf(0)) }
        }
    }

    @Test fun oversizedStderrAndRawStdoutReject() {
        for (process in listOf(FakeProcess(error = "x".repeat(4_097)),
            FakeProcess(input = GeneratedDozeOwnerDump(DozeOwnerCandidateParser.RAW_BYTES + 1)))) {
            assertThrows(Exception::class.java) { reader(process).read(setOf(0)) }
        }
    }

    @Test fun generatedLargerAndExactCapReadsRequireActualSuccessfulExitAndEof() {
        for (bytes in listOf(4 * 1024 * 1024 + 1, DozeOwnerCandidateParser.RAW_BYTES)) {
            val input = GeneratedDozeOwnerDump(bytes)
            val result = reader(FakeProcess(input = input)).read(setOf(0))
            assertEquals(bytes, input.consumedBytes)
            assertEquals(1, result.records.size)
            assertFalse(result.inventoryQualified)
        }
    }

    @Test fun successfulFixedReadRetainsUnresolvedAppIdAsUnknownCandidates() {
        val raw = "Packages:\n" + DozeOwnerCandidatesTest.record("unknown.pkg", -1, retained = true) +
            "Hidden system packages:\n" + DozeOwnerCandidatesTest.record("unknown.pkg", -1)
        val result = reader(FakeProcess(out = raw)).read(setOf(0))
        val matches = result.matchingRawAppId(10_123)
        assertEquals(2, matches.candidates.size)
        assertTrue(matches.hasUnresolvedAppId)
        assertFalse(result.inventoryQualified)
        assertFalse(matches.exclusiveOwner)
    }

    @Test fun realTimeoutPermissionMetadataIsAcceptedWithoutWeakeningTimeoutFailure() {
        val raw = valid() + "      android.permission.SCREEN_TIMEOUT_OVERRIDE: prot=signature\n"
        assertFalse(reader(FakeProcess(out = raw)).read(setOf(0)).inventoryQualified)
        assertThrows(Exception::class.java) { reader(FakeProcess(out = raw + "      timeout:\n")).read(setOf(0)) }
    }

    @Test fun errorPermissionAndMalformedStructureCannotSucceedAtExitZero() {
        for (out in listOf("Permission Denial: rejected\n", valid() + "Error: failed\n", valid().dropLast(20))) {
            assertThrows(Exception::class.java) { reader(FakeProcess(out = out)).read(setOf(0)) }
        }
    }

    @Test fun cancelledBeforeStartDoesNotCreateAProcess() {
        var created = false
        val reader = DozeOwnerCandidateReader(mayContinue = { false }, startFixedProcess = { created = true; FakeProcess() })
        assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
        assertFalse(created)
    }

    @Test fun callbackCancellationDestroysProcessAndClearsOwnership() {
        var active = true
        val events = ArrayList<Process?>()
        val process = FakeProcess(completes = false)
        val reader = DozeOwnerCandidateReader(mayContinue = { active }, startFixedProcess = { process },
            onProcess = { events += it; if (it != null) active = false })
        assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
        assertTrue(process.destroyed)
        assertEquals(listOf(process, null), events)
    }

    @Test fun commandTimeoutAndShorterRequestBudgetDestroyProcess() {
        for (budget in listOf(40_000L, 150L)) {
            val time = AtomicLong()
            val process = FakeProcess(completes = false, advance = { time.addAndGet(TimeUnit.MILLISECONDS.toNanos(it)) })
            val reader = DozeOwnerCandidateReader(remainingMillis = { budget }, startFixedProcess = { process }, nanoTime = time::get)
            assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
            assertTrue(process.destroyed)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(time.get()) <= minOf(budget, 6_000L))
        }
    }

    @Test fun lateProcessCreationAndLateExitReject() {
        for (lateStart in listOf(true, false)) {
            val time = AtomicLong()
            val process = FakeProcess(advance = { if (!lateStart) time.set(TimeUnit.MILLISECONDS.toNanos(6_001)) })
            val reader = DozeOwnerCandidateReader(startFixedProcess = {
                if (lateStart) time.set(TimeUnit.MILLISECONDS.toNanos(6_001))
                process
            }, nanoTime = time::get)
            assertThrows(Exception::class.java) { reader.read(setOf(0)) }
        }
    }

    @Test fun processExitWithoutStdoutEofStillTimesOut() {
        val time = AtomicLong()
        val input = object : InputStream() {
            private val prefix = ByteArrayInputStream(valid().toByteArray())
            private var prefixDone = false
            override fun read(): Int = error("Bulk read only")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (!prefixDone) {
                    val count = prefix.read(buffer, off, len)
                    if (count >= 0) return count
                    prefixDone = true
                }
                while (!Thread.currentThread().isInterrupted) Thread.sleep(5)
                throw java.io.IOException("interrupted")
            }
        }
        val calls = AtomicInteger()
        val process = FakeProcess(input = input)
        val reader = DozeOwnerCandidateReader(startFixedProcess = { process }, nanoTime = {
            if (calls.incrementAndGet() > 8) time.set(TimeUnit.MILLISECONDS.toNanos(6_001))
            time.get()
        })
        assertThrows(Exception::class.java) { reader.read(setOf(0)) }
    }

    @Test fun cancellationDuringDrainRejectsLateData() {
        var active = true
        val input = object : ByteArrayInputStream(valid().toByteArray()) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = super.read(buffer, off, len)
                active = false
                return count
            }
        }
        assertThrows(Exception::class.java) {
            DozeOwnerCandidateReader(mayContinue = { active }, startFixedProcess = { FakeProcess(input = input) }).read(setOf(0))
        }
    }

    @Test fun callbackExceptionStillReleasesProcessWithoutReplacingOriginalError() {
        val process = FakeProcess(completes = false)
        val reader = DozeOwnerCandidateReader(startFixedProcess = { process }, onProcess = { error("callback failure") })
        val failure = assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
        assertEquals("callback failure", failure.message)
        assertTrue(process.destroyed)
    }

    @Test fun concurrentReadIsRejectedUntilTheFirstProcessRegistrationIsCleared() {
        val registered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val created = AtomicInteger()
        val reader = DozeOwnerCandidateReader(startFixedProcess = { created.incrementAndGet(); FakeProcess() },
            onProcess = { process ->
                if (created.get() == 1) {
                    if (process != null) {
                        registered.countDown()
                        check(finish.await(2, TimeUnit.SECONDS))
                    } else {
                        cleanupStarted.countDown()
                        check(cleanupFinished.await(2, TimeUnit.SECONDS))
                    }
                }
            })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<DozeOwnerCandidates> { reader.read(setOf(0)) }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
            assertEquals(1, created.get())
            finish.countDown()
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
            assertEquals(1, created.get())
            cleanupFinished.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS).parsedComplete)
            assertTrue(reader.read(setOf(0)).parsedComplete)
            assertEquals(2, created.get())
        } finally {
            finish.countDown()
            cleanupFinished.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun callbackFailureReleasesAdmissionForTheNextRead() {
        val created = AtomicInteger()
        val reader = DozeOwnerCandidateReader(startFixedProcess = { created.incrementAndGet(); FakeProcess() },
            onProcess = { if (it != null && created.get() == 1) error("first callback failed") })
        assertThrows(IllegalStateException::class.java) { reader.read(setOf(0)) }
        assertTrue(reader.read(setOf(0)).parsedComplete)
        assertEquals(2, created.get())
    }

    @Test fun factoryAndClockFailuresReleaseAdmissionForTheNextRead() {
        val factoryCalls = AtomicInteger()
        val factoryReader = DozeOwnerCandidateReader(startFixedProcess = {
            if (factoryCalls.incrementAndGet() == 1) error("first factory failed")
            FakeProcess()
        })
        assertThrows(IllegalStateException::class.java) { factoryReader.read(setOf(0)) }
        assertTrue(factoryReader.read(setOf(0)).parsedComplete)
        val clockCalls = AtomicInteger()
        val clockReader = DozeOwnerCandidateReader(startFixedProcess = { FakeProcess() }, nanoTime = {
            if (clockCalls.incrementAndGet() == 1) error("first clock failed")
            System.nanoTime()
        })
        assertThrows(IllegalStateException::class.java) { clockReader.read(setOf(0)) }
        assertTrue(clockReader.read(setOf(0)).parsedComplete)
    }

    private fun reader(process: Process, onProcess: (Process?) -> Unit = {}) =
        DozeOwnerCandidateReader(startFixedProcess = { process }, onProcess = onProcess)

    private class FakeProcess(out: String = valid(), error: String = "", val exit: Int = 0,
        private val completes: Boolean = true, private val advance: (Long) -> Unit = {},
        private val input: InputStream = ByteArrayInputStream(out.toByteArray())) : Process() {
        var destroyed = false
        private var alive = true
        private val stderr = ByteArrayInputStream(error.toByteArray())
        private val stdin = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun waitFor(): Int = error("Unbounded process wait forbidden")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            advance(unit.toMillis(timeout))
            if (completes) alive = false
            return completes
        }
        override fun exitValue(): Int { check(!alive); return exit }
        override fun isAlive(): Boolean = alive
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    companion object {
        private fun valid() = "Packages:\n" + DozeOwnerCandidatesTest.record()
    }
}
