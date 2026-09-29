package io.github.originosfcmguardian.core

enum class AutostartReadStatus {
    READ_ONLY_VALUE_PRESENT,
    UNKNOWN,
}

enum class AutostartUnknownReason {
    NONE,
    WRONG_COMMAND,
    TRANSPORT_FAILURE,
    NO_UNIQUE_ROW,
    MALFORMED_ROW,
    PACKAGE_MISMATCH,
    UID_MISMATCH,
    FIELD_OUT_OF_RANGE,
}

/** Raw provider evidence. Build-specific meaning is applied separately after fingerprint gating. */
data class GuardianAutostartRead(
    val status: AutostartReadStatus,
    val rowId: Int? = null,
    val packageUid: Int? = null,
    val currentState: Int? = null,
    val setByUser: Int? = null,
    val unknownReason: AutostartUnknownReason = AutostartUnknownReason.NONE,
)

object GuardianAutostartParser {
    fun parse(result: CommandResult, expectedGuardianUid: Int): GuardianAutostartRead =
        parseExactRow(result, GUARDIAN_PACKAGE_NAME, expectedGuardianUid) {
            it == SafeCommand.GetGuardianAutostartRow
        }
}

object TargetAutostartParser {
    fun parse(
        result: CommandResult,
        expectedPackageName: String,
        expectedUid: Int,
    ): GuardianAutostartRead {
        if (!isSafeAndroidPackageName(expectedPackageName)) {
            return unknown(AutostartUnknownReason.PACKAGE_MISMATCH)
        }
        if (!isUserZeroTargetAppUid(expectedUid)) {
            return unknown(AutostartUnknownReason.UID_MISMATCH)
        }
        return parseExactRow(result, expectedPackageName, expectedUid) { command ->
            command is SafeCommand.GetTargetAutostartRow &&
                command.packageName == expectedPackageName
        }
    }
}

private val rowPrefix = Regex("^Row:\\s*(\\d+)\\s+(.+)$")
private val expectedFields = setOf("_id", "pkgname", "pkguid", "currentstate", "setbyuser")

private fun parseExactRow(
    result: CommandResult,
    expectedPackageName: String,
    expectedUid: Int,
    commandMatches: (SafeCommand) -> Boolean,
): GuardianAutostartRead {
        if (!commandMatches(result.command)) {
            return unknown(AutostartUnknownReason.WRONG_COMMAND)
        }
        if (!isUserZeroAppId(expectedUid)) {
            return unknown(AutostartUnknownReason.UID_MISMATCH)
        }
        if (result.transportProblems().isNotEmpty()) {
            return unknown(AutostartUnknownReason.TRANSPORT_FAILURE)
        }

        val lines = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size != 1) return unknown(AutostartUnknownReason.NO_UNIQUE_ROW)
        val row = rowPrefix.matchEntire(lines.single())
            ?: return unknown(AutostartUnknownReason.NO_UNIQUE_ROW)
        if (row.groupValues[1].toIntOrNull() != 0) {
            return unknown(AutostartUnknownReason.NO_UNIQUE_ROW)
        }

        val fields = linkedMapOf<String, String>()
        for (entry in row.groupValues[2].split(',')) {
            val parts = entry.trim().split('=', limit = 2)
            if (parts.size != 2 || parts[0] !in expectedFields || fields.put(parts[0], parts[1]) != null) {
                return unknown(AutostartUnknownReason.MALFORMED_ROW)
            }
        }
        if (fields.keys != expectedFields) return unknown(AutostartUnknownReason.MALFORMED_ROW)
        if (fields["pkgname"] != expectedPackageName) {
            return unknown(AutostartUnknownReason.PACKAGE_MISMATCH)
        }

        val rowId = fields["_id"]?.toIntOrNull()
            ?: return unknown(AutostartUnknownReason.MALFORMED_ROW)
        val packageUid = fields["pkguid"]?.toIntOrNull()
            ?: return unknown(AutostartUnknownReason.MALFORMED_ROW)
        val currentState = fields["currentstate"]?.toIntOrNull()
            ?: return unknown(AutostartUnknownReason.MALFORMED_ROW)
        val setByUser = fields["setbyuser"]?.toIntOrNull()
            ?: return unknown(AutostartUnknownReason.MALFORMED_ROW)

        if (rowId !in 1..Int.MAX_VALUE || currentState !in 0..1 || setByUser !in 0..1) {
            return unknown(AutostartUnknownReason.FIELD_OUT_OF_RANGE)
        }
        if (!isUserZeroAppId(packageUid) || packageUid != expectedUid) {
            return unknown(AutostartUnknownReason.UID_MISMATCH)
        }
        return GuardianAutostartRead(
            status = AutostartReadStatus.READ_ONLY_VALUE_PRESENT,
            rowId = rowId,
            packageUid = packageUid,
            currentState = currentState,
            setByUser = setByUser,
        )
}

private fun unknown(reason: AutostartUnknownReason) = GuardianAutostartRead(
    status = AutostartReadStatus.UNKNOWN,
    unknownReason = reason,
)
