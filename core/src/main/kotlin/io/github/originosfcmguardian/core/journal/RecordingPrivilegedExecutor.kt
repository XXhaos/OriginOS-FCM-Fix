package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.PrivilegedExecutor
import io.github.originosfcmguardian.core.SafeCommand

/** Records bounded immutable copies of successful executor returns for later persistence. */
class RecordingPrivilegedExecutor(
    private val delegate: PrivilegedExecutor,
    private val maxResults: Int = 32,
) : PrivilegedExecutor {
    init {
        require(maxResults in 1..256)
    }

    private val lock = Any()
    private val evidence = ArrayList<RecordedCommandResult>()
    private var overflowed = false

    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    override suspend fun getUid(): Int? = delegate.getUid()

    override suspend fun exec(command: SafeCommand): CommandResult {
        val result = delegate.exec(command)
        synchronized(lock) {
            if (evidence.size < maxResults) {
                evidence += result.toRecorded()
            } else {
                overflowed = true
            }
        }
        return result
    }

    fun snapshotEvidence(): RecordedEvidenceSnapshot = synchronized(lock) {
        RecordedEvidenceSnapshot(evidence.toList(), overflowed)
    }
}

data class RecordedEvidenceSnapshot(
    val results: List<RecordedCommandResult>,
    val overflowed: Boolean,
)

private fun CommandResult.toRecorded(): RecordedCommandResult = RecordedCommandResult(
    operationId = command.operationId,
    uidParameter = (command as? SafeCommand.GetAppOpByUid)?.uid,
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    timedOut = timedOut,
    outputTruncated = outputTruncated,
)
