package io.github.originosfcmguardian.core.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.CommandResult
import io.github.originosfcmguardian.core.DozeMembership
import io.github.originosfcmguardian.core.PrivilegedExecutor
import io.github.originosfcmguardian.core.SafeCommand
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalCodecAndStoreTest {
    @Test
    fun `recording executor preserves transport result and reports bounded overflow`() = runBlocking {
        val delegate = object : PrivilegedExecutor {
            override suspend fun isAvailable(): Boolean = true
            override suspend fun getUid(): Int = 2_000
            override suspend fun exec(command: SafeCommand) = CommandResult(
                command = command,
                exitCode = 42,
                stdout = "partial stdout",
                stderr = "transport stderr",
                timedOut = true,
                outputTruncated = true,
            )
        }
        val recorder = RecordingPrivilegedExecutor(delegate, maxResults = 1)
        recorder.exec(SafeCommand.GetCurrentUser)
        recorder.exec(SafeCommand.GetBuildFingerprint)
        val snapshot = recorder.snapshotEvidence()

        assertEquals(
            RecordedCommandResult(
                operationId = SafeCommand.GetCurrentUser.operationId,
                uidParameter = null,
                exitCode = 42,
                stdout = "partial stdout",
                stderr = "transport stderr",
                timedOut = true,
                outputTruncated = true,
            ),
            snapshot.results.single(),
        )
        assertTrue(snapshot.overflowed)
    }

    @Test
    fun `complete snapshot round trips all parsed and raw evidence`() = runBlocking {
        val document = JournalDocument(baseline = completeSnapshot())
        val decoded = JournalCodec.decode(JournalCodec.encode(document))

        assertEquals(document, decoded)
        assertFalse(decoded.baseline.eligibleForMutation)
        assertEquals(13, decoded.baseline.commandEvidence.size)
        assertTrue(JournalSemanticValidator.validatePersistable(decoded).isValid)
    }

    @Test
    fun `corrupt truncated unknown version and oversized inputs are rejected`() = runBlocking {
        val encoded = JournalCodec.encode(JournalDocument(baseline = completeSnapshot()))
        val corrupt = encoded.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals(CodecFailure.HASH_MISMATCH, codecFailure(corrupt))
        assertEquals(CodecFailure.TRUNCATED, codecFailure(encoded.copyOf(encoded.size - 1)))
        val unknown = encoded.copyOf().also { it[7] = 2 }
        assertEquals(CodecFailure.UNKNOWN_VERSION, codecFailure(unknown))
        assertEquals(
            CodecFailure.SIZE_LIMIT,
            codecFailure(ByteArray(JournalCodec.MAX_ENCODED_BYTES + 1)),
        )
    }

    @Test
    fun `semantic validator reparses raw and rejects altered mode uid and doze model`() = runBlocking {
        val original = completeSnapshot()
        val check = original.report.appOps.getValue(AppOp.RUN_IN_BACKGROUND)
        val alteredMode = original.copy(
            report = original.report.copy(
                appOps = original.report.appOps + (
                    AppOp.RUN_IN_BACKGROUND to check.copy(
                        packageState = check.packageState.copy(reportedDefault = AppOpMode.IGNORE),
                    )
                ),
            ),
        )
        assertTrue(
            SemanticIssue.COMMAND_EVIDENCE_REPORT_MISMATCH in
                JournalSemanticValidator.validateBaseline(alteredMode).issues,
        )

        val alteredUid = original.copy(
            report = original.report.copy(gms = original.report.gms.copy(appId = 10_209)),
            metadata = original.metadata.copy(gmsAppId = 10_209),
        )
        assertTrue(
            SemanticIssue.COMMAND_EVIDENCE_REPORT_MISMATCH in
                JournalSemanticValidator.validateBaseline(alteredUid).issues,
        )

        val alteredDoze = original.copy(
            report = original.report.copy(
                doze = original.report.doze.copy(
                    memberships = original.report.doze.memberships +
                        DozeMembership(
                            original.report.doze.memberships.first().source,
                            "com.example.changed",
                            12_345,
                        ),
                ),
            ),
        )
        assertTrue(
            SemanticIssue.COMMAND_EVIDENCE_REPORT_MISMATCH in
                JournalSemanticValidator.validateBaseline(alteredDoze).issues,
        )
    }

    @Test
    fun `first complete baseline is preserved and concurrent winner cannot be overwritten`() = runBlocking {
        val first = completeSnapshot(capturedAt = 1_000)
        val later = completeSnapshot(capturedAt = 2_000)
        val store = MemoryStore()
        val repository = FirstBaselineRepository(store)

        assertIs<BaselineCaptureResult.Stored>(repository.captureFirst(first))
        val preserved = assertIs<BaselineCaptureResult.Preserved>(repository.captureFirst(later))
        assertEquals(1_000, preserved.existing.baseline.metadata.capturedAtEpochMillis)
        assertEquals(1, store.writeCount)

        val winnerBytes = JournalCodec.encode(JournalDocument(baseline = first))
        val race = RacingStore(winnerBytes)
        val raceResult = assertIs<BaselineCaptureResult.Preserved>(
            FirstBaselineRepository(race).captureFirst(later),
        )
        assertEquals(1_000, raceResult.existing.baseline.metadata.capturedAtEpochMillis)
    }

    @Test
    fun `incomplete capture and corrupt existing record fail closed without write`() = runBlocking {
        val complete = completeSnapshot()
        val missing = complete.copy(commandEvidence = complete.commandEvidence.dropLast(1))
        val empty = MemoryStore()
        val incompleteResult = assertIs<BaselineCaptureResult.FailClosed>(
            FirstBaselineRepository(empty).captureFirst(missing),
        )
        assertEquals(BaselineStoreFailure.INCOMPLETE_SNAPSHOT, incompleteResult.reason)
        assertEquals(0, empty.writeCount)

        val corrupt = MemoryStore(byteArrayOf(1, 2, 3))
        val corruptResult = assertIs<BaselineCaptureResult.FailClosed>(
            FirstBaselineRepository(corrupt).captureFirst(complete),
        )
        assertEquals(BaselineStoreFailure.EXISTING_CORRUPT, corruptResult.reason)
        assertEquals(0, corrupt.writeCount)
    }

    @Test
    fun `persisted v1 rejects future in-memory operation state`() = runBlocking {
        val snapshot = completeSnapshot()
        val before = appOpObservation(snapshot, AppOp.WAKE_LOCK)
        val document = JournalDocument(
            baseline = snapshot,
            operations = listOf(
                JournalOperation(
                    "future",
                    JournalComponent.APP_OPS,
                    before,
                    before,
                    WriteIntentState.NONE,
                    OperationVerification.NOT_ATTEMPTED,
                ),
            ),
        )
        assertEquals(CodecFailure.MALFORMED, runCatching { JournalCodec.encode(document) }
            .exceptionOrNull().let { (it as JournalCodecException).failure })
    }

    private fun codecFailure(bytes: ByteArray): CodecFailure =
        (runCatching { JournalCodec.decode(bytes) }.exceptionOrNull() as JournalCodecException).failure

    private class MemoryStore(initial: ByteArray? = null) : JournalStore {
        private var bytes = initial
        var writeCount = 0
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun writeIfAbsent(encoded: ByteArray): StoreWriteResult {
            if (bytes != null) return StoreWriteResult.ALREADY_EXISTS
            writeCount += 1
            bytes = encoded.copyOf()
            return StoreWriteResult.STORED
        }
    }

    private class RacingStore(private val winner: ByteArray) : JournalStore {
        private var readCount = 0
        override fun read(): ByteArray? = if (readCount++ == 0) null else winner.copyOf()
        override fun writeIfAbsent(encoded: ByteArray): StoreWriteResult = StoreWriteResult.ALREADY_EXISTS
    }
}
