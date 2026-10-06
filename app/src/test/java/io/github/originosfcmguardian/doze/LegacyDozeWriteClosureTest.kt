package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import io.github.originosfcmguardian.core.doze.DozeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyDozeWriteClosureTest {
    @Test fun legacyWriteOutcomeIsExplicitlyBlockedWithoutATransactionPhase() {
        val outcome = legacyDozeWriteRejection()
        assertEquals(DozeOutcomeCode.BLOCKED, outcome.code)
        assertNull(outcome.phase)
        assertTrue(outcome.detail.contains("Legacy Doze writes are disabled"))
        assertTrue(outcome.detail.length <= 192)
    }

    @Test fun bothServerWriteEntriesOnlySerializeTheFixedRejection() {
        // Binder/Bundle in android.jar are host stubs. Guard the real entry wiring separately from
        // the pure outcome and device-port runtime tests; this is not a device IPC execution test.
        val source = serviceSource().readText()
        for (entry in listOf("apply", "restore")) {
            val signature = "override fun $entry(journal: IDozeJournalCallback?): Bundle = "
            val declaration = source.lineSequence().single { it.trimStart().startsWith(signature) }.trim()
            assertEquals(signature + "legacyDozeWriteRejection().asBundle()", declaration)
        }
        assertFalse(source.contains("engine.apply("))
        assertFalse(source.contains("engine.restore("))
        assertTrue(source.contains("engine.inspect().asBundle()"))
    }

    @Test fun addAndRemoveRejectBothFixedTargetsBeforeAnyRunnerCall() {
        for (target in listOf(DozeTarget.GMS, DozeTarget.OWNED_DEBUG_TEST)) {
            var admissionCalls = 0
            var budgetCalls = 0
            var processCalls = 0
            val runner = DozeCommandRunner(
                mayContinue = { admissionCalls++; false },
                remainingMillis = { budgetCalls++; 0L },
                onProcess = { processCalls++ },
            )
            val port = FixedDozeDevicePort(runner, target)
            repeat(2) {
                assertThrows(UnsupportedOperationException::class.java) { port.add() }
                assertThrows(UnsupportedOperationException::class.java) { port.remove() }
            }
            assertEquals(0, admissionCalls)
            assertEquals(0, budgetCalls)
            assertEquals(0, processCalls)
        }
    }

    @Test fun readOnlyProbeStillUsesTheRunnerAdmissionGate() {
        var admissionCalls = 0
        val runner = DozeCommandRunner(
            mayContinue = { admissionCalls++; false },
            remainingMillis = { error("Inactive probe must stop before budget or command dispatch") },
            onProcess = { error("Inactive probe must not launch a process") },
        )
        val port = FixedDozeDevicePort(runner, DozeTarget.GMS)
        assertThrows(IllegalStateException::class.java) { port.probe() }
        assertEquals(1, admissionCalls)
    }

    private fun serviceSource(): File = listOf(
        File("src/main/java/io/github/originosfcmguardian/doze/GmsDozeUserService.kt"),
        File("app/src/main/java/io/github/originosfcmguardian/doze/GmsDozeUserService.kt"),
    ).single { it.isFile }
}
