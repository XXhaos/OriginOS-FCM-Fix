package io.github.originosfcmguardian.core

object AppOpsParser {
    private val defaultLine = Regex("^Default mode:\\s*([^\\s;]+)\\s*$", RegexOption.IGNORE_CASE)
    private val operationLine = Regex("^([A-Z_]+):\\s*([^;\\s]+)(?:;\\s*(.*))?$")
    private val detailField = Regex("^[A-Za-z][A-Za-z_-]*=.+$")

    fun parse(
        scope: AppOpScope,
        operation: AppOp,
        result: CommandResult,
    ): AppOpState {
        val problems = mutableListOf<String>()
        if (!commandMatches(scope, operation, result.command)) {
            problems += "Result came from an unexpected command or scope"
        }
        problems += result.transportProblems()

        var noOperationsCount = 0
        val defaults = mutableListOf<AppOpMode>()
        val modes = mutableListOf<AppOpMode>()

        result.stdout.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { line ->
                when {
                    line == "No operations." -> noOperationsCount += 1
                    defaultLine.matches(line) -> {
                        val rawMode = defaultLine.matchEntire(line)!!.groupValues[1]
                        val mode = parseMode(rawMode)
                        if (mode == null) problems += "Unknown default mode"
                        else defaults += mode
                    }
                    operationLine.matches(line) -> {
                        val match = operationLine.matchEntire(line)!!
                        if (match.groupValues[1] != operation.shellName) {
                            problems += "Output contains a different AppOp"
                            return@forEach
                        }
                        val mode = parseMode(match.groupValues[2])
                        if (mode == null) problems += "Unknown operation mode"
                        else modes += mode

                        val details = match.groupValues[3]
                        if (details.isNotBlank()) {
                            val fields = details.split(';').map(String::trim)
                            if (fields.any { !detailField.matches(it) }) {
                                problems += "Malformed operation usage detail"
                            }
                        }
                    }
                    else -> problems += "Unrecognized AppOps output line"
                }
            }

        if (noOperationsCount > 1) problems += "Duplicate no-operations markers"
        if (defaults.size > 1) problems += "Duplicate or conflicting default modes"
        if (modes.size > 1) problems += "Duplicate or conflicting operation modes"
        if (noOperationsCount > 0 && modes.isNotEmpty()) {
            problems += "No-operations marker conflicts with an operation record"
        }
        if (noOperationsCount > 0 && defaults.size != 1) {
            problems += "No-operations output lacks one reported default"
        }
        if (modes.isNotEmpty() && defaults.isNotEmpty()) {
            problems += "Operation record and default mode appeared together"
        }
        if (noOperationsCount == 0 && modes.isEmpty()) problems += "No AppOps state was reported"

        return when {
            problems.isNotEmpty() -> AppOpState(
                scope = scope,
                operation = operation,
                operationRecordPresence = Presence.UNKNOWN,
                reportedMode = null,
                reportedDefault = null,
                overrideProvenance = OverrideProvenance.UNKNOWN,
                rawOutput = result.stdout,
                problems = problems.distinct(),
            )
            noOperationsCount == 1 -> AppOpState(
                scope = scope,
                operation = operation,
                operationRecordPresence = Presence.ABSENT,
                reportedMode = null,
                reportedDefault = defaults.single(),
                overrideProvenance = OverrideProvenance.ABSENT,
                rawOutput = result.stdout,
            )
            else -> AppOpState(
                scope = scope,
                operation = operation,
                operationRecordPresence = Presence.PRESENT,
                reportedMode = modes.single(),
                reportedDefault = null,
                // A displayed line may be usage history. It is not proof of an override.
                overrideProvenance = OverrideProvenance.UNPROVEN,
                rawOutput = result.stdout,
            )
        }
    }

    private fun commandMatches(scope: AppOpScope, operation: AppOp, command: SafeCommand): Boolean =
        when (scope) {
            is AppOpScope.Package ->
                command is SafeCommand.GetGmsAppOpByPackage && command.operation == operation
            is AppOpScope.Uid ->
                command is SafeCommand.GetAppOpByUid &&
                    command.operation == operation &&
                    command.uid == scope.appId
        }
}
