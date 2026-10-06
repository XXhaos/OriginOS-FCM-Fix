package io.github.originosfcmguardian.protection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
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
import org.junit.Assert.assertNull
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
    fun missingIdentityReportsAction() = runBlocking {
        val port = FakePort(healthyReport())
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
        assertEquals(TargetCheckStatus.MISSING, report.targets.single().status)
        assertEquals(ProtectionCheckStatus.NEEDS_USER_ACTION, report.status)
        assertTrue(report.summary.contains("1 项需手动处理"))
    }

    @Test
    fun changedIdentityReportsActionWithoutPermissionClassification() = runBlocking {
        val port = FakePort(healthyReport())
        var probes = 0
        val report = executeCheck(
            10,
            ProtectionCheckReason.APP_START,
            "known",
            "present",
            listOf(identity),
            identityProbe = { probes += 1; identity.copy(firstInstallTime = identity.firstInstallTime + 1) },
            port = port,
        )

        assertEquals(1, probes)
        assertEquals(TargetCheckStatus.IDENTITY_CHANGED, report.targets.single().status)
        assertEquals(ProtectionCheckStatus.NEEDS_USER_ACTION, report.status)
    }

    @Test
    fun matchingIdentityAndHealthyCoreDoNotClaimTargetPermissionAllowed() = runBlocking {
        val port = FakePort(healthyReport())
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
        assertEquals(TargetCheckStatus.IDENTITY_MATCH, report.targets.single().status)
        assertFalse(report.summary.contains("自启动"))
        assertTrue(report.summary.contains("目标应用身份"))
        assertFalse(report.containsRetiredAutostartEvidence)
    }

    @Test
    fun moreThanTenTargetsAreAllCheckedAndReportedWithoutTruncation() = runBlocking {
        val identities = (0 until 12).map { index ->
            identity.copy(
                packageName = "example.target.p$index",
                uid = identity.uid + index,
                firstInstallTime = identity.firstInstallTime + index,
            )
        }
        val byPackage = identities.associateBy(SelectedTargetIdentity::packageName)
        val port = FakePort(healthyReport())

        val report = executeCheck(
            10,
            ProtectionCheckReason.APP_START,
            "known",
            "present",
            identities,
            identityProbe = byPackage::get,
            port = port,
        )

        assertEquals(12, report.targets.size)
        assertEquals(identities.map { it.packageName }, report.targets.map { it.packageName })
        assertTrue(report.targets.all { it.status == TargetCheckStatus.IDENTITY_MATCH })
        assertTrue(report.summary.contains("12 项已检查"))
    }

    @Test
    fun dailyProtectionPersistenceCodecRoundTripsMoreThanTenTargets() {
        val targets = (0 until 12).map { index ->
            TargetCheckResult("example.target.p$index", TargetCheckStatus.IDENTITY_MATCH)
        }

        val encoded = DailyProtectionTargetsCodec.encode(targets)

        assertEquals(targets, DailyProtectionTargetsCodec.decode(encoded))
    }

    @Test
    fun dailyProtectionPersistenceCodecRejectsCorruptOrByteOversizedData() {
        assertNull(DailyProtectionTargetsCodec.decode("example.target=NOT_A_STATUS"))
        assertNull(DailyProtectionTargetsCodec.decode("not a package=ALLOWED"))
        assertNull(
            DailyProtectionTargetsCodec.decode(
                "x".repeat(DailyProtectionTargetsCodec.MAX_BYTES + 1),
            ),
        )
    }

    @Test
    fun legacyAutostartStatusesRemainReadableForHistoricalReports() {
        val legacy = listOf(
            TargetCheckStatus.ALLOWED,
            TargetCheckStatus.NEEDS_USER_ACTION,
            TargetCheckStatus.UNKNOWN,
            TargetCheckStatus.UNAVAILABLE,
        ).mapIndexed { index, status -> TargetCheckResult("example.legacy.p$index", status) }
        assertEquals(legacy, DailyProtectionTargetsCodec.decode(DailyProtectionTargetsCodec.encode(legacy)))
        val historical = DailyProtectionReport(1, ProtectionCheckReason.MANUAL, "known",
            ProtectionCheckStatus.NEEDS_USER_ACTION, "historical", legacy)
        assertTrue(historical.containsRetiredAutostartEvidence)
        assertTrue(historical.copy(targets = emptyList(), summary = "Guardian 自启动状态未知")
            .containsRetiredAutostartEvidence)
        assertFalse(historical.copy(targets = listOf(TargetCheckResult(identity.packageName, TargetCheckStatus.MISSING)))
            .containsRetiredAutostartEvidence)
    }

    @Test
    fun sessionAlwaysClosesAfterFailure() = runBlocking {
        val port = FakePort(healthyReport())
        runCatching {
            runProtectionSession(port) { error("boom") }
        }
        assertTrue(port.closed)
    }

    @Test
    fun sessionAlwaysClosesAfterCancellation() = runBlocking {
        val port = FakePort(healthyReport())
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
    fun unavailableCoreStillChecksTargetIdentityWithoutClaimingPermissionState() = runBlocking {
        val port = FakePort(healthyReport().copy(privilegedAccess = PrivilegedAccessState.UNAVAILABLE))
        val report = executeCheck(
            10,
            ProtectionCheckReason.SHIZUKU_AVAILABLE,
            "known",
            "present",
            listOf(identity),
            identityProbe = { identity },
            port = port,
        )

        assertEquals(TargetCheckStatus.IDENTITY_MATCH, report.targets.single().status)
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
            port = FakePort(healthyReport().copy(appOps = emptyMap())),
        )

        assertEquals(ProtectionCheckStatus.UNKNOWN, report.status)
    }

    private class FakePort(
        private val report: DetectionReport,
    ) : ProtectionReadPort {
        var coreReads = 0
        var closed = false

        override suspend fun detectCore(): DetectionReport {
            coreReads += 1
            return report
        }

        override fun close() {
            closed = true
        }
    }

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
