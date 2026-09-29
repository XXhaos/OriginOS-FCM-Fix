package io.github.originosfcmguardian.core

data class CommandResult(
    val command: SafeCommand,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val outputTruncated: Boolean = false,
) {
    val completedCleanly: Boolean
        get() = exitCode == 0 &&
            !timedOut &&
            !outputTruncated &&
            stderr.isBlank() &&
            !stdout.hasSemanticError()
}

interface PrivilegedExecutor {
    suspend fun isAvailable(): Boolean

    suspend fun getUid(): Int?

    suspend fun exec(command: SafeCommand): CommandResult
}
