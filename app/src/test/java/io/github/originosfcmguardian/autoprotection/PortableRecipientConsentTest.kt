package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class PortableRecipientConsentTest {
    private val current = "another/product/device:17/BUILD/123:user/release-keys"
    private val identity = SelectedTargetIdentity("org.telegram.messenger", 10123, 1234, listOf("a".repeat(64)))
    private val recipient = TargetConsent.Recipient(RecipientConsentToken(
        "12345678-1234-1234-1234-123456789abc", 3, 1000, identity, VERIFIED_AUTOSTART_FINGERPRINT))
    private val legacy = TargetConsent.Legacy(DurableConsentToken(recipient.id, recipient.generation,
        recipient.consentedAtMillis, identity, recipient.fingerprint, policyVersion = 2))

    private fun authority(consent: TargetConsent = recipient) = ProtectionAuthoritySnapshot(
        generation = 7, mode = ProtectionConsentMode.ACTIVE, token = null, suspensionReason = null,
        budget = BudgetSnapshot(BudgetClock(1, 600_000, 601_000), listOf(0L, 300_000L, 600_000L), null),
        targetConsents = listOf(consent), schemaVersion = 3, masterEnabledAtMillis = 2000)

    private fun inputs(root: ProtectionAuthoritySnapshot = authority()) = StartupEligibilityInputs(
        authorityReadSuccessful = true, authority = root, userUnlocked = true,
        exitHistory = StartupExitHistoryStatus.CLEAR, fingerprint = current, currentUser = 0,
        journal = RestartJournalStatus.CLEAR, budget = AutomaticCheckDecision.Recover,
        privilege = RestartPrivilegeEvidence(true, true, 2000, 0, true),
        targets = root.runnableConsents().map {
            StartupTargetEvidence(TargetConsentLease(root.generation, it), it.identity, it.identity, true)
        })

    @Test fun `policy4 accepts valid runtime firmware while legacy retains exact binding`() {
        assertTrue(recipient.acceptsRuntimeFingerprint(current))
        assertTrue(recipient.acceptsRuntimeFingerprint(recipient.fingerprint))
        assertFalse(legacy.acceptsRuntimeFingerprint(current))
        assertTrue(legacy.acceptsRuntimeFingerprint(legacy.fingerprint))
        for (invalid in listOf(null, "", "has space", "invalid|fingerprint", "x".repeat(257))) {
            assertFalse(recipient.acceptsRuntimeFingerprint(invalid))
            assertFalse(legacy.acceptsRuntimeFingerprint(invalid))
        }
        assertEquals(StartupEligibilityStatus.READY_TO_START, startupEligibility(inputs()))
        assertEquals(StartupEligibilityStatus.BLOCKED_IDENTITY, startupEligibility(inputs(authority(legacy))))
    }

    @Test fun `OTA startup keeps token authority generation history and budget byte for byte`() {
        for (root in listOf(authority(), authority(legacy))) {
            val encoded = ProtectionAuthorityCodec.encode(root)
            val decoded = ProtectionAuthorityCodec.decode(encoded)
            val input = inputs(decoded)
            repeat(3) {
                startupEligibility(input)
                confirmedInvalidTargetLeases(input, true)
                decoded.consents().forEach { it.acceptsRuntimeFingerprint(current) }
            }
            assertEquals(root, decoded)
            assertEquals(root.targetConsents, decoded.targetConsents)
            assertArrayEquals(encoded, ProtectionAuthorityCodec.encode(decoded))
        }
    }

    @Test fun `OTA never bypasses invalidation suspension user shell storage or identity gates`() {
        val ready = inputs()
        val root = ready.authority!!
        val target = ready.targets!!.single()
        val blocked = listOf(
            ready.copy(authority = root.copy(invalidatedConsentIds = listOf(recipient.id)), targets = emptyList()) to
                StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
            ready.copy(authority = root.copy(mode = ProtectionConsentMode.SUSPENDED,
                suspensionReason = ProtectionSuspensionReason.SERVICE_UNAVAILABLE), targets = emptyList()) to
                StartupEligibilityStatus.NEEDS_EXPLICIT_CONSENT,
            ready.copy(authority = root.copy(mode = ProtectionConsentMode.USER_PAUSED), targets = emptyList()) to
                StartupEligibilityStatus.OFF,
            ready.copy(currentUser = 10) to StartupEligibilityStatus.BLOCKED_IDENTITY,
            ready.copy(fingerprint = "invalid|fingerprint") to StartupEligibilityStatus.BLOCKED_IDENTITY,
            ready.copy(targets = listOf(target.copy(freshIdentity = identity.copy(firstInstallTime = 1235)))) to
                StartupEligibilityStatus.BLOCKED_IDENTITY,
            ready.copy(targets = listOf(target.copy(selectedIdentity = identity.copy(uid = 10124)))) to
                StartupEligibilityStatus.BLOCKED_IDENTITY,
            ready.copy(targets = listOf(target.copy(qualified = false))) to StartupEligibilityStatus.BLOCKED_IDENTITY,
            ready.copy(privilege = RestartPrivilegeEvidence()) to StartupEligibilityStatus.WAITING_PRIVILEGE,
            ready.copy(authorityReadSuccessful = false) to StartupEligibilityStatus.BLOCKED_STORAGE,
            ready.copy(authority = root.copy(storageBlocked = true)) to StartupEligibilityStatus.BLOCKED_STORAGE,
            ready.copy(journal = RestartJournalStatus.CORRUPT) to StartupEligibilityStatus.BLOCKED_RECONCILIATION,
            ready.copy(exitHistory = StartupExitHistoryStatus.USER_STOPPED) to StartupEligibilityStatus.STOPPED_BY_USER,
        )
        blocked.forEach { (input, expected) ->
            assertEquals(expected, startupEligibility(input))
            assertTrue(confirmedInvalidTargetLeases(input, false).isEmpty())
        }
        assertEquals(ProtectionConsentMode.ACTIVE, root.mode)
        assertTrue(root.invalidatedConsentIds.isEmpty())
    }

    @Test fun `confirmed invalid identity can retire after OTA only under remaining shared gates`() {
        val ready = inputs()
        val target = ready.targets!!.single()
        val changed = ready.copy(targets = listOf(target.copy(
            freshIdentity = identity.copy(firstInstallTime = 1235), qualified = false)))
        assertEquals(listOf(target.lease), confirmedInvalidTargetLeases(changed, true))
        for (blocked in listOf(changed.copy(currentUser = 10), changed.copy(privilege = RestartPrivilegeEvidence()),
            changed.copy(authorityReadSuccessful = false), changed.copy(journal = RestartJournalStatus.PENDING),
            changed.copy(fingerprint = "invalid|fingerprint"), changed.copy(targetReadUnavailable = true))) {
            assertTrue(confirmedInvalidTargetLeases(blocked, true).isEmpty())
        }
        val old = inputs(authority(legacy))
        assertTrue(confirmedInvalidTargetLeases(old.copy(targets = old.targets!!.map {
            it.copy(freshIdentity = identity.copy(firstInstallTime = 1235), qualified = false)
        }), true).isEmpty())
    }
}
