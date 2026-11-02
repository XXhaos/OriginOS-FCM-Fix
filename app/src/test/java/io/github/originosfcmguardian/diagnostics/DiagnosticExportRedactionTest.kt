package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autoprotection.ProtectionResourceAvailability
import io.github.originosfcmguardian.autoprotection.ProtectionResourceExit
import io.github.originosfcmguardian.autoprotection.ProtectionResourceSample
import io.github.originosfcmguardian.autoprotection.ProtectionResourceSnapshot
import io.github.originosfcmguardian.core.gms.FcmRecoveryOperation
import io.github.originosfcmguardian.core.gms.FcmRecoveryReason
import io.github.originosfcmguardian.core.gms.FcmRecoveryStatus
import io.github.originosfcmguardian.core.gms.GcmConnectionState
import io.github.originosfcmguardian.fcmrecovery.FcmPowerReference
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEvent
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryEventAction
import io.github.originosfcmguardian.fcmrecovery.FcmRecoveryUiState
import io.github.originosfcmguardian.recovery.StoppedRecoveryOutcome
import io.github.originosfcmguardian.recovery.StoppedRecoverySource
import io.github.originosfcmguardian.recovery.StoppedRecoveryStage
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportRedactionTest {
    @Test fun appReferencesReplaceEveryTargetAndLatestRecordWithoutChangingTheOriginal() {
        val original = report().copy(
            targets = listOf(DiagnosticTarget("org.private.bank", "Personal Bank\nAccount"),
                DiagnosticTarget("org.private.chat", "Private Chat")),
            stoppedRecovery = DiagnosticStoppedRecovery(loaded = true, latestRecord = record("org.private.chat")),
        )
        val redacted = sanitizeDiagnosticReportForExport(original)
        assertEquals(listOf("app-1", "app-2"), redacted.targets!!.map { it.packageName })
        assertEquals(redacted.targets!!.map { it.packageName }, redacted.targets!!.map { it.currentLabel })
        assertEquals("app-2", redacted.stoppedRecovery.latestRecord!!.packageName)
        assertEquals(original.stoppedRecovery.latestRecord!!.copy(packageName = "app-2"),
            redacted.stoppedRecovery.latestRecord)
        assertEquals("org.private.bank", original.targets!!.first().packageName)
        assertEquals("Private Chat", original.targets!!.last().currentLabel)
        assertEquals("org.private.chat", original.stoppedRecovery.latestRecord!!.packageName)
        val json = encodeCombinedDiagnosticReport(redacted).decodeToString()
        listOf("org.private.bank", "org.private.chat", "Personal Bank", "Private Chat")
            .forEach { assertFalse(it, json.contains(it)) }
        assertTrue(json.contains("\"packageName\":\"app-2\""))
        assertTrue(json.contains("\"outcome\":\"FLAG_CLEARED\""))
    }

    @Test fun recoveryForAnUnselectedAppIsStillRedactedAndUnloadedSelectionRemainsUnknown() {
        val original = report().copy(targets = null,
            stoppedRecovery = DiagnosticStoppedRecovery(latestRecord = record("org.private.removed")))
        val redacted = sanitizeDiagnosticReportForExport(original)
        assertNull(redacted.targets)
        assertEquals("app-1", redacted.stoppedRecovery.latestRecord!!.packageName)
        assertFalse(encodeCombinedDiagnosticReport(redacted).decodeToString().contains("org.private.removed"))
        assertEquals(original.sourceAvailability, redacted.sourceAvailability)
    }

    @Test fun repeatedAppReferencesAreConsistentIndependentOfListOrder() {
        val targets = listOf(DiagnosticTarget("org.example.z", "Z"), DiagnosticTarget("org.example.a", "A"))
        val first = sanitizeDiagnosticReportForExport(report().copy(targets = targets))
        val second = sanitizeDiagnosticReportForExport(report().copy(targets = targets.reversed()))
        assertEquals(listOf("app-2", "app-1"), first.targets!!.map { it.packageName })
        assertEquals(first.targets!!.map { it.packageName }, second.targets!!.reversed().map { it.packageName })
    }

    @Test fun processReferencesStayCorrelatedWithoutRawUuidsOrChangedCpuEvidence() {
        val first = sample(PROCESS)
        val second = first.copy(atMillis = 3000, elapsedMillis = 2100, cpuTimeMillis = 80)
        val exit = ProtectionResourceExit(123, 3100, 3, 0, 200, 2048, 1024)
        val event = FcmRecoveryEvent(3100, 2200, FcmRecoveryEventAction.REQUEST_RESULT,
            service = GcmConnectionState.REPORTED_DISCONNECTED, status = FcmRecoveryStatus.REQUESTED,
            reason = FcmRecoveryReason.COMMAND_ACCEPTED, operation = FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS,
            power = FcmPowerReference(mainProcessCpuMillis = 80, processAgeMillis = 2200, processInstanceId = PROCESS))
        val original = report().copy(
            protectionResources = ProtectionResourceSnapshot(ProtectionResourceAvailability.AVAILABLE,
                listOf(first, second), listOf(exit)),
            fcmRecoveryTrial = FcmRecoveryUiState(configured = true, events = listOf(event)),
        )
        val redacted = sanitizeDiagnosticReportForExport(original)
        val reference = redacted.protectionResources.samples.first().processInstance
        assertNotEquals(PROCESS, reference)
        assertEquals(reference, redacted.protectionResources.samples.last().processInstance)
        assertEquals(reference, redacted.fcmRecoveryTrial.events.single().power!!.processInstanceId)
        assertEquals(first.copy(processInstance = reference), redacted.protectionResources.samples.first())
        val originalAnalysis = analyzeResourceExit(original.protectionResources, exit)
        val redactedAnalysis = analyzeResourceExit(redacted.protectionResources, exit)
        assertEquals(originalAnalysis.copy(nearestSample = originalAnalysis.nearestSample?.copy(processInstance = reference)),
            redactedAnalysis)
        val json = encodeCombinedDiagnosticReport(redacted).decodeToString()
        assertFalse(json.contains(PROCESS))
        assertTrue(json.contains("\"requestOperation\":\"PRESENT_DISCONNECTED_MCS\""))
        assertTrue(json.contains("\"requestStatus\":\"REQUESTED\""))
        assertTrue(json.contains("\"guardianMainProcessCpuMillis\":80"))
        assertEquals(PROCESS, original.protectionResources.samples.first().processInstance)
    }

    @Test fun emptyAndNullableEvidenceIsNotInventedAndCompatibilityMetadataIsPreserved() {
        val original = report().copy(targets = emptyList(),
            fcmRecoveryTrial = FcmRecoveryUiState(events = listOf(FcmRecoveryEvent(1000, 0,
                FcmRecoveryEventAction.OBSERVATION, power = FcmPowerReference()))))
        val redacted = sanitizeDiagnosticReportForExport(original)
        assertEquals(original.copy(exportRedacted = true), redacted)
        assertEquals(original.environment, redacted.environment)
        assertEquals(original.fcmProtection, redacted.fcmProtection)
        assertEquals(original.automaticProtection, redacted.automaticProtection)
        assertTrue(encodeCombinedDiagnosticReport(redacted).size <= MAX_REPORT_BYTES)
    }

    @Test fun redactionIsDeclaredAndApplyingItAgainDoesNotChangeReferencesOrBytes() {
        val original = report().copy(targets = (1..12).map {
            DiagnosticTarget("org.private.app$it", "Private $it")
        }, protectionResources = ProtectionResourceSnapshot(ProtectionResourceAvailability.AVAILABLE,
            listOf(sample(PROCESS)), emptyList()))
        val redacted = sanitizeDiagnosticReportForExport(original)
        val bytes = encodeDiagnosticReport(redacted)
        assertTrue(bytes.decodeToString().contains("\"appReferences\":\"REPORT_LOCAL\""))
        assertTrue(bytes.decodeToString().contains("\"processIdentifiers\":\"ONE_WAY_REFERENCE\""))
        assertArrayEquals(bytes, encodeDiagnosticReport(sanitizeDiagnosticReportForExport(redacted)))
        assertFalse(encodeDiagnosticReport(original).decodeToString().contains("\"redaction\""))
        assertFalse(original.exportRedacted)
    }

    private fun report() = CombinedDiagnosticReport(4000,
        DiagnosticEnvironment(37, "17", "vivo", "fixture", "1.0.8", 108), "READY_SHELL", null,
        mapOf("targetSelection" to DiagnosticSourceAvailability.LOADING))

    private fun record(packageName: String) = DiagnosticRecoveryRecord(packageName, 2000,
        StoppedRecoverySource.MANUAL, StoppedRecoveryStage.FINISHED, StoppedRecoveryOutcome.FLAG_CLEARED)

    private fun sample(process: String) = ProtectionResourceSample(process, 123, 1000, 100,
        2000, 1100, cpuTimeMillis = 20, rssKiB = 1024)

    private companion object { const val PROCESS = "12345678-1234-1234-1234-123456789abc" }
}
