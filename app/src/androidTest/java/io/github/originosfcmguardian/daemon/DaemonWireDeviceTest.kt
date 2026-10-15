package io.github.originosfcmguardian.daemon

import android.os.Bundle
import android.os.Parcel
import androidx.test.filters.SmallTest
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import org.junit.Assert.*
import org.junit.Test

/** Parcel/Bundle fixtures only. No daemon binding, permissions, settings, network or UI writes. */
@SmallTest
class DaemonWireDeviceTest {
    private val record = DaemonControlRecord(DaemonOwnerKey("instance", 1), 2, "a".repeat(64), DaemonControlMode.ACTIVE, "migration")
    private fun parcel(value: Bundle): Bundle {
        val output = Parcel.obtain()
        val input = Parcel.obtain()
        return try {
            output.writeBundle(value)
            val bytes = output.marshall()
            input.unmarshall(bytes, 0, bytes.size)
            input.setDataPosition(0)
            requireNotNull(input.readBundle(null))
        } finally { output.recycle(); input.recycle() }
    }

    @Test fun exactTransitionAndRequestSurvivePrimitiveParcel() {
        val proof = DaemonPointerCommitProof("nonce", record)
        val challenge = DaemonPointerChallenge("nonce", DaemonPointerPurpose.TRANSITION, proof, "b".repeat(64))
        val request = DaemonControlRequest("request", record.owner, 1, DaemonControlIntent.Activate(proof))
        assertEquals(challenge, DaemonWire.challenge(parcel(DaemonWire.challenge(challenge))))
        assertEquals(request, DaemonWire.request(parcel(DaemonWire.request(request))))
    }

    @Test fun statusIncludesAdmissionClosureAndRemoteOriginWithoutAppIdentityClaims() {
        val unknown = GmsConnectionObservation.Unknown(7, GmsUnknown.BUSY)
        val host = DaemonHostStatus(record.owner, record, DaemonOwnerStatus.ACTIVE, 4, false, false, emptyList(),
            100, 1_000, 2_000, unknown, needsReconciliation = true)
        val decoded = DaemonWire.appStatus(parcel(DaemonWire.status(host)))
        assertEquals(record, decoded.record)
        assertEquals(100L, decoded.sessionStartedElapsed)
        assertTrue(decoded.needsReconciliation)
        assertEquals(unknown, decoded.latestGmsObservation)
    }

    @Test fun extraFieldsAndWrongPrimitiveTypesAreRejected() {
        assertRejected { DaemonWire.record(parcel(DaemonWire.record(record).apply { putString("shell", "not-allowed") })) }
        assertRejected { DaemonWire.record(parcel(DaemonWire.record(record).apply { putInt("epoch", 1) })) }
        assertRejected { DaemonWire.chunk(Bundle().apply { putByteArray("bytes", ByteArray(65_537)) }, "bytes") }
    }

    @Test fun displayReadinessEvidenceSurvivesPrimitiveParcelAndMissingFieldIsUnknown() {
        val evidence = DaemonRecoveryReadinessEvidence(record, 8, 200, 210, true)
        val host = DaemonHostStatus(record.owner, record, DaemonOwnerStatus.ACTIVE, 4, false, false,
            emptyList(), recoveryReadiness = evidence)
        assertEquals(evidence, DaemonWire.appStatus(parcel(DaemonWire.status(host))).recoveryReadiness)
        val oldReply = DaemonWire.status(host).apply { remove("recoveryReadiness") }
        assertNull(DaemonWire.appStatus(parcel(oldReply)).recoveryReadiness)
        assertRejected { DaemonWire.appStatus(parcel(DaemonWire.status(host).apply {
            getBundle("recoveryReadiness")!!.putString("admitted", "true")
        })) }
    }

    private fun assertRejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
    }
}
