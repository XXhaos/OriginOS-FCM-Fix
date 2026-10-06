package io.github.originosfcmguardian.doze.ofd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class DozeOfdFixtureDiagnosticTest {
    @Test fun lateProducerProgressCannotOverwriteFirstFailureSnapshot() {
        val diagnostic = DozeOfdFixtureDiagnostic()
        diagnostic.stage(DozeOfdFixtureStage.APP_NATIVE_LOAD)
        diagnostic.recordNative(DozeOfdFixtureStage.APP_NATIVE_LOAD, GuardianOfdNative.Status.UNSUPPORTED, 95)
        diagnostic.failure(TimeoutException("private detail must not be recorded"))
        val first = diagnostic.snapshot()
        diagnostic.stage(DozeOfdFixtureStage.SCRATCH_SETUP)
        diagnostic.recordNative(DozeOfdFixtureStage.SCRATCH_SETUP, GuardianOfdNative.Status.OK, 0)
        diagnostic.failure(IOException("different late failure"))
        diagnostic.unconfirmed(DozeOfdFixtureStage.RESOURCE_CLEANUP)
        assertEquals(first, diagnostic.snapshot())
        assertEquals(DozeOfdFixtureStage.APP_NATIVE_LOAD, first.stage)
        assertEquals(DozeOfdFailureClass.TIMEOUT, first.failureClass)
        assertEquals(GuardianOfdNative.Status.UNSUPPORTED, first.nativeStatus)
        assertEquals(95, first.nativeErrno)
    }

    @Test fun wrappedIoFailureProjectsOnlyFixedCategories() {
        val diagnostic = DozeOfdFixtureDiagnostic()
        diagnostic.stage(DozeOfdFixtureStage.SCRATCH_SETUP)
        diagnostic.failure(ExecutionException(InvocationTargetException(IOException("secret/path/token"))))
        val result = diagnostic.snapshot()
        assertEquals(DozeOfdFixtureStage.SCRATCH_SETUP, result.stage)
        assertEquals(DozeOfdFailureClass.IO, result.failureClass)
        assertEquals(DozeOfdFailureReason.EXCEPTION, result.reason)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun nativeResultFromEarlierStageCannotBeAttachedToAnotherStage() {
        val diagnostic = DozeOfdFixtureDiagnostic()
        diagnostic.stage(DozeOfdFixtureStage.SCRATCH_SETUP)
        diagnostic.recordNative(DozeOfdFixtureStage.APP_NATIVE_LOAD, GuardianOfdNative.Status.ERROR, 13)
        diagnostic.failure(IOException())
        assertEquals(null, diagnostic.snapshot().nativeStatus)
        assertEquals(0, diagnostic.snapshot().nativeErrno)
    }
}
