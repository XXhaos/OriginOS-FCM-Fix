package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerReadOnlySessionTest {
    @Test fun successAndErrorExportOnlySmallUnknownSummary() {
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val result = session.read { parsed() }
        assertEquals(DozeOwnerReadOnlyCode.PARSED, result.code)
        assertEquals("UNKNOWN", result.inventory)
        assertTrue(result.isValidReadOnlySummary())
        val failure = session.read { error("private package dump and exception detail") }
        assertEquals(DozeOwnerReadOnlyCode.REJECTED, failure.code)
        assertEquals("", failure.normalizedSha256)
        assertFalse(failure.toString().contains("private"))
    }

    @Test fun cancellationBeforeReadCannotStartWork() {
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        session.cancel()
        val result = session.read { error("Must never run") }
        assertEquals(DozeOwnerReadOnlyCode.CANCELLED, result.code)
    }

    @Test fun cancelledLateReadCannotPublishAndProcessIsDestroyed() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val process = FakeProcess()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<DozeOwnerReadOnlySummary> {
                session.read { scope ->
                    scope.onProcess(process)
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    parsed()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.cancel()
            assertTrue(process.destroyed)
            release.countDown()
            val result = future.get(2, TimeUnit.SECONDS)
            assertEquals(DozeOwnerReadOnlyCode.CANCELLED, result.code)
            assertEquals("", result.normalizedSha256)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun singleFlightRejectsOverlapAndAllowsFollowingSuccessfulRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<DozeOwnerReadOnlySummary> {
                session.read { calls.incrementAndGet(); entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); parsed() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(DozeOwnerReadOnlyCode.BUSY, session.read { calls.incrementAndGet(); parsed() }.code)
            assertEquals(1, calls.get())
            release.countDown()
            assertEquals(DozeOwnerReadOnlyCode.PARSED, first.get(2, TimeUnit.SECONDS).code)
            assertEquals(DozeOwnerReadOnlyCode.PARSED, session.read { parsed() }.code)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun sharedFortySecondDeadlineRejectsLateParsedCandidates() {
        val time = AtomicLong()
        val session = DozeOwnerReadOnlySession(wallTime = { 100 }, nanoTime = time::get)
        val result = session.read {
            time.set(TimeUnit.MILLISECONDS.toNanos(40_000))
            parsed()
        }
        assertEquals(DozeOwnerReadOnlyCode.DEADLINE, result.code)
        assertEquals(0, result.activeRecords)
    }

    @Test fun lateProcessRegistrationAfterCancelIsDestroyed() {
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val process = FakeProcess()
        val result = session.read { scope -> session.cancel(); scope.onProcess(process); parsed() }
        assertEquals(DozeOwnerReadOnlyCode.CANCELLED, result.code)
        assertTrue(process.destroyed)
    }

    @Test fun sharedBindLifecycleRemovesEarlyAndLateDispatchWithNoNewBindingFramework() {
        val removals = AtomicInteger()
        val lifecycle = DozeBindLifecycle { removals.incrementAndGet() }
        lifecycle.beforeDispatch()
        lifecycle.close()
        lifecycle.afterReturn()
        lifecycle.close()
        assertEquals(2, removals.get())
        assertThrows(IllegalStateException::class.java) { lifecycle.beforeDispatch() }
    }

    @Test fun sharedExpiryStaysSixtyFiveSecondsAndNeverExitsAppUid() {
        val exits = AtomicInteger()
        var delay = 0L
        var expired: (() -> Unit)? = null
        val scheduler = DozeExpiryScheduler { millis, action ->
            delay = millis; expired = action; DozeExpiryCancellation { }
        }
        val expiry = DozeServiceExpiry(2_000, { exits.incrementAndGet() }, scheduler)
        assertEquals(65_000L, delay)
        requireNotNull(expired).invoke()
        requireNotNull(expired).invoke()
        assertEquals(1, exits.get())
        assertFalse(expiry.terminateNow())
        val notShell = DozeServiceExpiry(10_123, { error("App UID cannot exit") }, scheduler)
        assertFalse(notShell.terminateNow())
    }

    private fun parsed() = DozeOwnerCandidateParser.parse(
        ByteArrayInputStream(("Packages:\n" + DozeOwnerCandidatesTest.record()).toByteArray()), setOf(0))

    private class FakeProcess : Process() {
        var destroyed = false
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
