package io.github.originosfcmguardian.doze.ofd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DozeOfdAppHeldProbeTest {
    @Test fun deniedNonExclusiveDuplicateRemainsStructuredUnknown() {
        val fields = fields().toMutableMap().apply {
            put("code", "UNKNOWN")
            put("shellProbeStage", "DUP_A_TRY_LOCK")
            put("shellProbeFailureClass", "CHECK")
            put("shellDupAStatus", "ERROR")
            put("shellDupAErrno", 13)
        }
        val value = decodeAppHeldProbeFields(fields)
        assertEquals("UNKNOWN", value.code)
        assertEquals(GuardianOfdNative.Status.ERROR, value.dupAStatus)
        assertEquals(13, value.dupAErrno)
        assertEquals(GuardianOfdNative.Status.BUSY, value.bStatus)
        assertEquals(11, value.bErrno)
    }

    @Test fun unknownOrExtraFieldsFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("shellDupAStatus" to "SOME_NEW_STATUS"))
        }
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("unexpected" to "raw value"))
        }
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() - "shellBErrno")
        }
    }

    @Test fun bothDeniedResultsRemainUnknownAtFinalGate() {
        val value = decodeAppHeldProbeFields(fields() + mapOf(
            "code" to "UNKNOWN", "shellProbeFailureClass" to "CHECK", "shellBStatus" to "ERROR",
            "shellBErrno" to 13, "shellDupAStatus" to "ERROR", "shellDupAErrno" to 13,
        ))
        assertEquals("UNKNOWN", value.code)
        assertEquals(DozeOfdAppHeldProbeStage.FINAL_GATE, value.stage)
        assertEquals(13, value.bErrno)
        assertEquals(13, value.dupAErrno)
    }

    @Test fun incorrectNativeTypesAndFalsePassFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("shellBErrno" to "11"))
        }
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("shellBErrno" to -1))
        }
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("shellDupAStatus" to "ERROR"))
        }
        assertThrows(IllegalStateException::class.java) {
            decodeAppHeldProbeFields(fields() + ("shellBErrno" to 13))
        }
    }

    private fun fields(): Map<String, Any?> = mapOf(
        "schema" to "guardian-ofd-basic-v1", "code" to "APP_HELD_BUSY_DUP_A_SHARED", "shellUID" to 2_000,
        "writerPermission" to false, "shellProbeStage" to "FINAL_GATE", "shellProbeFailureClass" to "NONE",
        "shellBStatus" to "BUSY", "shellBErrno" to 11, "shellDupAStatus" to "OK", "shellDupAErrno" to 0,
    )
}
