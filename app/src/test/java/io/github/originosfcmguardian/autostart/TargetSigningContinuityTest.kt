package io.github.originosfcmguardian.autostart

import org.junit.Assert.*
import org.junit.Test

class TargetSigningContinuityTest {
    private val old = SelectedTargetIdentity("example.app", 10434, 123, listOf("a".repeat(64)))
    private val current = old.copy(signerSha256 = listOf("b".repeat(64)))
    private val lineage = TargetSigningEvidence(false, old.signerSha256 + current.signerSha256)

    @Test fun validLineageKeepsOnlyAnExistingBaselineAndNewSelectionStaysRaw() {
        val snapshot = TargetPackageSnapshot(current, false, lineage)
        assertTrue(targetSigningContinues(old, current, lineage))
        assertEquals(old, snapshot.identityFor(old))
        assertEquals(current, snapshot.identity)
        assertNotEquals(old, current)
        assertEquals(listOf(old), (TargetSelectionCodec.decode(TargetSelectionCodec.encode(listOf(old)))
            as TargetSelectionDecodeResult.Valid).values)
    }

    @Test fun packageUidAndInstallationChangesNeverInheritBaseline() {
        listOf(current.copy(packageName = "example.other"), current.copy(uid = 10435),
            current.copy(firstInstallTime = 124)).forEach {
            assertFalse(targetSigningContinues(old, it, lineage))
        }
    }

    @Test fun rotationRequiresBothSignersAndWellFormedBoundedUniqueHistory() {
        val invalid = listOf(null, lineage.copy(hasMultipleSigners = null), lineage.copy(hasMultipleSigners = true),
            lineage.copy(historySha256 = null), lineage.copy(historySha256 = emptyList()),
            lineage.copy(historySha256 = old.signerSha256), lineage.copy(historySha256 = current.signerSha256),
            lineage.copy(historySha256 = lineage.historySha256!! + old.signerSha256),
            lineage.copy(historySha256 = lineage.historySha256!! + "malformed"),
            lineage.copy(historySha256 = lineage.historySha256!! + (1..31).map { it.toString(16).padStart(64, '0') }))
        invalid.forEach { assertFalse(targetSigningContinues(old, current, it)) }
    }

    @Test fun exactMultisignerSetsWorkAndNoSubsetCanUseLineage() {
        val multi = old.copy(signerSha256 = (old.signerSha256 + current.signerSha256).sorted())
        assertTrue(targetSigningContinues(multi, multi, TargetSigningEvidence(true, null)))
        assertFalse(targetSigningContinues(multi, current, lineage))
        assertFalse(targetSigningContinues(old, multi, lineage))
        assertFalse(targetSigningContinues(multi, multi.copy(signerSha256 = listOf("c".repeat(64))), lineage))
    }

    @Test fun recipientLineageDoesNotBroadenCorePackageTrust() {
        val core = old.copy(packageName = "moe.shizuku.privileged.api")
        assertFalse(targetSigningContinues(core, current.copy(packageName = core.packageName), lineage))
        assertTrue(targetSigningContinues(core, core, null))
    }

    @Test fun malformedCertificateListsCannotBecomeTrustedDigests() {
        val cert = byteArrayOf(1, 2, 3)
        for (certificates in listOf(null, emptyList(), listOf(byteArrayOf()), listOf(cert, cert),
            listOf(ByteArray(MAX_TARGET_CERTIFICATE_BYTES + 1)), (1..33).map { byteArrayOf(it.toByte()) })) {
            assertNull(targetCertificateDigests(certificates, MAX_TARGET_SIGNING_HISTORY))
        }
        assertNotNull(targetCertificateDigests(listOf(cert), MAX_TARGET_SIGNING_HISTORY))
    }

    @Test fun frozenInventoryBaselineRequiresOneDistinctIdentityForPackage() {
        assertEquals(mapOf(old.packageName to old), uniqueTargetBaselines(listOf(old, old)))
        assertTrue(uniqueTargetBaselines(listOf(old, current)).isEmpty())
        assertTrue(uniqueTargetBaselines(emptyList()).isEmpty())
    }
}
