package io.github.originosfcmguardian.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactsQueryTest {
    @Test
    fun packageTimeAndKindFilterIsInclusiveAndKeepsStoreOrder() {
        val events = listOf(
            fact("org.telegram.messenger", 120, NotificationFactKind.REMOVED),
            fact("com.example.other", 110, NotificationFactKind.POSTED_OR_UPDATED),
            fact("org.telegram.messenger", 100, NotificationFactKind.POSTED_OR_UPDATED),
            fact("org.telegram.messenger", 100, NotificationFactKind.REMOVED),
            fact("org.telegram.messenger", 80, NotificationFactKind.POSTED_OR_UPDATED),
        )
        val inclusive = queryNotificationFacts(
            events,
            NotificationFactsQuery("org.telegram.messenger", fromEpochMillis = 100, throughEpochMillis = 120),
        )
        assertEquals(listOf(events[0], events[2], events[3]), inclusive)
        assertEquals(
            listOf(events[0], events[3]),
            queryNotificationFacts(
                events,
                NotificationFactsQuery("org.telegram.messenger", 100, 120, NotificationFactKind.REMOVED),
            ),
        )
        assertEquals(emptyList<NotificationFact>(), queryNotificationFacts(events, NotificationFactsQuery("com.example.empty")))
        val fullHistory = List(NotificationFactsStore.MAX_EVENTS) { index ->
            fact("org.telegram.messenger", (index + 1).toLong(), NotificationFactKind.REMOVED)
        }
        assertEquals(NotificationFactsStore.MAX_EVENTS,
            queryNotificationFacts(fullHistory, NotificationFactsQuery("org.telegram.messenger")).size)
        assertThrows(IllegalArgumentException::class.java) {
            NotificationFactsQuery("org.telegram.messenger", fromEpochMillis = 120, throughEpochMillis = 100)
        }
    }

    @Test
    fun exportedSnapshotHasOnlyAllowedFieldsAndDoesNotChangeAfterSourceMutation() {
        val source = mutableListOf(
            fact("org.telegram.messenger", 120, NotificationFactKind.REMOVED),
            fact("com.example.other", 110, NotificationFactKind.POSTED_OR_UPDATED),
            fact("org.telegram.messenger", 100, NotificationFactKind.POSTED_OR_UPDATED),
        )
        val bytes = encodeNotificationFactsExport(
            queryNotificationFacts(source, NotificationFactsQuery("org.telegram.messenger", kind = NotificationFactKind.REMOVED)),
        )
        source.clear() // An explicit clear after snapshot preparation cannot change the export.
        val json = bytes.decodeToString()
        assertEquals(
            "{\"schema\":\"guardian.notification-facts.v1\"," +
                "\"statement\":\"Notification publication or removal is not proof of FCM delivery or app wake. " +
                "This is package-name history; installation continuity was not recorded and prior installations may be included.\"," +
                "\"events\":[{\"packageName\":\"org.telegram.messenger\"," +
                "\"observedAtEpochMillis\":120,\"kind\":\"REMOVED\",\"evidenceLevel\":\"CONFIRMED\"}]}",
            json,
        )
        assertFalse(json.contains("com.example.other"))
        listOf("title", "body", "extras", "key", "tag", "channel", "uid", "device", "token").forEach {
            assertFalse(json.contains("\"$it\":"))
        }
        assertTrue(json.contains("not proof of FCM delivery or app wake"))
        assertTrue(json.contains("installation continuity was not recorded"))
    }

    private fun fact(packageName: String, time: Long, kind: NotificationFactKind) =
        NotificationFact(packageName, time, kind)
}
