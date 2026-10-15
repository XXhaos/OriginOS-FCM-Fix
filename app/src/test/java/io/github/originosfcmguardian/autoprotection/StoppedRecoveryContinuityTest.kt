package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoveryRecord
import io.github.originosfcmguardian.recovery.StoppedRecoveryResult
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoppedRecoveryContinuityTest {
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private val consent = TargetConsent.Recipient(RecipientConsentToken(
        "12345678-1234-1234-1234-123456789abc", 1, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val record = StoppedRecoveryRecord(2, identity, 2000, 1900, VERIFIED_AUTOSTART_FINGERPRINT, 0,
        baselineStopped = true, confirmedManualAction = false, acknowledgedRuntimeRisk = true,
        stage = StoppedRecoveryStage.FINISHED, outcome = StoppedRecoveryOutcome.STILL_STOPPED,
        source = StoppedRecoverySource.AUTOMATIC, consentId = consent.id,
        consentedAtMillis = consent.consentedAtMillis, consentVersion = consent.policyVersion)
    private val result = StoppedRecoveryResult(StoppedRecoveryOutcome.STILL_STOPPED, "confirmed true", record)

    @Test fun `fresh matching terminal automatic readback can continue without claiming command success`() {
        assertTrue(mayContinueAfterStoppedRecovery(result, record, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        assertFalse(result.commandSucceeded)
        val legacy = TargetConsent.Legacy(DurableConsentToken(consent.id, 1, 1000, identity,
            VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2))
        val legacyRecord = record.copy(consentVersion = 2)
        assertTrue(mayContinueAfterStoppedRecovery(result.copy(record = legacyRecord), legacyRecord, 1, legacy,
            VERIFIED_AUTOSTART_FINGERPRINT))
    }

    @Test fun `pending reconciled missing old or replaced requests never continue`() {
        assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = null), record, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        assertFalse(mayContinueAfterStoppedRecovery(result, null, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        assertFalse(mayContinueAfterStoppedRecovery(result, record, 2, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        assertFalse(mayContinueAfterStoppedRecovery(result, record, -1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        assertFalse(mayContinueAfterStoppedRecovery(result, record.copy(requestId = 3), 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        for (stage in StoppedRecoveryStage.entries.filter { it != StoppedRecoveryStage.FINISHED }) {
            val changed = record.copy(stage = stage)
            assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = changed), changed, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        }
    }

    @Test fun `blocked unknown or success outcomes cannot use stopped continuation`() {
        for (outcome in StoppedRecoveryOutcome.entries.filter { it != StoppedRecoveryOutcome.STILL_STOPPED }) {
            assertFalse(mayContinueAfterStoppedRecovery(result.copy(outcome = outcome), record, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
            val changed = record.copy(outcome = outcome)
            assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = changed), changed, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        }
    }

    @Test fun `identity consent provenance firmware and user must all match`() {
        val mismatches = listOf(
            record.copy(identity = identity.copy(firstInstallTime = 1235)),
            record.copy(identity = identity.copy(signerSha256 = listOf("b".repeat(64)))),
            record.copy(source = StoppedRecoverySource.MANUAL),
            record.copy(consentId = "22345678-1234-1234-1234-123456789abc"),
            record.copy(consentedAtMillis = 1001), record.copy(consentVersion = 2),
            record.copy(fingerprint = "other"), record.copy(currentUser = 10),
            record.copy(baselineStopped = false), record.copy(confirmedManualAction = true),
            record.copy(acknowledgedRuntimeRisk = false),
        )
        mismatches.forEach { changed ->
            assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = changed), changed, 1, consent, VERIFIED_AUTOSTART_FINGERPRINT))
        }
    }

    @Test fun `recipient consent continues after OTA only with receipt from current firmware`() {
        val current = "another/product/device:17/BUILD/123:user/release-keys"
        val fresh = record.copy(fingerprint = current)
        assertTrue(mayContinueAfterStoppedRecovery(result.copy(record = fresh), fresh, 1, consent, current))
        assertFalse(mayContinueAfterStoppedRecovery(result, record, 1, consent, current))
        assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = fresh), fresh, 1, consent, "invalid|fingerprint"))
        val legacy = TargetConsent.Legacy(DurableConsentToken(consent.id, 1, 1000, identity,
            VERIFIED_AUTOSTART_FINGERPRINT, policyVersion = 2))
        val legacyFresh = fresh.copy(consentVersion = 2)
        assertFalse(mayContinueAfterStoppedRecovery(result.copy(record = legacyFresh), legacyFresh, 1, legacy, current))
    }
}
