package io.github.originosfcmguardian.autostart

import org.junit.Assert.*
import org.junit.Test

class RecoveryCandidatePackageReadTest {
    private val certificate = byteArrayOf(1, 2, 3)
    private val identity = requireNotNull(targetPackageIdentity(
        "example.app", "example.app", "example.app", 10434, 123, listOf(certificate)))
    private val qualified = RecoveryTargetQualifications(true, true, false, false, false)
    private val evidence = RecoveryCandidatePackageEvidence(identity, qualified)

    @Test fun qualificationUsesOneSignedReadAndFreshUidMembershipOnEveryCall() {
        var packageReads = 0
        var memberReads = 0
        var owners = listOf("example.app", "example.shared")
        val read = { packageReads++; evidence }
        val members: (Int) -> List<String>? = { uid ->
            assertEquals(identity.uid, uid)
            memberReads++
            owners
        }
        assertTrue(readRecoveryCandidate(identity, read, members))
        assertEquals(1, packageReads)
        assertEquals(1, memberReads)
        owners = listOf("example.shared")
        assertFalse(readRecoveryCandidate(identity, read, members))
        assertEquals(2, packageReads)
        assertEquals(2, memberReads)
    }

    @Test fun qualificationFailuresNeverReadUidMembership() {
        val failures = listOf(qualified.copy(installed = false), qualified.copy(installed = null),
            qualified.copy(enabled = false), qualified.copy(enabled = null), qualified.copy(system = true),
            qualified.copy(system = null), qualified.copy(updatedSystem = true), qualified.copy(updatedSystem = null),
            qualified.copy(persistent = true), qualified.copy(persistent = null))
        failures.forEach { flags ->
            assertFalse(readRecoveryCandidate(identity, { evidence.copy(qualifications = flags) }) {
                fail("Unqualified package must not reach UID membership read"); null
            })
        }
    }

    @Test fun changedIdentityAndUnavailableSignedReadRemainFailClosed() {
        val changed = listOf(identity.copy(uid = 10435), identity.copy(firstInstallTime = 124),
            identity.copy(packageName = "example.other"), identity.copy(signerSha256 = listOf("a".repeat(64))), null)
        changed.forEach { current ->
            assertFalse(readRecoveryCandidate(identity, { evidence.copy(identity = current) }) {
                fail("Changed identity must not reach UID membership read"); null
            })
        }
        assertFalse(readRecoveryCandidate(identity, { null }) { listOf(identity.packageName) })
        assertFalse(readRecoveryCandidate(identity, { error("Package read unavailable") }) { listOf(identity.packageName) })
        assertFalse(readRecoveryCandidate(identity, { evidence }) { error("UID read unavailable") })
    }

    @Test fun sharedUidStillRequiresBoundedUniqueValidMembersIncludingTarget() {
        for (owners in listOf(null, emptyList(), listOf("example.other"),
            listOf("example.app", "example.app"), listOf("example.app", "bad member"),
            listOf("example.app") + (1..64).map { "example.member$it" })) {
            assertFalse(readRecoveryCandidate(identity, { evidence }) { owners })
        }
        assertTrue(readRecoveryCandidate(identity, { evidence }) { listOf(identity.packageName) })
        assertTrue(readRecoveryCandidate(identity, { evidence }) {
            listOf(identity.packageName) + (1..63).map { "example.member$it" }
        })
    }

    @Test fun excludedCorePackageDoesNotStartPackageOrUidRead() {
        val core = identity.copy(packageName = "com.google.android.gms")
        assertFalse(readRecoveryCandidate(core, { fail("Core target must not be read"); evidence }) {
            fail("Core target must not reach UID read"); null
        })
    }

    @Test fun signedPackageIdentityPreservesPackageUidInstallAndSignerValidation() {
        fun read(name: String? = "example.app", appName: String? = "example.app", uid: Int = 10434,
            installedAt: Long = 123, certificates: List<ByteArray>? = listOf(certificate)) =
            targetPackageIdentity("example.app", name, appName, uid, installedAt, certificates)
        assertEquals(identity, read())
        assertNull(read(name = "example.other"))
        assertNull(read(name = null))
        assertNull(read(appName = "example.other"))
        assertNull(read(appName = null))
        assertNull(read(uid = 1000))
        assertNull(read(uid = 110434))
        assertNull(read(installedAt = 0))
        assertNull(read(certificates = null))
        assertNull(read(certificates = emptyList()))
        assertNull(targetPackageIdentity("bad name", "bad name", "bad name", 10434, 123, listOf(certificate)))
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", identity.signerSha256.single())
    }

    @Test fun multisignerIdentitySortsExactSetsButRejectsDuplicateCertificates() {
        val other = byteArrayOf(4, 5, 6)
        val first = targetPackageIdentity("example.app", "example.app", "example.app", 10434, 123,
            listOf(certificate, other, certificate))
        val second = targetPackageIdentity("example.app", "example.app", "example.app", 10434, 123,
            listOf(other, certificate))
        assertNull(first)
        assertEquals(2, second?.signerSha256?.size)
        assertEquals(second?.signerSha256?.sorted(), second?.signerSha256)
    }

    @Test fun freshVerifiedLineageKeepsQualifiedBaselineWithoutChangingStoredIdentity() {
        val rotated = identity.copy(signerSha256 = listOf("b".repeat(64)))
        val signing = TargetSigningEvidence(false, identity.signerSha256 + rotated.signerSha256)
        var fresh = evidence.copy(identity = rotated, signing = signing)
        assertTrue(readRecoveryCandidate(identity, { fresh }) { listOf(identity.packageName) })
        fresh = fresh.copy(signing = null)
        assertFalse(readRecoveryCandidate(identity, { fresh }) { fail("Missing lineage must block UID read"); null })
        assertEquals(rotated, fresh.identity)
    }
}
