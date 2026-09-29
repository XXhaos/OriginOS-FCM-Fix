package io.github.originosfcmguardian.data.journal

import android.os.Build
import io.github.originosfcmguardian.core.journal.BaselineCaptureResult
import io.github.originosfcmguardian.core.journal.BaselineSnapshot
import io.github.originosfcmguardian.core.journal.BaselineStoreFailure
import io.github.originosfcmguardian.core.journal.FirstBaselineRepository
import io.github.originosfcmguardian.core.journal.JournalCodec
import io.github.originosfcmguardian.core.journal.JournalDocument
import io.github.originosfcmguardian.core.journal.JournalSemanticValidator
import io.github.originosfcmguardian.core.journal.JournalStore

internal data class AndroidBuildIdentity(
    val fingerprint: String,
    val id: String,
    val display: String,
    val sdkInt: Int,
) {
    companion object {
        fun current(): AndroidBuildIdentity = AndroidBuildIdentity(
            fingerprint = Build.FINGERPRINT,
            id = Build.ID,
            display = Build.DISPLAY,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

internal class OriginalRecordController(
    private val store: JournalStore,
    private val currentBuild: AndroidBuildIdentity = AndroidBuildIdentity.current(),
) {
    private val repository = FirstBaselineRepository(store)

    fun load(): OriginalRecordState {
        val bytes = try {
            store.read()
        } catch (_: Throwable) {
            return damagedState()
        } ?: return OriginalRecordState(
            status = OriginalRecordStatus.NOT_SAVED,
            detail = "尚未保存可用于核对的首次完整只读记录。",
        )

        val document = try {
            JournalCodec.decode(bytes)
        } catch (_: Throwable) {
            return damagedState()
        }
        return stateForDocument(document)
    }

    fun captureFirst(snapshot: BaselineSnapshot): OriginalRecordState {
        val latestValidation = JournalSemanticValidator.validateBaseline(snapshot)
        val state = when (val result = repository.captureFirst(snapshot)) {
            is BaselineCaptureResult.Stored -> stateForDocument(result.document)
            is BaselineCaptureResult.Preserved -> stateForDocument(result.existing)
            is BaselineCaptureResult.FailClosed -> when (result.reason) {
                BaselineStoreFailure.INCOMPLETE_SNAPSHOT -> OriginalRecordState(
                    status = OriginalRecordStatus.INCOMPLETE,
                    latestCaptureIncomplete = true,
                    detail = "本次读取证据不完整，原始记录未保存。请保持系统状态不变后重试检测。",
                )
                else -> storageFailureState()
            }
        }
        return if (!latestValidation.isValid && state.status == OriginalRecordStatus.SAVED) {
            state.copy(
                latestCaptureIncomplete = true,
                detail = "已保存的首次完整记录保持不变；本次读取证据不完整，未覆盖原记录。",
            )
        } else {
            state
        }
    }

    private fun stateForDocument(document: JournalDocument): OriginalRecordState {
        val validation = JournalSemanticValidator.validatePersistable(document)
        if (!validation.isValid) {
            return OriginalRecordState(
                status = OriginalRecordStatus.INCOMPLETE,
                capturedAtEpochMillis = document.baseline.metadata.capturedAtEpochMillis,
                detail = "已存在的原始记录证据不完整；为保护原记录，不会自动覆盖。",
            )
        }
        return savedState(document.baseline)
    }

    private fun savedState(snapshot: BaselineSnapshot): OriginalRecordState {
        val metadata = snapshot.metadata
        val matches = metadata.androidApiFingerprint == currentBuild.fingerprint &&
            metadata.buildFingerprint == currentBuild.fingerprint &&
            metadata.buildId == currentBuild.id &&
            metadata.buildDisplay == currentBuild.display &&
            metadata.sdkInt == currentBuild.sdkInt
        return OriginalRecordState(
            status = OriginalRecordStatus.SAVED,
            capturedAtEpochMillis = metadata.capturedAtEpochMillis,
            matchesCurrentBuild = matches,
            detail = if (matches) {
                "已保留首次完整只读记录；重复检测不会覆盖它。该记录不能授权系统修改。"
            } else {
                "已保存的记录来自不同的系统版本，仅供核对；不会自动覆盖或用于系统修改。"
            },
        )
    }

    private fun damagedState(): OriginalRecordState = OriginalRecordState(
        status = OriginalRecordStatus.DAMAGED,
        detail = "发现无法安全读取的原始记录。为保留证据，后续检测不会覆盖它。",
    )

    private fun storageFailureState(): OriginalRecordState = OriginalRecordState(
        status = OriginalRecordStatus.DAMAGED,
        detail = "原始记录无法安全读取或保存。未覆盖任何现有记录，也没有执行系统修改。",
    )
}
