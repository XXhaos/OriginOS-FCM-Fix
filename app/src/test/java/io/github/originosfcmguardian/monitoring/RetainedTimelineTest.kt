package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import io.github.originosfcmguardian.notification.NotificationFact
import io.github.originosfcmguardian.notification.NotificationFactKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedTimelineTest {
    @Test
    fun combinesSourcesNewestFirstWithStableTiesAndDifferentInstallationSemantics() {
        val notifications = listOf(
            notification(TELEGRAM, START + 300, NotificationFactKind.REMOVED),
            notification(OLD_PACKAGE, START + 100, NotificationFactKind.POSTED_OR_UPDATED),
            notification(TELEGRAM, START + 200, NotificationFactKind.POSTED_OR_UPDATED),
        )
        val session = session(
            events = listOf(
                process(START + 200, ProcessTraceKind.UID_ACTIVE),
                process(START + 400, ProcessTraceKind.PROCESS_START, pid = 77),
                process(START + 200, ProcessTraceKind.UID_IDLE),
            ),
        )

        val result = combineRetainedTimeline(
            notificationEvents = notifications,
            notificationHistoryCorrupt = false,
            processSession = session,
            processHistoryCorrupt = false,
            currentSelectedAppLabels = mapOf(TELEGRAM to "Telegram"),
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals(
            listOf(
                RetainedTimelineKind.PROCESS_START,
                RetainedTimelineKind.NOTIFICATION_REMOVED,
                RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED,
                RetainedTimelineKind.UID_ACTIVE,
                RetainedTimelineKind.UID_IDLE,
                RetainedTimelineKind.NOTIFICATION_POSTED_OR_UPDATED,
            ),
            result.items.map { it.kind },
        )

        val packageOnly = result.items.first { it.source == RetainedTimelineSource.NOTIFICATION }
        assertEquals(RetainedTimelineTimestampMeaning.NOTIFICATION_OBSERVED_AT, packageOnly.timestampMeaning)
        assertEquals(RetainedTimelineInstallationScope.PACKAGE_ONLY_UNKNOWN, packageOnly.installationScope)
        assertEquals("Telegram", packageOnly.currentSelectedAppLabel)
        assertNull(packageOnly.sessionId)

        val pinned = result.items.first { it.source == RetainedTimelineSource.PROCESS_SESSION }
        assertEquals(RetainedTimelineTimestampMeaning.SYSTEM_EVENT_TIME, pinned.timestampMeaning)
        assertEquals(RetainedTimelineInstallationScope.PINNED_SESSION_CURRENT_UNKNOWN, pinned.installationScope)
        assertEquals(SESSION_ID, pinned.sessionId)
        assertEquals(RetainedTimelineEvidenceLevel.CONFIRMED, pinned.evidenceLevel)

        val oldUnselected = result.items.single { it.packageName == OLD_PACKAGE }
        assertNull(oldUnselected.currentSelectedAppLabel)
        assertEquals(RetainedTimelineInstallationScope.PACKAGE_ONLY_UNKNOWN, oldUnselected.installationScope)
    }

    @Test
    fun corruptSourceIsOmittedWithoutHidingTheOtherSource() {
        val notification = notification(TELEGRAM, START + 100, NotificationFactKind.REMOVED)
        val processSession = session(events = listOf(process(START + 200, ProcessTraceKind.UID_ACTIVE)))

        val corruptNotifications = combineRetainedTimeline(
            notificationEvents = listOf(notification),
            notificationHistoryCorrupt = true,
            processSession = processSession,
            processHistoryCorrupt = false,
        )
        assertEquals(listOf(RetainedTimelineSource.PROCESS_SESSION), corruptNotifications.items.map { it.source })
        assertEquals(setOf(RetainedTimelineWarning.NOTIFICATION_SOURCE_OMITTED), corruptNotifications.warnings)

        val corruptProcess = combineRetainedTimeline(
            notificationEvents = listOf(notification),
            notificationHistoryCorrupt = false,
            processSession = processSession,
            processHistoryCorrupt = true,
        )
        assertEquals(listOf(RetainedTimelineSource.NOTIFICATION), corruptProcess.items.map { it.source })
        assertEquals(setOf(RetainedTimelineWarning.PROCESS_SOURCE_OMITTED), corruptProcess.warnings)

        val bothCorrupt = combineRetainedTimeline(
            notificationEvents = listOf(notification),
            notificationHistoryCorrupt = true,
            processSession = processSession,
            processHistoryCorrupt = true,
        )
        assertTrue(bothCorrupt.items.isEmpty())
        assertEquals(2, bothCorrupt.warnings.size)
    }

    @Test
    fun querySearchesCurrentLabelsAndAllPackageHistoryWithExactFilters() {
        val capturedAt = START + RetainedTimelineTimeWindow.RECENT_24_HOURS_MILLIS
        val window = RetainedTimelineTimeWindow.recent24Hours(capturedAt)
        val snapshot = combineRetainedTimeline(
            notificationEvents = listOf(
                notification(TELEGRAM, window.fromEpochMillis, NotificationFactKind.POSTED_OR_UPDATED),
                notification(OLD_PACKAGE, capturedAt, NotificationFactKind.REMOVED),
                notification(TELEGRAM, capturedAt + 1, NotificationFactKind.REMOVED),
            ),
            notificationHistoryCorrupt = false,
            processSession = session(
                startedAt = capturedAt - 1_000,
                deadline = capturedAt + 2,
                events = listOf(process(capturedAt, ProcessTraceKind.UID_ACTIVE)),
            ),
            processHistoryCorrupt = false,
            currentSelectedAppLabels = mapOf(TELEGRAM to "Telegram Messenger"),
        )

        assertEquals(
            3,
            queryRetainedTimeline(snapshot, RetainedTimelineQuery(searchText = "telegram")).size,
        )
        assertEquals(
            listOf(OLD_PACKAGE),
            queryRetainedTimeline(snapshot, RetainedTimelineQuery(searchText = "legacy.package"))
                .map { it.packageName },
        )
        assertTrue(queryRetainedTimeline(snapshot, RetainedTimelineQuery(searchText = "Old Label")).isEmpty())
        assertEquals(
            listOf(RetainedTimelineKind.UID_ACTIVE),
            queryRetainedTimeline(
                snapshot,
                RetainedTimelineQuery(
                    packageName = TELEGRAM,
                    timeWindow = window,
                    source = RetainedTimelineSource.PROCESS_SESSION,
                    kind = RetainedTimelineKind.UID_ACTIVE,
                ),
            ).map { it.kind },
        )
        assertEquals(
            setOf(START, capturedAt),
            queryRetainedTimeline(snapshot, RetainedTimelineQuery(timeWindow = window))
                .map { it.timestampEpochMillis }
                .toSet(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RetainedTimelineQuery(
                source = RetainedTimelineSource.NOTIFICATION,
                kind = RetainedTimelineKind.PROCESS_START,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedTimelineQuery(searchText = "x".repeat(MAX_RETAINED_TIMELINE_SEARCH_LENGTH + 1))
        }
    }

    @Test
    fun uidLifecycleRemainsDistinctInProcessSourceAndKindFilters() {
        val snapshot = combineRetainedTimeline(
            notificationEvents = emptyList(),
            notificationHistoryCorrupt = false,
            processSession = session(events = listOf(
                process(START, ProcessTraceKind.UID_RUNNING),
                process(START + 1, ProcessTraceKind.UID_STOPPED),
            )),
            processHistoryCorrupt = false,
        )
        listOf(RetainedTimelineKind.UID_RUNNING, RetainedTimelineKind.UID_STOPPED).forEach { kind ->
            val result = queryRetainedTimeline(snapshot, RetainedTimelineQuery(
                source = RetainedTimelineSource.PROCESS_SESSION,
                kind = kind,
            ))
            assertEquals(kind, result.single().kind)
        }
    }

    @Test
    fun preservesCombinedSourceCeilingsAndAppliesResultLimitAfterOrdering() {
        val notifications = List(200) { index ->
            notification(
                packageName = if (index % 2 == 0) TELEGRAM else OLD_PACKAGE,
                timestamp = START + index,
                kind = NotificationFactKind.POSTED_OR_UPDATED,
            )
        }
        val processEvents = List(MonitorSession.MAX_EVENTS) { index ->
            process(START + index, ProcessTraceKind.PROCESS_START, pid = index + 1)
        }
        val snapshot = combineRetainedTimeline(
            notificationEvents = notifications,
            notificationHistoryCorrupt = false,
            processSession = session(events = processEvents),
            processHistoryCorrupt = false,
        )

        assertEquals(MAX_RETAINED_TIMELINE_ITEMS, snapshot.items.size)
        val limited = queryRetainedTimeline(snapshot, RetainedTimelineQuery(limit = 17))
        assertEquals(17, limited.size)
        assertEquals(snapshot.items.take(17), limited)
        assertThrows(IllegalArgumentException::class.java) { RetainedTimelineQuery(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedTimelineQuery(limit = MAX_RETAINED_TIMELINE_ITEMS + 1)
        }

        val oversizedNotifications = combineRetainedTimeline(
            notificationEvents = notifications + notifications.first(),
            notificationHistoryCorrupt = false,
            processSession = null,
            processHistoryCorrupt = false,
        )
        assertTrue(oversizedNotifications.items.isEmpty())
        assertEquals(setOf(RetainedTimelineWarning.NOTIFICATION_SOURCE_OMITTED), oversizedNotifications.warnings)
    }

    private fun notification(packageName: String, timestamp: Long, kind: NotificationFactKind) =
        NotificationFact(packageName, timestamp, kind)

    private fun session(
        startedAt: Long = START,
        deadline: Long = startedAt + MonitorSession.MAX_DURATION_MILLIS,
        events: List<ProcessTraceEvent>,
    ) = MonitorSession(
        id = SESSION_ID,
        identity = identity,
        startedAtEpochMillis = startedAt,
        deadlineEpochMillis = deadline,
        status = MonitorSessionStatus.STOPPED,
        events = events,
    )

    private fun process(
        timestamp: Long,
        kind: ProcessTraceKind,
        pid: Int? = if (kind == ProcessTraceKind.PROCESS_START) 1 else null,
    ) = ProcessTraceEvent(timestamp, processTarget, kind, pid)

    private companion object {
        const val START = 1_800_000_000_000L
        const val SESSION_ID = "session-retained-timeline"
        const val TELEGRAM = "org.telegram.messenger"
        const val OLD_PACKAGE = "com.example.legacy.package"
        val identity = SelectedTargetIdentity(
            packageName = TELEGRAM,
            uid = 10_321,
            firstInstallTime = 1_700_000_000_000L,
            signerSha256 = listOf("a".repeat(64)),
        )
        val processTarget = ProcessTraceTarget(TELEGRAM, identity.uid)
    }
}
