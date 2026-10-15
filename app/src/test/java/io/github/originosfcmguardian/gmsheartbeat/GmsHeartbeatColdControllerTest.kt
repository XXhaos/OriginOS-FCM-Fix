package io.github.originosfcmguardian.gmsheartbeat

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GmsHeartbeatColdControllerTest {
    @Test fun migrationClosesMemoryFirstThenRequiresOriginalEndpointAcknowledgementWithoutNewConsent() = runBlocking {
        Fixture(cold = false).use { f ->
            assertTrue(f.controller.confirmDurable())
            val before = f.store.read()!!
            val exact = f.storage.read()
            f.backend.revokeWait = CompletableDeferred()
            f.controller.prepareForDaemonMigration()
            assertEquals(exact, f.storage.read())
            assertFalse(f.controller.state.value.active)
            assertFalse(f.controller.migrationEndpointClosureAttested())
            val drain = async { f.controller.awaitDaemonMigrationDrain() }
            yield()
            assertFalse(drain.isCompleted)
            assertFalse(f.controller.migrationEndpointClosureAttested())
            assertEquals(1, f.backend.revokes)
            assertEquals(1, f.backend.arms)
            f.backend.revokeWait!!.complete(Unit)
            assertTrue(drain.await())
            assertTrue(f.controller.migrationEndpointClosureAttested())
            val after = f.store.read()!!
            assertEquals(before.durableConsent, after.durableConsent)
            assertEquals(before.callerIdentity, after.callerIdentity)
            assertEquals(before.generation, after.generation)
            assertEquals(before.lastStatus, after.lastStatus)
            assertTrue(after.configured)
            assertEquals(HeartbeatPause.WAITING_HOST, after.pause)
            assertNull(after.remoteSessionId)
            assertFalse(f.controller.confirmDurable())
            assertEquals(1, f.backend.arms)
        }
    }

    @Test fun migrationExportsColdActiveRootInertWithoutNormalizingOrQualifyingItsOldCaller() = runBlocking {
        for (version in 2..3) Fixture(cold = true, consentVersion = version).use { f ->
            val before = f.store.read()
            f.controller.prepareForDaemonMigration()
            assertTrue(f.controller.awaitDaemonMigrationDrain())
            assertFalse(f.controller.migrationEndpointClosureAttested())
            assertEquals(before, f.store.read())
            assertEquals(0, f.backend.revokes)
            assertEquals(0, f.backend.arms)
            assertEquals(0, f.backend.qualificationCount)
        }
    }

    @Test fun coldMigrationWaitsForCurrentWorkersAndNeverReleasesDurableBarriers() = runBlocking {
        Fixture(cold = true).use { f ->
            val before = f.store.read()
            val worker = f.scope.launch { awaitCancellation() }
            f.controller.prepareForDaemonMigration()
            assertFalse(f.controller.awaitDaemonMigrationDrain())
            worker.cancelAndJoin()
            for (barrier in 0..2) {
                f.storage.haltPresent = barrier == 0
                f.storage.interruptedAtomic = barrier == 1
                f.storage.damagedPending = barrier == 2
                val events = f.storage.events.toList()
                assertFalse(f.controller.awaitDaemonMigrationDrain())
                assertEquals(events, f.storage.events)
                assertEquals(before, f.store.read())
            }
            f.storage.damagedPending = false
            assertTrue(f.controller.awaitDaemonMigrationDrain())
            assertFalse(f.controller.migrationEndpointClosureAttested())
        }
    }

    @Test fun coldMigrationRejectsEveryUnknownEpochPendingAndNonWaitingPauseWithoutChangingBytes() = runBlocking {
        Fixture(cold = true).use { f ->
            val active = f.store.read()!!
            f.controller.prepareForDaemonMigration()
            val rejected = listOf(
                active.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN, lastReason = GmsHeartbeatReason.BRIDGE_UNAVAILABLE,
                    lastResultEpoch = UUID.randomUUID().toString()),
                active.copy(pendingAttempt = UUID.randomUUID().toString()),
                active.copy(pendingArm = UUID.randomUUID().toString()),
                active.copy(resumeBlock = HeartbeatResumeBlock.UNCERTAIN_OWNER),
                active.copy(pause = HeartbeatPause.OFF_PENDING),
                active.copy(pause = HeartbeatPause.HOST_LOST),
                active.copy(remoteSessionId = null),
                active.copy(pause = HeartbeatPause.WAITING_HOST),
                active.copy(pause = HeartbeatPause.WAITING_HOST, remoteSessionId = null,
                    durableConsent = active.durableConsent!!.copy(version = 1)),
            )
            for (saved in rejected) {
                f.storage.write(saved)
                val events = f.storage.events.toList()
                assertFalse(saved.toString(), f.controller.awaitDaemonMigrationDrain())
                assertEquals(saved, f.store.read())
                assertEquals(events, f.storage.events)
                assertFalse(f.controller.migrationEndpointClosureAttested())
            }
            assertEquals(0, f.backend.qualificationCount)
            assertEquals(0, f.backend.arms)
            assertEquals(0, f.backend.revokes)
        }
    }

    @Test fun migrationCannotAcknowledgeAnUnconfirmedOriginalRevoke() = runBlocking {
        Fixture(cold = false).use { f ->
            assertTrue(f.controller.confirmDurable())
            val before = f.store.read()!!
            f.backend.revokeConfirmed = false
            f.controller.prepareForDaemonMigration()
            assertFalse(f.controller.awaitDaemonMigrationDrain())
            assertFalse(f.controller.migrationEndpointClosureAttested())
            val after = f.store.read()!!
            assertTrue(after.configured)
            assertEquals(before.durableConsent, after.durableConsent)
            assertEquals(before.remoteSessionId, after.remoteSessionId)
            assertEquals(HeartbeatPause.OFF_PENDING, after.pause)
            assertEquals(1, f.backend.arms)
        }
    }

    @Test fun cleanWaitingMigrationRootIsReadOnlyAndPendingCannotBecomeClean() = runBlocking {
        Fixture(cold = true).use { f ->
            val active = f.store.read()!!
            val waiting = active.copy(pause = HeartbeatPause.WAITING_HOST, remoteSessionId = null)
            f.storage.write(waiting)
            f.controller.prepareForDaemonMigration()
            assertTrue(f.controller.awaitDaemonMigrationDrain())
            assertFalse(f.controller.migrationEndpointClosureAttested())
            assertEquals(waiting, f.store.read())
            assertFalse(cleanHeartbeatDaemonMigrationRoot(active.copy(pendingAttempt = UUID.randomUUID().toString())))
            assertFalse(cleanHeartbeatDaemonMigrationRoot(waiting.copy(resumeBlock = HeartbeatResumeBlock.UNKNOWN_RESULT)))
            assertFalse(cleanHeartbeatDaemonMigrationRoot(waiting.copy(lastStatus = GmsHeartbeatStatus.UNKNOWN,
                lastReason = GmsHeartbeatReason.BRIDGE_UNAVAILABLE, lastResultEpoch = waiting.durableConsent!!.epoch)))
            assertEquals(0, f.backend.arms)
        }
    }
    private val digest = "c".repeat(64)
    private val oldOwner = GmsHeartbeatCallerIdentity(10123, 5001, 100L, "12345678-1234-1234-1234-123456789abc")
    private val newOwner = oldOwner.copy(pid = 5002, startTicks = 200L)
    private inner class Backend : AutomaticHeartbeatBackend {
        override val callerQualificationRequired = true
        var requireBundle = false
        var parentEpoch: String? = null
        override val bundledEpochRequired get() = requireBundle
        override fun bundleAuthorized(epoch: String?) = !requireBundle || epoch != null && epoch == parentEpoch
        var identity: GmsHeartbeatCallerIdentity? = newOwner
        var proof = GmsHeartbeatCallerReason.OLD_INSTANCE_GONE
        var qualifiedPrevious: GmsHeartbeatCallerIdentity? = null
        var qualificationCount = 0; var arms = 0; var requests = 0; var revokes = 0
        var armReason = GmsAutoArmReason.ARMED
        var revokeWait: CompletableDeferred<Unit>? = null
        var bindingWait: CompletableDeferred<Unit>? = null
        var matchingDigest: String? = digest
        var result = GmsHeartbeatResult(1, GmsHeartbeatStatus.REQUESTED, GmsHeartbeatReason.COMMAND_ACCEPTED)
        var beforeArm: (() -> Unit)? = null
        override var manualUnknownSerial = 0L
        var unknownDuringArm = false
        var revokeConfirmed = true
        override suspend fun qualifyCaller(previous: GmsHeartbeatCallerIdentity?): GmsHeartbeatCallerQualification {
            qualifiedPrevious = previous; qualificationCount++
            return GmsHeartbeatCallerQualification(identity, if (previous == null) GmsHeartbeatCallerReason.CURRENT_ONLY else proof)
        }
        override suspend fun freshBindingDigest(): String? { bindingWait?.await(); return matchingDigest }
        override suspend fun arm(generation: Long, digest: String): GmsAutoArmResult {
            beforeArm?.invoke()
            arms++
            if (unknownDuringArm) manualUnknownSerial++
            return GmsAutoArmResult(if (armReason == GmsAutoArmReason.ARMED) GmsAutoSession(UUID.randomUUID().toString(), generation, digest) else null, armReason)
        }
        override suspend fun request(session: GmsAutoSession, attempt: String): GmsHeartbeatResult { requests++; return result }
        override suspend fun revoke(session: GmsAutoSession): Boolean { revokes++; revokeWait?.await(); return revokeConfirmed }
    }
    private inner class Fixture(cold: Boolean = true, consentVersion: Int = 3, crossBoot: Boolean = false) : AutoCloseable {
        var now = 500_000L; var eligible = true
        val storage = ColdHeartbeatStorage()
        val store = GmsAutoHeartbeatStore(storage)
        val backend = Backend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val wake = Channel<Unit>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()
        val controller = GmsAutoHeartbeatController(store, scope, { now }) { remaining, manual ->
            waits += remaining; select { manual.onReceive { true }; wake.onReceive { false } }
        }
        init {
            if (crossBoot) {
                backend.identity = newOwner.copy(kernelBootId = "87654321-1234-1234-1234-123456789abc")
                backend.proof = GmsHeartbeatCallerReason.BOOT_CHANGED
            }
            if (cold) {
                val consent = store.prepareConfirmation(null, digest, true, oldOwner)!!
                val op = UUID.randomUUID().toString(); store.beginArm(consent.generation, op)
                store.armed(consent.generation, UUID.randomUUID().toString())!!.also {
                    store.settleArm(it, op); store.releaseExplicitConsent(it)
                    if (consentVersion != 3) storage.write(it.copy(durableConsent = it.durableConsent!!.copy(version = consentVersion)))
                }
            }
            controller.attachHost(HeartbeatHost("fresh-host", { eligible }, backend))
        }
        suspend fun initialize() { controller.initialize(); yield() }
        override fun close() { scope.cancel(); wake.close() }
    }
    @Test fun oldCallerGoneAndFreshHostRearmNewOwnerSameEpochAndWaitFull15Minutes() = runBlocking {
        Fixture().use { f ->
            val old = f.store.read()!!; f.initialize()
            val fresh = f.store.read()!!
            assertEquals(old.durableConsent, fresh.durableConsent)
            assertEquals(oldOwner, f.backend.qualifiedPrevious); assertEquals(newOwner, fresh.callerIdentity)
            assertTrue(f.controller.state.value.active); assertEquals(1, f.backend.arms)
            assertNotEquals(old.remoteSessionId, fresh.remoteSessionId)
            assertTrue(fresh.generation > old.generation); assertEquals(900_000L, f.waits.last())
            assertEquals(0, f.backend.requests); assertFalse(f.controller.manualHeartbeatAllowed())
            f.now += 899_999; f.wake.send(Unit); yield()
            assertEquals(0, f.backend.requests); assertEquals(1L, f.waits.last())
        }
    }
    @Test fun completeParentBDoesNotColdResumeLegacyChildAEvenOnSameInstallation() = runBlocking {
        Fixture().use { f ->
            val childA = f.store.read()!!.durableConsent!!.epoch
            f.backend.requireBundle = true; f.backend.parentEpoch = UUID.randomUUID().toString()
            assertNotEquals(childA, f.backend.parentEpoch)
            f.initialize(); yield()
            assertEquals(0, f.backend.arms); assertEquals(0, f.backend.requests)
            assertFalse(f.controller.confirmDurable()) // Ordinary legacy entry cannot mint random child C.
            assertEquals(0, f.backend.arms); assertEquals(childA, f.store.read()!!.durableConsent!!.epoch)
        }
    }
    @Test fun associatedBundleEpochStillUsesQualifiedColdContinuationAndFullInterval() = runBlocking {
        Fixture().use { f ->
            f.backend.requireBundle = true; f.backend.parentEpoch = f.store.read()!!.durableConsent!!.epoch
            f.initialize()
            assertEquals(1, f.backend.arms); assertEquals(0, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
        }
    }
    @Test fun newExplicitConsentCrossBootRearmsNewOwnerSameEpochAndWaitsFull15Minutes() = runBlocking {
        Fixture(crossBoot = true).use { f ->
            val old = f.store.read()!!
            f.backend.beforeArm = {
                val intent = f.store.read()!!
                assertEquals(f.backend.identity, intent.callerIdentity)
                assertTrue(intent.generation > old.generation)
                assertEquals(old.durableConsent, intent.durableConsent)
                assertNotNull(intent.pendingArm)
                assertNotNull(f.storage.pendingValue)
            }
            f.initialize()
            val fresh = f.store.read()!!
            assertEquals(old.durableConsent, fresh.durableConsent)
            assertEquals(oldOwner, f.backend.qualifiedPrevious)
            assertEquals(f.backend.identity, fresh.callerIdentity)
            assertNotEquals(old.remoteSessionId, fresh.remoteSessionId)
            assertTrue(f.controller.state.value.active)
            assertEquals(3, f.controller.state.value.consentVersion)
            assertEquals(1, f.backend.arms); assertEquals(0, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
            f.now += 899_999; f.wake.send(Unit); yield()
            assertEquals(0, f.backend.requests); assertEquals(1L, f.waits.last())
        }
    }
    @Test fun legacyVersionTwoStillResumesSameBootButRejectsNewBootWithoutUpgrade() = runBlocking {
        Fixture(consentVersion = 2).use { f ->
            f.initialize()
            assertTrue(f.controller.state.value.active)
            assertEquals(2, f.store.read()!!.durableConsent!!.version)
            assertEquals(900_000L, f.waits.last()); assertEquals(0, f.backend.requests)
        }
        Fixture(consentVersion = 2, crossBoot = true).use { f ->
            val consent = f.store.read()!!.durableConsent
            f.initialize()
            assertEquals(consent, f.store.read()!!.durableConsent)
            assertEquals(0, f.backend.arms); assertTrue(f.controller.state.value.resumeBlocked)
            assertEquals(HeartbeatResumeBlock.COLD_RECONSTRUCTION, f.store.read()!!.resumeBlock)
            assertTrue(f.storage.haltPresent)
        }
    }
    @Test fun aliveUnknownCrossBootOrMismatchedCurrentProofCannotCreateNewCapability() = runBlocking {
        for (proof in listOf(GmsHeartbeatCallerReason.OLD_INSTANCE_ALIVE, GmsHeartbeatCallerReason.UNKNOWN, GmsHeartbeatCallerReason.BOOT_CHANGED)) {
            Fixture().use { f ->
                f.backend.proof = proof; f.initialize()
                assertEquals(0, f.backend.arms); assertEquals(0, f.backend.requests)
                assertTrue(f.controller.state.value.resumeBlocked); assertFalse(f.controller.manualHeartbeatAllowed())
            }
        }
        Fixture().use { f ->
            f.backend.identity = newOwner.copy(kernelBootId = "87654321-1234-1234-1234-123456789abc")
            f.initialize(); assertEquals(0, f.backend.arms); assertTrue(f.controller.state.value.resumeBlocked)
        }
        Fixture(crossBoot = true).use { f ->
            f.backend.identity = f.backend.identity!!.copy(uid = oldOwner.uid + 1)
            f.initialize(); assertEquals(0, f.backend.arms); assertTrue(f.controller.state.value.resumeBlocked)
        }
        Fixture(crossBoot = true).use { f ->
            f.backend.identity = null
            f.initialize(); assertEquals(0, f.backend.arms); assertTrue(f.controller.state.value.resumeBlocked)
        }
    }
    @Test fun staleGmsBindingOrMissingHostCannotUseOldCallerGoneAlone() = runBlocking {
        for (crossBoot in listOf(false, true)) Fixture(crossBoot = crossBoot).use { f ->
            f.backend.matchingDigest = "d".repeat(64); f.initialize()
            assertEquals(0, f.backend.arms); assertEquals(HeartbeatResumeBlock.IDENTITY_CHANGED, f.store.read()!!.resumeBlock)
        }
        Fixture().use { f ->
            f.eligible = false; f.initialize()
            assertEquals(0, f.backend.arms); assertEquals(0, f.backend.qualificationCount)
            assertFalse(f.controller.manualHeartbeatAllowed())
        }
    }
    @Test fun coldHostInitiallyNotReadyCanQualifyOnNextReadyEventWithoutConsumingReadBackoff() = runBlocking {
        Fixture().use { f ->
            val initializedAt = f.now
            f.eligible = false; f.initialize()
            assertEquals(0, f.backend.qualificationCount); assertEquals(0, f.backend.arms)
            assertTrue(f.waits.isEmpty()); assertEquals(HeartbeatPause.WAITING_HOST, f.store.read()!!.pause)
            f.eligible = true; f.controller.hostEligible("fresh-host"); yield()
            assertEquals(initializedAt, f.now)
            assertEquals(1, f.backend.qualificationCount); assertEquals(1, f.backend.arms)
            assertTrue(f.controller.state.value.active); assertEquals(0, f.backend.requests)
            assertEquals(900_000L, f.waits.last())
            f.now += 899_999; f.wake.send(Unit); yield()
            assertEquals(0, f.backend.requests); assertEquals(1L, f.waits.last())
        }
    }
    @Test fun waitingHostLossAndConfirmedOffDoNotMisreportReconfirmation() = runBlocking {
        Fixture().use { f ->
            f.eligible = false; f.initialize()
            f.controller.endHost("fresh-host"); yield()
            assertEquals(HeartbeatPause.WAITING_HOST, f.store.read()!!.pause)
            assertTrue(f.controller.state.value.waitingForHost)
            assertFalse(f.controller.state.value.requiresConfirmation)
            assertEquals("等待保护服务恢复；合格后重新等待至少15分钟", f.controller.state.value.detail)
            assertEquals(0, f.backend.qualificationCount); assertEquals(0, f.backend.arms)
            f.controller.disable(); yield()
            assertFalse(f.store.read()!!.configured); assertTrue(f.storage.haltPresent)
            assertEquals("低频心跳已关闭；已提交请求不能撤回", f.controller.state.value.detail)
            assertEquals(0, f.backend.requests)
        }
    }
    @Test fun everyIndependentBarrierRejectsBeforeReadQualificationArmOrManual() = runBlocking {
        for (barrier in listOf("halt", "pending", "atomic")) Fixture(crossBoot = true).use { f ->
            when (barrier) { "halt" -> f.storage.haltPresent = true; "pending" -> f.storage.damagedPending = true; else -> f.storage.interruptedAtomic = true }
            f.initialize()
            assertEquals(0, f.backend.qualificationCount); assertEquals(0, f.backend.arms)
            assertFalse(f.controller.manualHeartbeatAllowed())
            if (barrier == "pending") assertFalse(f.controller.confirmDurable())
        }
    }
    @Test fun policy3BlocksManualDuringArmAndUntilDurableOffAndOriginalRevokeAck() = runBlocking {
        Fixture(cold = false).use { f ->
            f.initialize(); assertTrue(f.controller.manualHeartbeatAllowed())
            f.backend.bindingWait = CompletableDeferred()
            val confirmation = launch { assertTrue(f.controller.confirmDurable()) }
            yield(); assertFalse(f.controller.manualHeartbeatAllowedNow())
            f.backend.bindingWait!!.complete(Unit); confirmation.join()
            assertEquals(3, f.store.read()!!.durableConsent!!.version)
            assertFalse(f.controller.manualHeartbeatAllowed())
            f.backend.revokeWait = CompletableDeferred(); f.controller.disable(); yield()
            assertFalse(f.controller.manualHeartbeatAllowedNow()); assertTrue(f.controller.state.value.stopPending)
            f.backend.revokeWait!!.complete(Unit); yield()
            assertFalse(f.controller.state.value.stopPending); assertTrue(f.controller.manualHeartbeatAllowed())
            assertTrue(f.storage.haltPresent); assertFalse(f.store.read()!!.configured)
        }
    }
    @Test fun armUnknownRetainsIndependentMarkerOffPendingAndCannotReconfirmOrManual() = runBlocking {
        Fixture(cold = false).use { f ->
            f.backend.armReason = GmsAutoArmReason.TIMED_OUT; f.initialize()
            assertFalse(f.controller.confirmDurable()); assertNotNull(f.storage.pendingValue)
            assertTrue(f.controller.state.value.stopPending); assertFalse(f.controller.manualHeartbeatAllowed())
            f.controller.disable(); yield()
            assertFalse(f.controller.confirmDurable()); assertNotNull(f.storage.pendingValue)
        }
    }
    @Test fun manualBusyArmDoesNotReleaseHaltOrCreateColdAuthority() = runBlocking {
        Fixture(cold = false).use { f ->
            f.backend.armReason = GmsAutoArmReason.BUSY; f.initialize()
            assertFalse(f.controller.confirmDurable()); assertTrue(f.storage.haltPresent)
            assertNull(f.storage.pendingValue); assertFalse(f.controller.manualHeartbeatAllowedNow())
            val cold = f.store.reconstruct()!!
            assertNotNull(cold.resumeBlock); assertEquals(HeartbeatPause.RECONFIRM, cold.pause)
        }
    }
    @Test fun automaticKnownArmRejectionCannotBeWashedAwayByAnotherColdReconstruction() = runBlocking {
        for (reason in listOf(GmsAutoArmReason.IDENTITY_CHANGED, GmsAutoArmReason.CALLER_UNAVAILABLE,
                GmsAutoArmReason.INVALID_REQUEST, GmsAutoArmReason.LIFETIME_ENDED,
                GmsAutoArmReason.BUSY, GmsAutoArmReason.NOT_ELIGIBLE)) Fixture().use { f ->
            f.backend.armReason = reason; f.initialize()
            assertEquals(1, f.backend.arms); assertEquals(0, f.backend.requests)
            assertTrue(f.storage.haltPresent); assertNull(f.storage.pendingValue)
            assertEquals(HeartbeatPause.RECONFIRM, f.store.read()!!.pause)
            if (reason == GmsAutoArmReason.IDENTITY_CHANGED)
                assertEquals(HeartbeatResumeBlock.IDENTITY_CHANGED, f.store.read()!!.resumeBlock)
            else if (reason !in setOf(GmsAutoArmReason.BUSY, GmsAutoArmReason.NOT_ELIGIBLE))
                assertEquals(HeartbeatResumeBlock.SECURITY_FAILURE, f.store.read()!!.resumeBlock)
            f.backend.armReason = GmsAutoArmReason.ARMED
            f.backend.identity = newOwner.copy(pid = 5003, startTicks = 300L)
            val cold = GmsAutoHeartbeatController(f.store, f.scope, { f.now }) { _, _ -> error("Refused root cannot schedule") }
            cold.attachHost(HeartbeatHost("another-cold-host", { true }, f.backend))
            cold.initialize(); yield()
            assertEquals(1, f.backend.arms); assertEquals(0, f.backend.requests)
            assertFalse(cold.state.value.active); assertTrue(cold.state.value.resumeBlocked)
            assertFalse(cold.manualHeartbeatAllowed())
        }
    }
    @Test fun precedingManualUnknownSerialCannotBeWashedAwayBySuccessfulArm() = runBlocking {
        Fixture(cold = false).use { f ->
            f.backend.unknownDuringArm = true; f.initialize()
            assertFalse(f.controller.confirmDurable()); assertTrue(f.storage.haltPresent)
            assertEquals(0, f.backend.requests); assertEquals(1, f.backend.revokes)
            assertEquals(HeartbeatResumeBlock.UNKNOWN_RESULT, f.store.read()!!.resumeBlock)
            assertFalse(f.controller.manualHeartbeatAllowedNow()); assertFalse(f.controller.state.value.active)
        }
    }
    @Test fun sendUnknownThenOffAckKeepsPendingEvidenceAndManualClosed() = runBlocking {
        Fixture().use { f ->
            f.initialize(); f.backend.result = GmsHeartbeatResult.unknown(1, GmsHeartbeatReason.TIMED_OUT)
            f.now += 900_000; f.wake.send(Unit); yield()
            assertEquals(1, f.backend.requests); assertNotNull(f.storage.pendingValue)
            f.controller.disable(); yield()
            assertFalse(f.controller.state.value.stopPending); assertFalse(f.controller.manualHeartbeatAllowed())
            assertNotNull(f.store.read()!!.pendingAttempt); assertNotNull(f.store.read()!!.remoteSessionId)
        }
    }
    @Test fun offDuringFreshColdReadCancelsAdmissionAndCannotBeOverwrittenByOldProof() = runBlocking {
        Fixture(crossBoot = true).use { f ->
            f.backend.bindingWait = CompletableDeferred(); f.initialize()
            f.controller.disable(); yield(); f.backend.bindingWait!!.complete(Unit); yield()
            assertEquals(0, f.backend.arms); assertEquals(0, f.backend.requests)
            assertFalse(f.store.read()!!.configured); assertNull(f.store.read()!!.durableConsent)
            assertTrue(f.storage.haltPresent)
        }
    }
    @Test fun crossBootHostLossDuringFreshBindingCannotArmAndRetainsCleanWaitingConsent() = runBlocking {
        Fixture(crossBoot = true).use { f ->
            val consent = f.store.read()!!.durableConsent
            f.backend.bindingWait = CompletableDeferred(); f.initialize()
            f.controller.endHost("fresh-host"); yield()
            f.backend.bindingWait!!.complete(Unit); yield()
            assertEquals(0, f.backend.arms); assertEquals(0, f.backend.requests)
            assertEquals(consent, f.store.read()!!.durableConsent)
            assertEquals(HeartbeatPause.WAITING_HOST, f.store.read()!!.pause)
            assertFalse(f.controller.state.value.active)
        }
    }
    @Test fun crossBootCannotClearStickySnapshotReasonsOffPendingOrUnknownResult() = runBlocking {
        for (block in HeartbeatResumeBlock.entries) Fixture(crossBoot = true).use { f ->
            val old = f.store.read()!!
            f.storage.write(old.copy(resumeBlock = block))
            f.initialize()
            assertEquals(0, f.backend.qualificationCount); assertEquals(0, f.backend.arms)
            assertEquals(block, f.store.read()!!.resumeBlock)
            assertFalse(f.controller.manualHeartbeatAllowed())
        }
        for (pause in listOf(HeartbeatPause.OFF_PENDING, HeartbeatPause.UNKNOWN_RESULT)) Fixture(crossBoot = true).use { f ->
            val old = f.store.read()!!
            f.storage.write(old.copy(pause = pause, lastStatus = GmsHeartbeatStatus.UNKNOWN,
                lastReason = GmsHeartbeatReason.TIMED_OUT, lastResultEpoch = old.durableConsent!!.epoch))
            f.initialize()
            assertEquals(0, f.backend.qualificationCount); assertEquals(0, f.backend.arms)
            assertFalse(f.controller.manualHeartbeatAllowed())
        }
    }
    @Test fun pendingDeletionFailureClosesLocalScheduleAndPersistsHaltEvenWithOldCleanSnapshot() = runBlocking {
        Fixture(cold = false).use { f ->
            f.initialize(); f.storage.failAt = "delete-dirsync"
            assertFalse(f.controller.confirmDurable()); assertEquals(0, f.backend.requests)
            assertTrue(f.storage.haltPresent); assertTrue(f.controller.state.value.resumeBlocked)
            assertFalse(f.controller.manualHeartbeatAllowedNow())
            f.storage.failAt = null
            assertNotNull(f.store.reconstruct()!!.resumeBlock)
        }
    }
}
