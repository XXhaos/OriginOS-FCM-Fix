package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autoprotection.*
import io.github.originosfcmguardian.ui.HomeUiState
import org.junit.Assert.*
import org.junit.Test

class ProtectionLifecycleReportTest {
    @Test fun `both formats contain lifecycle and explicit uncertainty within existing limit`() {
        val events = List(64) { index -> ProtectionLifecycleEvent(1000L + index, 50L + index, 123,
            ProtectionLifecycleEventKind.RUNTIME_INTERRUPTED, ProtectionLifecycleReason.IO_ERROR) }
        val base = buildCombinedDiagnosticReport(HomeUiState(), DiagnosticEnvironment(37, "17", "vivo", "test", "trial", 63), 2000)
        val report = base.copy(protectionLifecycle = ProtectionLifecycleSnapshot(
            ProtectionLifecycleAvailability.AVAILABLE, events, droppedEvents = 2))
        val json = encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8)
        val markdown = encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8)
        assertTrue(json.contains("\"protectionLifecycle\":{\"availability\":\"AVAILABLE\""))
        assertTrue(json.contains("\"droppedEvents\":2"))
        assertTrue(json.contains("not proof of an OS kill"))
        assertTrue(markdown.contains("保护停止与进程记录"))
        assertTrue(markdown.contains("RUNTIME_INTERRUPTED / IO_ERROR"))
        assertTrue(json.toByteArray().size < MAX_REPORT_BYTES)
        assertTrue(markdown.toByteArray().size < MAX_REPORT_BYTES)
        assertFalse(json.contains("exceptionMessage"))
    }

    @Test fun `missing journal is not presented as empty healthy history`() {
        val base = buildCombinedDiagnosticReport(HomeUiState(), DiagnosticEnvironment(37, "17", "vivo", "test", "trial", 63), 2000)
        for (availability in listOf(ProtectionLifecycleAvailability.CORRUPT, ProtectionLifecycleAvailability.UNAVAILABLE)) {
            val report = base.copy(protectionLifecycle = ProtectionLifecycleSnapshot(availability))
            assertTrue(encodeCombinedDiagnosticReport(report).toString(Charsets.UTF_8).contains("\"availability\":\"${availability.name}\""))
            assertTrue(encodeCombinedDiagnosticMarkdown(report).toString(Charsets.UTF_8).contains(availability.name))
        }
    }
}
