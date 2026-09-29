package io.github.originosfcmguardian.core

object PemParser {
    private val packageEntry = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun parse(listResult: CommandResult, pathResult: CommandResult): PemState {
        val problems = mutableListOf<String>()
        if (listResult.command != SafeCommand.GetVivoPemList) {
            problems += "PEM list result came from an unexpected command"
        }
        if (pathResult.command != SafeCommand.GetVivoPemPackagePath) {
            problems += "PEM path result came from an unexpected command"
        }
        problems += listResult.transportProblems().map { "PEM list: $it" }
        problems += pathResult.transportProblems().map { "PEM package: $it" }

        val path = pathResult.stdout.trim()
        val packagePresence = when {
            path.isEmpty() -> Presence.ABSENT
            path.startsWith("package:") && path.removePrefix("package:").isNotBlank() -> Presence.PRESENT
            else -> {
                problems += "Malformed PEM package path"
                Presence.UNKNOWN
            }
        }

        val rawList = listResult.stdout.trim()
        val listState: PemListState
        val entries: Set<String>
        when {
            rawList.equals("null", ignoreCase = true) -> {
                listState = PemListState.NULL_OR_MISSING_AMBIGUOUS
                entries = emptySet()
            }
            rawList.isEmpty() -> {
                listState = PemListState.UNKNOWN
                entries = emptySet()
                problems += "Empty PEM output is ambiguous"
            }
            else -> {
                val parsed = rawList.split(',').map(String::trim)
                if (parsed.any { it.isEmpty() || !packageEntry.matches(it) }) {
                    listState = PemListState.UNKNOWN
                    entries = emptySet()
                    problems += "Malformed PEM list"
                } else {
                    listState = PemListState.VALUE_REPORTED
                    entries = parsed.toSet()
                }
            }
        }

        return PemState(
            packagePresence = if (problems.any { it.startsWith("PEM package:") }) {
                Presence.UNKNOWN
            } else {
                packagePresence
            },
            listState = if (problems.any { it.startsWith("PEM list:") }) {
                PemListState.UNKNOWN
            } else {
                listState
            },
            entries = entries,
            rawPackageOutput = pathResult.stdout,
            rawListOutput = listResult.stdout,
            problems = problems.distinct(),
        )
    }
}
