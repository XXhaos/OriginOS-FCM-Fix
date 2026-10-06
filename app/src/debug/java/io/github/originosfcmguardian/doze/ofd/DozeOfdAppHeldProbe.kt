package io.github.originosfcmguardian.doze.ofd

import android.os.Bundle
import java.io.IOException
import java.lang.reflect.InvocationTargetException

internal enum class DozeOfdAppHeldProbeStage {
    NOT_CALLED, STEP_GATE, IDENTITY, FD_PATH, B_TRY_LOCK, DUP_A_TRY_LOCK, DUP_A_CLOSE, FINAL_GATE,
}

internal data class DozeOfdAppHeldProbeSnapshot(
    val code: String = "NOT_CALLED",
    val stage: DozeOfdAppHeldProbeStage = DozeOfdAppHeldProbeStage.NOT_CALLED,
    val failureClass: DozeOfdFailureClass = DozeOfdFailureClass.NONE,
    val bStatus: GuardianOfdNative.Status? = null,
    val bErrno: Int = 0,
    val dupAStatus: GuardianOfdNative.Status? = null,
    val dupAErrno: Int = 0,
)

internal fun Bundle.attachAppHeldProbe(value: DozeOfdAppHeldProbeSnapshot): Bundle = apply {
    putString("shellProbeStage", value.stage.name)
    putString("shellProbeFailureClass", value.failureClass.name)
    putString("shellBStatus", value.bStatus?.name ?: "NOT_CALLED")
    putInt("shellBErrno", value.bErrno)
    putString("shellDupAStatus", value.dupAStatus?.name ?: "NOT_CALLED")
    putInt("shellDupAErrno", value.dupAErrno)
}

@Suppress("DEPRECATION")
internal fun decodeAppHeldProbe(bundle: Bundle?): DozeOfdAppHeldProbeSnapshot {
    val value = requireNotNull(bundle)
    return decodeAppHeldProbeFields(value.keySet().associateWith { value.get(it) })
}

internal fun decodeAppHeldProbeFields(fields: Map<String, Any?>): DozeOfdAppHeldProbeSnapshot {
    check(fields.keys == setOf("schema", "code", "shellUID", "writerPermission", "shellProbeStage",
        "shellProbeFailureClass", "shellBStatus", "shellBErrno", "shellDupAStatus", "shellDupAErrno"))
    check(fields["schema"] == "guardian-ofd-basic-v1" && fields["shellUID"] == 2_000 && fields["writerPermission"] == false)
    val code = fields["code"] as? String ?: error("Invalid probe code")
    check(code == "UNKNOWN" || code == "APP_HELD_BUSY_DUP_A_SHARED")
    val stage = DozeOfdAppHeldProbeStage.entries.singleOrNull { it.name == fields["shellProbeStage"] }
        ?: error("Invalid probe stage")
    check(stage != DozeOfdAppHeldProbeStage.NOT_CALLED)
    val kind = DozeOfdFailureClass.entries.singleOrNull { it.name == fields["shellProbeFailureClass"] }
        ?: error("Invalid probe failure class")
    fun status(key: String): GuardianOfdNative.Status? {
        val value = fields[key] as? String ?: error("Invalid probe native status")
        if (value == "NOT_CALLED") return null
        return GuardianOfdNative.Status.values().singleOrNull { it.name == value } ?: error("Invalid probe native status")
    }
    fun errno(key: String): Int = (fields[key] as? Int ?: error("Invalid probe errno")).also { check(it in 0..4095) }
    val bStatus = status("shellBStatus")
    val bErrno = errno("shellBErrno")
    val dupStatus = status("shellDupAStatus")
    val dupErrno = errno("shellDupAErrno")
    check(bStatus != null || bErrno == 0)
    check(dupStatus != null || dupErrno == 0)
    check(bStatus != GuardianOfdNative.Status.OK || bErrno == 0)
    check(dupStatus != GuardianOfdNative.Status.OK || dupErrno == 0)
    check(bStatus != GuardianOfdNative.Status.BUSY || bErrno == 11)
    check(dupStatus != GuardianOfdNative.Status.BUSY || dupErrno == 11)
    if (code == "APP_HELD_BUSY_DUP_A_SHARED") {
        check(stage == DozeOfdAppHeldProbeStage.FINAL_GATE && kind == DozeOfdFailureClass.NONE &&
            bStatus == GuardianOfdNative.Status.BUSY && dupStatus == GuardianOfdNative.Status.OK)
    } else check(kind != DozeOfdFailureClass.NONE)
    return DozeOfdAppHeldProbeSnapshot(code, stage, kind, bStatus, bErrno, dupStatus, dupErrno)
}

internal fun appHeldProbeFailureClass(failure: Throwable): DozeOfdFailureClass {
    var cause = failure
    repeat(4) { if (cause is InvocationTargetException) cause = cause.cause ?: cause }
    return when (cause) {
        is SecurityException -> DozeOfdFailureClass.SECURITY
        is InterruptedException -> DozeOfdFailureClass.INTERRUPTED
        is ReflectiveOperationException -> DozeOfdFailureClass.REFLECTION
        is IOException -> DozeOfdFailureClass.IO
        is LinkageError -> DozeOfdFailureClass.LINKAGE
        is IllegalStateException, is IllegalArgumentException -> DozeOfdFailureClass.CHECK
        else -> DozeOfdFailureClass.OTHER
    }
}
