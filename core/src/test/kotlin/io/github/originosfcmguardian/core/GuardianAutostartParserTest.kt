package io.github.originosfcmguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuardianAutostartParserTest {
    private val uid = 10_362
    private val valid =
        "Row: 0 _id=228, pkgname=$GUARDIAN_PACKAGE_NAME, pkguid=$uid, currentstate=1, setbyuser=0\n"

    @Test
    fun `accepts one exact guardian row without interpreting numeric semantics`() {
        val parsed = GuardianAutostartParser.parse(result(valid), uid)

        assertEquals(AutostartReadStatus.READ_ONLY_VALUE_PRESENT, parsed.status)
        assertEquals(228, parsed.rowId)
        assertEquals(uid, parsed.packageUid)
        assertEquals(1, parsed.currentState)
        assertEquals(0, parsed.setByUser)
        assertEquals(AutostartUnknownReason.NONE, parsed.unknownReason)
    }

    @Test
    fun `accepts exact fields in provider supplied order`() {
        val output =
            "Row: 0 _id=228, currentstate=1, pkguid=$uid, pkgname=$GUARDIAN_PACKAGE_NAME, setbyuser=0\n"
        assertEquals(
            AutostartReadStatus.READ_ONLY_VALUE_PRESENT,
            GuardianAutostartParser.parse(result(output), uid).status,
        )
    }

    @Test
    fun `no row multiple rows malformed fields and unexpected values stay unknown`() {
        val cases = listOf(
            "" to AutostartUnknownReason.NO_UNIQUE_ROW,
            "$valid$valid" to AutostartUnknownReason.NO_UNIQUE_ROW,
            "No result found.\n" to AutostartUnknownReason.NO_UNIQUE_ROW,
            valid.replace("setbyuser=0", "extra=0") to AutostartUnknownReason.MALFORMED_ROW,
            valid.replace("currentstate=1", "currentstate=2") to AutostartUnknownReason.FIELD_OUT_OF_RANGE,
            valid.replace("pkguid=$uid", "pkguid=10363") to AutostartUnknownReason.UID_MISMATCH,
            valid.replace(GUARDIAN_PACKAGE_NAME, "example.other") to AutostartUnknownReason.PACKAGE_MISMATCH,
        )

        cases.forEach { (output, reason) ->
            val parsed = GuardianAutostartParser.parse(result(output), uid)
            assertEquals(AutostartReadStatus.UNKNOWN, parsed.status)
            assertEquals(reason, parsed.unknownReason)
            assertNull(parsed.currentState)
        }
    }

    @Test
    fun `transport failure and wrong command stay unknown`() {
        val failed = GuardianAutostartParser.parse(result(valid, exitCode = 1), uid)
        assertEquals(AutostartUnknownReason.TRANSPORT_FAILURE, failed.unknownReason)

        val wrong = GuardianAutostartParser.parse(
            CommandResult(SafeCommand.GetCurrentUser, 0, valid, ""),
            uid,
        )
        assertEquals(AutostartUnknownReason.WRONG_COMMAND, wrong.unknownReason)
    }

    @Test
    fun `target parser binds command package row package and fresh uid`() {
        val packageName = "org.telegram.messenger"
        val command = SafeCommand.GetTargetAutostartRow(packageName)
        val output =
            "Row: 0 _id=157, pkgname=$packageName, pkguid=10434, currentstate=0, setbyuser=1\n"
        val parsed = TargetAutostartParser.parse(
            CommandResult(command, 0, output, ""),
            packageName,
            10_434,
        )
        assertEquals(AutostartReadStatus.READ_ONLY_VALUE_PRESENT, parsed.status)
        assertEquals(0, parsed.currentState)

        assertEquals(
            AutostartUnknownReason.WRONG_COMMAND,
            TargetAutostartParser.parse(
                CommandResult(command, 0, output, ""),
                "org.telegram.other",
                10_434,
            ).unknownReason,
        )
        assertEquals(
            AutostartUnknownReason.UID_MISMATCH,
            TargetAutostartParser.parse(
                CommandResult(command, 0, output, ""),
                packageName,
                10_435,
            ).unknownReason,
        )
        assertEquals(
            AutostartUnknownReason.UID_MISMATCH,
            TargetAutostartParser.parse(
                CommandResult(command, 0, output.replace("10434", "9999"), ""),
                packageName,
                9_999,
            ).unknownReason,
        )
    }

    private fun result(stdout: String, exitCode: Int = 0) = CommandResult(
        command = SafeCommand.GetGuardianAutostartRow,
        exitCode = exitCode,
        stdout = stdout,
        stderr = "",
    )
}
