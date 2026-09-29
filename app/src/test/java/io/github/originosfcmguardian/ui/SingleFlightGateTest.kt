package io.github.originosfcmguardian.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightGateTest {
    @Test
    fun rapidDoubleRequestQueuesOnlyOneDetectionAndCapture() {
        val gate = SingleFlightGate()
        val queued = mutableListOf<() -> Unit>()
        val job = Job()
        var detections = 0
        var captures = 0

        fun requestDetection() {
            if (!gate.tryEnter()) return
            gate.releaseOnCompletion(job)
            queued += {
                detections += 1
                captures += 1
                job.complete()
            }
        }

        requestDetection()
        requestDetection()
        assertEquals(1, queued.size)

        queued.single().invoke()
        assertEquals(1, detections)
        assertEquals(1, captures)
    }

    @Test
    fun cancelledScopeReleasesGateEvenWhenBodyNeverStarts() {
        val gate = SingleFlightGate()
        val parent = Job().apply { cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        var bodyStarted = false

        assertTrue(gate.tryEnter())
        val job = scope.launch { bodyStarted = true }
        gate.releaseOnCompletion(job)

        assertFalse(bodyStarted)
        assertTrue(gate.tryEnter())
        gate.exit()
        scope.cancel()
    }
}
