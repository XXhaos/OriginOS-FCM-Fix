package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.*
import io.github.originosfcmguardian.privileged.*
import io.github.originosfcmguardian.core.isSafeAndroidPackageName
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicInteger

class RecipientStoppedRecoveryTest {
    private val qualifications = RecoveryTargetQualifications(true, true, false, false, false, true)
    private val fields = StoppedPackageFields("com.example.recipient", "com.example.recipient", 10234, 100,
        listOf(byteArrayOf(1, 2, 3)), true, qualifications)
    private val identity = validatedStoppedPackage(fields, "com.example.recipient")!!.first
    private val consent = AutomaticRecoveryConsent("b1a9dce2-544b-499a-9dc3-16eb5b4c97a2", 90, 4)
    private fun known(stopped: Boolean = true) = StoppedTargetObservation.Known(1, identity, stopped, true, 100, qualifiedRecipient = true)
    private class Memory : RecoveryJournalStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { this.bytes = bytes.clone() }
        override fun markUncertain() = Unit
    }
    private inner class Port(var samples: List<StoppedPackageFields?> = listOf(fields, fields),
        var owners: List<String>? = listOf(identity.packageName)) : StoppedIdentityReadPort {
        val targets = mutableListOf<String>()
        var index = 0
        override fun environment() = StoppedProbeEnvironment(VERIFIED_AUTOSTART_FINGERPRINT, 0, 2000)
        override fun telegramPackage(): StoppedPackageFields? = error("Legacy route must not read recipient")
        override fun targetPackage(packageName: String): StoppedPackageFields? { targets += packageName; return samples[index++] }
        override fun packagesForUid(uid: Int) = owners
    }

    @Test fun qualifiedTargetReadBindsFullExpectedIdentityAndFinalFlag() {
        val port = Port(listOf(fields, fields.copy(stopped = false)))
        val result = observeStoppedTarget(1, identity, port) as StoppedTargetObservation.Known
        assertEquals(listOf(identity.packageName, identity.packageName), port.targets)
        assertEquals(identity, result.identity)
        assertTrue(result.qualifiedRecipient)
        assertFalse(result.stopped)
    }

    @Test fun sharedServiceTargetReadSamplesOnlyLocalCancellationOnProbeThread() {
        val owner = Thread.currentThread()
        val authorityCalls = AtomicInteger(0)
        val localCalls = AtomicInteger(0)
        val reader = StoppedIdentityProbeRunner({ Port() }, 2_000)
        try {
            // Both observeTarget and every recoverTarget engine sample use this exact helper.
            val result = readTargetRecoveryObservation(reader, 12, identity,
                { localCalls.incrementAndGet(); true },
                { assertSame("Authority callback must not run on concurrent probe worker", owner, Thread.currentThread()); authorityCalls.incrementAndGet(); true })
            assertTrue(result is StoppedTargetObservation.Known)
            assertEquals(12L, result.requestId)
            assertEquals(2, authorityCalls.get())
            assertTrue(localCalls.get() > authorityCalls.get())
        } finally { reader.close() }
    }

    @Test fun sharedServiceTargetReadStillChecksRevocationAfterRead() {
        var authorityCalls = 0
        val reader = StoppedIdentityProbeRunner({ Port() }, 2_000)
        try {
            assertThrows(IllegalStateException::class.java) {
                readTargetRecoveryObservation(reader, 12, identity, { true }, { ++authorityCalls == 1 })
            }
            assertEquals(2, authorityCalls)
        } finally { reader.close() }
    }

    @Test fun unavailableRecipientReadBlocksAsIdentityUnknownWithoutClaim() {
        val result = StoppedRecoveryEngine({ id -> StoppedTargetObservation.Unknown(id, StoppedObservationUnknown.CAPABILITY_UNAVAILABLE) },
            { error("claim") }, { true }, { error("write") }).recover(12, identity)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertTrue(result.detail.contains("IDENTITY_UNKNOWN"))
    }

    @Test fun incompleteAndUnsafeQualificationsNeverReturnKnown() {
        val unsafe = listOf(null, qualifications.copy(installed = null), qualifications.copy(installed = false),
            qualifications.copy(enabled = false), qualifications.copy(enabled = null), qualifications.copy(system = null),
            qualifications.copy(updatedSystem = null), qualifications.copy(persistent = true), qualifications.copy(persistent = null))
        unsafe.forEach { value ->
            assertTrue(observeStoppedTarget(1, identity, Port(listOf(fields.copy(qualifications = value)))) is StoppedTargetObservation.Unknown)
            assertTrue(observeStoppedTarget(1, identity, Port(listOf(fields, fields.copy(qualifications = value)))) is StoppedTargetObservation.Unknown)
        }
    }

    @Test fun installedEnabledNonpersistentSystemTargetsIncludingGmsReturnQualifiedEvidence() {
        for (packageName in listOf(identity.packageName, "com.google.android.gms")) {
            val target = identity.copy(packageName = packageName)
            for (system in listOf(false, true)) for (updatedSystem in listOf(false, true)) {
                val sample = fields.copy(packageName = packageName, applicationPackageName = packageName,
                    qualifications = qualifications.copy(system = system, updatedSystem = updatedSystem))
                val observation = observeStoppedTarget(1, target,
                    Port(listOf(sample, sample.copy(stopped = false)), listOf(packageName))) as StoppedTargetObservation.Known
                assertEquals(target, observation.identity)
                assertTrue(observation.qualifiedRecipient)
                assertTrue(observation.exclusiveUidOwner)
                assertTrue(observation.hasVerifiedPackageUid)
                assertFalse(observation.stopped)
                assertEquals(observation, StoppedObservationCodec.decode(StoppedObservationCodec.encode(observation), 1, target))
            }
        }
    }

    @Test fun sharedSystemOwnershipFailsBeforeOrAfterSystemFlagChangesWithoutClaimOrWrite() {
        val owners = listOf(identity.packageName, "com.example.shared")
        for (systemFlags in listOf(qualifications.copy(system = true), qualifications.copy(updatedSystem = true),
            qualifications.copy(system = true, updatedSystem = true))) {
            val system = fields.copy(qualifications = systemFlags)
            for ((before, after) in listOf(system to system, fields to system, system to fields)) {
                val observation = observeStoppedTarget(1, identity, Port(listOf(before, after), owners))
                assertEquals(StoppedObservationUnknown.INELIGIBLE_TARGET,
                    (observation as StoppedTargetObservation.Unknown).reason)
                val blocked = StoppedRecoveryEngine({ observation }, { error("Shared system target must not claim") },
                    { true }, { error("Shared system target must not dispatch") }).recover(1, identity)
                assertEquals(StoppedRecoveryOutcome.BLOCKED, blocked.outcome)
            }
        }
    }

    @Test fun gmsSystemQualificationCannotBypassCoreUidOrIncompleteApplicationEvidence() {
        val target = identity.copy(packageName = "com.google.android.gms")
        val systemFlags = qualifications.copy(system = true)
        val sample = fields.copy(packageName = target.packageName, applicationPackageName = target.packageName,
            qualifications = systemFlags)
        for (invalidUid in listOf(0, 1000, 2000, 9999, 110234)) {
            assertEquals(StoppedObservationUnknown.IDENTITY_UNKNOWN,
                (observeStoppedTarget(1, target, Port(listOf(sample.copy(uid = invalidUid)), listOf(target.packageName)))
                    as StoppedTargetObservation.Unknown).reason)
        }
        for (invalid in listOf(systemFlags.copy(installed = false), systemFlags.copy(installed = null),
            systemFlags.copy(enabled = false), systemFlags.copy(enabled = null), systemFlags.copy(system = null),
            systemFlags.copy(updatedSystem = null), systemFlags.copy(persistent = true), systemFlags.copy(persistent = null))) {
            val invalidSample = sample.copy(qualifications = invalid)
            for (samples in listOf(listOf(invalidSample, sample), listOf(sample, invalidSample))) {
                assertEquals(StoppedObservationUnknown.INELIGIBLE_TARGET,
                    (observeStoppedTarget(1, target, Port(samples, listOf(target.packageName)))
                        as StoppedTargetObservation.Unknown).reason)
            }
        }
    }

    @Test fun stableUserApplicationWithoutLauncherStillReturnsQualifiedIdentityEvidence() {
        for (launcherEvidence in listOf(false, null)) {
            val nonLauncher = fields.copy(qualifications = qualifications.copy(enabledExportedLauncher = launcherEvidence))
            val observation = observeStoppedTarget(1, identity, Port(listOf(nonLauncher, nonLauncher)))
                as StoppedTargetObservation.Known
            assertEquals(identity, observation.identity)
            assertTrue(observation.qualifiedRecipient)
        }
    }

    @Test fun validSharedMembershipKeepsNonexclusiveQualifiedEvidence() {
        val result = observeStoppedTarget(1, identity,
            Port(owners = listOf(identity.packageName, "com.example.shared"))) as StoppedTargetObservation.Known
        assertFalse(result.exclusiveUidOwner)
        assertTrue(result.qualifiedRecipient)
        assertTrue(result.hasVerifiedPackageUid)
        assertEquals(result, StoppedObservationCodec.decode(StoppedObservationCodec.encode(result), 1, identity))
    }

    @Test fun missingDuplicateMalformedOversizedAndForeignOwnersFailClosed() {
        listOf(null, emptyList(), listOf(identity.packageName, "invalid;package"),
            listOf(identity.packageName, identity.packageName), listOf("com.example.other"),
            listOf(identity.packageName) + (1..64).map { "com.example.member$it" }).forEach { owners ->
            val observation = observeStoppedTarget(1, identity, Port(owners = owners))
            assertTrue(observation is StoppedTargetObservation.Unknown)
            assertEquals(StoppedRecoveryOutcome.BLOCKED,
                StoppedRecoveryEngine({ observation }, { error("claim") }, { true }, { error("write") }).recover(1, identity).outcome)
        }
    }

    @Test fun identityDriftBeforeOrAfterOwnershipDiscardsEvidence() {
        val drift = fields.copy(firstInstallTime = 101)
        assertTrue(observeStoppedTarget(1, identity, Port(listOf(drift))) is StoppedTargetObservation.Unknown)
        assertTrue(observeStoppedTarget(1, identity, Port(listOf(fields, drift))) is StoppedTargetObservation.Unknown)
    }

    @Test fun stableReinstallIsDistinctFromSamplingRaceForReadonlyReconciliation() {
        val reinstalled = fields.copy(firstInstallTime = 101)
        assertEquals(StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED,
            (observeStoppedTarget(1, identity, Port(listOf(reinstalled, reinstalled))) as StoppedTargetObservation.Unknown).reason)
        assertEquals(StoppedObservationUnknown.IDENTITY_CHANGED,
            (observeStoppedTarget(1, identity, Port(listOf(fields, reinstalled))) as StoppedTargetObservation.Unknown).reason)
    }

    @Test fun cancelledTargetReadReturnsNoEvidence() {
        val port = Port()
        assertEquals(StoppedObservationUnknown.CANCELLED,
            (observeStoppedTarget(1, identity, port, { StoppedObservationUnknown.CANCELLED }) as StoppedTargetObservation.Unknown).reason)
        assertTrue(port.targets.isEmpty())
    }

    @Test fun targetCodecRequiresExpectedFullIdentityAndVersionedQualification() {
        val raw = StoppedObservationCodec.encode(known())
        assertTrue(raw.startsWith("2|"))
        assertEquals(known(), StoppedObservationCodec.decode(raw, 1, identity))
        listOf(StoppedObservationCodec.decode(raw, 1),
            StoppedObservationCodec.decode(raw, 1, identity.copy(firstInstallTime = 101)),
            StoppedObservationCodec.decode(raw.dropLast(1) + "0", 1, identity),
            StoppedObservationCodec.decode(raw.substringBeforeLast('|').replaceFirst("2|", "1|"), 1, identity)).forEach {
            assertTrue(it is StoppedTargetObservation.Unknown)
        }
    }

    @Test fun excludedPackagesCannotEnterRecipientServiceRoute() {
        listOf("android", "io.github.originosfcmguardian", "io.github.originosfcmguardian.test", "io.github.originosfcmguardian.fcmtest",
            "moe.shizuku.privileged.api", "com.android.vending", "com.android.shell", "com.android.systemui").forEach {
            assertFalse(isRecoveryTargetPackage(it))
            assertThrows(IllegalArgumentException::class.java) { stoppedRecoveryArgv(identity.copy(packageName = it)) }
            if (!isSafeAndroidPackageName(it)) return@forEach
            val excluded = identity.copy(packageName = it)
            val observation = observeStoppedTarget(1, excluded, Port())
            assertTrue(observation is StoppedTargetObservation.Unknown)
            assertEquals(StoppedRecoveryOutcome.BLOCKED,
                StoppedRecoveryEngine({ observation }, { error("claim") }, { true }, { error("write") }).recover(1, excluded).outcome)
        }
    }

    @Test fun fixedArgvAllowsOnlyOneValidatedTargetArgument() {
        assertEquals(listOf("/system/bin/cmd", "package", "unstop", "--user", "0", identity.packageName), stoppedRecoveryArgv(identity))
        listOf("com.example.app;id", "--user", "com.example.app\ncmd").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { identity.copy(packageName = name) }
        }
    }

    @Test fun legacyEndpointScopeRemainsExactTelegramWithoutRecipientQualification() {
        val telegram = identity.copy(packageName = "org.telegram.messenger")
        assertEquals(telegram, decodeRecoveryTarget(TargetSelectionCodec.encode(listOf(telegram)), false))
        assertThrows(IllegalArgumentException::class.java) { decodeRecoveryTarget(TargetSelectionCodec.encode(listOf(identity)), false) }
        assertEquals(identity, decodeRecoveryTarget(TargetSelectionCodec.encode(listOf(identity)), true))
        var reads = 0
        val result = StoppedRecoveryEngine({ StoppedTargetObservation.Known(1, telegram, ++reads < 3, true, 100) },
            { true }, { true }, { 0 }).recover(1, telegram)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
    }

    @Test fun schemaThreeAutomaticRetainsPolicyFourAndGlobalPendingBlockade() {
        val memory = Memory()
        var journal = StoppedRecoveryJournal(memory)
        val record = journal.prepareAutomatically(identity, known(), consent)
        assertEquals(3, DataInputStream(memory.bytes!!.inputStream()).readInt())
        assertEquals(4, record.consentVersion)
        assertTrue(journal.claim(record.requestId))
        journal = StoppedRecoveryJournal(memory)
        val other = identity.copy(packageName = "com.example.second", uid = 10235)
        assertThrows(IllegalStateException::class.java) {
            journal.prepareAutomatically(other, known().copy(identity = other), consent)
        }
        journal.finish(record.requestId, StoppedRecoveryOutcome.FLAG_CLEARED, "confirmed")
        assertEquals(2L, journal.prepareAutomatically(other, known().copy(identity = other), consent).requestId)
        assertFalse(journal.claim(record.requestId))
    }

    @Test fun schemaThreeManualRequiresConfirmationAndNoAutomaticConsent() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        assertThrows(IllegalStateException::class.java) { journal.prepare(identity, known(), false) }
        val record = journal.prepare(identity, known(), true)
        assertEquals(3, DataInputStream(memory.bytes!!.inputStream()).readInt())
        assertTrue(record.confirmedManualAction)
        assertNull(record.consentId)
        assertEquals(record, StoppedRecoveryJournal(memory).load().latest)
    }

    @Test fun oldConsentAndOldSchemaCannotExpandToRecipientTargets() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        assertThrows(IllegalStateException::class.java) { journal.prepareAutomatically(identity, known(), consent.copy(version = 2)) }
        assertNull(memory.bytes)
        journal.prepareAutomatically(identity, known(), consent)
        val oldSchema = memory.bytes!!.clone().also { it[3] = 2 }
        assertThrows(IllegalArgumentException::class.java) { RecoveryJournalCodec.decode(oldSchema) }
        assertThrows(IllegalArgumentException::class.java) { consent.copy(version = 3).validate() }
    }

    @Test fun finalEligibilityLossAndWrongRequestPreventDispatch() {
        var reads = 0
        var writes = 0
        val result = StoppedRecoveryEngine({ if (++reads == 1) known() else StoppedTargetObservation.Unknown(1, StoppedObservationUnknown.INELIGIBLE_TARGET) },
            { true }, { true }, { writes++; 0 }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, result.outcome)
        assertEquals(0, writes)
        val mismatch = StoppedRecoveryEngine({ known().copy(requestId = 2) }, { error("claim") }, { true }, { error("write") }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, mismatch.outcome)
    }

    @Test fun clearedReadbackCountsOnlyNewSuccessfulCommittedRecovery() {
        val journal = StoppedRecoveryJournal(Memory())
        val record = journal.prepareAutomatically(identity, known(), consent)
        var reads = 0
        var writes = 0
        val result = StoppedRecoveryEngine({ known(++reads < 3) }, journal::claim, { true }, { writes++; 0 }).recover(record.requestId, identity)
        assertEquals(1, writes)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
        val finished = journal.finish(record.requestId, result.outcome, result.detail)
        assertEquals(identity, confirmedRecoveryReceipt(result.copy(record = finished), finished.createdAtMillis + 1)?.identity)
        assertNull(confirmedRecoveryReceipt(result.copy(record = finished, commandSucceeded = false), finished.createdAtMillis + 1))
        assertNull(confirmedRecoveryReceipt(result.copy(record = finished.copy(stage = StoppedRecoveryStage.RECONCILED)), finished.createdAtMillis + 1))
    }

    @Test fun sharedRecipientUsesOnePackageWriteAndRetainsJournalAndCodecEvidence() {
        val sharedOwners = listOf(identity.packageName, "com.example.shared")
        fun sample(stopped: Boolean) = observeStoppedTarget(1, identity,
            Port(listOf(fields.copy(stopped = stopped), fields.copy(stopped = stopped)), sharedOwners)) as StoppedTargetObservation.Known
        val baseline = sample(true)
        assertFalse(baseline.exclusiveUidOwner)
        val journal = StoppedRecoveryJournal(Memory())
        val record = journal.prepareAutomatically(identity, baseline, consent)
        var reads = 0
        var writes = 0
        val result = StoppedRecoveryEngine({
            val observed = sample(++reads < 3)
            StoppedObservationCodec.decode(StoppedObservationCodec.encode(observed), 1, identity)
        }, journal::claim, { true }, {
            assertEquals(identity.packageName, stoppedRecoveryArgv(identity).last())
            writes++
            0
        }).recover(record.requestId, identity)
        assertEquals(1, writes)
        assertEquals(3, reads)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
        assertFalse(sample(false).exclusiveUidOwner)
        val finished = journal.finish(record.requestId, result.outcome, result.detail)
        assertEquals(finished, journal.load().latest)
        assertEquals(4, finished.consentVersion)
        assertEquals(identity, finished.identity)
    }

    @Test fun sharedRecipientManualPrepareKeepsExistingSchemaAndRequiresConfirmation() {
        val memory = Memory()
        val journal = StoppedRecoveryJournal(memory)
        val baseline = known().copy(exclusiveUidOwner = false)
        assertThrows(IllegalStateException::class.java) { journal.prepare(identity, baseline, false) }
        val record = journal.prepare(identity, baseline, true)
        assertEquals(3, DataInputStream(memory.bytes!!.inputStream()).readInt())
        assertEquals(record, StoppedRecoveryJournal(memory).load().latest)
    }

    @Test fun sharedMembershipCannotBypassFreshIdentityOrApplicationQualification() {
        val owners = listOf(identity.packageName, "com.example.shared")
        val drift = fields.copy(firstInstallTime = 101)
        val unsafe = fields.copy(qualifications = qualifications.copy(system = true))
        listOf(Port(listOf(fields, drift), owners), Port(listOf(unsafe, unsafe), owners),
            Port(listOf(fields, unsafe), owners)).forEach { port ->
            val observation = observeStoppedTarget(1, identity, port)
            var writes = 0
            val result = StoppedRecoveryEngine({ observation }, { error("claim") }, { true }, { writes++; 0 }).recover(1, identity)
            assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
            assertEquals(0, writes)
        }
    }

    @Test fun legacySharedTelegramNeverClaimsAndCannotProduceConfirmedReadback() {
        val telegram = identity.copy(packageName = "org.telegram.messenger")
        val shared = StoppedTargetObservation.Known(1, telegram, true, false, 100)
        assertFalse(shared.hasVerifiedPackageUid)
        var writes = 0
        val blocked = StoppedRecoveryEngine({ shared }, { error("claim") }, { true }, { writes++; 0 }).recover(1, telegram)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, blocked.outcome)
        assertEquals(0, writes)
        var reads = 0
        val afterLostOwnership = StoppedRecoveryEngine({
            if (++reads < 3) shared.copy(exclusiveUidOwner = true) else shared.copy(stopped = false)
        }, { true }, { true }, { writes++; 0 }).recover(1, telegram)
        assertEquals(StoppedRecoveryOutcome.UNCONFIRMED, afterLostOwnership.outcome)
        assertEquals(1, writes)
        assertThrows(IllegalStateException::class.java) { StoppedRecoveryJournal(Memory()).prepare(telegram, shared, true) }
    }

    private fun rotatedFields(certificate: ByteArray = byteArrayOf(4, 5, 6)): StoppedPackageFields {
        val rotated = fields.copy(currentSignerCertificates = listOf(certificate))
        val current = requireNotNull(validatedStoppedPackage(rotated, identity.packageName)).first
        return rotated.copy(signing = TargetSigningEvidence(false, identity.signerSha256 + current.signerSha256))
    }

    @Test fun gmsSystemUsesFrozenRecipientIdentityAndFreshLineageWithoutBypassingOffOrCorruptStorage() {
        val target = identity.copy(packageName = "com.google.android.gms")
        val rotated = rotatedFields().copy(packageName = target.packageName, applicationPackageName = target.packageName,
            qualifications = qualifications.copy(system = true, updatedSystem = true))
        val owners = listOf(target.packageName)
        val baseline = observeStoppedTarget(1, target, Port(listOf(rotated, rotated), owners)) as StoppedTargetObservation.Known
        assertEquals(target, baseline.identity)
        assertNotEquals(target, validatedStoppedPackage(rotated, target.packageName)!!.first)
        for (samples in listOf(listOf(rotated.copy(signing = null), rotated),
            listOf(rotated, rotated.copy(signing = null)),
            listOf(rotated.copy(signing = rotated.signing!!.copy(historySha256 = listOf("invalid"))), rotated))) {
            assertEquals(StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED,
                (observeStoppedTarget(1, target, Port(samples, owners)) as StoppedTargetObservation.Unknown).reason)
        }
        assertEquals(StoppedRecoveryOutcome.BLOCKED,
            StoppedRecoveryEngine({ baseline }, { error("OFF must not claim") }, { false },
                { error("OFF must not dispatch") }).recover(1, target).outcome)
        val memory = Memory().apply { bytes = byteArrayOf(1, 2, 3) }
        val corrupt = StoppedRecoveryJournal(memory)
        assertThrows(Exception::class.java) { corrupt.prepareAutomatically(target, baseline, consent) }
        assertArrayEquals(byteArrayOf(1, 2, 3), memory.bytes)
    }

    @Test fun verifiedRotationRetainsExpectedIdentityAcrossCodecJournalAndRecovery() {
        val rotated = rotatedFields()
        assertNotEquals(identity, validatedStoppedPackage(rotated, identity.packageName)!!.first)
        fun sample(stopped: Boolean) = observeStoppedTarget(1, identity,
            Port(listOf(rotated.copy(stopped = stopped), rotated.copy(stopped = stopped))))
        val baseline = sample(true) as StoppedTargetObservation.Known
        assertEquals(identity, baseline.identity)
        assertEquals(baseline, StoppedObservationCodec.decode(StoppedObservationCodec.encode(baseline), 1, identity))
        val journal = StoppedRecoveryJournal(Memory())
        val record = journal.prepareAutomatically(identity, baseline, consent)
        var reads = 0
        var writes = 0
        val result = StoppedRecoveryEngine({ sample(++reads < 3) }, journal::claim, { true },
            { writes++; 0 }).recover(record.requestId, identity)
        assertEquals(StoppedRecoveryOutcome.FLAG_CLEARED, result.outcome)
        assertEquals(1, writes)
        assertEquals(identity, journal.load().latest!!.identity)
        assertEquals(consent.id, journal.load().latest!!.consentId)
    }

    @Test fun rotationNeverOverridesRevocationOrGrantsAuthorityByItself() {
        val rotated = rotatedFields()
        val observation = observeStoppedTarget(1, identity, Port(listOf(rotated, rotated)))
        var writes = 0
        val result = StoppedRecoveryEngine({ observation }, { fail("OFF must not claim"); false },
            { false }, { writes++; 0 }).recover(1, identity)
        assertEquals(StoppedRecoveryOutcome.BLOCKED, result.outcome)
        assertEquals(0, writes)
    }

    @Test fun rawSignerRacesRemainVisibleEvenWhenEachReadHasValidHistory() {
        val middle = rotatedFields()
        val latest = rotatedFields(byteArrayOf(7, 8, 9))
        for ((before, after) in listOf(fields to middle, middle to latest)) {
            val result = observeStoppedTarget(1, identity, Port(listOf(before, after))) as StoppedTargetObservation.Unknown
            assertEquals(StoppedObservationUnknown.IDENTITY_CHANGED, result.reason)
        }
    }

    @Test fun lineageMustBePresentOnEachReadAndCannotBypassUidInstallOrMultisignerChanges() {
        val rotated = rotatedFields()
        val malformed = listOf(rotated.copy(signing = null),
            rotated.copy(signing = rotated.signing!!.copy(hasMultipleSigners = true)),
            rotated.copy(signing = rotated.signing!!.copy(historySha256 = identity.signerSha256)),
            rotated.copy(signing = rotated.signing!!.copy(historySha256 = rotated.signing!!.historySha256!! + "invalid")),
            rotated.copy(signing = rotated.signing!!.copy(historySha256 = rotated.signing!!.historySha256!! + identity.signerSha256)),
            rotated.copy(signing = rotated.signing!!.copy(historySha256 = (1..33).map { it.toString(16).padStart(64, '0') })),
            rotated.copy(uid = identity.uid + 1), rotated.copy(firstInstallTime = identity.firstInstallTime + 1),
            rotated.copy(currentSignerCertificates = fields.currentSignerCertificates!! + rotated.currentSignerCertificates!!))
        malformed.forEach { sample ->
            assertEquals(StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED,
                (observeStoppedTarget(1, identity, Port(listOf(sample, sample))) as StoppedTargetObservation.Unknown).reason)
        }
        for (missingIndex in 0..1) {
            val samples = List(2) { if (it == missingIndex) rotated.copy(signing = null) else rotated }
            assertEquals(StoppedObservationUnknown.EXPECTED_IDENTITY_CHANGED,
                (observeStoppedTarget(1, identity, Port(samples)) as StoppedTargetObservation.Unknown).reason)
        }
    }
}
