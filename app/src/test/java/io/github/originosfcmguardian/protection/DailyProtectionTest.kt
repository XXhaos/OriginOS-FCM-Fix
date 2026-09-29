package io.github.originosfcmguardian.protection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.AutostartReadStatus
import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpCheck
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.AppOpScope
import io.github.originosfcmguardian.core.AppOpState
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.DozeState
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.GmsPackageState
import io.github.originosfcmguardian.core.GuardianAutostartRead
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.PemState
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.OverrideProvenance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyProtectionTest {
    private val identity = SelectedTargetIdentity(
        packageName = "example.target",
        uid = 12_345,
        firstInstallTime = 100,
        signerSha256 = listOf("a".repeat(64)),
    )

    @Test
    fun buildSemanticGateAcceptsOnlyExactVerifiedFingerprint() {
        assertTrue(
            isVerifiedProtectionBuild(
                "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys",
            ),
        )
        assertFalse(isVerifiedProtectionBuild("vivo/PD2609/PD2609:18/new:user/release-keys"))
        assertFalse(isVerifiedProtectionBuild(""))
    }

    @Test
    fun eventDecisionDeduplicatesUnchangedStampsAndOrdersBuildFirst() {
        val old = ProtectionEventStamps("old", 1, 2, false)

        assertTrue(decideEvents(old, old).isEmpty())
        assertEquals(
            listOf(
                ProtectionCheckReason.BUILD_CHANGED,
                ProtectionCheckReason.GUARDIAN_VERSION_CHANGED,
                ProtectionCheckReason.GMS_VERSION_CHANGED,
            ),
            decideEvents(old, ProtectionEventStamps("new", 2, 3, false)),
        )
        assertTrue(isShizukuAvailableTransition(false, true))
        assertTrue(isShizukuAvailableTransition(null, true))
        assertFalse(isShizukuAvailableTransition(true, true))
    }

    @Test
    fun missingIdentitySkipsPrivilegedTargetReadAndReportsAction() = runBlocking {
        val port = FakePort(healthyReport(), allowedRead())
        val report = executeCheck(
            now = 10,
            reason = ProtectionCheckReason.DAILY_FALLBACK,
            fingerprint = "known",
            normalGmsSummary = "present",
            selected = listOf(identity),
            identityProbe = { null },
            port = port,
        )

        assertEquals(1, port.coreReads)
        assertEquals(0, port.targetReads)
        assertEquals(TargetCheckStatus.MISSING, report.targets.single().status)
        assertEquals(ProtectionCheckStatus.NEEDS_USER_ACTION, report.status)
        assertTrue(report.summary.contains("1 项需手动处理"))
    }

    @Test
    fun targetActionStatusUsesFreshIdentityBeforeAndAfterRead() = runBlocking {
        val port = FakePort(
            healthyReport(),
            GuardianAutostartRead(
                status = AutostartReadStatus.READ_ONLY_VALUE_PRESENT,
                packageUid = identity.uid,
                currentState = 1,
                setByUser = 1,
            ),
        )
        var probes = 0
        val report = executeCheck(
            10,
            ProtectionCheckReason.APP_START,
            "known",
            "present",
            listOf(identity),
            identityProbe = { probes += 1; identity },
            port = port,
        )

        assertEquals(2, probes)
        assertEquals(1, port.targetReads)
        assertEquals(TargetCheckStatus.NEEDS_USER_ACTION, report.targets.single().status)
        assertEquals(ProtectionCheckStatus.NEEDS_USER_ACTION, report.status)
    }

    @Test
    fun matchingAllowedTargetAndHealthyCoreProduceReadOnlyHealthyStatus() = runBlocking {
        val port = FakePort(healthyReport(), allowedRead())
        val report = executeCheck(
            10,
            ProtectionCheckReason.APP_START,
            "known",
            "present",
            listOf(identity),
            identityProbe = { identity },
            port = port,
        )

        assertEquals(ProtectionCheckStatus.HEALTHY_READ_ONLY, report.status)
        assertEquals(TargetCheckStatus.ALLOWED, report.targets.single().status)
        assertEquals(1, port.targetReads)
    }

    @Test
    fun sessionAlwaysClosesAfterFailure() = runBlocking {
        val port = FakePort(healthyReport(), allowedRead())
        runCatching {
            runProtectionSession(port) { error("boom") }
        }
        assertTrue(port.closed)
    }

    @Test
    fun sessionAlwaysClosesAfterCancellation() = runBlocking {
        val port = FakePort(healthyReport(), allowedRead())
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            runProtectionSession(port) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(port.closed)
    }

    @Test
    fun unavailableCoreDoesNotAttemptTargetReadOrMutation() = runBlocking {
        val port = FakePort(healthyReport().copy(privilegedAccess = PrivilegedAccessState.UNAVAILABLE), allowedRead())
        val report = executeCheck(
            10,
            ProtectionCheckReason.SHIZUKU_AVAILABLE,
            "known",
            "present",
            listOf(identity),
            identityProbe = { identity },
            port = port,
        )

        assertEquals(0, port.targetReads)
        assertEquals(TargetCheckStatus.UNAVAILABLE, report.targets.single().status)
        assertEquals(ProtectionCheckStatus.UNKNOWN, report.status)
        assertTrue(report.summary.contains("Shizuku 只读检测不可用"))
    }

    @Test
    fun incompleteCoreCannotProduceHealthyStatus() = runBlocking {
        val report = executeCheck(
            10,
            ProtectionCheckReason.DAILY_FALLBACK,
            "known",
            "present",
            emptyList(),
            identityProbe = { null },
            port = FakePort(healthyReport().copy(appOps = emptyMap()), allowedRead()),
        )

        assertEquals(ProtectionCheckStatus.UNKNOWN, report.status)
    }

    private class FakePort(
        private val report: DetectionReport,
        private val read: GuardianAutostartRead,
    ) : ProtectionReadPort {
        var coreReads = 0
        var targetReads = 0
        var closed = false

        override suspend fun detectCore(): DetectionReport {
            coreReads += 1
            return report
        }

        override suspend fun readTarget(identity: SelectedTargetIdentity): GuardianAutostartRead {
            targetReads += 1
            return read
        }

        override fun close() {
            closed = true
        }
    }

    private fun allowedRead() = GuardianAutostartRead(
        status = AutostartReadStatus.READ_ONLY_VALUE_PRESENT,
        packageUid = identity.uid,
        currentState = 0,
        setByUser = 1,
    )

    private fun healthyReport() = DetectionReport(
        privilegedAccess = PrivilegedAccessState.READY_SHELL,
        currentUser = 0,
        buildFingerprint = "known",
        gms = GmsPackageState(
            presence = Presence.PRESENT,
            enabled = EnabledState.ENABLED,
            appId = 12_208,
            rawInstalledOutput = "",
            rawEnabledOutput = "",
        ),
        appOps = AppOp.entries.associateWith(::conformingAppOp),
        doze = DozeState(emptySet(), "", EvidenceStatus.CONFORMING),
        pem = PemState(Presence.PRESENT, PemListState.NULL_OR_MISSING_AMBIGUOUS, emptySet(), "", ""),
    )

    private fun conformingAppOp(operation: AppOp): AppOpCheck {
        val packageState = AppOpState(
            scope = AppOpScope.Package(),
            operation = operation,
            operationRecordPresence = Presence.PRESENT,
            reportedMode = AppOpMode.ALLOW,
            reportedDefault = null,
            overrideProvenance = OverrideProvenance.UNPROVEN,
            rawOutput = "",
        )
        val uidState = AppOpState(
            scope = AppOpScope.Uid(12_208),
            operation = operation,
            operationRecordPresence = Presence.PRESENT,
            reportedMode = AppOpMode.ALLOW,
            reportedDefault = null,
            overrideProvenance = OverrideProvenance.UNPROVEN,
            rawOutput = "",
        )
        return AppOpCheck(operation, packageState, uidState, EvidenceStatus.CONFORMING)
    }
}
