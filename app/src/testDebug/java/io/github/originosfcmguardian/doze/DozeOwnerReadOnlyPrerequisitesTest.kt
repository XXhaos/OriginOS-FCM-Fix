package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerReadOnlyPrerequisitesTest {
    @Test fun onlyFixedCallerAndReportedRosterCommandsExist() {
        assertEquals(listOf("/system/bin/pm", "list", "packages", "-U", "--user", "0"),
            DozeOwnerReadOnlyPrerequisites.Kind.CALLER_PACKAGES.argv)
        assertEquals(listOf("/system/bin/pm", "list", "users"), DozeOwnerReadOnlyPrerequisites.Kind.REPORTED_USERS.argv)
        val called = ArrayList<DozeOwnerReadOnlyPrerequisites.Kind>()
        val runner = DozeOwnerReadOnlyPrerequisites(scope(), startProcess = { called += it; FakeProcess(out = "fixed\n") })
        assertEquals("fixed\n", runner.readCallerPackages())
        assertEquals("fixed\n", runner.readReportedUsers())
        assertEquals(DozeOwnerReadOnlyPrerequisites.Kind.entries, called)
    }

    @Test fun exitStderrAndByteOverflowCannotBecomePrerequisiteData() {
        for (process in listOf(FakeProcess(exit = 1), FakeProcess(error = " "), FakeProcess(error = "x".repeat(4_097)),
            FakeProcess(out = "x".repeat(128 * 1024 + 1)))) {
            val runner = DozeOwnerReadOnlyPrerequisites(scope(), startProcess = { process })
            assertThrows(Exception::class.java) { runner.readReportedUsers() }
        }
    }

    @Test fun processAndLateDrainStayInsideIndependentSixSecondBudget() {
        for (complete in listOf(true, false)) {
            val time = AtomicLong()
            val process = FakeProcess(complete = complete, advance = {
                time.addAndGet(TimeUnit.MILLISECONDS.toNanos(if (complete) 6_001 else it))
            })
            val scope = DozeOwnerReadOnlySession.Scope(0, time::get)
            val runner = DozeOwnerReadOnlyPrerequisites(scope, startProcess = { process }, nanoTime = time::get)
            assertThrows(Exception::class.java) { runner.readReportedUsers() }
            if (!complete) assertTrue(process.destroyed)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(time.get()) <= if (complete) 6_001 else 6_000)
        }
    }

    @Test fun cancelledScopeDoesNotStartPrerequisiteProcess() {
        val scope = scope()
        scope.cancel()
        var started = false
        val runner = DozeOwnerReadOnlyPrerequisites(scope, startProcess = { started = true; FakeProcess() })
        assertThrows(IllegalStateException::class.java) { runner.readCallerPackages() }
        assertFalse(started)
    }

    private fun scope() = DozeOwnerReadOnlySession.Scope(System.nanoTime(), System::nanoTime)

    private class FakeProcess(out: String = "", error: String = "", private val exit: Int = 0,
        private val complete: Boolean = true, private val advance: (Long) -> Unit = {}) : Process() {
        var destroyed = false
        private var alive = true
        private val input = ByteArrayInputStream(out.toByteArray())
        private val stderr = ByteArrayInputStream(error.toByteArray())
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = error("Unbounded wait forbidden")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            advance(unit.toMillis(timeout))
            if (complete) alive = false
            return complete
        }
        override fun exitValue(): Int { check(!alive); return exit }
        override fun isAlive(): Boolean = alive
        override fun destroy() { alive = false; destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
