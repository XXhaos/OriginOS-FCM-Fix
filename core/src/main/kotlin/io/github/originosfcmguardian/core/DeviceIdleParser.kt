package io.github.originosfcmguardian.core

object DeviceIdleParser {
    private val membershipLine = Regex("^([^,]+),([^,]+),(\\d+)$")

    fun parse(result: CommandResult): DozeState {
        val problems = mutableListOf<String>()
        if (result.command != SafeCommand.GetDeviceIdleWhitelist) {
            problems += "Result came from an unexpected command"
        }
        problems += result.transportProblems()
        val memberships = linkedSetOf<DozeMembership>()
        var parsedLineCount = 0

        result.stdout.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { line ->
                val match = membershipLine.matchEntire(line)
                if (match == null) {
                    problems += "Malformed DeviceIdle membership line"
                    return@forEach
                }
                val packageName = match.groupValues[2]
                val appId = match.groupValues[3].toIntOrNull()
                if (appId == null || !isUserZeroAppId(appId)) {
                    problems += "DeviceIdle appId is outside the supported range"
                    return@forEach
                }
                parsedLineCount += 1
                if (packageName != GMS_PACKAGE_NAME) return@forEach

                val source = DozeSource.entries.singleOrNull {
                    it.wireName == match.groupValues[1]
                }
                if (source == null) {
                    problems += "GMS has an unknown DeviceIdle membership source"
                } else {
                    memberships += DozeMembership(source, packageName, appId)
                }
            }

        if (parsedLineCount == 0) problems += "No DeviceIdle state was reported"

        val status = when {
            problems.isNotEmpty() -> EvidenceStatus.UNKNOWN
            memberships.isNotEmpty() -> EvidenceStatus.CONFORMING
            else -> EvidenceStatus.ANOMALOUS
        }
        return DozeState(
            memberships = memberships,
            rawOutput = result.stdout,
            status = status,
            problems = problems.distinct(),
        )
    }
}
