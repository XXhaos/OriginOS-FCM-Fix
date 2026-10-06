package io.github.originosfcmguardian.ui

import io.github.originosfcmguardian.autostart.TargetStoppedReadFailure
import io.github.originosfcmguardian.autostart.TargetStoppedSnapshot
import io.github.originosfcmguardian.autostart.TargetStoppedStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedAppRecheckTest {
    private val healthy = TargetStoppedSnapshot(TargetStoppedStatus.NOT_STOPPED, 123)

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    @Test fun successAndExceptionAreTypedWithoutInventingHealth() = runBlocking {
        BoundedAppRecheck().use { reader ->
            assertEquals(healthy, reader.read { healthy })
            val failed = reader.read { error("fixture") }
            assertEquals(TargetStoppedStatus.UNKNOWN, failed.status)
            assertEquals(TargetStoppedReadFailure.FAILED, failed.readFailure)
        }
    }

    @Test fun stuckReadTimesOutFinishesUiAndLateReplyDoesNotPublishAgain() = runBlocking {
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val entered = AtomicInteger()
        BoundedAppRecheck(100).use { reader ->
            val publications = mutableListOf<TargetStoppedSnapshot>()
            var finishes = 0
            try {
                readAndPublishVisibleAppRecheck(
                    read = { reader.read { entered.incrementAndGet(); try { awaitIgnoringInterrupts(release); healthy } finally { exited.countDown() } } },
                    publish = { publications += it }, finish = { finishes++ })
                assertEquals(TargetStoppedReadFailure.TIMED_OUT, publications.single().readFailure)
                assertEquals(1, finishes)
                release.countDown()
                // Under build load the deadline may cancel the task before it starts. That path
                // correctly has no child to exit; the entered-worker path must still settle.
                if (entered.get() > 0) assertTrue(exited.await(2, TimeUnit.SECONDS))
                delay(30)
                assertEquals(1, publications.size)
                assertEquals(1, finishes)
            } finally { release.countDown() }
        }
    }

    @Test fun twoStuckWorkersRejectRetryWithoutQueueOrNewThread() = runBlocking {
        val release = CountDownLatch(1)
        val entered = AtomicInteger()
        BoundedAppRecheck(150).use { reader ->
            try {
                val reads = (1..2).map { async { reader.read { entered.incrementAndGet(); awaitIgnoringInterrupts(release); healthy } } }
                reads.forEach { assertEquals(TargetStoppedReadFailure.TIMED_OUT, it.await().readFailure) }
                assertEquals(2, entered.get())
                repeat(5) {
                    assertEquals(TargetStoppedReadFailure.BUSY, reader.read { entered.incrementAndGet(); healthy }.readFailure)
                }
                assertEquals(2, entered.get())
            } finally { release.countDown() }
        }
    }

    @Test fun cancellationReleasesMarkerAndDoesNotPublishLateResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        BoundedAppRecheck().use { reader ->
            var published = false
            var finished = false
            try {
                val job = launch {
                    readAndPublishVisibleAppRecheck(
                        read = { reader.read { entered.countDown(); awaitIgnoringInterrupts(release); healthy } },
                        publish = { published = true }, finish = { finished = true })
                }
                while (entered.count > 0) delay(5)
                job.cancelAndJoin()
                assertTrue(finished)
                assertFalse(published)
                release.countDown()
                delay(30)
                assertFalse(published)
            } finally { release.countDown() }
        }
    }

    @Test fun oneSlowAppDoesNotBlockAnotherRead() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        BoundedAppRecheck().use { reader ->
            try {
                val slow = async { reader.read { entered.countDown(); awaitIgnoringInterrupts(release); healthy } }
                while (entered.count > 0) delay(5)
                assertEquals(healthy, reader.read { healthy })
                release.countDown()
                assertEquals(healthy, slow.await())
            } finally { release.countDown() }
        }
    }
}
