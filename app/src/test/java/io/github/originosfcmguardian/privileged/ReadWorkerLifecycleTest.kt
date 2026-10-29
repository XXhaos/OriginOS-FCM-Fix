package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autoprotection.AutomaticTargetObservation
import io.github.originosfcmguardian.autoprotection.AutomaticTargetReadFailure
import io.github.originosfcmguardian.autoprotection.BoundedAutomaticTargetRead
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Short injected lifetimes exercise the same lazy, zero-core executors as production. */
class ReadWorkerLifecycleTest {
    private enum class Result { COMPLETED, TIMED_OUT, BUSY }
    private interface Reader : AutoCloseable {
        fun read(): Result
        fun quiescent(): Boolean
    }
    private class Threads : ThreadFactory {
        val created = CopyOnWriteArrayList<Thread>()
        override fun newThread(task: Runnable) = Thread(task, "read-worker-lifecycle-test").apply {
            isDaemon = true
            created += this
        }
        fun awaitExit() {
            created.forEach { it.join(3_000); assertFalse("worker must eventually exit", it.isAlive) }
        }
    }
    private val stoppedPort = object : StoppedIdentityReadPort {
        override fun environment() = StoppedProbeEnvironment(VERIFIED_AUTOSTART_FINGERPRINT, 0, 2000)
        override fun telegramPackage() = StoppedPackageFields(STOPPED_TARGET_PACKAGE, STOPPED_TARGET_PACKAGE,
            10234, 123L, listOf(byteArrayOf(1, 2, 3)), false)
        override fun packagesForUid(uid: Int) = listOf(STOPPED_TARGET_PACKAGE)
    }
    private val gmsPort = object : GmsConnectionReadPort {
        override fun environment() = GmsProbeEnvironment(0, 2000, "12345678-1234-1234-1234-123456789abc")
        override fun gmsPackage() = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
            10234, 99, 123, 456, "a".repeat(64))
        override fun packagesForUid(uid: Int) = listOf(GMS_OBSERVATION_PACKAGE)
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean) =
            SocketSource(SourceCompleteness.COMPLETE, !ipv6)
        override fun reconnectReceivers() = GmsReceiverInspection(true, emptyList())
        override fun elapsedRealtime() = 900L
    }
    private fun reader(kind: Int, keepAlive: Long, threads: Threads, beforeRead: () -> Unit = {}): Reader = when (kind) {
        0 -> {
            val runner = BoundedAutomaticTargetRead(timeoutMillis = 100, maxParallel = 1,
                keepAliveMillis = keepAlive, threadFactory = threads)
            object : Reader {
                override fun read() = runBlocking {
                    val value = runner.readChunk(listOf(1)) { beforeRead(); AutomaticTargetObservation(qualified = true) }.single()
                    when (value.failure) {
                        null -> Result.COMPLETED
                        AutomaticTargetReadFailure.BUSY -> Result.BUSY
                        AutomaticTargetReadFailure.TIMED_OUT -> Result.TIMED_OUT
                        else -> error("unexpected failed read")
                    }
                }
                override fun quiescent() = runner.isQuiescent()
                override fun close() = runner.close()
            }
        }
        1 -> {
            val runner = StoppedIdentityProbeRunner({ beforeRead(); stoppedPort }, budgetMillis = 100,
                keepAliveMillis = keepAlive, threadFactory = threads)
            object : Reader {
                override fun read(): Result = when (val value = runner.read(1) { false }) {
                    is StoppedTargetObservation.Known -> Result.COMPLETED
                    is StoppedTargetObservation.Unknown -> when (value.reason) {
                        StoppedObservationUnknown.BUSY -> Result.BUSY
                        StoppedObservationUnknown.TIMED_OUT -> Result.TIMED_OUT
                        else -> error("unexpected stopped result: $value")
                    }
                }
                override fun quiescent() = runner.isQuiescent()
                override fun close() = runner.close()
            }
        }
        else -> {
            val runner = GmsConnectionProbeRunner({ beforeRead(); gmsPort }, budgetMillis = 100,
                keepAliveMillis = keepAlive, threadFactory = threads)
            object : Reader {
                override fun read(): Result = when (val value = runner.read(1) { false }) {
                    is GmsConnectionObservation.Sample -> Result.COMPLETED
                    is GmsConnectionObservation.Unknown -> when (value.reason) {
                        GmsUnknown.BUSY -> Result.BUSY
                        GmsUnknown.TIMED_OUT -> Result.TIMED_OUT
                        else -> error("unexpected GMS result: $value")
                    }
                }
                override fun quiescent() = runner.isQuiescent()
                override fun close() = runner.close()
            }
        }
    }

    @Test fun longerLifetimeReusesLazyWorkersBeyondShortExpiryThenStillExpires() {
        repeat(3) { kind ->
            val shortThreads = Threads(); val longThreads = Threads()
            reader(kind, 100, shortThreads).use { short ->
                reader(kind, 1_500, longThreads).use { retained ->
                    assertTrue(shortThreads.created.isEmpty())
                    assertTrue(longThreads.created.isEmpty())
                    assertEquals(Result.COMPLETED, short.read())
                    assertEquals(Result.COMPLETED, retained.read())
                    shortThreads.awaitExit() // Past the prior, shorter idle expiry.
                    assertTrue(longThreads.created.single().isAlive)
                    assertEquals(Result.COMPLETED, retained.read())
                    assertEquals(1, longThreads.created.size)
                    assertTrue(retained.quiescent())
                    longThreads.awaitExit()
                    assertEquals(Result.COMPLETED, retained.read())
                    assertEquals(2, longThreads.created.size)
                }
                longThreads.awaitExit() // close also stops the newly idle worker.
            }
        }
    }

    @Test fun idleExpiryAndCloseCannotReplaceAStuckWorkerOrHideItsActivity() {
        repeat(3) { kind ->
            val threads = Threads(); val release = CountDownLatch(1); val entered = CountDownLatch(1)
            val runner = reader(kind, 25, threads) {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
            try {
                assertEquals(Result.TIMED_OUT, runner.read()) // Blocked longer than idle keepalive.
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertFalse(runner.quiescent())
                repeat(3) { assertEquals(Result.BUSY, runner.read()) }
                assertEquals(1, threads.created.size)
                runner.close()
                assertFalse(runner.quiescent())
                assertEquals(Result.BUSY, runner.read())
                assertEquals(1, threads.created.size)
            } finally { release.countDown(); runner.close(); threads.awaitExit() }
            assertTrue(runner.quiescent())
        }
    }

    @Test fun closeRejectsReadsWithoutStartingWorkers() {
        repeat(3) { kind ->
            val threads = Threads(); val runner = reader(kind, 1_500, threads)
            runner.close()
            assertEquals(Result.BUSY, runner.read())
            assertTrue(threads.created.isEmpty())
            assertTrue(runner.quiescent())
        }
    }
}
