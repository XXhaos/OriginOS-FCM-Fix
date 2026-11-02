package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GmsUidOwnershipTest {
    private val anchor = FcmRecoveryPolicy.SIGNER
    private val gms = GmsPackageIdentity(GMS_OBSERVATION_PACKAGE, GMS_OBSERVATION_PACKAGE,
        10234, 99, 123, 456, anchor)
    // Deliberately not a Google package-name allowlist: only fresh PM signing evidence counts.
    private val peer = gms.copy(packageName = "org.example.uidmember", applicationPackageName = "org.example.uidmember")
    private val owners = listOf(gms.packageName, peer.packageName)
    private fun qualified(member: GmsPackageIdentity? = peer, names: List<String>? = owners,
        identity: GmsPackageIdentity? = gms) = qualifiedGmsUidOwnership(identity, names, { member })

    @Test fun sharedGoogleSignerAndAuthenticatedRotationsAreAcceptedWithoutANameOrVersionAllowlist() {
        assertNotNull(qualified())
        val rotated = "b".repeat(64)
        assertNotNull(qualified(peer.copy(signerSha256 = rotated, signingCertificateHistorySha256 = listOf(anchor, rotated))))
        assertNotNull(qualified(identity = gms.copy(signerSha256 = rotated,
            signingCertificateHistorySha256 = listOf(anchor, rotated))))
        assertEquals(qualified(), qualified(names = owners.reversed()))
        assertNotNull(qualified(peer.copy(versionCode = Long.MAX_VALUE)))
    }

    @Test fun oneUntrustedUnreadableWrongUidOrMalformedMemberRejectsUidSocketAttribution() {
        val bad = "c".repeat(64)
        listOf(null, peer.copy(uid = peer.uid + 1), peer.copy(packageName = "org.example.wrong"),
            peer.copy(applicationPackageName = "org.example.wrong"), peer.copy(uid = 1_010_234),
            peer.copy(firstInstallTime = 0), peer.copy(lastUpdateTime = 0), peer.copy(versionCode = -1),
            peer.copy(signerSha256 = bad), peer.copy(signerSha256 = bad, signingCertificateHistorySha256 = listOf(anchor)),
            peer.copy(signerSha256 = bad, signingCertificateHistorySha256 = listOf(anchor, bad, bad)),
            peer.copy(signerSha256 = bad, signingCertificateHistorySha256 = listOf(anchor, bad), hasMultipleSigners = true),
            peer.copy(hasMultipleSigners = true)).forEach { member -> assertNull("member=$member", qualified(member)) }
        assertNull(qualified(identity = gms.copy(signerSha256 = bad)))
    }

    @Test fun ownershipMustContainGmsAndBeCompleteUniqueValidAndBoundedBeforeMemberReads() {
        val malformed = listOf(null, emptyList(), listOf(peer.packageName), owners + peer.packageName,
            owners + "invalid name", owners + "org.example." + "x".repeat(256),
            listOf(gms.packageName) + (1..64).map { "org.example.member$it" })
        malformed.forEach { names ->
            var reads = 0
            assertNull(qualifiedGmsUidOwnership(gms, names, { reads++; peer }))
            assertEquals(0, reads)
        }
    }

    @Test fun exclusiveObservationDoesNotSilentlyGainGoogleAuthorityOrReadUnrelatedPackages() {
        val otherSigner = gms.copy(signerSha256 = "a".repeat(64))
        val value = qualifiedGmsUidOwnership(otherSigner, listOf(gms.packageName), { error("no member read") })
        assertEquals(listOf(otherSigner), value?.packages)
        assertNull(gmsGoogleTrustAnchor(value!!.packages.single()))
    }

    private inner class Port : GmsConnectionReadPort {
        var currentGms = gms
        var names = owners
        var member: GmsPackageIdentity? = peer
        var gmsReads = 0
        var ownerReads = 0
        var memberReads = 0
        var socketReads = 0
        var serviceReads = 0
        var receiverReads = 0
        var duringMember: () -> Unit = {}
        var afterOwnerRead: () -> Unit = {}
        var afterSources: () -> Unit = {}
        override fun environment() = GmsProbeEnvironment(0, 2000, "12345678-1234-1234-1234-123456789abc")
        override fun gmsPackage() = currentGms.also { gmsReads++ }
        override fun packageIdentity(packageName: String): GmsPackageIdentity? {
            memberReads++; duringMember()
            return if (packageName == peer.packageName) member else null
        }
        override fun packagesForUid(uid: Int): List<String> = names.also { ownerReads++; afterOwnerRead() }
        override fun sockets(ipv6: Boolean, uid: Int, interrupted: () -> Boolean): SocketSource {
            socketReads++
            return SocketSource(SourceCompleteness.COMPLETE, !ipv6)
        }
        override fun gcmService(interrupted: () -> Boolean): GcmServiceSource {
            serviceReads++
            return GcmServiceParser.parse((
                "SERVICE com.google.android.gms/.gcm.GcmService f7b120a pid=13069 user=0\n" +
                    "  Client:\n    Is client connected: true\n    connectTime=2:03/00:00\n").toByteArray()).also { afterSources() }
        }
        override fun reconnectReceivers() = GmsReceiverInspection(true, emptyList()).also { receiverReads++ }
        override fun elapsedRealtime() = 100L
    }

    @Test fun sharedServiceObservationNeverAssignsUidSocketsEvenToTrustedPeerSigners() {
        val port = Port()
        val sample = observeGmsConnection(1, port) as GmsConnectionObservation.Sample
        assertEquals(SocketObservation.UNKNOWN, sample.observation)
        assertTrue(FcmRecoveryPolicy.reportedConnected(sample, 100))
        assertFalse(FcmRecoveryPolicy.connected(sample, 100))
        assertFalse(GmsObservationCodec.encode(sample).contains(peer.packageName))
        assertEquals(0, port.memberReads); assertEquals(4, port.ownerReads)
        assertEquals(4, port.gmsReads); assertEquals(0, port.socketReads)

        val mutations: List<(Port) -> Unit> = listOf(
            { it.member = peer.copy(versionCode = peer.versionCode + 1) },
            { it.member = peer.copy(firstInstallTime = peer.firstInstallTime + 1) },
            { it.member = peer.copy(signerSha256 = "b".repeat(64),
                signingCertificateHistorySha256 = listOf(anchor, "b".repeat(64))) })
        mutations.forEach { mutation ->
            val drift = Port().apply { afterSources = { mutation(this) } }
            val result = observeGmsConnection(2, drift) as GmsConnectionObservation.Sample
            assertEquals(SocketObservation.UNKNOWN, result.observation)
            assertEquals(SourceCompleteness.UNAVAILABLE, result.tcp.completeness)
            assertEquals(GcmConnectionState.REPORTED_CONNECTED, result.gcmService.state)
            assertEquals(SourceCompleteness.COMPLETE, result.gcmService.completeness)
        }
        val membershipChanged = Port().apply { afterSources = { names = listOf(gms.packageName) } }
        assertEquals(GmsUnknown.IDENTITY_CHANGED,
            (observeGmsConnection(2, membershipChanged) as GmsConnectionObservation.Unknown).reason)
        val reordered = Port().apply { afterSources = { names = names.reversed() } }
        assertTrue(observeGmsConnection(3, reordered) is GmsConnectionObservation.Sample)
    }

    @Test fun sharedAndExclusiveObservationHaveFourTargetAndMembershipReadsWithNoPeerBinderCalls() {
        for (peerCount in listOf(0, 1, 4, 63)) {
            for (peerWouldBlock in listOf(false, true)) {
                val peerRelease = CountDownLatch(1)
                val port = Port().apply {
                    names = listOf(gms.packageName) + (1..peerCount).map { "org.example.member$it" }
                    duringMember = {
                        if (peerWouldBlock) peerRelease.await(100, TimeUnit.MILLISECONDS)
                        error("Optional peer Binder must never be invoked")
                    }
                }
                try {
                    val sample = observeGmsConnection(5, port) as GmsConnectionObservation.Sample
                    assertEquals(4, port.gmsReads)
                    assertEquals(4, port.ownerReads)
                    assertEquals(0, port.memberReads)
                    assertEquals(if (peerCount == 0) 2 else 0, port.socketReads)
                    assertEquals(1, port.serviceReads)
                    assertEquals(1, port.receiverReads)
                    assertEquals(if (peerCount == 0) SocketObservation.OBSERVED else SocketObservation.UNKNOWN,
                        sample.observation)
                    assertTrue(FcmRecoveryPolicy.reportedConnected(sample, 100))
                } finally { peerRelease.countDown() }
            }
        }
    }

    @Test fun eitherDirectionOfExclusiveMembershipChangeDiscardsServiceAndSocketEvidence() {
        for (sharedFirst in listOf(false, true)) {
            val port = Port().apply {
                names = if (sharedFirst) owners else listOf(gms.packageName)
                afterSources = { names = if (sharedFirst) listOf(gms.packageName) else owners }
            }
            val result = observeGmsConnection(6, port) as GmsConnectionObservation.Unknown
            assertEquals(GmsUnknown.IDENTITY_CHANGED, result.reason)
            assertEquals(0, port.memberReads)
        }
    }

    @Test fun sharedUidStillDiscardsAChangedOfficialTargetWithoutReadingAnyPeer() {
        val changes: List<(GmsPackageIdentity) -> GmsPackageIdentity> = listOf(
            { it.copy(uid = it.uid + 1) }, { it.copy(versionCode = it.versionCode + 1) },
            { it.copy(firstInstallTime = it.firstInstallTime + 1) },
            { it.copy(signerSha256 = "b".repeat(64), signingCertificateHistorySha256 = listOf(anchor, "b".repeat(64))) },
        )
        changes.forEach { change ->
            val port = Port().apply { afterSources = { currentGms = change(currentGms) } }
            val result = observeGmsConnection(7, port) as GmsConnectionObservation.Unknown
            assertEquals(GmsUnknown.IDENTITY_CHANGED, result.reason)
            assertEquals(0, port.memberReads)
            assertEquals(0, port.socketReads)
        }
    }

    @Test fun exactPackageProofRequiresPinnedGmsAndStableActualMembersWithoutReadingPeerIdentity() {
        val port = Port().apply { duringMember = { error("a fixed target proof must not inspect peers") } }
        val proof = requireNotNull(readGmsPackageOwnership(port, gms))
        assertEquals(gms, proof.gms)
        assertEquals(owners.sorted(), proof.members)
        assertEquals(0, port.memberReads)
        assertEquals(qualifiedGmsPackageOwnership(gms, owners.reversed()), proof)
        val rotated = gms.copy(signerSha256 = "b".repeat(64),
            signingCertificateHistorySha256 = listOf(anchor, "b".repeat(64)))
        assertNotNull(qualifiedGmsPackageOwnership(rotated, owners))
        listOf(gms.copy(signerSha256 = "c".repeat(64)), gms.copy(hasMultipleSigners = true),
            gms.copy(packageName = peer.packageName), gms.copy(applicationPackageName = peer.packageName),
            gms.copy(uid = 1_010_234), gms.copy(firstInstallTime = 0)).forEach {
            assertNull(qualifiedGmsPackageOwnership(it, owners))
        }
        listOf(null, emptyList(), listOf(peer.packageName), owners + peer.packageName,
            owners + "invalid name", listOf(gms.packageName) + (1..64).map { "org.example.member$it" }).forEach {
            assertNull(qualifiedGmsPackageOwnership(gms, it))
        }
    }

    @Test fun mixedUnreadableAndDeniedPeerMetadataRetainsRealServiceReportWithoutClaimingSockets() {
        val cases: List<(Port) -> Unit> = listOf(
            { it.member = peer.copy(signerSha256 = "c".repeat(64)) },
            { it.member = null },
            { it.member = peer.copy(uid = peer.uid + 1) },
            { it.duringMember = { throw SecurityException("member read denied") } },
        )
        cases.forEach { setup ->
            val port = Port().also(setup)
            var proof: GmsPackageOwnership? = null
            val sample = observeGmsConnection(4, port, ownershipObserved = { proof = it }) as GmsConnectionObservation.Sample
            assertEquals(GcmConnectionState.REPORTED_CONNECTED, sample.gcmService.state)
            assertEquals(SourceCompleteness.COMPLETE, sample.gcmService.completeness)
            assertEquals(123_000L, sample.gcmService.connectedDurationMillis)
            assertEquals(SocketObservation.UNKNOWN, sample.observation)
            assertEquals(SocketSource(SourceCompleteness.UNAVAILABLE, false), sample.tcp)
            assertEquals(SocketSource(SourceCompleteness.UNAVAILABLE, false), sample.tcp6)
            assertEquals(0, port.socketReads)
            assertEquals(0, port.memberReads)
            assertTrue(FcmRecoveryPolicy.qualified(sample, 100))
            assertFalse(FcmRecoveryPolicy.disconnected(sample, 100))
            assertNotNull(proof)
            assertEquals(owners.sorted(), proof!!.members)
            val wire = GmsObservationCodec.encode(sample)
            assertFalse(wire.contains(peer.packageName))
            assertEquals(sample, GmsObservationCodec.decode(wire, 4))
        }
    }

    @Test fun packageProofRejectsMembershipOrTargetChangesAndNeverUsesMissingMetadataAsExclusive() {
        val membership = Port().apply { afterOwnerRead = { names = listOf(gms.packageName) } }
        assertNull(readGmsPackageOwnership(membership, gms))
        val target = Port().apply { afterOwnerRead = { currentGms = gms.copy(versionCode = gms.versionCode + 1) } }
        assertNull(readGmsPackageOwnership(target, gms))
        val missing = Port().apply { names = emptyList() }
        assertNull(readGmsPackageOwnership(missing, gms))
        val cancelled = Port()
        assertNull(readGmsPackageOwnership(cancelled, gms) { true })
        assertEquals(0, cancelled.ownerReads)
        assertEquals(0, cancelled.memberReads)
    }

    @Test fun membershipChangeDuringMemberReadAndCancellationCannotCreateTrustedSnapshot() {
        val port = Port().apply { duringMember = { names = listOf(gms.packageName) } }
        assertNull(readGmsUidOwnership(port, gms))
        val targetDrift = Port().apply { duringMember = { currentGms = gms.copy(versionCode = gms.versionCode + 1) } }
        assertNull(readGmsUidOwnership(targetDrift, gms))
        val cancelled = Port()
        assertNull(readGmsUidOwnership(cancelled, gms) { true })
        assertEquals(0, cancelled.ownerReads); assertEquals(0, cancelled.memberReads)
    }
}
