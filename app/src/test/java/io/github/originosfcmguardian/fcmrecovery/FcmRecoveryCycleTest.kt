package io.github.originosfcmguardian.fcmrecovery

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class FcmRecoveryCycleTest {
    private class AuthorityStorage : FcmRecoveryStorage {
        var value = FcmRecoverySnapshot()
        var marker = false
        var mcsMarker: FcmRecoverySnapshot? = null
        var failConsume = false
        override fun read() = value.copy(consumed = value.consumed || marker,
            mcsConsumed = value.mcsConsumed || mcsMarker != null,
            mcsConsumedAt = mcsMarker?.mcsConsumedAt ?: value.mcsConsumedAt,
            mcsConsumedElapsed = mcsMarker?.mcsConsumedElapsed ?: value.mcsConsumedElapsed,
            mcsConsumedBoot = mcsMarker?.mcsConsumedBoot ?: value.mcsConsumedBoot)
        override fun write(value: FcmRecoverySnapshot) { this.value = value }
        override fun markConsumed(value: FcmRecoverySnapshot) {
            if (failConsume) throw IOException("mandatory consume failed")
            marker = true
        }
        override fun clearConsumed() { marker = false }
        override fun markMcsConsumed(value: FcmRecoverySnapshot) {
            if (failConsume) throw IOException("mandatory consume failed")
            mcsMarker = value
        }
        override fun clearMcsConsumed() { mcsMarker = null }
        override fun halt() { value = value.copy(configured = false) }
        override fun releaseHalt() = Unit
    }
    private class EvidenceStorage : FcmRecoveryEpisodeStorage {
        var value = FcmRecoveryEpisodeSnapshot(FcmEpisodeAvailability.AVAILABLE)
        var fail = false
        override fun read() = value
        override fun write(value: FcmRecoveryEpisodeSnapshot) {
            if (fail) throw IOException("optional journal failed")
            this.value = value
        }
    }
    private class Access : FcmRecoveryCycleAccess {
        val authority = AuthorityStorage()
        val evidence = EvidenceStorage()
        override val store = FcmRecoveryStore(authority)
        val journal = FcmRecoveryEpisodeJournal(evidence, 101, 0, { wallMillis() }, { elapsedMillis() }).also { it.initialize() }
        var elapsed = 10000L
        var allowed = true
        var currentGeneration = true
        var admitted = true
        var network = true
        var mcsNetwork = true
        var firstState = GcmConnectionState.SERVICE_ABSENT
        var afterState = GcmConnectionState.REPORTED_CONNECTED
        var unknownFirst = false
        var unknownAfter = false
        var optionalThrows = false
        var bindingChanged = false
        var sends = 0
        var reads = 0
        var finishedConnected: Boolean? = null
        var live: AtomicBoolean? = null
        var duringRequest: (() -> Unit)? = null
        var result = FcmRecoveryResult(1, FcmRecoveryStatus.REQUESTED, FcmRecoveryReason.COMMAND_ACCEPTED)
        var requestedOperation = FcmRecoveryOperation.SERVICE_ABSENT_SEND
        val operations = mutableListOf<FcmRecoveryOperation>()
        val records = mutableListOf<FcmRecoveryEventAction>()
        val observationEligibility = mutableListOf<Boolean>()

        init { store.confirm(FcmRecoveryPolicy.binding(sample(firstState))) }
        override fun wallMillis() = 1000000 + elapsed
        override fun elapsedMillis() = elapsed
        override fun current() = currentGeneration
        override fun authorized() = allowed && currentGeneration && network
        override fun admitted(sample: GmsConnectionObservation.Sample) = admitted
        override fun usableNetwork() = network
        override fun disconnectedNetwork() = network && mcsNetwork
        fun sample(state: GcmConnectionState) = GmsConnectionObservation.Sample(1, 10208, 263737035,
            1000, 2000, FcmRecoveryPolicy.SIGNER, "01234567-89ab-cdef-0123-456789abcdef",
            wallMillis(), elapsed, SocketSource(SourceCompleteness.COMPLETE, false),
            SocketSource(SourceCompleteness.COMPLETE, state == GcmConnectionState.REPORTED_CONNECTED), true, emptyList(),
            GcmServiceSource(state, SourceCompleteness.COMPLETE, wallMillis(), elapsed, 0))
        override suspend fun read(): GmsConnectionObservation {
            elapsed += 20; reads++
            if ((reads == 1 && unknownFirst) || (reads > 1 && unknownAfter)) return GmsConnectionObservation.Unknown(1, GmsUnknown.TIMED_OUT)
            return sample(if (reads == 1) firstState else afterState)
        }
        override suspend fun request(binding: String, authorizeDispatch: () -> Boolean): FcmRecoveryResult {
            elapsed += 10
            assertEquals(FcmRecoveryPolicy.binding(sample(firstState)), binding)
            assertTrue(authorizeDispatch()); assertFalse("Only one dispatch admission", authorizeDispatch())
            sends++
            operations += requestedOperation
            assertTrue("Consume marker precedes dispatch", authority.read().consumed(requestedOperation))
            if (!optionalThrows && !evidence.fail) assertTrue("Diagnostic correlation begins before the RPC", journal.snapshot().episodes.last().requestPending)
            duringRequest?.invoke()
            elapsed += 10
            return result.copy(operation = requestedOperation)
        }
        override suspend fun request(operation: FcmRecoveryOperation, binding: String, authorizeDispatch: () -> Boolean): FcmRecoveryResult {
            requestedOperation = operation; return request(binding, authorizeDispatch)
        }
        override fun retainLive(value: AtomicBoolean) { live = value }
        override fun observed(observation: GmsConnectionObservation, authorized: Boolean) {
            observationEligibility += authorized
            if (optionalThrows) throw IOException("optional observer failed")
            val sample = observation as? GmsConnectionObservation.Sample
            journal.observe(FcmRecoveryEvent(sample?.observedAtMillis ?: wallMillis(), sample?.elapsedRealtimeMillis ?: elapsed,
                FcmRecoveryEventAction.OBSERVATION, sample?.gcmService?.state ?: GcmConnectionState.UNKNOWN,
                sample?.gcmService?.completeness ?: SourceCompleteness.UNAVAILABLE,
                sample?.observation ?: SocketObservation.UNKNOWN, authorized),
                sample != null && FcmRecoveryPolicy.qualified(sample, elapsed),
                sample != null && FcmRecoveryPolicy.connected(sample, elapsed))
        }
        override fun record(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample?,
            authorized: Boolean, result: FcmRecoveryResult?, unknown: GmsUnknown?) { records += action }
        override fun requestStarted(event: FcmRecoveryEvent, correlation: String) {
            if (optionalThrows) throw IOException("optional begin failed")
            journal.requestStarted(event, correlation)
        }
        override fun requestCompleted(event: FcmRecoveryEvent, correlation: String) {
            if (optionalThrows) throw IOException("optional completion failed")
            journal.requestCompleted(event, correlation)
        }
        override fun bindingChanged(sample: GmsConnectionObservation.Sample, saved: FcmRecoverySnapshot) { bindingChanged = true }
        override fun finished(saved: FcmRecoverySnapshot, result: FcmRecoveryResult, connected: Boolean) { finishedConnected = connected }
        fun endHost() {
            allowed = false; currentGeneration = false
            live?.set(false)
            journal.coverageGap(wallMillis(), elapsed)
        }
    }

    @Test fun absentProductionCycleConsumesDispatchesOnceAndObservesConnection() = runBlocking {
        val access = Access(); FcmRecoveryCycle(access).run()
        assertEquals(1, access.sends); assertEquals(2, access.reads)
        assertEquals(listOf(FcmRecoveryEventAction.OBSERVATION, FcmRecoveryEventAction.ATTEMPT_CONSUMED,
            FcmRecoveryEventAction.REQUEST_RESULT, FcmRecoveryEventAction.READBACK), access.records)
        assertFalse(access.authority.marker); assertFalse(access.store.read().consumed)
        val episode = access.journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST, episode.outcome)
        assertFalse(episode.requestPending); assertNotNull(episode.resultAtMillis)
        assertTrue(episode.resultElapsedMillis!! >= episode.requestElapsedMillis!!)
        assertEquals(true, access.finishedConnected); assertFalse(access.live!!.get())
    }

    @Test fun consumedAbsenceDoesNotRetryWithoutQualifiedConnection() = runBlocking {
        val access = Access().also { it.afterState = GcmConnectionState.SERVICE_ABSENT }
        FcmRecoveryCycle(access).run(); FcmRecoveryCycle(access).run()
        assertEquals(1, access.sends); assertTrue(access.store.read().consumed)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, access.journal.snapshot().episodes.single().outcome)
    }

    @Test fun unknownOrInadmissibleSourceNeverDispatchesMissingCandidate() = runBlocking {
        for (mode in 0..3) {
            val access = Access()
            when (mode) {
                0 -> { access.firstState = GcmConnectionState.REPORTED_DISCONNECTED; access.mcsNetwork = false }
                1 -> access.unknownFirst = true
                2 -> access.allowed = false
                3 -> access.admitted = false
            }
            FcmRecoveryCycle(access).run()
            assertEquals(0, access.sends); assertFalse(access.authority.marker)
        }
    }

    @Test fun installationBindingChangeCannotDispatch() = runBlocking {
        val access = Access(); access.authority.value = access.authority.value.copy(binding = "b".repeat(64))
        FcmRecoveryCycle(access).run(); assertTrue(access.bindingChanged); assertEquals(0, access.sends)
    }

    @Test fun inadmissibleInitialIdentityCannotStartQualifiedDiagnosticEpisode() = runBlocking {
        val access = Access().also { it.admitted = false }
        FcmRecoveryCycle(access).run()
        assertEquals(listOf(false), access.observationEligibility)
        assertTrue(access.journal.snapshot().episodes.isEmpty())
        assertEquals(0, access.sends)
    }

    @Test fun inadmissibleReadbackIdentityClosesOldRequestAssociation() = runBlocking {
        val access = Access().also { value -> value.duringRequest = { value.admitted = false } }
        FcmRecoveryCycle(access).run()
        assertEquals(listOf(true, false), access.observationEligibility)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, access.journal.snapshot().episodes.single().outcome)
        assertEquals(FcmRecoveryEpisodeBlock.INELIGIBLE, access.journal.snapshot().episodes.single().lastBlock)
    }

    @Test fun hostEndsDuringRpcPreservesLateResultButNeverReadbacksOrCreditsRecovery() = runBlocking {
        val access = Access().also { it.duringRequest = { it.endHost() } }
        FcmRecoveryCycle(access).run()
        assertEquals(1, access.reads); assertEquals(1, access.sends); assertTrue(access.store.read().consumed)
        val episode = access.journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, episode.outcome)
        assertEquals(FcmRecoveryStatus.REQUESTED, episode.requestStatus); assertFalse(episode.requestPending)
        assertNotNull(episode.resultAtMillis); assertNull(access.finishedConnected); assertFalse(access.live!!.get())
    }

    @Test fun cancellationDuringRpcClosesLiveAuthorityAndPreservesUnknownOnOriginalGap() {
        val access = Access().also { a -> a.duringRequest = { a.endHost(); throw CancellationException("host ended") } }
        assertThrows(CancellationException::class.java) { runBlocking { FcmRecoveryCycle(access).run() } }
        val episode = access.journal.snapshot().episodes.single()
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, episode.outcome)
        assertEquals(FcmRecoveryStatus.UNKNOWN, episode.requestStatus)
        assertEquals(FcmRecoveryReason.CANCELLED, episode.reason); assertFalse(episode.requestPending)
        assertTrue(access.store.read().consumed); assertFalse(access.live!!.get()); assertEquals(1, access.reads)
    }

    @Test fun unknownReadbackCannotRearmOrCountAsRecovered() = runBlocking {
        val access = Access().also { it.unknownAfter = true }; FcmRecoveryCycle(access).run()
        assertTrue(access.store.read().consumed); assertEquals(false, access.finishedConnected)
        assertEquals(FcmRecoveryEpisodeOutcome.COVERAGE_GAP, access.journal.snapshot().episodes.single().outcome)
    }

    @Test fun optionalDiagnosticCallbackAndStorageFailuresNeverStopCoreRecovery() = runBlocking {
        for (callbackFailure in listOf(true, false)) {
            val access = Access().also { it.optionalThrows = callbackFailure; it.evidence.fail = !callbackFailure }
            FcmRecoveryCycle(access).run(); assertEquals(1, access.sends); assertEquals(true, access.finishedConnected)
            assertFalse(access.store.read().consumed); assertFalse(access.live!!.get())
        }
    }

    @Test fun failedMandatoryConsumePreventsDispatch() {
        val access = Access().also { it.authority.failConsume = true }
        assertThrows(IOException::class.java) { runBlocking { FcmRecoveryCycle(access).run() } }
        assertEquals(0, access.sends)
    }

    @Test fun freshDisconnectedUsesOnlyMcsOnceAndDoesNotConsumeAbsence() = runBlocking {
        val access = Access().also { it.firstState = GcmConnectionState.REPORTED_DISCONNECTED; it.afterState = it.firstState }
        FcmRecoveryCycle(access).run()
        assertEquals(listOf(FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS), access.operations)
        assertTrue(access.store.read().mcsConsumed); assertFalse(access.authority.marker)
        assertEquals(false, access.finishedConnected)
        assertEquals(FcmRecoveryStatus.REQUESTED, access.journal.snapshot().episodes.single().requestStatus)
        assertEquals(FcmRecoveryEpisodeOutcome.OBSERVING, access.journal.snapshot().episodes.single().outcome)
        FcmRecoveryCycle(access).run(); assertEquals(1, access.sends)
        assertEquals(FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS, access.journal.snapshot().episodes.single().requestOperation)
    }

    @Test fun mcsCommandFailureAndUnknownReadbackCannotClaimConnectionOrReplayAttempt() = runBlocking {
        val access = Access().also {
            it.firstState = GcmConnectionState.REPORTED_DISCONNECTED; it.unknownAfter = true
            it.result = FcmRecoveryResult(1, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.COMMAND_FAILED)
        }
        FcmRecoveryCycle(access).run()
        val request = access.journal.snapshot().episodes.first { it.requestOperation == FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS }
        assertEquals(FcmRecoveryStatus.UNKNOWN, request.requestStatus)
        assertEquals(FcmRecoveryReason.COMMAND_FAILED, request.reason)
        assertEquals(FcmRecoveryEpisodeOutcome.REQUEST_UNKNOWN, request.outcome)
        assertEquals(false, access.finishedConnected); assertTrue(access.store.read().mcsConsumed)
        assertTrue(access.journal.snapshot().episodes.none { it.outcome == FcmRecoveryEpisodeOutcome.CONNECTION_AFTER_REQUEST })
        access.unknownAfter = false; access.afterState = GcmConnectionState.REPORTED_DISCONNECTED
        FcmRecoveryCycle(access).run(); assertEquals(1, access.sends)
    }

    @Test fun mcsFailureThenServiceAbsentStillUsesLegacySendAndFreezesOriginalKind() = runBlocking {
        val access = Access().also {
            it.firstState = GcmConnectionState.REPORTED_DISCONNECTED; it.afterState = it.firstState
            it.result = FcmRecoveryResult(1, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.TIMED_OUT)
        }
        FcmRecoveryCycle(access).run()
        access.afterState = GcmConnectionState.SERVICE_ABSENT
        FcmRecoveryCycle(access).run()
        assertEquals(listOf(FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS, FcmRecoveryOperation.SERVICE_ABSENT_SEND), access.operations)
        assertTrue(access.store.read().mcsConsumed); assertTrue(access.store.read().consumed)
        val requests = access.journal.snapshot().episodes.filter { it.requestOperation != null }
        assertEquals(listOf(FcmRecoveryEpisodeKind.PRESENT_DISCONNECTED, FcmRecoveryEpisodeKind.SERVICE_ABSENT), requests.map { it.kind })
    }

    @Test fun mcsNetworkLossUnknownAndNewRuntimeCannotReplayConsumedAttempt() = runBlocking {
        val access = Access().also { it.firstState = GcmConnectionState.REPORTED_DISCONNECTED; it.afterState = it.firstState }
        access.mcsNetwork = false; FcmRecoveryCycle(access).run(); assertEquals(0, access.sends)
        access.mcsNetwork = true; FcmRecoveryCycle(access).run(); assertEquals(1, access.sends)
        access.unknownAfter = true; FcmRecoveryCycle(access).run()
        access.unknownAfter = false; FcmRecoveryCycle(access).run(); assertEquals(1, access.sends)
        access.store.disable(); access.store.confirm(FcmRecoveryPolicy.binding(access.sample(access.firstState)))
        FcmRecoveryCycle(access).run(); assertEquals(1, access.sends)
    }
}
