package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.FcmRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class GmsSigningTrustTest {
    private val anchor = FcmRecoveryPolicy.SIGNER
    private val current = "b".repeat(64)
    private fun identity() = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
        10234, 99, 123, 456, current, listOf(anchor, current))

    @Test fun exactPinDoesNotDependOnUnusedHistoryAndRotationNeedsBothCertificates() {
        assertEquals(anchor, gmsGoogleTrustAnchor(identity().copy(signerSha256 = anchor,
            signingCertificateHistorySha256 = null)))
        assertEquals(anchor, gmsGoogleTrustAnchor(identity().copy(signerSha256 = anchor,
            signingCertificateHistorySha256 = listOf("malformed"))))
        assertEquals(anchor, gmsGoogleTrustAnchor(identity()))
        assertEquals(anchor, gmsGoogleTrustAnchor(identity().copy(signingCertificateHistorySha256 =
            listOf(anchor, "c".repeat(64), current))))
    }

    @Test fun malformedMissingDuplicateAndOversizedHistoryCannotProveRotation() {
        val histories = listOf(null, emptyList(), listOf(anchor), listOf(current),
            listOf("c".repeat(64), current), listOf(anchor, current, current),
            listOf(anchor, "bad", current), listOf(anchor.uppercase(), current),
            listOf(anchor, current) + (1..31).map { it.toString(16).padStart(64, '0') })
        histories.forEach { assertNull(gmsGoogleTrustAnchor(identity().copy(signingCertificateHistorySha256 = it))) }
        assertNull(gmsGoogleTrustAnchor(identity().copy(hasMultipleSigners = true)))
        assertNull(gmsGoogleTrustAnchor(identity().copy(signerSha256 = anchor, hasMultipleSigners = true)))
    }

    @Test fun historyCertificateCountSizeAndDuplicatesAreBoundedBeforeHashing() {
        val bytes = listOf(byteArrayOf(1), byteArrayOf(2))
        assertEquals(bytes.map { gmsSignerDigest(listOf(it)) }, gmsSigningHistoryDigests(bytes))
        assertNotNull(gmsSigningHistoryDigests((1..32).map { byteArrayOf(it.toByte()) }))
        assertNotNull(gmsSigningHistoryDigests(listOf(ByteArray(65536))))
        listOf(emptyList(), List(33) { byteArrayOf(it.toByte()) }, listOf(ByteArray(0)),
            listOf(ByteArray(65537)), listOf(bytes[0], bytes[0])).forEach {
            assertNull(gmsSigningHistoryDigests(it))
        }
    }

    @Test fun disconnectedMcsRequiresGoogleAnchorExclusiveOwnerAndPrimaryShellUser() {
        val owners = listOf(GMS_OBSERVATION_PACKAGE)
        assertTrue(qualifiedGmsMcsIdentity(identity(), 2000, 0, owners))
        assertTrue(qualifiedGmsMcsIdentity(identity().copy(signerSha256 = anchor,
            signingCertificateHistorySha256 = null), 2000, 0, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity().copy(signingCertificateHistorySha256 = null), 2000, 0, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity().copy(hasMultipleSigners = true), 2000, 0, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity(), 0, 0, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity(), 2000, 10, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity(), 2000, 0, owners + "other.pkg"))
        assertFalse(qualifiedGmsMcsIdentity(identity().copy(applicationPackageName = "other.pkg"), 2000, 0, owners))
        assertFalse(qualifiedGmsMcsIdentity(identity().copy(uid = 1010234), 2000, 0, owners))
    }

    @Test fun disconnectedMcsIdentityUsesFreshRuntimeEvidenceAcrossUnlistedVersions() {
        val owners = listOf(GMS_OBSERVATION_PACKAGE)
        for (version in listOf(0L, FcmRecoveryPolicy.MCS_VERSION, 264032035L, 264032036L, Long.MAX_VALUE)) {
            assertTrue("version=$version", qualifiedGmsMcsIdentity(identity().copy(versionCode = version), 2000, 0, owners))
            assertFalse("version=$version", qualifiedGmsMcsIdentity(identity().copy(versionCode = version,
                signingCertificateHistorySha256 = null), 2000, 0, owners))
        }
    }
}
