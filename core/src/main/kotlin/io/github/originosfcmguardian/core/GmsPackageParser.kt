package io.github.originosfcmguardian.core

object GmsPackageParser {
    private val packageLine = Regex("^package:([^\\s]+)\\s+uid:(\\d+)\\s*$")

    fun parse(
        installedResult: CommandResult,
        enabledResult: CommandResult,
    ): GmsPackageState {
        val problems = mutableListOf<String>()
        if (installedResult.command != SafeCommand.GetGmsPackage) {
            problems += "Installed-package result came from an unexpected command"
        }
        if (enabledResult.command != SafeCommand.GetGmsEnabledPackage) {
            problems += "Enabled-package result came from an unexpected command"
        }
        problems += installedResult.transportProblems().map { "Installed query: $it" }
        problems += enabledResult.transportProblems().map { "Enabled query: $it" }

        val installed = parsePackageOutput(installedResult.stdout)
        val enabled = parsePackageOutput(enabledResult.stdout)
        problems += installed.problems.map { "Installed query: $it" }
        problems += enabled.problems.map { "Enabled query: $it" }

        if (problems.isNotEmpty()) {
            return unknown(installedResult, enabledResult, problems)
        }

        return when {
            installed.appId == null && enabled.appId == null -> GmsPackageState(
                presence = Presence.ABSENT,
                enabled = EnabledState.UNKNOWN,
                appId = null,
                rawInstalledOutput = installedResult.stdout,
                rawEnabledOutput = enabledResult.stdout,
            )
            installed.appId != null && enabled.appId == null -> GmsPackageState(
                presence = Presence.PRESENT,
                enabled = EnabledState.DISABLED,
                appId = installed.appId,
                rawInstalledOutput = installedResult.stdout,
                rawEnabledOutput = enabledResult.stdout,
            )
            installed.appId == null -> unknown(
                installedResult,
                enabledResult,
                listOf("Enabled query reports GMS but installed query does not"),
            )
            installed.appId != enabled.appId -> unknown(
                installedResult,
                enabledResult,
                listOf("Installed and enabled queries report different appIds"),
            )
            else -> GmsPackageState(
                presence = Presence.PRESENT,
                enabled = EnabledState.ENABLED,
                appId = installed.appId,
                rawInstalledOutput = installedResult.stdout,
                rawEnabledOutput = enabledResult.stdout,
            )
        }
    }

    private fun parsePackageOutput(output: String): ParsedPackageOutput {
        val problems = mutableListOf<String>()
        val exactMatches = mutableListOf<Int>()
        output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { line ->
                val match = packageLine.matchEntire(line)
                if (match == null) {
                    problems += "Malformed package-list line"
                    return@forEach
                }
                val packageName = match.groupValues[1]
                val appId = match.groupValues[2].toIntOrNull()
                if (appId == null || !isUserZeroAppId(appId)) {
                    problems += "Package-list UID is outside user-0 appId scope"
                } else if (packageName == GMS_PACKAGE_NAME) {
                    exactMatches += appId
                }
            }

        if (exactMatches.size > 1) problems += "Package query returned duplicate exact GMS rows"
        return ParsedPackageOutput(exactMatches.singleOrNull(), problems)
    }

    private fun unknown(
        installedResult: CommandResult,
        enabledResult: CommandResult,
        problems: List<String>,
    ) = GmsPackageState(
        presence = Presence.UNKNOWN,
        enabled = EnabledState.UNKNOWN,
        appId = null,
        rawInstalledOutput = installedResult.stdout,
        rawEnabledOutput = enabledResult.stdout,
        problems = problems.distinct(),
    )

    private data class ParsedPackageOutput(
        val appId: Int?,
        val problems: List<String>,
    )
}
