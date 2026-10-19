package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GmsAutoHeartbeatControllerTest {
    @Test fun bundledAbsentFirstProvisionDefersWithoutChildThenAssociatesExactlyOnce() = runBlocking {
        Fixture().use { f ->
            val epoch = UUID.randomUUID().toString()
            f.backend.bindingDigest = null
            assertEquals(BundledHeartbeatProvision.DEFERRED, f.controller.provisionBundled(epoch))
            assertNull(f.storage.bytes); assertEquals(0, f.backend.arms)
            f.backend.bindingDigest = f.backend.digest
            assertEquals(BundledHeartbeatProvision.ASSOCIATED, f.controller.provisionBundled(epoch))
            assertEquals(epoch, GmsAutoHeartbeatStore(f.storage).read()!!.durableConsent!!.epoch)
            assertEquals(BundledHeartbeatProvision.ASSOCIATED, f.controller.provisionBundled(epoch))
            assertEquals(1, f.backend.arms); assertEquals(0, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
        }
    }
    @Test fun bundledSavedChildCrashAndUnknownCannotBeReconfirmedByNextPoll() = runBlocking {
        Fixture().use { f ->
            val epoch = UUID.randomUUID().toString(); val store = GmsAutoHeartbeatStore(f.storage)
            val prepared = store.prepareConfirmation(null, f.backend.digest, true, bundledEpoch = epoch)!!
            // Parent already recorded this epoch; process died after child consent, before ARM.
            assertEquals(BundledHeartbeatProvision.BLOCKED, f.controller.provisionBundled(epoch))
            assertEquals(0, f.backend.arms)
            assertEquals(prepared.durableConsent!!.epoch, store.read()!!.durableConsent!!.epoch)
        }
        Fixture().use { f ->
            val epoch = UUID.randomUUID().toString()
            assertEquals(BundledHeartbeatProvision.ASSOCIATED, f.controller.provisionBundled(epoch))
            f.backend.result = GmsHeartbeatResult(1, GmsHeartbeatStatus.UNKNOWN, GmsHeartbeatReason.TIMED_OUT)
            f.due()
            assertEquals(BundledHeartbeatProvision.BLOCKED, f.controller.provisionBundled(epoch))
            assertEquals(1, f.backend.arms)
        }
    }
    @Test fun bundledOFFWhileFreshBindingIsPendingPreventsArm() = runBlocking {
        Fixture().use { f ->
            f.backend.bindingWait = CompletableDeferred()
            val request = async { f.controller.provisionBundled(UUID.randomUUID().toString()) }
            yield(); f.controller.disable(); f.backend.bindingWait!!.complete(Unit)
            request.await(); yield()
            assertEquals(0, f.backend.arms); assertFalse(f.controller.state.value.active)
        }
    }
    private class Backend : AutomaticHeartbeatBackend {
        val digest = "a".repeat(64)
        var arms = 0; var requests = 0; var revokes = 0
        var reads = 0
        val generations = mutableListOf<Long>()
        var revokeConfirmed = true
        var armWait: CompletableDeferred<Unit>? = null
        var requestWait: CompletableDeferred<Unit>? = null
        var revokeWait: CompletableDeferred<Unit>? = null
        var bindingWait: CompletableDeferred<Unit>? = null
        var ignoreBindingCancellation = false
        var ignoreArmCancellation = false
        var ignoreRequestCancellation = false
        var onRequest: () -> Unit = {}
        var bindingDigest: String? = digest
        override var lastBindingRejection: GmsHeartbeatReason? = null
        var result = GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)
        override suspend fun freshBindingDigest(): String? {
            reads++
            if (ignoreBindingCancellation) withContext(NonCancellable) { bindingWait?.await() } else bindingWait?.await()
            return bindingDigest
        }
        override suspend fun arm(generation: Long, digest: String): GmsAutoArmResult {
            arms++; generations.add(generation)
            if (ignoreArmCancellation) withContext(NonCancellable) { armWait?.await() } else armWait?.await()
            return GmsAutoArmResult(GmsAutoSession(UUID.randomUUID().toString(), generation, digest), GmsAutoArmReason.ARMED)
        }
        override suspend fun request(session: GmsAutoSession, attempt: String): GmsHeartbeatResult {
            requests++; onRequest()
            if (ignoreRequestCancellation) withContext(NonCancellable) { requestWait?.await() }
            else requestWait?.await()
            return result
        }
        override suspend fun revoke(session: GmsAutoSession): Boolean { revokes++; revokeWait?.await(); return revokeConfirmed }
    }
    private class Fixture : AutoCloseable {
        var now = 0L; var eligible = true
        val backend = Backend()
        val storage = HeartbeatMemoryStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val wake = Channel<Unit>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()
        val controller = GmsAutoHeartbeatController(GmsAutoHeartbeatStore(storage), scope, { now }) { remaining, manual ->
            waits.add(remaining)
            select { manual.onReceive { true }; wake.onReceive { false } }
        }
        init { controller.attachHost(HeartbeatHost("host", { eligible }, backend)) }
        suspend fun due() { now += GMS_AUTO_HEARTBEAT_INTERVAL_MILLIS; wake.send(Unit); yield() }
        override fun close() { scope.cancel(); wake.close() }
    }
    @Test fun waitsFullIntervalAfterConfirmationCompletionAndManualActivityWithoutCatchUp() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm()); assertEquals(0, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
            f.now = 899_999; f.wake.send(Unit); yield()
            assertEquals(0, f.backend.requests); assertEquals(1L, f.waits.last())
            f.now = 900_000; f.wake.send(Unit); yield()
            assertEquals(1, f.backend.requests); assertEquals(900_000L, f.waits.last())
            f.now = 1_000_000; f.controller.manualHeartbeatChanged(); yield()
            assertEquals(900_000L, f.waits.last())
            f.now = 1_899_999; f.wake.send(Unit); yield(); assertEquals(1, f.backend.requests)
            f.now = 10_000_000; f.wake.send(Unit); yield()
            assertEquals(2, f.backend.requests); assertEquals(900_000L, f.waits.last())
        }
    }
    @Test fun absentStartupStaysOffButMissingHostConfirmationHasSpecificDiagnosticWithoutPersistence() = runBlocking {
        Fixture().use { f ->
            f.controller.initialize()
            assertEquals("低频心跳未开启", f.controller.state.value.detail)
            assertNull(f.storage.bytes)
            f.controller.endHost("host"); yield()
            assertFalse(f.controller.confirm())
            assertEquals("目标保护服务会话尚未附接，未取得自动写权", f.controller.state.value.detail)
            assertFalse(f.controller.state.value.configured); assertFalse(f.controller.state.value.active)
            assertEquals(0, f.storage.writes); assertEquals(0, f.backend.arms)
        }
    }
    @Test fun inactiveHostConfirmationPreservesFailureReasonBeforeFirstPreferenceExists() = runBlocking {
        Fixture().use { f ->
            f.eligible = false
            assertFalse(f.controller.confirm())
            assertEquals("目标保护尚未满足运行资格（实际检测或当前应用授权），未取得自动写权", f.controller.state.value.detail)
            assertFalse(f.controller.state.value.configured); assertFalse(f.controller.state.value.active)
            assertNull(f.storage.bytes); assertEquals(0, f.storage.writes); assertEquals(0, f.backend.arms)
        }
    }
    @Test fun freshGmsUnknownPreservesBoundedPolicyReasonWithoutIdentityOrPreferenceWrite() = runBlocking {
        Fixture().use { f ->
            f.backend.bindingDigest = null
            f.backend.lastBindingRejection = GmsHeartbeatReason.OBSERVATION_UNKNOWN
            assertFalse(f.controller.confirm())
            assertEquals("当前 GMS 连接或安装身份无法确认（OBSERVATION_UNKNOWN），尚未取得自动写权", f.controller.state.value.detail)
            assertFalse(f.controller.state.value.detail.contains(f.backend.digest))
            assertFalse(f.controller.state.value.configured); assertFalse(f.controller.state.value.active)
            assertNull(f.storage.bytes); assertEquals(0, f.storage.writes); assertEquals(0, f.backend.arms)
        }
    }
    @Test fun busyAndAlreadyArmedConfirmationEntriesReportTheirReasons() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm()); assertFalse(f.controller.confirm())
            assertEquals("低频心跳已在当前保护会话确认", f.controller.state.value.detail)
            assertTrue(f.controller.state.value.active)
            f.backend.requestWait = CompletableDeferred()
            f.due(); assertTrue(f.controller.state.value.busy)
            assertFalse(f.controller.confirm())
            assertEquals("低频心跳正在处理，当前操作尚未结束", f.controller.state.value.detail)
            assertEquals(1, f.backend.arms)
        }
    }
    @Test fun temporaryHostIneligibleDefersOneFullIntervalWithoutGrantRevocation() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm()); f.eligible = false; f.due()
            assertEquals(0, f.backend.requests); assertEquals(0, f.backend.revokes)
            assertEquals(900_000L, f.waits.last()); assertFalse(f.controller.state.value.requiresConfirmation)
            f.eligible = true; f.due(); assertEquals(1, f.backend.requests)
        }
    }
    @Test fun pendingSaveFailureDoesNotDispatchAndOnlyPausesHeartbeat() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm()); f.storage.failWrite = true; f.due()
            assertEquals(0, f.backend.requests); assertFalse(f.controller.state.value.active)
            assertTrue(f.controller.state.value.requiresConfirmation); assertTrue(f.controller.state.value.paused)
            assertEquals(1, f.backend.revokes)
        }
    }
    @Test fun eligibilityLostDuringPendingCommitSettlesUnsentAndWaitsFullInterval() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm())
            f.storage.onWrite = { if (it.pendingAttempt != null) f.eligible = false }
            f.due()
            assertEquals(0, f.backend.requests); assertEquals(0, f.backend.revokes)
            assertNull(f.storage.read()!!.pendingAttempt)
            assertEquals(GmsHeartbeatStatus.NOT_SENT, f.storage.read()!!.lastStatus)
            assertEquals(GmsHeartbeatReason.CANCELLED, f.storage.read()!!.lastReason)
            assertTrue(f.controller.state.value.active); assertFalse(f.controller.state.value.requiresConfirmation)
            assertEquals(900_000L, f.waits.last())
            f.storage.onWrite = {}; f.eligible = true
            f.due(); assertEquals(1, f.backend.requests)
        }
    }
    @Test fun unknownNeverReplaysAndRequiresFreshExplicitConfirmation() = runBlocking {
        Fixture().use { f ->
            f.backend.result = GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT)
            assertTrue(f.controller.confirm()); f.due()
            assertEquals(1, f.backend.requests); assertFalse(f.controller.state.value.active)
            assertEquals(GmsHeartbeatStatus.UNKNOWN, f.controller.state.value.lastStatus)
            assertTrue(f.controller.state.value.requiresConfirmation)
            f.due(); assertEquals(1, f.backend.requests)
            assertTrue(f.controller.confirm()); assertEquals(1, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
        }
    }
    @Test fun resultSaveFailureLeavesPendingEvidenceAndPausesWithoutChangingPreference() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm())
            f.backend.onRequest = { f.storage.failWrite = true }
            f.due()
            assertEquals(1, f.backend.requests); assertFalse(f.controller.state.value.active)
            assertTrue(f.controller.state.value.configured); assertTrue(f.controller.state.value.requiresConfirmation)
            assertEquals(GmsHeartbeatStatus.UNKNOWN, f.controller.state.value.lastStatus)
            assertNotNull(f.storage.read()!!.pendingAttempt)
            f.due(); assertEquals(1, f.backend.requests)
        }
    }
    @Test fun terminalUnsentResultsRevokeOriginalSessionAndRequireExplicitConfirmation() = runBlocking {
        val terminal = GmsHeartbeatReason.entries.filter {
            it != GmsHeartbeatReason.COMMAND_ACCEPTED && it !in setOf(GmsHeartbeatReason.OBSERVATION_UNKNOWN,
                GmsHeartbeatReason.REPORTED_DISCONNECTED, GmsHeartbeatReason.STALE_OBSERVATION,
                GmsHeartbeatReason.BUSY, GmsHeartbeatReason.INTERVAL_NOT_ELAPSED, GmsHeartbeatReason.EXECUTION_NOT_READY)
        }
        for (reason in terminal) Fixture().use { f ->
            f.backend.result = GmsHeartbeatResult.notSent(1, reason)
            assertTrue(f.controller.confirm()); f.due()
            assertEquals(reason.name, 1, f.backend.revokes)
            assertFalse(reason.name, f.controller.state.value.active)
            assertTrue(reason.name, f.controller.state.value.requiresConfirmation)
            assertTrue(reason.name, f.controller.state.value.configured)
            assertEquals(GmsHeartbeatStatus.NOT_SENT, f.controller.state.value.lastStatus)
            f.due(); assertEquals(reason.name, 1, f.backend.requests)
        }
    }
    @Test fun transientUnsentObservationWaitsAnotherFullIntervalWithoutRevocation() = runBlocking {
        for (reason in listOf(GmsHeartbeatReason.OBSERVATION_UNKNOWN, GmsHeartbeatReason.EXECUTION_NOT_READY)) Fixture().use { f ->
            f.backend.result = GmsHeartbeatResult.notSent(1, reason)
            assertTrue(f.controller.confirm()); f.due()
            assertEquals(0, f.backend.revokes); assertTrue(f.controller.state.value.active)
            assertFalse(f.controller.state.value.requiresConfirmation); assertEquals(900_000L, f.waits.last())
            f.due(); assertEquals(2, f.backend.requests)
        }
    }
    @Test fun manualUnknownClosesAutomaticSessionAndPersistsUnknownWithoutTargetMutation() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm()); f.controller.manualUnknown(); yield()
            assertFalse(f.controller.state.value.active); assertTrue(f.controller.state.value.configured)
            assertTrue(f.controller.state.value.requiresConfirmation); assertEquals(1, f.backend.revokes)
            assertEquals(GmsHeartbeatStatus.UNKNOWN, f.storage.read()!!.lastStatus)
            f.due(); assertEquals(0, f.backend.requests)
        }
    }
    @Test fun disableClosesLocalGateAndUnconfirmedRevokeCannotRearm() = runBlocking {
        Fixture().use { f ->
            f.backend.revokeConfirmed = false
            assertTrue(f.controller.confirm()); f.controller.disable(); yield()
            assertFalse(f.controller.state.value.configured); assertFalse(f.controller.state.value.active)
            assertTrue(f.controller.state.value.stopPending); assertFalse(f.controller.confirm())
            assertEquals("停止待确认：旧会话远端撤权尚未确认，未取得新的自动写权", f.controller.state.value.detail)
            f.due(); assertEquals(0, f.backend.requests); assertEquals(1, f.backend.arms)
            f.backend.revokeConfirmed = true; f.controller.disable(); yield()
            assertFalse(f.controller.state.value.stopPending); assertFalse(f.controller.state.value.configured)
        }
    }
    @Test fun serviceSessionEndRequiresExplicitConfirmationAndInactiveHostCannotConfirm() = runBlocking {
        Fixture().use { f ->
            f.eligible = false; assertFalse(f.controller.confirm()); assertEquals(0, f.backend.arms)
            f.eligible = true; assertTrue(f.controller.confirm())
            f.controller.endHost("host"); yield()
            assertFalse(f.controller.state.value.active); assertTrue(f.controller.state.value.configured)
            assertTrue(f.controller.state.value.requiresConfirmation); assertFalse(f.controller.confirm())
            f.due(); assertEquals(0, f.backend.requests)
        }
    }
    @Test fun cancellationDuringPossiblyCommittedArmPersistsOffPendingAndBlocksNewArm() = runBlocking {
        Fixture().use { f ->
            f.backend.armWait = CompletableDeferred()
            val confirm = launch(start = CoroutineStart.UNDISPATCHED) { f.controller.confirm() }
            assertEquals(1, f.backend.arms)
            confirm.cancelAndJoin(); yield()
            assertFalse(f.controller.state.value.active); assertTrue(f.controller.state.value.stopPending)
            assertEquals(HeartbeatPause.OFF_PENDING, f.storage.read()!!.pause)
            assertFalse(f.controller.confirm()); assertEquals(1, f.backend.arms)
            assertEquals("旧自动会话建立结果不明，停止待确认；未取得新的自动写权", f.controller.state.value.detail)
        }
    }
    @Test fun reconstructedOffPendingWithoutOriginalEndpointStaysBlockedAfterRetry() = runBlocking {
        Fixture().use { f ->
            f.storage.bytes = HeartbeatSnapshotCodec.encode(HeartbeatSnapshot(7, false, HeartbeatPause.OFF_PENDING,
                bindingDigest = f.backend.digest, remoteSessionId = UUID.randomUUID().toString(),
                lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.CANCELLED))
            f.controller.initialize()
            assertTrue(f.controller.state.value.stopPending)
            assertTrue(f.controller.state.value.detail.contains("旧会话停止无法确认，本版本不自动恢复"))
            f.controller.disable(); yield()
            assertTrue(f.controller.state.value.stopPending)
            assertTrue(f.controller.state.value.detail.contains("旧会话停止无法确认，本版本不自动恢复"))
            assertFalse(f.controller.confirm()); assertEquals(0, f.backend.arms)
            assertEquals(0, f.backend.revokes); assertEquals(HeartbeatPause.OFF_PENDING, f.storage.read()!!.pause)
        }
    }
    @Test fun lateResultAfterOffCannotReactivateOrOverwriteOffSnapshot() = runBlocking {
        Fixture().use { f ->
            f.backend.requestWait = CompletableDeferred()
            f.backend.ignoreRequestCancellation = true
            assertTrue(f.controller.confirm()); f.due()
            assertEquals(1, f.backend.requests); assertNotNull(f.storage.read()!!.pendingAttempt)
            f.controller.disable(); yield()
            val off = f.storage.bytes!!.clone()
            f.backend.requestWait!!.complete(Unit); yield()
            assertArrayEquals(off, f.storage.bytes)
            assertFalse(f.controller.state.value.active); assertFalse(f.controller.state.value.configured)
            assertEquals(GmsHeartbeatStatus.UNKNOWN, f.controller.state.value.lastStatus)
        }
    }
    @Test fun corruptPreferenceCannotBeReplacedByConfirmationOrGenerateRemoteAuthority() = runBlocking {
        Fixture().use { f ->
            f.storage.bytes = byteArrayOf(1, 2, 3)
            assertFalse(f.controller.confirm()); assertEquals(0, f.backend.arms)
            assertEquals(0, f.backend.requests); assertTrue(f.controller.state.value.paused)
            assertArrayEquals(byteArrayOf(1, 2, 3), f.storage.bytes)
        }
    }
    @Test fun deadlineRejectsBackwardClockAndNeverRepeatsAnOverdueInterval() {
        val deadline = HeartbeatDeadline()
        deadline.reset(100); assertEquals(900_000L, deadline.remaining(100))
        assertEquals(0L, deadline.remaining(9_000_100)); deadline.reset(9_000_100)
        assertEquals(900_000L, deadline.remaining(9_000_100))
        assertThrows(IllegalArgumentException::class.java) { deadline.remaining(99) }
    }

    @Test fun durableCleanHostReplacementRearmsNewGenerationAndWaitsFullIntervalWithoutSending() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            val first = f.storage.read()!!
            f.now = 123_000
            f.controller.endHost("host"); yield()
            assertTrue(f.controller.state.value.waitingForHost)
            assertTrue(f.controller.state.value.resumeAuthorized)
            assertEquals(1, f.backend.revokes)
            f.controller.attachHost(HeartbeatHost("new-host", { f.eligible }, f.backend)); yield()
            val next = f.storage.read()!!
            assertTrue(f.controller.state.value.active); assertFalse(f.controller.state.value.requiresConfirmation)
            assertEquals(first.durableConsent, next.durableConsent)
            assertTrue(next.generation > first.generation); assertNotEquals(first.remoteSessionId, next.remoteSessionId)
            assertEquals(2, f.backend.arms); assertEquals(0, f.backend.requests); assertEquals(900_000L, f.waits.last())
            f.controller.endHost("host"); f.controller.privilegeLost("host"); yield()
            assertTrue(f.controller.state.value.active); assertEquals(1, f.backend.revokes)
        }
    }

    @Test fun durablePrivilegeReturnReadsAreSingleFlightAndFailedFreshSampleIsThrottledFifteenMinutes() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            f.backend.bindingDigest = null; f.backend.lastBindingRejection = GmsHeartbeatReason.OBSERVATION_UNKNOWN
            f.controller.privilegeLost("host"); yield()
            assertEquals(2, f.backend.reads); assertEquals(1, f.backend.arms)
            repeat(20) { f.controller.hostEligible("host"); f.controller.privilegeLost("host") }
            yield(); assertEquals(2, f.backend.reads)
            f.now = 899_999; f.wake.send(Unit); yield(); assertEquals(2, f.backend.reads)
            f.backend.bindingDigest = f.backend.digest; f.backend.lastBindingRejection = null
            f.now = 900_000; f.wake.send(Unit); yield()
            assertEquals(3, f.backend.reads); assertEquals(2, f.backend.arms)
            assertTrue(f.controller.state.value.active); assertEquals(900_000L, f.waits.last()); assertEquals(0, f.backend.requests)
        }
    }

    @Test fun mismatchedBindingAndSecurityAreStickyAcrossCleanLossAndNewHostUntilNewExplicitConsent() = runBlocking {
        for (security in listOf(false, true)) Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            if (security) f.controller.securityLost("host")
            else { f.backend.bindingDigest = "b".repeat(64); f.controller.privilegeLost("host") }
            yield()
            val block = if (security) HeartbeatResumeBlock.SECURITY_FAILURE else HeartbeatResumeBlock.IDENTITY_CHANGED
            assertEquals(block, f.storage.read()!!.resumeBlock)
            f.controller.endHost("host"); f.backend.bindingDigest = f.backend.digest
            f.controller.attachHost(HeartbeatHost("new", { true }, f.backend)); yield()
            assertEquals(1, f.backend.arms); assertTrue(f.controller.state.value.resumeBlocked)
            assertFalse(f.controller.state.value.resumeAuthorized); assertTrue(f.controller.state.value.requiresConfirmation)
            assertTrue(f.controller.confirmDurable()); assertNull(f.storage.read()!!.resumeBlock)
            assertTrue(f.controller.state.value.resumeAuthorized)
        }
    }

    @Test fun currentUnknownAndTerminalFailureAreNeverCleanLossButFreshExplicitEpochCanResume() = runBlocking {
        for (result in listOf(GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT),
                GmsHeartbeatResult.notSent(1, GmsHeartbeatReason.SESSION_REVOKED))) Fixture().use { f ->
            f.backend.result = result; assertTrue(f.controller.confirmDurable()); f.due()
            assertTrue(f.controller.state.value.resumeBlocked); assertFalse(f.controller.state.value.resumeAuthorized)
            f.controller.endHost("host"); f.controller.attachHost(HeartbeatHost("new", { true }, f.backend)); yield()
            assertEquals(1, f.backend.arms)
            assertTrue(f.controller.confirmDurable())
            f.controller.endHost("new"); f.controller.attachHost(HeartbeatHost("next", { true }, f.backend)); yield()
            assertEquals(3, f.backend.arms); assertTrue(f.controller.state.value.active)
            assertEquals(result.status, f.controller.state.value.lastStatus) // Historical evidence is still honest.
            assertEquals(1, f.backend.requests)
        }
    }

    @Test fun coldDurableControllerNeverRearmsEvenWhenFreshHostBecomesEligible() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable()); f.controller.endHost("host"); yield()
            val cold = GmsAutoHeartbeatController(GmsAutoHeartbeatStore(f.storage), f.scope, { f.now }) { remaining, manual ->
                select { manual.onReceive { true }; f.wake.onReceive { false } }
            }
            cold.attachHost(HeartbeatHost("cold", { true }, f.backend)); cold.initialize()
            repeat(5) { cold.hostEligible("cold") }; yield()
            assertEquals(1, f.backend.arms); assertTrue(cold.state.value.requiresConfirmation)
            assertFalse(cold.state.value.resumeAuthorized)
            assertEquals(HeartbeatResumeBlock.COLD_RECONSTRUCTION, f.storage.read()!!.resumeBlock)
        }
    }

    @Test fun unconfirmedOriginalRevokeBlocksNewHostAndOffRevokesDurableConsent() = runBlocking {
        Fixture().use { f ->
            f.backend.revokeConfirmed = false; assertTrue(f.controller.confirmDurable())
            f.controller.endHost("host"); f.controller.attachHost(HeartbeatHost("new", { true }, f.backend)); yield()
            repeat(5) { f.controller.hostEligible("new") }; yield()
            assertTrue(f.controller.state.value.stopPending); assertEquals(1, f.backend.arms)
            f.controller.disable(); yield()
            assertNull(f.storage.read()!!.durableConsent); assertFalse(f.controller.state.value.configured)
            f.backend.revokeConfirmed = true; f.controller.disable(); yield()
            assertFalse(f.controller.state.value.stopPending); assertFalse(f.controller.state.value.resumeAuthorized)
            assertEquals(1, f.backend.arms)
        }
    }

    @Test fun offDuringOriginalStopRejectsLateCleanAckAndNeverResumes() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable()); f.backend.revokeWait = CompletableDeferred()
            f.controller.privilegeLost("host"); yield()
            f.controller.disable(); f.controller.hostEligible("host")
            assertFalse(f.controller.state.value.active); assertFalse(f.controller.state.value.configured)
            f.backend.revokeWait!!.complete(Unit); yield()
            assertFalse(f.controller.state.value.stopPending); assertFalse(f.controller.state.value.resumeAuthorized)
            assertNull(f.storage.read()!!.durableConsent); assertEquals(1, f.backend.arms)
        }
    }

    @Test fun offDuringLateSuccessfulAutomaticArmStopsOriginalAndOldQueuedStopCannotRevokeNewConsent() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            f.backend.armWait = CompletableDeferred(); f.backend.ignoreArmCancellation = true
            f.controller.privilegeLost("host"); yield(); assertEquals(2, f.backend.arms)
            f.controller.disable(); f.backend.armWait!!.complete(Unit); yield()
            assertFalse(f.controller.state.value.active); assertFalse(f.controller.state.value.configured)
            // This non-cancellable fake returns its session: the original endpoint can actually acknowledge revoke.
            assertNull(f.storage.read()!!.durableConsent); assertFalse(f.controller.state.value.stopPending)
            assertEquals(2, f.backend.revokes); assertEquals(0, f.backend.requests)
            f.backend.armWait = null; assertTrue(f.controller.confirmDurable()); yield()
            val replacement = f.storage.bytes!!.clone()
            f.controller.hostEligible("host"); yield()
            assertEquals(3, f.backend.arms); assertEquals(2, f.backend.revokes)
            assertTrue(f.controller.state.value.active); assertArrayEquals(replacement, f.storage.bytes)
        }
    }

    @Test fun offDuringCancelledAutomaticArmWithoutCapturedSessionRetainsUncertainOwnerBlock() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            f.backend.armWait = CompletableDeferred() // Cancellation returns no session to the controller.
            f.controller.privilegeLost("host"); yield(); assertEquals(2, f.backend.arms)
            f.controller.disable(); yield()
            assertFalse(f.controller.state.value.active); assertFalse(f.controller.state.value.configured)
            assertNull(f.storage.read()!!.durableConsent); assertTrue(f.controller.state.value.stopPending)
            assertEquals(HeartbeatResumeBlock.UNCERTAIN_OWNER, f.storage.read()!!.resumeBlock)
            assertFalse(f.controller.confirmDurable()); f.controller.hostEligible("host"); yield()
            assertEquals(2, f.backend.arms); assertEquals(0, f.backend.requests)
        }
    }

    @Test fun lateOldResumeChildCannotEraseNewSingleFlightHandle() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable())
            f.backend.bindingWait = CompletableDeferred(); f.backend.ignoreBindingCancellation = true
            f.controller.privilegeLost("host"); yield(); assertEquals(2, f.backend.reads)
            f.controller.endHost("host"); f.controller.attachHost(HeartbeatHost("new", { true }, f.backend))
            f.backend.bindingWait!!.complete(Unit); yield()
            assertEquals(3, f.backend.reads); assertEquals(2, f.backend.arms)
            repeat(10) { f.controller.hostEligible("new") }; yield()
            assertEquals(3, f.backend.reads); assertTrue(f.controller.state.value.active)
        }
    }

    @Test fun acceptanceSubmissionUsesRealControllerLossAndCannotAcceptSessionOnlyConsent() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirm())
            assertFalse(f.controller.reattachForAcceptance(HeartbeatHost("host", { true }, f.backend)))
            f.controller.disable(); yield(); assertTrue(f.controller.confirmDurable())
            assertTrue(f.controller.reattachForAcceptance(HeartbeatHost("host", { true }, f.backend))); yield()
            assertEquals(3, f.backend.arms); assertTrue(f.controller.state.value.active)
            assertEquals(0, f.backend.requests); assertEquals(900_000L, f.waits.last())
        }
    }

    @Test fun failedResumeBackoffSurvivesHostReplacementRatherThanReadingOnEveryAttach() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.controller.confirmDurable()); f.backend.bindingDigest = null
            f.controller.privilegeLost("host"); yield(); assertEquals(2, f.backend.reads)
            repeat(5) { index ->
                val old = if (index == 0) "host" else "host-$index"
                f.controller.endHost(old)
                f.controller.attachHost(HeartbeatHost("host-${index + 1}", { true }, f.backend)); yield()
            }
            assertEquals(2, f.backend.reads); assertEquals(1, f.backend.arms)
            f.backend.bindingDigest = f.backend.digest; f.now = 900_000; f.wake.send(Unit); yield()
            assertEquals(3, f.backend.reads); assertEquals(2, f.backend.arms)
            assertEquals(900_000L, f.waits.last())
        }
    }
}
