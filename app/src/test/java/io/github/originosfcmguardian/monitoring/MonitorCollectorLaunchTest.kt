package io.github.originosfcmguardian.monitoring

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class MonitorCollectorLaunchTest {
    @Test fun stopBeforeQueuedCollectorStartsStillRunsCleanupWithoutReading() {
        val queue = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob())
        var readStarted = false
        var cleanedUp = false
        val job = scope.launchMonitorCollector(dispatcher) {
            try {
                coroutineContext.ensureActive()
                readStarted = true
            } finally {
                cleanedUp = true
            }
        }
        job.cancel() // STOP/Service destruction happens before the queued IO body.
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertTrue(job.isCompleted)
        assertTrue(cleanedUp)
        assertFalse(readStarted)
    }
}
