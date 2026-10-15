package io.github.originosfcmguardian.daemon

import java.security.MessageDigest
import io.github.originosfcmguardian.core.gms.GmsHeartbeatCallerIdentity
import org.junit.Assert.*
import org.junit.Test

class DaemonControlProposalTest {
    private val before = RootFixtures.snapshot().control
    private fun plan(patch: Map<DaemonDomainRole, ByteArray?> = emptyMap()) = DaemonControlTransitionPlan(
        "exact-request-96fbde55-15e2-45b6-8bea-0de37312dd94", before,
        before.copy(revision = 2, mode = DaemonControlMode.ACTIVE), DaemonControlTransition.SET_AUTHORITY, patch)

    @Test fun roundtripRetainsExactRecordsRequestAndEveryPatchByteIncludingExplicitAbsence() {
        val original = plan(mapOf(DaemonDomainRole.AUTHORITY to "exact-consent-470130f6-6518-44fb-8ab6-c76daee78ffb".toByteArray(),
            DaemonDomainRole.SELECTION to null, DaemonDomainRole.FCM_RECOVERY to byteArrayOf(),
            DaemonDomainRole.HEARTBEAT_HALT to byteArrayOf(1)))
        val bytes = DaemonControlProposalCodec.encode(original)
        val restored = DaemonControlProposalCodec.decode(bytes)
        assertEquals(original.requestId, restored.requestId)
        assertEquals(original.expected, restored.expected)
        assertEquals(original.next, restored.next)
        assertEquals(original.transition, restored.transition)
        assertEquals(original.patchDigest, restored.patchDigest)
        assertEquals(original.patch().keys, restored.patch().keys)
        for ((role, content) in original.patch()) assertArrayEquals(content, restored.patch()[role])
        assertTrue(restored.patch().containsKey(DaemonDomainRole.SELECTION))
        assertNull(restored.patch()[DaemonDomainRole.SELECTION])
        assertArrayEquals(bytes, DaemonControlProposalCodec.encode(restored))
        restored.patch()[DaemonDomainRole.AUTHORITY]!![0] = 0
        assertArrayEquals(bytes, DaemonControlProposalCodec.encode(restored))
    }

    @Test fun activationRecordIsRetainedWithoutConstructingNewAuthority() {
        val prepared = before.copy(mode = DaemonControlMode.PREPARED)
        val original = DaemonControlTransitionPlan("activate-exact", prepared,
            prepared.copy(revision = 2, mode = DaemonControlMode.ACTIVE), DaemonControlTransition.ACTIVATE)
        val restored = DaemonControlProposalCodec.decode(DaemonControlProposalCodec.encode(original))
        assertEquals(prepared, restored.expected)
        assertEquals(original.next, restored.next)
        assertEquals(DaemonControlTransition.ACTIVATE, restored.transition)
        assertTrue(restored.patch().isEmpty())
    }

    @Test fun nativePermitIsSeparateImmutableAndCoveredByProposalAndPointerDigests() {
        val plain = plan()
        val native = DaemonHeartbeatDerivationPermitCodec.encode(DaemonHeartbeatDerivationPermit(plain.next,
            "12345678-1234-1234-1234-123456789abc", "a".repeat(64), "b".repeat(64), "c".repeat(64),
            GmsHeartbeatCallerIdentity(10436, 123, 100, "01234567-89ab-cdef-0123-456789abcdef")))
        val proposed = DaemonControlTransitionPlan(plain.requestId, plain.expected, plain.next, plain.transition,
            plain.patch(), native)
        val bytes = DaemonControlProposalCodec.encode(proposed)
        assertNotEquals(plain.patchDigest, proposed.patchDigest)
        assertTrue(proposed.patch().isEmpty())
        val decoded = DaemonControlProposalCodec.decode(bytes)
        assertArrayEquals(native, decoded.nativeHeartbeatDerivation())
        assertEquals(proposed.patchDigest, decoded.patchDigest)
        native[0] = 0
        decoded.nativeHeartbeatDerivation()!![0] = 0
        assertArrayEquals(bytes, DaemonControlProposalCodec.encode(proposed))
        assertArrayEquals(bytes, DaemonControlProposalCodec.encode(decoded))
        RootFixtures.fails { plan(mapOf(DaemonDomainRole.HEARTBEAT_DERIVATION to native)) }
        RootFixtures.fails { plan(mapOf(DaemonDomainRole.HEARTBEAT_DAEMON_OWNER to null)) }
    }

    @Test fun corruptionTruncationOversizeAndIncorrectPatchDigestCannotDecode() {
        val original = plan(mapOf(DaemonDomainRole.SELECTION to byteArrayOf(1, 2, 3)))
        val bytes = DaemonControlProposalCodec.encode(original)
        val corrupt = bytes.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        RootFixtures.fails { DaemonControlProposalCodec.decode(corrupt) }
        RootFixtures.fails { DaemonControlProposalCodec.decode(bytes.copyOf(bytes.size - 1)) }
        RootFixtures.fails { DaemonControlProposalCodec.decode(ByteArray(DaemonControlProposalCodec.MAX_BYTES + 1)) }
        val body = bytes.copyOf(bytes.size - 32)
        val digest = original.patchDigest.toByteArray()
        val offset = (0..body.size - digest.size).first { start ->
            digest.indices.all { body[start + it] == digest[it] }
        }
        body[offset] = if (body[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        val wrongPatchDigest = body + MessageDigest.getInstance("SHA-256").digest(body)
        RootFixtures.fails { DaemonControlProposalCodec.decode(wrongPatchDigest) }
    }

    @Test fun maximumAllowedConfigurationFitsTheProposalBoundAndCannotRecursivelyContainAProposal() {
        val roles = listOf(DaemonDomainRole.AUTHORITY, DaemonDomainRole.SELECTION, DaemonDomainRole.FCM_PROTECTION,
            DaemonDomainRole.FCM_RECOVERY, DaemonDomainRole.HEARTBEAT, DaemonDomainRole.FCM_PROTECTION_HALT,
            DaemonDomainRole.FCM_RECOVERY_HALT, DaemonDomainRole.HEARTBEAT_HALT)
        val bytes = DaemonControlProposalCodec.encode(plan(roles.associateWith { ByteArray(it.maximumBytes) }))
        assertTrue(bytes.size <= DaemonControlProposalCodec.MAX_BYTES)
        assertArrayEquals(bytes, DaemonControlProposalCodec.encode(DaemonControlProposalCodec.decode(bytes)))
        RootFixtures.fails { plan(mapOf(DaemonDomainRole.CONTROL_PROPOSAL to byteArrayOf(1))) }
    }
}
