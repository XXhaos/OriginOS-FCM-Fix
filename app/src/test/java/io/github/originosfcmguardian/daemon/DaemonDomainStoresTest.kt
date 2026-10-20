package io.github.originosfcmguardian.daemon

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.fcmprotection.*
import io.github.originosfcmguardian.fcmrecovery.*
import io.github.originosfcmguardian.gmsheartbeat.*
import io.github.originosfcmguardian.recovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DaemonDomainStoresTest {
    private val authority = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.DISABLED, null, null,
        BudgetSnapshot(BudgetClock(1, 100, 1000), emptyList(), null))
    private fun imported(extras: Map<DaemonDomainRole, ByteArray?> = emptyMap()) =
        RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority), extras))
    private fun previousJournal(highWater: Long): ByteArray {
        val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
        val finished = StoppedRecoveryRecord(highWater, identity, 1000, 900, VERIFIED_AUTOSTART_FINGERPRINT,
            0, true, true, true, StoppedRecoveryStage.FINISHED, StoppedRecoveryOutcome.FLAG_CLEARED)
        return RecoveryJournalCodec.encode(RecoveryJournalState(highWater, finished))
    }
    private fun actor(root: DaemonProtectionRoot, patch: (DaemonRootMutation) -> Unit) {
        val before = root.snapshot()
        root.controlTransaction(before.dataRevision) {
            it.control = before.control.copy(revision = before.control.revision + 1)
            patch(it)
        }
    }

    @Test fun importedAllCodecPayloadsAndMarkersRemainByteForByteDuringUnrelatedWrites() {
        val journal = previousJournal(7)
        val counts = RecoveryCountsCodec.encode(RecoveryCountsLedger(100, countingZoneId = "UTC"))
        val fcm = FcmProtectionCodec.encode(FcmProtectionConsent())
        val heartbeat = HeartbeatSnapshotCodec.encode(HeartbeatSnapshot(1, false))
        val extras = mapOf(DaemonDomainRole.STOPPED_JOURNAL to journal, DaemonDomainRole.RECOVERY_COUNTS to counts,
            DaemonDomainRole.FCM_PROTECTION to fcm, DaemonDomainRole.HEARTBEAT to heartbeat,
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(), DaemonDomainRole.LEGACY_BUDGET to byteArrayOf(77))
        val (root, _) = imported(extras)
        val stores = DaemonDomainStores(root)
        assertEquals(authority, stores.authority.read())
        assertEquals(7L, StoppedRecoveryJournal(stores.recoveryJournal).load().claimedHighWater)
        assertArrayEquals(counts, stores.recoveryCounts.read())
        assertFalse(stores.fcmProtection.read().configured)
        assertEquals(HeartbeatResumeBlock.COLD_RECONSTRUCTION, stores.heartbeat.coldBarrier())
        actor(root) { it.put(DaemonDomainRole.SELECTION, byteArrayOf()) }
        for ((role, bytes) in extras) assertArrayEquals("role=$role", bytes, root.bytes(role))
        assertArrayEquals(ProtectionAuthorityCodec.encode(authority), root.bytes(DaemonDomainRole.AUTHORITY))
    }

    @Test fun authorityMutationsRequireOneMatchingControlTransactionAndDoNotMintConsent() {
        val (root, storage) = imported(); val stores = DaemonDomainStores(root)
        val nextAuthority = authority.copy(generation = 2)
        RootFixtures.fails { stores.authority.write(nextAuthority) }
        assertEquals(1, storage.writes)
        val before = root.snapshot()
        root.controlTransaction(before.dataRevision) {
            stores.authority.write(nextAuthority)
            it.control = before.control.copy(revision = 2,
                authorityDigest = DaemonProtectionRootSnapshot.digest(ProtectionAuthorityCodec.encode(nextAuthority)))
        }
        assertEquals(2, storage.writes)
        assertEquals(nextAuthority, stores.authority.read())
        assertEquals(root.authorityDigest(), root.snapshot().control.authorityDigest)
    }

    @Test fun journalAndCountsMarkersDoNotErasePayloadsOrEachOthersState() {
        val journal = previousJournal(9)
        val counts = RecoveryCountsCodec.encode(RecoveryCountsLedger(100, countingZoneId = "UTC"))
        val (root, _) = imported(mapOf(DaemonDomainRole.STOPPED_JOURNAL to journal, DaemonDomainRole.RECOVERY_COUNTS to counts))
        val stores = DaemonDomainStores(root)
        stores.recoveryCounts.markUnavailable()
        RootFixtures.fails { stores.recoveryCounts.read() }
        assertArrayEquals(journal, stores.recoveryJournal.read())
        stores.recoveryJournal.markUncertain()
        RootFixtures.fails { stores.recoveryJournal.read() }
        assertArrayEquals(journal, root.bytes(DaemonDomainRole.STOPPED_JOURNAL))
        assertArrayEquals(counts, root.bytes(DaemonDomainRole.RECOVERY_COUNTS))
    }

    @Test fun fcmConsumeMarkersAndHaltSurviveMainSnapshotWriteWithoutRearming() {
        val marker = FcmRecoverySnapshot(configured = true, binding = "a".repeat(64), consumed = true,
            consumedBoot = "12345678-1234-1234-1234-123456789abc", consumedAt = 1000, consumedElapsed = 100)
        val (root, _) = imported(mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(marker)))
        val stores = DaemonDomainStores(root)
        root.transaction {
            stores.fcmRecovery.markConsumed(marker)
            stores.fcmRecovery.write(marker.copy(consumed = false))
            stores.fcmRecovery.halt()
        }
        val read = stores.fcmRecovery.read()
        assertFalse(read.configured); assertTrue(read.consumed)
        assertEquals(marker.consumedBoot, read.consumedBoot)
        RootFixtures.fails { stores.fcmRecovery.releaseHalt() }
        actor(root) { it.put(DaemonDomainRole.FCM_RECOVERY_HALT, null) }
        assertTrue(stores.fcmRecovery.read().consumed)
        stores.fcmRecovery.clearConsumed()
        assertFalse(stores.fcmRecovery.read().consumed)
    }

    @Test fun heartbeatPendingIsExactAndMissingInitializedSnapshotIsNotFirstUse() {
        val (root, _) = imported(); val stores = DaemonDomainStores(root)
        val pending = HeartbeatPending(HeartbeatPendingKind.ARM, "96fbde55-15e2-45b6-8bea-0de37312dd94", 1,
            "470130f6-6518-44fb-8ab6-c76daee78ffb")
        stores.heartbeat.write(HeartbeatSnapshot(1, false))
        stores.heartbeat.pending(pending)
        assertTrue(stores.heartbeat.unsettledPending())
        assertEquals(HeartbeatResumeBlock.LEFTOVER_PENDING, stores.heartbeat.coldBarrier())
        RootFixtures.fails { stores.heartbeat.clearPending(pending.copy(generation = 2)) }
        stores.heartbeat.clearPending(pending)
        assertFalse(stores.heartbeat.unsettledPending())
        actor(root) { it.put(DaemonDomainRole.HEARTBEAT, null) }
        RootFixtures.fails { stores.heartbeat.read() }
    }

    @Test fun selectionUsesExistingCodecAndMalformedUtf8OrInterruptedImportNeverLooksEmpty() {
        val (root, _) = imported(); val stores = DaemonDomainStores(root)
        val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
        val encoded = TargetSelectionCodec.encode(listOf(identity))
        RootFixtures.fails { stores.selection.write(encoded) }
        actor(root) { it.put(DaemonDomainRole.SELECTION, encoded.toByteArray()) }
        assertEquals(listOf(identity), TargetSelectionStore(stores.selection).load())
        assertArrayEquals(encoded.toByteArray(), root.bytes(DaemonDomainRole.SELECTION))
        actor(root) { it.put(DaemonDomainRole.SELECTION, byteArrayOf(0xc3.toByte(), 0x28)) }
        RootFixtures.fails { stores.selection.read() }
        root.put(DaemonDomainRole.INTERRUPTED_ARTIFACTS, byteArrayOf())
        RootFixtures.fails { stores.authority.read() }
        RootFixtures.fails { stores.fcmRecovery.read() }
    }

    @Test fun staleFcmAppendCannotRestoreOnAfterControlOffWhileBookkeepingPreservesOff() {
        val on = FcmRecoverySnapshot(configured = true, binding = "a".repeat(64))
        val bundled = FcmProtectionConsent(true, "96fbde55-15e2-45b6-8bea-0de37312dd94", "b".repeat(64), "a".repeat(64), true)
        val (root, _) = imported(mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(on),
            DaemonDomainRole.FCM_PROTECTION to FcmProtectionCodec.encode(bundled)))
        val stores = DaemonDomainStores(root)
        val stale = stores.fcmRecovery.read()
        actor(root) {
            it.put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(on.copy(configured = false)))
            it.put(DaemonDomainRole.FCM_PROTECTION, FcmProtectionCodec.encode(bundled.copy(configured = false)))
        }
        RootFixtures.fails { stores.fcmRecovery.write(stale.copy(powerSampleAtMillis = 1000)) }
        RootFixtures.fails { stores.fcmProtection.write(bundled) }
        RootFixtures.fails { stores.fcmProtection.write(bundled.copy(configured = false, epoch = "470130f6-6518-44fb-8ab6-c76daee78ffb")) }
        stores.fcmRecovery.write(stores.fcmRecovery.read().copy(consumed = true,
            consumedBoot = "12345678-1234-1234-1234-123456789abc", consumedAt = 1000, consumedElapsed = 100))
        stores.recoveryCounts.write(RecoveryCountsCodec.encode(RecoveryCountsLedger(100, countingZoneId = "UTC")))
        assertFalse(stores.fcmRecovery.read().configured)
        assertFalse(stores.fcmProtection.read().configured)
    }

    @Test fun staleHeartbeatResultCannotRestoreOldConsentButSameScopeRuntimePauseIsAllowed() {
        val digest = "a".repeat(64)
        val epoch = "96fbde55-15e2-45b6-8bea-0de37312dd94"
        val on = HeartbeatSnapshot(1, true, bindingDigest = digest, schemaVersion = 2,
            durableConsent = HeartbeatDurableConsent(1, epoch, 0, digest))
        val (root, _) = imported(mapOf(DaemonDomainRole.HEARTBEAT to HeartbeatSnapshotCodec.encode(on),
            DaemonDomainRole.HEARTBEAT_INITIALIZED to byteArrayOf(1)))
        val stores = DaemonDomainStores(root)
        stores.heartbeat.write(on.copy(generation = 2, pause = HeartbeatPause.RECONFIRM))
        val off = on.copy(generation = 3, configured = false, durableConsent = null)
        actor(root) { it.put(DaemonDomainRole.HEARTBEAT, HeartbeatSnapshotCodec.encode(off)) }
        RootFixtures.fails { stores.heartbeat.write(on.copy(generation = 4)) }
        RootFixtures.fails { stores.heartbeat.write(on.copy(generation = 4,
            durableConsent = on.durableConsent!!.copy(epoch = "470130f6-6518-44fb-8ab6-c76daee78ffb"))) }
        stores.heartbeat.write(off.copy(generation = 4, pause = HeartbeatPause.RECONFIRM))
        assertFalse(stores.heartbeat.read()!!.configured)
    }

    @Test fun controlActorUsesCodecPatchWithoutAcquiringBackgroundStoreMonitor() {
        val on = FcmRecoverySnapshot(true, "a".repeat(64))
        val (root, _) = imported(mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(on)))
        val stores = DaemonDomainStores(root)
        val backgroundStoreMonitor = Any()
        val readDone = CountDownLatch(1); val continueWrite = CountDownLatch(1); val done = CountDownLatch(1)
        val unexpected = AtomicReference<Throwable?>()
        val denied = AtomicReference(false)
        val background = Thread {
            try { synchronized(backgroundStoreMonitor) {
                val stale = stores.fcmRecovery.read(); readDone.countDown()
                check(continueWrite.await(3, TimeUnit.SECONDS))
                try { stores.fcmRecovery.write(stale.copy(powerSampleAtMillis = 1000)) }
                catch (_: IllegalStateException) { denied.set(true) }
            } } catch (failure: Throwable) { unexpected.set(failure) } finally { done.countDown() }
        }
        background.start()
        try {
            assertTrue(readDone.await(3, TimeUnit.SECONDS))
            // Actor touches codec bytes only; acquiring backgroundStoreMonitor here would deadlock.
            actor(root) {
                it.put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(on.copy(configured = false)))
            }
        } finally { continueWrite.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); background.join(1000)
        assertNull(unexpected.get()); assertTrue(denied.get()); assertFalse(stores.fcmRecovery.read().configured)
    }

    @Test fun protectedFcmOffWithUnchangedAuthorityDigestStillRequiresNextKernelRevision() {
        val on = FcmRecoverySnapshot(true, "a".repeat(64))
        val (root, storage) = imported(mapOf(DaemonDomainRole.FCM_RECOVERY to FcmRecoveryStoreCodec.encode(on)))
        val before = root.snapshot()
        RootFixtures.fails { root.controlTransaction(before.dataRevision) {
            it.put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(on.copy(configured = false)))
        } }
        assertEquals(1, storage.writes)
        assertTrue(DaemonDomainStores(root).fcmRecovery.read().configured)
        actor(root) { it.put(DaemonDomainRole.FCM_RECOVERY, FcmRecoveryStoreCodec.encode(on.copy(configured = false))) }
        assertEquals(before.control.authorityDigest, root.snapshot().control.authorityDigest)
        assertEquals(before.control.revision + 1, root.snapshot().control.revision)
        assertFalse(DaemonDomainStores(root).fcmRecovery.read().configured)
    }

    private fun activeAuthority(): ProtectionAuthoritySnapshot {
        val identity = SelectedTargetIdentity("example.recipient", 10123, 1234, listOf("a".repeat(64)))
        val consent = TargetConsent.Recipient(RecipientConsentToken("96fbde55-15e2-45b6-8bea-0de37312dd94",
            1, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
        return ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, null, null,
            BudgetSnapshot(BudgetClock(1, 100, 1000), listOf(50L), null), listOf(consent), 3, 1000)
    }

    @Test fun authorityMemoReusesOnlyExactBytesAndExternalOffWithSameGenerationIsFresh() {
        val active = activeAuthority()
        val (root, storage) = RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(active)))
        val stores = DaemonDomainStores(root)
        val warm = stores.authority.read()!!
        assertSame(warm, stores.authority.read())
        val off = active.copy(mode = ProtectionConsentMode.USER_PAUSED)
        storage.saved = DaemonProtectionRootCodec.encode(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(off)))
        val fresh = stores.authority.read()!!
        assertNotSame(warm, fresh)
        assertEquals(warm.generation, fresh.generation)
        assertEquals(ProtectionConsentMode.USER_PAUSED, fresh.mode)
        val lease = TargetConsentLease(active.generation, active.targetConsents.single())
        assertFalse(ProtectionConsentStore(stores.authority).verifyAuthorized(lease))
        assertEquals(1, storage.writes)
    }

    @Test fun warmedAuthorityStillRechecksStopPendingInitializedAndInterruptedMarkers() {
        val active = activeAuthority()
        for (role in listOf(DaemonDomainRole.AUTHORITY_STOP_PENDING, DaemonDomainRole.AUTHORITY_INITIALIZED,
            DaemonDomainRole.INTERRUPTED_ARTIFACTS)) {
            val (root, _) = RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(active)))
            val stores = DaemonDomainStores(root)
            val before = stores.authority.read()!!
            actor(root) { it.put(role, if (role == DaemonDomainRole.AUTHORITY_INITIALIZED) null else byteArrayOf(1)) }
            if (role == DaemonDomainRole.AUTHORITY_STOP_PENDING) {
                val blocked = stores.authority.read()!!
                assertNotSame(before, blocked)
                assertTrue(blocked.storageBlocked)
                assertTrue(blocked.runnableConsents().isEmpty())
            } else RootFixtures.fails { stores.authority.read() }
        }
    }

    @Test fun successfulDecodeMemoNeverMasksMalformedChangedAuthorityOrMissingRoot() {
        for (missing in listOf(false, true)) {
            val (root, storage) = RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(activeAuthority())))
            val stores = DaemonDomainStores(root)
            assertNotNull(stores.authority.read())
            storage.saved = if (missing) null else DaemonProtectionRootCodec.encode(RootFixtures.snapshot(byteArrayOf(1, 2, 3)))
            RootFixtures.fails { stores.authority.read() }
            assertEquals(1, storage.writes)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun memoizedRecipientAndLegacyAuthorityHaveNoMutableListAliases() {
        val recipient = activeAuthority()
        val token = DurableConsentToken("96fbde55-15e2-45b6-8bea-0de37312dd94", 1, 1000,
            SelectedTargetIdentity("org.telegram.messenger", 10124, 1234, listOf("b".repeat(64))), VERIFIED_AUTOSTART_FINGERPRINT)
        val legacy = ProtectionAuthoritySnapshot(1, ProtectionConsentMode.ACTIVE, token, null, recipient.budget)
        for (authority in listOf(recipient, legacy, legacy.configuredSchema3())) {
            val (root, _) = RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(authority)))
            val stores = DaemonDomainStores(root)
            val value = stores.authority.read()!!
            assertThrows(UnsupportedOperationException::class.java) { (value.budget.attempts as MutableList<Long>).clear() }
            assertThrows(UnsupportedOperationException::class.java) {
                (value.invalidatedConsentIds as MutableList<String>).add("invalid")
            }
            val identity = value.token?.identity ?: value.targetConsents.single().identity
            assertThrows(UnsupportedOperationException::class.java) { (identity.signerSha256 as MutableList<String>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (value.targetConsents as MutableList<TargetConsent>).clear() }
            assertEquals(authority, stores.authority.read())
            assertSame(value, stores.authority.read())
        }
    }

    @Test fun rolledBackStagedAuthorityCannotSurviveAsReadAuthority() {
        val active = activeAuthority()
        val (root, storage) = RootFixtures.root(RootFixtures.snapshot(ProtectionAuthorityCodec.encode(active)))
        val stores = DaemonDomainStores(root)
        assertEquals(active, stores.authority.read())
        val before = root.snapshot()
        RootFixtures.fails { root.controlTransaction(before.dataRevision) { mutation ->
            val paused = active.copy(mode = ProtectionConsentMode.USER_PAUSED)
            val bytes = ProtectionAuthorityCodec.encode(paused)
            mutation.put(DaemonDomainRole.AUTHORITY, bytes)
            mutation.control = before.control.copy(revision = before.control.revision + 1,
                authorityDigest = DaemonProtectionRootSnapshot.digest(bytes))
            assertEquals(paused, stores.authority.read())
            error("rollback staged authority")
        } }
        assertEquals(active, stores.authority.read())
        assertEquals(1, storage.writes)
    }
}
