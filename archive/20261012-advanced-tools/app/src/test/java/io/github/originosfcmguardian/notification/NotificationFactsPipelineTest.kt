package io.github.originosfcmguardian.notification

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactsPipelineTest {
    private val event = CapturedNotificationFact(
        "org.telegram.messenger", 10_434, 1_700_000_000_000, NotificationFactKind.POSTED_OR_UPDATED,
    )
    private val target = NotificationCaptureTarget(event.packageName, event.uid)

    @Test fun offDisconnectedAndUnselectedCallbacksNeverQueue() {
        val queue = ManualQueue()
        val pipe = pipe(queue)
        pipe.offer(event)
        pipe.setEnabled(true)
        pipe.setConnected(true)
        pipe.offer(event)
        pipe.updateSelectedTargets(setOf(target), false)
        pipe.offer(event.copy(uid = event.uid + 1))
        assertEquals(0, queue.size)
    }

    @Test fun offerReturnsPromptlyWhileWorkerBlocksInStorage() {
        val queue = ManualQueue()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pipe = NotificationFactsPipeline(queue, { NotificationFactEligibility.ACCEPT }, {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            RecordNotificationFactResult.STORED
        }, {})
        activate(pipe)
        pipe.offer(event)
        val worker = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val running = worker.submit { queue.runNext() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.submit { pipe.offer(event.copy(observedAtEpochMillis = event.observedAtEpochMillis + 1)) }
                .get(250, TimeUnit.MILLISECONDS)
            assertEquals(1, queue.size)
            release.countDown()
            running.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
            caller.shutdownNow()
        }
    }

    @Test fun selectionRevisionInvalidatesPendingAndRunningEvents() {
        val queue = ManualQueue()
        val recorded = mutableListOf<NotificationFact>()
        val pipe = pipe(queue, recorded)
        activate(pipe)
        pipe.offer(event)
        val old = queue.takeNext()
        pipe.offer(event)
        pipe.updateSelectedTargets(setOf(target), true)
        old()
        assertTrue(recorded.isEmpty())
        assertEquals(2, pipe.droppedEvents())
    }

    @Test fun clearRejectsCallbacksAndOldEventsCannotResurrectHistory() {
        val queue = ManualQueue()
        val recorded = mutableListOf<NotificationFact>()
        val pipe = pipe(queue, recorded)
        activate(pipe)
        pipe.offer(event)
        val old = queue.takeNext()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val clear = caller.submit<Boolean> { pipe.clearAndAwait { recorded.clear(); true } }
            assertTrue(queue.awaitControl())
            pipe.offer(event)
            old()
            queue.runNext()
            assertTrue(clear.get(2, TimeUnit.SECONDS))
            assertTrue(recorded.isEmpty())
            assertEquals(0, pipe.droppedEvents())
            pipe.offer(event)
            queue.runNext()
            assertEquals(1, recorded.size)
        } finally { caller.shutdownNow() }
    }

    @Test fun failedClearStaysPausedUntilSuccessfulRetry() {
        val queue = ManualQueue()
        val pipe = pipe(queue)
        activate(pipe)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val failed = caller.submit<Boolean> { pipe.clearAndAwait { false } }
            assertTrue(queue.awaitControl())
            queue.runNext()
            assertFalse(failed.get(2, TimeUnit.SECONDS))
            assertFalse(pipe.isConnected())
            pipe.offer(event)
            assertEquals(0, queue.size)
            val retried = caller.submit<Boolean> { pipe.clearAndAwait { true } }
            queue.runNextWhenAvailable()
            assertTrue(retried.get(2, TimeUnit.SECONDS))
            assertTrue(pipe.isConnected())
        } finally { caller.shutdownNow() }
    }

    @Test fun disconnectCannotDrainDisableBarrier() {
        val queue = ManualQueue()
        val pipe = pipe(queue)
        activate(pipe)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val disabled = caller.submit<Boolean> { pipe.disableAndAwait() }
            assertTrue(queue.awaitControl())
            pipe.setConnected(false)
            assertEquals(1, queue.size)
            queue.runNext()
            assertTrue(disabled.get(2, TimeUnit.SECONDS))
        } finally { caller.shutdownNow() }
    }

    @Test fun permissionLossInvalidatesLifecycleAndPendingWork() {
        val queue = ManualQueue()
        val pipe = NotificationFactsPipeline(
            queue, { NotificationFactEligibility.INVALIDATE_LIFECYCLE },
            { RecordNotificationFactResult.STORED }, {},
        )
        activate(pipe)
        pipe.offer(event)
        pipe.offer(event.copy(observedAtEpochMillis = event.observedAtEpochMillis + 1))
        queue.runNext()
        assertFalse(pipe.isConnected())
        assertEquals(2, pipe.droppedEvents())
        assertEquals(0, queue.size)
    }

    @Test fun currentPackageIdentityMustStillMatch() {
        val identity = SelectedTargetIdentity(event.packageName, event.uid, 1_600_000_000_000, listOf("a".repeat(64)))
        assertEquals(NotificationFactEligibility.ACCEPT, notificationFactEligibility(event, listOf(identity), identity))
        assertEquals(
            NotificationFactEligibility.REJECT_EVENT,
            notificationFactEligibility(event, listOf(identity), identity.copy(firstInstallTime = identity.firstInstallTime + 1)),
        )
        assertEquals(
            NotificationFactEligibility.REJECT_EVENT,
            notificationFactEligibility(event.copy(uid = event.uid + 1), listOf(identity), identity),
        )
    }

    private fun activate(pipe: NotificationFactsPipeline) {
        pipe.updateSelectedTargets(setOf(target), false)
        pipe.setEnabled(true)
        pipe.setConnected(true)
    }

    private fun pipe(queue: ManualQueue, recorded: MutableList<NotificationFact> = mutableListOf()) =
        NotificationFactsPipeline(queue, { NotificationFactEligibility.ACCEPT },
            { recorded += it; RecordNotificationFactResult.STORED }, {})

    private class ManualQueue : NotificationFactsTaskQueue {
        private data class Task(val control: Boolean, val action: () -> Unit)
        private val tasks = ArrayDeque<Task>()
        private val controls = CountDownLatch(1)
        val size: Int get() = synchronized(tasks) { tasks.size }
        override fun executeEvent(task: () -> Unit): Boolean = synchronized(tasks) {
            tasks.addLast(Task(false, task)); true
        }
        override fun executeControl(task: () -> Unit): Boolean = synchronized(tasks) {
            tasks.addLast(Task(true, task)); controls.countDown(); true
        }
        override fun clearPending(): Int = synchronized(tasks) {
            val old = tasks.size
            tasks.removeAll { !it.control }
            old - tasks.size
        }
        fun awaitControl() = controls.await(2, TimeUnit.SECONDS)
        fun takeNext(): () -> Unit = synchronized(tasks) { tasks.removeFirst().action }
        fun runNext() = takeNext().invoke()
        fun runNextWhenAvailable() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (size == 0 && System.nanoTime() < deadline) Thread.yield()
            assertTrue(size > 0)
            runNext()
        }
    }
}
