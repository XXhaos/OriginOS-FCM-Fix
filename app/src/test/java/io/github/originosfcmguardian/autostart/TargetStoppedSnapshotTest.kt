package io.github.originosfcmguardian.autostart

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TargetStoppedSnapshotTest {
    private val identity = SelectedTargetIdentity("example.target", 10434, 1000, listOf("a".repeat(64)))
    private fun sample(stopped: Boolean, target: SelectedTargetIdentity = identity) = TargetPackageSnapshot(target, stopped)

    @Test fun stoppedIsSeparateFromAutostartAndHasConditionalManualGuidance() {
        val result = readTargetStoppedSnapshot(identity, { sample(true) }, { 1234 })
        assertEquals(TargetStoppedStatus.STOPPED, result.status)
        assertEquals(1234L, result.checkedAtEpochMillis)
        assertTrue(result.detail.contains("若希望恢复接收"))
        assertTrue(result.detail.contains("若是主动停止"))
        val target = SelectedTargetUiState(identity, "test", TargetAutostartStatus.ALLOWED)
        assertEquals(TargetAutostartStatus.ALLOWED, listOf(target).withStoppedSnapshot(identity, result).single().status)
    }

    @Test fun absentFlagDoesNotClaimLiveProcessOrDelivery() {
        val result = readTargetStoppedSnapshot(identity, { sample(false) })
        assertEquals(TargetStoppedStatus.NOT_STOPPED, result.status)
        assertTrue(result.detail.contains("读取时未设置"))
        assertTrue(result.detail.contains("不代表"))
    }

    @Test fun secondSnapshotDeterminesValue() {
        var reads = 0
        val result = readTargetStoppedSnapshot(identity, { sample(reads++ == 0) })
        assertEquals(2, reads)
        assertEquals(TargetStoppedStatus.NOT_STOPPED, result.status)
    }

    @Test fun eitherMissingSnapshotIsUnknown() {
        for (missingIndex in 0..1) {
            var reads = 0
            assertEquals(TargetStoppedStatus.UNKNOWN, readTargetStoppedSnapshot(identity, {
                if (reads++ == missingIndex) null else sample(false)
            }).status)
        }
    }

    @Test fun eitherReadFailureIsUnknownWithoutPrivateDetails() {
        for (failureIndex in 0..1) {
            var reads = 0
            val result = readTargetStoppedSnapshot(identity, {
                if (reads++ == failureIndex) error("private data") else sample(false)
            })
            assertEquals(TargetStoppedStatus.UNKNOWN, result.status)
            assertFalse(result.detail.contains("private data"))
        }
    }

    @Test fun changedInstallationUidSignerOrPackageNeverInheritsFlag() {
        val variants = listOf(identity.copy(firstInstallTime = 2000), identity.copy(uid = 10435),
            identity.copy(signerSha256 = listOf("b".repeat(64))), identity.copy(packageName = "example.other"))
        for (changed in variants) for (changedIndex in 0..1) {
            var reads = 0
            val result = readTargetStoppedSnapshot(identity, {
                sample(false, if (reads++ == changedIndex) changed else identity)
            })
            assertEquals(TargetStoppedStatus.IDENTITY_CHANGED, result.status)
        }
    }

    @Test fun lateResultDoesNotAttachToRemovedOrReplacedSelection() {
        val snapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 1234)
        assertTrue(emptyList<SelectedTargetUiState>().withStoppedSnapshot(identity, snapshot).isEmpty())
        val replacement = SelectedTargetUiState(identity.copy(firstInstallTime = 2000), "test")
        assertEquals(TargetStoppedStatus.NOT_CHECKED,
            listOf(replacement).withStoppedSnapshot(identity, snapshot).single().stoppedSnapshot.status)
    }

    @Test fun newAttemptClearsPreviousSnapshot() {
        val target = SelectedTargetUiState(identity, "test", stoppedSnapshot = TargetStoppedSnapshot(TargetStoppedStatus.STOPPED, 1234))
        val reset = listOf(target).withStoppedSnapshot(identity, TargetStoppedSnapshot()).single()
        assertEquals(TargetStoppedStatus.NOT_CHECKED, reset.stoppedSnapshot.status)
        assertNull(reset.stoppedSnapshot.checkedAtEpochMillis)
    }

    @Test fun cancellationIsNotAnUnknownResult() {
        assertThrows(CancellationException::class.java) {
            readTargetStoppedSnapshot(identity, { throw CancellationException("cancel") })
        }
    }

    @Test fun verifiedRotationUsesSecondFlagAndRequiresFreshHistoryOnBothReads() {
        val rotated = identity.copy(signerSha256 = listOf("b".repeat(64)))
        val signing = TargetSigningEvidence(false, identity.signerSha256 + rotated.signerSha256)
        var reads = 0
        assertEquals(TargetStoppedStatus.NOT_STOPPED, readTargetStoppedSnapshot(identity, {
            TargetPackageSnapshot(rotated, reads++ == 0, signing)
        }).status)
        for (missingIndex in 0..1) {
            reads = 0
            assertEquals(TargetStoppedStatus.IDENTITY_CHANGED, readTargetStoppedSnapshot(identity, {
                TargetPackageSnapshot(rotated, true, if (reads++ == missingIndex) null else signing)
            }).status)
        }
    }

    @Test fun individuallyValidLineagesCannotHideRawSignerRace() {
        val middle = identity.copy(signerSha256 = listOf("b".repeat(64)))
        val latest = identity.copy(signerSha256 = listOf("c".repeat(64)))
        for ((first, second) in listOf(identity to middle, middle to latest)) {
            var reads = 0
            assertEquals(TargetStoppedStatus.IDENTITY_CHANGED, readTargetStoppedSnapshot(identity, {
                val raw = if (reads++ == 0) first else second
                TargetPackageSnapshot(raw, false, TargetSigningEvidence(false,
                    (identity.signerSha256 + raw.signerSha256).distinct()))
            }).status)
        }
    }
}
