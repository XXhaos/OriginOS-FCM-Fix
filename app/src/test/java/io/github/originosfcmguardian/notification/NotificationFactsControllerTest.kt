package io.github.originosfcmguardian.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactsControllerTest {
    @Test fun failedPreferenceCommitStillStopsAndUnbindsWithStickyWarning() {
        val actions = mutableListOf<String>()
        var uncertain = false
        val stopped = disableNotificationFacts(
            pause = { actions += "pause"; true },
            unbind = { actions += "unbind" },
            persist = { actions += "persist"; false },
            markUncertain = { uncertain = it },
        )

        assertFalse(stopped)
        assertTrue(uncertain)
        assertEquals(listOf("unbind", "pause", "persist"), actions)

        val retried = disableNotificationFacts(
            pause = { true },
            unbind = {},
            persist = { true },
            markUncertain = { uncertain = it },
        )
        assertTrue(retried)
        assertFalse(uncertain)
    }
}
