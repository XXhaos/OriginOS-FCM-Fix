package io.github.originosfcmguardian.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DozeEffectiveOwnerReadOnlySessionTest {
    @Test fun qualifiedMappingPublishesOnlyValidSmallSummary() {
        val value = DozeOwnerReadOnlySession(wallTime = { 100 }).readEffective({ qualified() }, { false })
        assertTrue(value.isValidEffectiveSummary())
        assertEquals(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING, value.result.code)
        assertFalse(value.uncertainBinderExit)
    }
    @Test fun partialUnknownCountersRemainUsefulAndContainNoNameOrDigest() {
        val partial = DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.UNKNOWN, DozeEffectiveOwnerRejection.NULL_APPLICATION,
            records = 650, canonicalPackages = 629, apexPackages = 10, applicationPackages = 100, processedNames = 110)
        val value = DozeOwnerReadOnlySession(wallTime = { 100 }).readEffective({ partial }, { false })
        assertTrue(value.isValidEffectiveSummary())
        assertEquals(110, value.result.processedNames)
        assertEquals("", value.result.mappingDigest)
    }
    @Test fun unreturnedBinderClosesSessionAndCannotPublishPositiveResult() {
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val value = session.readEffective({ session.cancel(); qualified() }, { true })
        assertEquals(DozeEffectiveOwnerRejection.IN_FLIGHT, value.result.rejection)
        assertEquals(DozeEffectiveOwnerCode.UNKNOWN, value.result.code)
        assertTrue(value.uncertainBinderExit && value.isValidEffectiveSummary())
        assertEquals("", value.result.mappingDigest)
        assertEquals(DozeEffectiveOwnerRejection.CANCELLED,
            session.readEffective({ error("must not dispatch") }, { false }).result.rejection)
        assertEquals(DozeOwnerReadOnlyCode.CANCELLED, session.read { error("old route cannot bypass closure") }.code)
    }
    @Test fun existingAndEffectiveReadShareTheSameSingleFlightAdmission() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = DozeOwnerReadOnlySession(wallTime = { 100 })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<DozeEffectiveOwnerReadOnlySummary> {
                session.readEffective({ entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); qualified() }, { false })
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(DozeOwnerReadOnlyCode.BUSY, session.read { error("must not dispatch") }.code)
            assertEquals(DozeEffectiveOwnerRejection.BUSY,
                session.readEffective({ error("must not dispatch") }, { false }).result.rejection)
            release.countDown()
            assertTrue(future.get(2, TimeUnit.SECONDS).isValidEffectiveSummary())
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun deadlineRejectsLateQualifiedMappingWithoutRaisingBudget() {
        val time = AtomicLong()
        val session = DozeOwnerReadOnlySession(wallTime = { 100 }, nanoTime = time::get)
        val value = session.readEffective({ time.set(TimeUnit.MILLISECONDS.toNanos(40_000)); qualified() }, { false })
        assertEquals(DozeEffectiveOwnerRejection.DEADLINE, value.result.rejection)
        assertEquals(DozeEffectiveOwnerCode.UNKNOWN, value.result.code)
        assertTrue(value.isValidEffectiveSummary())
        assertEquals("", value.result.mappingDigest)
    }
    @Test fun malformedSummaryCannotPretendToBeQualified() {
        val summary = DozeEffectiveOwnerReadOnlySummary(1, 2, 1, qualified())
        assertFalse(summary.copy(uncertainBinderExit = true).isValidEffectiveSummary())
        assertFalse(summary.copy(result = qualified().copy(gmsAppId = -1)).isValidEffectiveSummary())
        assertFalse(summary.copy(result = qualified().copy(processedNames = 1)).isValidEffectiveSummary())
        assertFalse(summary.copy(result = qualified().copy(mappingDigest = "private invalid digest")).isValidEffectiveSummary())
    }
    private fun qualified() = DozeEffectiveOwnerResult(DozeEffectiveOwnerCode.QUALIFIED_CURRENT_MAPPING,
        DozeEffectiveOwnerRejection.NONE, records = 650, canonicalPackages = 629, apexPackages = 45,
        applicationPackages = 584, gmsAppId = 10208, ownerCount = 1, structureDigest = "a".repeat(64),
        mappingDigest = "b".repeat(64), processedNames = 629)
}
