package io.github.originosfcmguardian.privileged

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PrivilegedWorkSettlementTest {
    @Test fun quiescenceCountsActualWorkersRatherThanCallerAbandonment() {
        val activity = PrivilegedWorkerActivity()
        assertTrue(activity.isQuiescent())
        val running = activity.track()
        val queued = activity.track()
        assertTrue(running.workerEntered())
        running.abandonQueued(); queued.abandonQueued()
        assertFalse(activity.isQuiescent())
        assertFalse(queued.workerEntered())
        running.workerEnded(false, null)
        assertTrue(activity.isQuiescent())
        running.workerEnded(false, null); queued.abandonQueued()
        assertTrue(activity.isQuiescent())
    }
    private class Fence : PrivilegedExecutionFence {
        val settlements = mutableListOf<PrivilegedExecutionSettlement>()
        override fun prepare() = true
        override fun start(launch: () -> Process) = launch()
        override fun workerSettled(value: PrivilegedExecutionSettlement) { settlements += value }
    }
    private class Child(var alive: Boolean) : Process() {
        override fun isAlive() = alive
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue() = 0
        override fun waitFor() = 0
        override fun destroy() {}
    }
    @Test fun cancelledQueuedTaskCannotEnterAndSettlesExactlyOnce() {
        val fence = Fence(); val work = PrivilegedWorkSettlement(fence)
        work.abandonQueued(); work.abandonQueued()
        assertFalse(work.workerEntered())
        work.workerEnded(false, null)
        assertEquals(listOf(PrivilegedExecutionSettlement.NOT_STARTED), fence.settlements)
    }
    @Test fun callerAbandonmentCannotSettleAnActuallyRunningWorker() {
        val fence = Fence(); val work = PrivilegedWorkSettlement(fence)
        assertTrue(work.workerEntered()); work.abandonQueued()
        assertTrue(fence.settlements.isEmpty())
        work.workerEnded(false, null); work.workerEnded(false, null)
        assertEquals(listOf(PrivilegedExecutionSettlement.NOT_STARTED), fence.settlements)
    }
    @Test fun liveUnknownAndConfirmedExitedProcessesAreNotConflated() {
        listOf(null to PrivilegedExecutionSettlement.UNKNOWN,
            Child(true) to PrivilegedExecutionSettlement.UNKNOWN,
            Child(false) to PrivilegedExecutionSettlement.PROCESS_EXIT_CONFIRMED).forEach { (child, expected) ->
            val fence = Fence(); val work = PrivilegedWorkSettlement(fence)
            assertTrue(work.workerEntered()); work.workerEnded(true, child)
            assertEquals(listOf(expected), fence.settlements)
        }
    }
    @Test fun workerFinallyCannotHideAStillLiveChild() {
        val activity = PrivilegedWorkerActivity()
        val child = Child(true)
        val work = activity.track()
        assertTrue(work.workerEntered())
        work.workerEnded(true, child)
        assertFalse(activity.isQuiescent())
        child.alive = false
        assertTrue(activity.isQuiescent())
    }
    @Test fun enteredLaunchWithoutAChildHandleRemainsUnknown() {
        val activity = PrivilegedWorkerActivity()
        val work = activity.track()
        assertTrue(work.workerEntered())
        work.workerEnded(true, null)
        assertFalse(activity.isQuiescent())
        activity.track().abandonQueued()
        assertFalse(activity.isQuiescent())
    }
}
