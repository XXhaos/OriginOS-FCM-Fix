package io.github.originosfcmguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppOpsParserTest {
    @Test
    fun `real no-operations fixture preserves absent record and reported default`() {
        val command = SafeCommand.GetGmsAppOpByPackage(AppOp.RUN_IN_BACKGROUND)
        val state = AppOpsParser.parse(
            AppOpScope.Package(),
            AppOp.RUN_IN_BACKGROUND,
            result(command, fixture("appop-run-in-background.stdout.txt")),
        )

        assertEquals(Presence.ABSENT, state.operationRecordPresence)
        assertEquals(AppOpMode.ALLOW, state.reportedDefault)
        assertEquals(OverrideProvenance.ABSENT, state.overrideProvenance)
        assertEquals(EvidenceStatus.CONFORMING, state.status)
    }

    @Test
    fun `real usage fixture does not establish override provenance`() {
        val command = SafeCommand.GetGmsAppOpByPackage(AppOp.WAKE_LOCK)
        val state = AppOpsParser.parse(
            AppOpScope.Package(),
            AppOp.WAKE_LOCK,
            result(command, fixture("appop-wake-lock.stdout.txt")),
        )

        assertEquals(Presence.PRESENT, state.operationRecordPresence)
        assertEquals(AppOpMode.ALLOW, state.reportedMode)
        assertEquals(OverrideProvenance.UNPROVEN, state.overrideProvenance)
        assertEquals(EvidenceStatus.CONFORMING, state.status)
    }

    @Test
    fun `real uid fixture remains separate from package scope`() {
        val scope = AppOpScope.Uid(10_208)
        val command = SafeCommand.GetAppOpByUid(10_208, AppOp.WAKE_LOCK)
        val state = AppOpsParser.parse(
            scope,
            AppOp.WAKE_LOCK,
            result(command, fixture("gms-uid-wake-lock.stdout.txt")),
        )

        assertEquals(scope, state.scope)
        assertEquals(AppOpMode.ALLOW, state.reportedDefault)
        assertEquals(Presence.ABSENT, state.operationRecordPresence)
    }

    @Test
    fun `synthetic non-allow state is an anomaly but not an executable repair`() {
        val command = SafeCommand.GetGmsAppOpByPackage(AppOp.RUN_ANY_IN_BACKGROUND)
        val state = AppOpsParser.parse(
            AppOpScope.Package(),
            AppOp.RUN_ANY_IN_BACKGROUND,
            result(command, "No operations.\nDefault mode: ignore\n"),
        )

        assertEquals(EvidenceStatus.ANOMALOUS, state.status)
        assertEquals(OverrideProvenance.ABSENT, state.overrideProvenance)
    }

    @Test
    fun `synthetic malformed conflicting failed and truncated output fail closed`() {
        val command = SafeCommand.GetGmsAppOpByPackage(AppOp.RUN_IN_BACKGROUND)
        val outputs = listOf(
            result(command, "gibberish\n"),
            result(command, "No operations.\nDefault mode: allow\nRUN_IN_BACKGROUND: allow\n"),
            result(command, "No operations.\nDefault mode: allow\nDefault mode: ignore\n"),
            result(command, "Error: package lookup failed\n"),
            result(command, "No operations.\nDefault mode: allow\n", exitCode = 1),
            result(command, "No operations.\nDefault mode: allow\n", outputTruncated = true),
        )

        outputs.forEach { output ->
            val state = AppOpsParser.parse(
                AppOpScope.Package(),
                AppOp.RUN_IN_BACKGROUND,
                output,
            )
            assertEquals(EvidenceStatus.UNKNOWN, state.status)
            assertTrue(state.problems.isNotEmpty())
        }
    }
}
