package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MonitorSessionTest {
    @Test
    fun constructorRejectsMalformedIdWindowCrossTargetDuplicatesAndOutOfWindowEvents() {
        assertThrows(IllegalArgumentException::class.java) { session(id = "bad id") }
        assertThrows(IllegalArgumentException::class.java) {
            session(deadline = START + MonitorSession.MAX_DURATION_MILLIS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session(events = listOf(event(START - 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            session(events = listOf(event(START), event(START)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            session(events = listOf(event(START, target = otherTarget)))
        }
    }

    @Test
    fun appendDeduplicatesAndRetainsOrderWhileIgnoringOutliersAndCrossTargetEvents() {
        val original = event(START + 1_000, ProcessTraceKind.UID_ACTIVE)
        val acceptedFirst = event(START + 2_000, ProcessTraceKind.PROCESS_START, pid = 41)
        val acceptedSecond = event(START + 2_000, ProcessTraceKind.UID_IDLE)
        val current = session(events = listOf(original), pollCount = 3)

        val updated = current.appendBatch(
            events = listOf(
                event(START - 1),
                event(START + 2_000, target = otherTarget),
                original,
                acceptedFirst,
                acceptedFirst,
                event(START + 15_001),
                acceptedSecond,
            ),
            nowEpochMillis = START + 15_000,
        )

        assertEquals(listOf(original, acceptedFirst, acceptedSecond), updated.events)
        assertEquals(4, updated.pollCount)
        assertEquals(MonitorSessionStatus.RUNNING, updated.status)
    }

    @Test
    fun pollBeforeStartAndAppendAfterStopAreInert() {
        val running = session()
        assertSame(running, running.appendBatch(listOf(event(START)), START - 1))

        val stopped = running.copy(status = MonitorSessionStatus.STOPPED)
        assertSame(stopped, stopped.appendBatch(listOf(event(START)), START + 1_000))
    }

    @Test
    fun deadlinePollRetainsOnlyInWindowEventsAndDoesNotClaimCompletion() {
        val session = session(deadline = START + 10_000)
        val valid = event(START + 9_999)

        val updated = session.appendBatch(
            listOf(valid, event(START + 10_001)),
            nowEpochMillis = START + 10_000,
        )

        assertEquals(listOf(valid), updated.events)
        assertEquals(MonitorSessionStatus.DEADLINE, updated.status)
        assertEquals(1, updated.pollCount)
    }

    @Test
    fun reachingFiveHundredEventsStopsAtTheLimit() {
        val existing = List(MonitorSession.MAX_EVENTS - 1) { index ->
            event(START + index, pid = index + 1)
        }
        val acceptedLast = event(START + 1_000, pid = 10_001)
        val discarded = event(START + 1_001, pid = 10_002)

        val updated = session(events = existing).appendBatch(
            listOf(acceptedLast, discarded),
            nowEpochMillis = START + 2_000,
        )

        assertEquals(MonitorSession.MAX_EVENTS, updated.events.size)
        assertEquals(acceptedLast, updated.events.last())
        assertEquals(MonitorSessionStatus.LIMIT_REACHED, updated.status)
        assertEquals(1, updated.pollCount)
    }

    private fun session(
        id: String = "9bd602a4-74d6-456e-a7a5-73c3fe530f33",
        deadline: Long = START + MonitorSession.MAX_DURATION_MILLIS,
        status: MonitorSessionStatus = MonitorSessionStatus.RUNNING,
        events: List<ProcessTraceEvent> = emptyList(),
        pollCount: Int = 0,
    ) = MonitorSession(id, identity, START, deadline, status, events, pollCount)

    private fun event(
        timestamp: Long,
        kind: ProcessTraceKind = ProcessTraceKind.PROCESS_START,
        pid: Int? = if (kind == ProcessTraceKind.PROCESS_START) 10 else null,
        target: ProcessTraceTarget = traceTarget,
    ) = ProcessTraceEvent(timestamp, target, kind, pid)

    private companion object {
        const val START = 1_800_000_000_000L
        val identity = SelectedTargetIdentity(
            packageName = "org.example.target",
            uid = 10_321,
            firstInstallTime = 1_700_000_000_000L,
            signerSha256 = listOf("a".repeat(64), "b".repeat(64)),
        )
        val traceTarget = ProcessTraceTarget(identity.packageName, identity.uid)
        val otherTarget = ProcessTraceTarget("org.example.other", 10_322)
    }
}
