package io.github.originosfcmguardian.data.journal

import io.github.originosfcmguardian.core.AppOp
import io.github.originosfcmguardian.core.AppOpCheck
import io.github.originosfcmguardian.core.AppOpMode
import io.github.originosfcmguardian.core.AppOpScope
import io.github.originosfcmguardian.core.AppOpState
import io.github.originosfcmguardian.core.DetectionReport
import io.github.originosfcmguardian.core.DozeMembership
import io.github.originosfcmguardian.core.DozeSource
import io.github.originosfcmguardian.core.DozeState
import io.github.originosfcmguardian.core.EnabledState
import io.github.originosfcmguardian.core.EvidenceStatus
import io.github.originosfcmguardian.core.GmsPackageState
import io.github.originosfcmguardian.core.OverrideProvenance
import io.github.originosfcmguardian.core.PemListState
import io.github.originosfcmguardian.core.PemState
import io.github.originosfcmguardian.core.Presence
import io.github.originosfcmguardian.core.PrivilegedAccessState
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.core.journal.BaselineSnapshot
import io.github.originosfcmguardian.core.journal.CaptureMetadata
import io.github.originosfcmguardian.core.journal.CaptureSource
import io.github.originosfcmguardian.core.journal.JournalCodec
import io.github.originosfcmguardian.core.journal.JournalDocument
import io.github.originosfcmguardian.core.journal.JournalStore
import io.github.originosfcmguardian.core.journal.JournalSemanticValidator
import io.github.originosfcmguardian.core.journal.MetadataFieldSource
import io.github.originosfcmguardian.core.journal.RecordedCommandResult
import io.github.originosfcmguardian.core.journal.StoreWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalRecordControllerTest {
    private val current = AndroidBuildIdentity(FINGERPRINT, "build-id", "display", 37)

    @Test
    fun missingStoreIsReportedAsNotSaved() {
        val state = OriginalRecordController(MemoryStore(), current).load()

        assertEquals(OriginalRecordStatus.NOT_SAVED, state.status)
    }

    @Test
    fun firstCompleteCaptureIsSavedAndRepeatPreservesOriginal() {
        val store = MemoryStore()
        val controller = OriginalRecordController(store, current)
        val validation = JournalSemanticValidator.validateBaseline(snapshot(capturedAt = 100))
        assertTrue(validation.issues.toString(), validation.isValid)

        val first = controller.captureFirst(snapshot(capturedAt = 100))
        val repeated = controller.captureFirst(snapshot(capturedAt = 200))

        assertEquals(OriginalRecordStatus.SAVED, first.status)
        assertEquals(100L, first.capturedAtEpochMillis)
        assertEquals(OriginalRecordStatus.SAVED, repeated.status)
        assertEquals(100L, repeated.capturedAtEpochMillis)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun incompleteCaptureDoesNotOccupyFirstRecord() {
        val store = MemoryStore()
        val incomplete = snapshot(capturedAt = 100).copy(evidenceOverflowed = true)

        val state = OriginalRecordController(store, current).captureFirst(incomplete)

        assertEquals(OriginalRecordStatus.INCOMPLETE, state.status)
        assertNull(store.bytes)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun incompleteRepeatDoesNotHideOrReplaceSavedRecord() {
        val store = MemoryStore()
        val controller = OriginalRecordController(store, current)
        controller.captureFirst(snapshot(capturedAt = 100))

        val state = controller.captureFirst(
            snapshot(capturedAt = 200).copy(evidenceOverflowed = true),
        )

        assertEquals(OriginalRecordStatus.SAVED, state.status)
        assertEquals(100L, state.capturedAtEpochMillis)
        assertTrue(state.latestCaptureIncomplete)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun corruptOrUnknownStoredBytesAreFailClosed() {
        val corrupt = OriginalRecordController(MemoryStore(byteArrayOf(1, 2, 3)), current).load()
        val unknownVersion = JournalCodec.encode(JournalDocument(baseline = snapshot(100))).clone()
        unknownVersion[7] = 2
        val unknown = OriginalRecordController(MemoryStore(unknownVersion), current).load()

        assertEquals(OriginalRecordStatus.DAMAGED, corrupt.status)
        assertEquals(OriginalRecordStatus.DAMAGED, unknown.status)
    }

    @Test
    fun validRecordFromAnotherBuildIsClearlyMarkedNonCurrent() {
        val oldSnapshot = snapshot(capturedAt = 100, fingerprint = "vendor/device/old:1/id/tag")
        val bytes = JournalCodec.encode(JournalDocument(baseline = oldSnapshot))

        val state = OriginalRecordController(MemoryStore(bytes), current).load()

        assertEquals(OriginalRecordStatus.SAVED, state.status)
        assertFalse(state.matchesCurrentBuild!!)
        assertTrue(state.detail.contains("不同"))
    }

    @Test
    fun lateInitialLoadCannotReplaceCaptureResult() {
        val captured = OriginalRecordState(
            status = OriginalRecordStatus.SAVED,
            capturedAtEpochMillis = 100,
        )
        val lateMissing = OriginalRecordState(status = OriginalRecordStatus.NOT_SAVED)

        assertEquals(captured, applyInitialRecordIfStillLoading(captured, lateMissing))
        assertEquals(
            lateMissing,
            applyInitialRecordIfStillLoading(OriginalRecordState(), lateMissing),
        )
    }

    private fun snapshot(
        capturedAt: Long,
        fingerprint: String = FINGERPRINT,
    ): BaselineSnapshot {
        val appId = 10_208
        val packageRaw = "package:com.google.android.gms uid:$appId\n"
        val enabledRaw = packageRaw
        val dozeRaw = "system,com.google.android.gms,$appId\n"
        val pemListRaw = "null\n"
        val pemPackageRaw = "package:/system/app/VivoPem/base.apk\n"
        val appOpRaw = "No operations.\nDefault mode: allow\n"
        val appOps = AppOp.entries.associateWith { operation ->
            val packageState = AppOpState(
                scope = AppOpScope.Package(),
                operation = operation,
                operationRecordPresence = Presence.ABSENT,
                reportedMode = null,
                reportedDefault = AppOpMode.ALLOW,
                overrideProvenance = OverrideProvenance.ABSENT,
                rawOutput = appOpRaw,
            )
            val uidState = AppOpState(
                scope = AppOpScope.Uid(appId),
                operation = operation,
                operationRecordPresence = Presence.ABSENT,
                reportedMode = null,
                reportedDefault = AppOpMode.ALLOW,
                overrideProvenance = OverrideProvenance.ABSENT,
                rawOutput = appOpRaw,
            )
            AppOpCheck(operation, packageState, uidState, EvidenceStatus.CONFORMING)
        }
        val report = DetectionReport(
            privilegedAccess = PrivilegedAccessState.READY_SHELL,
            currentUser = 0,
            buildFingerprint = fingerprint,
            gms = GmsPackageState(
                presence = Presence.PRESENT,
                enabled = EnabledState.ENABLED,
                appId = appId,
                rawInstalledOutput = packageRaw,
                rawEnabledOutput = enabledRaw,
            ),
            appOps = appOps,
            doze = DozeState(
                memberships = setOf(DozeMembership(DozeSource.SYSTEM, "com.google.android.gms", appId)),
                rawOutput = dozeRaw,
                status = EvidenceStatus.CONFORMING,
            ),
            pem = PemState(
                packagePresence = Presence.PRESENT,
                listState = PemListState.NULL_OR_MISSING_AMBIGUOUS,
                entries = emptySet(),
                rawPackageOutput = pemPackageRaw,
                rawListOutput = pemListRaw,
            ),
        )
        val evidence = buildList {
            add(record(SafeCommand.GetCurrentUser, "0\n"))
            add(record(SafeCommand.GetBuildFingerprint, "$fingerprint\n"))
            add(record(SafeCommand.GetGmsPackage, packageRaw))
            add(record(SafeCommand.GetGmsEnabledPackage, enabledRaw))
            add(record(SafeCommand.GetDeviceIdleWhitelist, dozeRaw))
            add(record(SafeCommand.GetVivoPemList, pemListRaw))
            add(record(SafeCommand.GetVivoPemPackagePath, pemPackageRaw))
            AppOp.entries.forEach { operation ->
                add(record(SafeCommand.GetGmsAppOpByPackage(operation), appOpRaw))
                add(record(SafeCommand.GetAppOpByUid(appId, operation), appOpRaw, appId))
            }
        }
        return BaselineSnapshot(
            metadata = CaptureMetadata(
                capturedAtEpochMillis = capturedAt,
                captureSource = CaptureSource.SHIZUKU_READ_ONLY,
                buildFingerprint = fingerprint,
                buildFingerprintSource = MetadataFieldSource.SHELL_REPORT,
                androidApiFingerprint = fingerprint,
                buildId = "build-id",
                buildDisplay = "display",
                sdkInt = 37,
                buildFieldsSource = MetadataFieldSource.ANDROID_BUILD_API,
                userId = 0,
                userIdSource = MetadataFieldSource.SHELL_REPORT,
                executorUid = 2_000,
                executorUidSource = MetadataFieldSource.EXECUTOR,
                gmsAppId = appId,
            ),
            report = report,
            commandEvidence = evidence,
        )
    }

    private fun record(command: SafeCommand, stdout: String, uid: Int? = null) = RecordedCommandResult(
        operationId = command.operationId,
        uidParameter = uid,
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        timedOut = false,
        outputTruncated = false,
    )

    private class MemoryStore(initial: ByteArray? = null) : JournalStore {
        var bytes: ByteArray? = initial?.copyOf()
        var writeCount: Int = 0

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun writeIfAbsent(encoded: ByteArray): StoreWriteResult = synchronized(this) {
            if (bytes != null) return@synchronized StoreWriteResult.ALREADY_EXISTS
            bytes = encoded.copyOf()
            writeCount += 1
            StoreWriteResult.STORED
        }
    }

    private companion object {
        const val FINGERPRINT = "vendor/device/device:17/id/tag"
    }
}
