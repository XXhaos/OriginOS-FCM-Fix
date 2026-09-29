package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSessionQueryTest {
    @Test
    fun validatesQueryAndRejectsMismatchedSessionInsteadOfExportingReplacement() {
        listOf("", "bad id", "../escape", "a".repeat(65), "中文").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { MonitorSessionQuery(id) }
        }
        assertThrows(IllegalArgumentException::class.java) { MonitorSessionQuery(ID, fromEpochMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { MonitorSessionQuery(ID, throughEpochMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { MonitorSessionQuery(ID, 20, 19) }

        val mismatch = MonitorSessionQuery("different-session")
        assertThrows(IllegalArgumentException::class.java) { queryMonitorSession(session(), mismatch) }
        assertThrows(IllegalArgumentException::class.java) { encodeMonitorSessionExport(session(), mismatch) }
    }

    @Test
    fun filtersInclusiveBoundsAndKindThenSortsNewestFirstWithStableTies() {
        val oldest = event(START, ProcessTraceKind.UID_IDLE)
        val tieFirst = event(START + 100, ProcessTraceKind.UID_ACTIVE)
        val newest = event(START + 200, ProcessTraceKind.PROCESS_START, pid = 72)
        val tieSecond = event(START + 100, ProcessTraceKind.UID_IDLE)
        val storedOutOfOrder = listOf(oldest, tieFirst, newest, tieSecond)

        assertEquals(
            listOf(newest, tieFirst, tieSecond, oldest),
            queryMonitorSession(session(events = storedOutOfOrder), MonitorSessionQuery(ID, START, START + 200)),
        )
        assertEquals(
            listOf(tieFirst),
            queryMonitorSession(
                session(events = storedOutOfOrder),
                MonitorSessionQuery(ID, START + 100, START + 100, ProcessTraceKind.UID_ACTIVE),
            ),
        )
    }

    @Test
    fun exportUsesFixedPrivacySchemaAndExactFilters() {
        val monitoringSession = session(
            status = MonitorSessionStatus.STOPPED,
            pollCount = 4,
            events = listOf(
                event(START + 200, ProcessTraceKind.PROCESS_START, pid = 72),
                event(START + 100, ProcessTraceKind.UID_ACTIVE),
            ),
        )
        val json = encodeMonitorSessionExport(
            monitoringSession,
            MonitorSessionQuery(ID, START + 100, START + 200, ProcessTraceKind.PROCESS_START),
        ).decodeToString()

        assertEquals(
            "{\"schema\":\"guardian.monitor-session.v1\",\"session\":{" +
                "\"id\":\"$ID\",\"packageName\":\"org.example.target\"," +
                "\"startedAtEpochMillis\":$START,\"deadlineEpochMillis\":${START + 1_000}," +
                "\"status\":\"STOPPED\",\"snapshotStatus\":\"NOT_RUNNING\",\"pollCount\":4," +
                "\"retainedEventCount\":2},\"query\":{\"sessionId\":\"$ID\"," +
                "\"fromEpochMillis\":${START + 100},\"throughEpochMillis\":${START + 200}," +
                "\"kind\":\"PROCESS_START\"},\"evidence\":{" +
                "\"captureScope\":\"BOUNDED\",\"completeness\":\"UNKNOWN\"," +
                "\"systemEventFacts\":\"CONFIRMED\",\"fcmDelivery\":\"UNKNOWN\"," +
                "\"fcmCause\":\"UNKNOWN\",\"fcmConnection\":\"UNKNOWN\"," +
                "\"emptyResultMeaning\":\"UNKNOWN\"," +
                "\"installationIdentity\":\"PINNED_AT_COLLECTION\"," +
                "\"currentInstallationContinuity\":\"UNVERIFIED\"}," +
                "\"events\":[{\"timestampEpochMillis\":${START + 200}," +
                "\"kind\":\"PROCESS_START\",\"pid\":72}]}",
            json,
        )
        listOf(
            "uid", "signer", "firstInstallTime", "deviceId", "token", "raw", "log", "component", "reason",
        ).forEach { forbidden -> assertFalse(json.contains("\"$forbidden\"", ignoreCase = true)) }

        val uidActiveJson = encodeMonitorSessionExport(
            monitoringSession,
            MonitorSessionQuery(ID, kind = ProcessTraceKind.UID_ACTIVE),
        ).decodeToString()
        assertTrue(uidActiveJson.contains("\"kind\":\"UID_ACTIVE\""))
        assertFalse(uidActiveJson.contains("\"pid\":"))
    }

    @Test
    fun runningEmptySnapshotStatesPartialAndUnknownWithoutClaimingAbsenceOrCause() {
        val json = encodeMonitorSessionExport(session(), MonitorSessionQuery(ID)).decodeToString()

        assertTrue(json.contains("\"status\":\"RUNNING\""))
        assertTrue(json.contains("\"snapshotStatus\":\"RUNNING_PARTIAL\""))
        assertTrue(json.contains("\"events\":[]"))
        assertTrue(json.contains("\"emptyResultMeaning\":\"UNKNOWN\""))
        assertTrue(json.contains("\"systemEventFacts\":\"UNKNOWN\""))
        assertTrue(json.contains("\"fcmDelivery\":\"UNKNOWN\""))
        assertTrue(json.contains("\"fcmCause\":\"UNKNOWN\""))
        assertTrue(json.contains("\"fcmConnection\":\"UNKNOWN\""))
        assertFalse(json.contains("causalFCM", ignoreCase = true))
        assertFalse(json.contains("no event", ignoreCase = true))
        assertFalse(json.contains("absent", ignoreCase = true))
    }

    @Test
    fun queryAndExportRetainTheBoundedFiveHundredEventMaximum() {
        val events = List(MonitorSession.MAX_EVENTS) { index ->
            event(START + index, ProcessTraceKind.PROCESS_START, pid = index + 1)
        }
        val monitoringSession = session(deadline = START + 1_000, events = events)

        val queried = queryMonitorSession(monitoringSession, MonitorSessionQuery(ID))
        assertEquals(MonitorSession.MAX_EVENTS, queried.size)
        assertEquals(START + MonitorSession.MAX_EVENTS - 1, queried.first().timestampEpochMillis)
        assertEquals(START, queried.last().timestampEpochMillis)

        val exported = encodeMonitorSessionExport(monitoringSession, MonitorSessionQuery(ID))
        val json = exported.decodeToString()
        assertTrue(json.contains("\"retainedEventCount\":500"))
        assertEquals(500, "\"timestampEpochMillis\":".toRegex().findAll(json).count())
        assertTrue(exported.size < MonitorSessionStore.MAX_FILE_BYTES)
    }

    private fun session(
        deadline: Long = START + 1_000,
        status: MonitorSessionStatus = MonitorSessionStatus.RUNNING,
        events: List<ProcessTraceEvent> = emptyList(),
        pollCount: Int = 0,
    ) = MonitorSession(ID, identity, START, deadline, status, events, pollCount)

    private fun event(
        timestamp: Long,
        kind: ProcessTraceKind,
        pid: Int? = if (kind == ProcessTraceKind.PROCESS_START) 1 else null,
    ) = ProcessTraceEvent(timestamp, traceTarget, kind, pid)

    private companion object {
        const val ID = "session-20260929"
        const val START = 1_800_000_000_000L
        val identity = SelectedTargetIdentity(
            packageName = "org.example.target",
            uid = 10_321,
            firstInstallTime = 1_700_000_000_000L,
            signerSha256 = listOf("a".repeat(64)),
        )
        val traceTarget = ProcessTraceTarget(identity.packageName, identity.uid)
    }
}
