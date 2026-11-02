package io.github.originosfcmguardian.pushactivation

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.daemon.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PushActivationNotificationStopTest {
    private class Memory : PushActivationStorage {
        var value: PushActivationSnapshot? = null
        override fun read() = value
        override fun write(value: PushActivationSnapshot) { this.value = value }
        override fun <T> exclusive(action: () -> T): T = synchronized(this) { action() }
    }

    private class Owner : AutoCloseable {
        val memory = Memory()
        private val job = SupervisorJob()
        val controller = PushActivationController(PushActivationStore(memory), object : PushActivationPort {
            override fun installation() = PushActivationInstallation(10_123, 100, 106, 200, listOf("a".repeat(64)))
            override fun readiness() = PushActivationReadiness("same-owner", masterOn = true)
            override fun startMaster() = error("No master start in this fixture")
            override suspend fun dispatch(current: () -> Boolean, admit: (() -> Unit) -> Boolean): PushActivationDispatch =
                error("No FCM dispatch in this fixture")
        }, CoroutineScope(job + Dispatchers.Unconfined))
        override fun close() { job.cancel() }
    }

    @Test fun issuedOldTokenCannotCancelNewOnOrEvenEnterRemoteOffAdmission() = runBlocking {
        Owner().use { owner ->
            assertTrue(owner.controller.confirmExplicit())
            val oldToken = owner.controller.cancellationToken()
            assertTrue(owner.controller.confirmExplicit())
            val replacement = owner.memory.read()
            val commands = DaemonAppCommandAdmission()
            var admitted = false
            dispatchAutomaticNotificationStop("retired-session", oldToken) { _, token ->
                assertFalse(owner.controller.cancelExplicit(token, admit = {
                    admitted = true; commands.offer(DaemonAppCommand(DaemonWire.MASTER_OFF)); true
                }))
            }
            assertFalse(admitted); assertFalse(commands.busy())
            assertEquals(replacement, owner.memory.read())
            assertTrue(owner.controller.state.value.pending)
        }
    }

    @Test fun lostExactSessionAfterInitialMatchDoesNotRetireTicketOrOfferOff() = runBlocking {
        Owner().use { owner ->
            assertTrue(owner.controller.confirmExplicit())
            val before = owner.memory.read()
            val token = owner.controller.cancellationToken()
            assertFalse(owner.controller.cancelExplicit(token, admit = {
                legacyNegativeAdmission(true, "old", 4, 6, null, null, "replacement") != LegacyNegativeAdmission.STALE
            }))
            assertEquals(token, owner.controller.cancellationToken())
            assertEquals(before, owner.memory.read())
        }
    }

    @Test fun exactOriginalMigrationStopOffersOneOffAndLaterOnIsNotCancelledByItsCompletion() = runBlocking {
        Owner().use { owner ->
            assertTrue(owner.controller.confirmExplicit())
            val token = owner.controller.cancellationToken()
            val commands = DaemonAppCommandAdmission()
            var finishes = 0
            var finish: (() -> Unit)? = null
            assertTrue(owner.controller.cancelExplicit(token, admit = {
                check(matchesLegacyNotificationStop("original", null, null, true, "original"))
                check(legacyNegativeAdmission(true, "original", 4, 5, null, null, "original") == LegacyNegativeAdmission.REMOTE)
                assertEquals(DaemonAppCommandAdmission.Offer.START, commands.offer(DaemonAppCommand(DaemonWire.MASTER_OFF)))
                finish = { finishes++; commands.finishAndNext() }
                true
            }))
            assertTrue(commands.busy())
            assertEquals(PushActivationMode.RETIRED, owner.memory.read()?.mode)
            assertTrue(owner.controller.confirmExplicit())
            val replacement = owner.memory.read()
            checkNotNull(finish).invoke()
            assertEquals(1, finishes)
            assertEquals(replacement, owner.memory.read())
            assertTrue(owner.controller.state.value.pending)
            assertFalse(owner.controller.cancelExplicit(token, admit = { error("Old STOP replay") }))
        }
    }

    @Test fun replacementOnCannotInterleaveBetweenExactTokenDecisionAndRemoteOffOffer() = runBlocking {
        Owner().use { owner ->
            assertTrue(owner.controller.confirmExplicit())
            val token = owner.controller.cancellationToken()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val onStarted = CountDownLatch(1); val onFinished = CountDownLatch(1)
            val errors = AtomicReference<Throwable>()
            val commands = DaemonAppCommandAdmission()
            val stop = Thread {
                try {
                    assertTrue(owner.controller.cancelExplicit(token, admit = {
                        entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
                        assertEquals(DaemonAppCommandAdmission.Offer.START,
                            commands.offer(DaemonAppCommand(DaemonWire.MASTER_OFF)))
                        true
                    }))
                } catch (failure: Throwable) { errors.set(failure) }
            }
            val replacement = Thread {
                try { onStarted.countDown(); runBlocking { assertTrue(owner.controller.confirmExplicit()) } }
                catch (failure: Throwable) { errors.set(failure) }
                finally { onFinished.countDown() }
            }
            try {
                stop.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                replacement.start(); assertTrue(onStarted.await(2, TimeUnit.SECONDS))
                assertFalse(onFinished.await(50, TimeUnit.MILLISECONDS))
                release.countDown(); stop.join(2_000); replacement.join(2_000)
                assertFalse(stop.isAlive); assertFalse(replacement.isAlive)
                errors.get()?.let { throw it }
                assertTrue(commands.busy())
                assertTrue(owner.controller.state.value.pending)
                assertFalse(owner.controller.cancelExplicit(token, admit = { error("Stale old OFF") }))
            } finally { release.countDown(); stop.join(2_000); if (replacement.isAlive) replacement.join(2_000) }
        }
    }
}
