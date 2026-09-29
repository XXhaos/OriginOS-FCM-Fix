package io.github.originosfcmguardian.core

internal fun CommandResult.transportProblems(): List<String> = buildList {
    if (exitCode != 0) add("Command exited with code $exitCode")
    if (timedOut) add("Command timed out")
    if (outputTruncated) add("Command output was truncated")
    if (stderr.isNotBlank()) add("Command wrote to stderr")
    if (stdout.hasSemanticError()) add("Command stdout reports a semantic error")
}

internal fun String.hasSemanticError(): Boolean = lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .any { line ->
        line.startsWith("Error:", ignoreCase = true) ||
            line.startsWith("Exception", ignoreCase = true) ||
            line.startsWith("Security exception", ignoreCase = true) ||
            line.startsWith("Permission denial", ignoreCase = true) ||
            line.contains("does not have permission", ignoreCase = true)
    }

internal fun parseMode(raw: String): AppOpMode? = when (raw.lowercase()) {
    "allow" -> AppOpMode.ALLOW
    "ignore" -> AppOpMode.IGNORE
    "deny" -> AppOpMode.DENY
    "foreground" -> AppOpMode.FOREGROUND
    "default" -> AppOpMode.DEFAULT
    "errored" -> AppOpMode.ERRORED
    "ask" -> AppOpMode.ASK
    else -> null
}
