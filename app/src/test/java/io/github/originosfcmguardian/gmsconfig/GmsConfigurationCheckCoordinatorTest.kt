package io.github.originosfcmguardian.gmsconfig

import io.github.originosfcmguardian.core.EvidenceStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production publication boundary; Android/Binder reads are supplied at its seam. */
class GmsConfigurationCheckCoordinatorTest {
    private val context = GmsConfigContext("test/device/build", 0, 1, null)
    private fun sample(status: EvidenceStatus) = GmsConfigSnapshot(context, 1_000, 100,
        status, status, status, status, status, GmsConfigFailure.NONE)
    private fun unknown(failure: GmsConfigFailure) = unknownGmsConfig(context, 1_000, 100, failure)

    private suspend fun check(
        budgetMillis: Long = 5_000,
        read: suspend () -> GmsConfigSnapshot? = { sample(EvidenceStatus.CONFORMING) },
        eligible: () -> Boolean = { true },
        publish: (GmsConfigSnapshot) -> Unit,
    ) = GmsConfigurationCheckCoordinator.check(budgetMillis, read,
        { unknown(GmsConfigFailure.TIMED_OUT) }, { unknown(GmsConfigFailure.READ_FAILED) }, eligible, publish)

    @Test fun newerAnomalyWaitsUntilOlderHealthyPublicationCompletes() = runBlocking {
        val eligibleEntered = CompletableDeferred<Unit>()
        val releaseEligible = CountDownLatch(1)
        val publications = CopyOnWriteArrayList<EvidenceStatus>()
        var newerRead = false
        val older = launch(Dispatchers.Default) {
            check(eligible = {
                eligibleEntered.complete(Unit)
                check(releaseEligible.await(5, TimeUnit.SECONDS))
                true
            }, publish = { publications.add(it.gms) })
        }
        withTimeout(5_000) { eligibleEntered.await() }
        val newer = launch(start = CoroutineStart.UNDISPATCHED) {
            check(read = { newerRead = true; sample(EvidenceStatus.ANOMALOUS) },
                publish = { publications.add(it.gms) })
        }
        try {
            assertFalse("Newer read must wait through the older eligibility/commit boundary", newerRead)
        } finally {
            releaseEligible.countDown()
            joinAll(older, newer)
        }
        assertEquals(listOf(EvidenceStatus.CONFORMING, EvidenceStatus.ANOMALOUS), publications)
    }

    @Test fun timeoutWaitingForAdmissionDoesNotReadOrPublish() = runBlocking {
        val admitted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            check(read = { admitted.complete(Unit); releaseRead.await(); null }, publish = { fail("No result") })
        }
        admitted.await()
        var reads = 0
        val publications = mutableListOf<GmsConfigSnapshot>()
        try {
            check(budgetMillis = 50, read = { reads++; sample(EvidenceStatus.CONFORMING) }, publish = publications::add)
            assertEquals(0, reads)
            assertTrue("Unadmitted timeout must preserve the stored observation", publications.isEmpty())
        } finally {
            releaseRead.complete(Unit)
            holder.join()
        }
    }

    @Test fun admittedTimeoutPublishesUnknownOnlyWhileEligible() = runBlocking {
        val publications = mutableListOf<GmsConfigSnapshot>()
        check(budgetMillis = 50, read = { awaitCancellation() }, publish = publications::add)
        assertEquals(GmsConfigFailure.TIMED_OUT, publications.single().failure)
        assertTrue(publications.single().settings.all { it == EvidenceStatus.UNKNOWN })
        publications.clear()
        check(budgetMillis = 50, read = { awaitCancellation() }, eligible = { false }, publish = publications::add)
        assertTrue(publications.isEmpty())
    }

    @Test fun cancellationInsideFinalEligibilityDoesNotPublish() = runBlocking {
        val publications = mutableListOf<GmsConfigSnapshot>()
        val owner = launch(start = CoroutineStart.LAZY) {
            val ownerJob = currentCoroutineContext().job
            check(eligible = { ownerJob.cancel(); true }, publish = publications::add)
        }
        owner.start()
        owner.join()
        assertTrue(owner.isCancelled)
        assertTrue("Owner cancellation after eligibility must prevent commit", publications.isEmpty())
    }

    @Test fun ordinaryReadCancellationPropagatesWithoutPublication() = runBlocking {
        val publications = mutableListOf<GmsConfigSnapshot>()
        try {
            check(read = { throw CancellationException("owner retired") }, publish = publications::add)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(publications.isEmpty())
    }

    @Test fun readFailurePublishesUnknownAndPublicationFailureEscapes() = runBlocking {
        val publications = mutableListOf<GmsConfigSnapshot>()
        check(read = { error("read failed") }, publish = publications::add)
        assertEquals(GmsConfigFailure.READ_FAILED, publications.single().failure)
        assertTrue(publications.single().settings.all { it == EvidenceStatus.UNKNOWN })
        val failure = IllegalStateException("commit failed")
        try {
            check(publish = { throw failure })
            fail("Publication failure must propagate")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy the exception across withTimeout.
            assertEquals(failure.message, actual.message)
        }
    }
}
