package io.github.originosfcmguardian.core

internal fun fixture(name: String): String = requireNotNull(
    object {}.javaClass.classLoader.getResource(name),
) { "Missing fixture: $name" }.readText()

internal fun result(
    command: SafeCommand,
    stdout: String,
    exitCode: Int = 0,
    stderr: String = "",
    timedOut: Boolean = false,
    outputTruncated: Boolean = false,
) = CommandResult(command, exitCode, stdout, stderr, timedOut, outputTruncated)
