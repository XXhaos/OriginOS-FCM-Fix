package io.github.originosfcmguardian.doze.gmsbroker

import io.github.originosfcmguardian.core.doze.GmsGlobalDozeOperation
import io.github.originosfcmguardian.doze.GmsDozeBrokerProcessEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fixed command/receipt checks. No Binder instance, Process start or mutation. */
class GmsManualBrokerCommandContractTest {
    private val add = GmsGlobalDozeOperation.ADD
    private val remove = GmsGlobalDozeOperation.REMOVE
    private val complete = GmsDozeBrokerProcessEvidence(true, true, true, true,
        "Added: com.google.android.gms\n", "", false, 0)

    @Test fun commandIsExactlyOneFixedGmsOperation() {
        assertEquals(listOf("/system/bin/cmd", "deviceidle", "whitelist", "+com.google.android.gms"),
            GmsManualBrokerUserService.fixedCommand(add))
        assertEquals(listOf("/system/bin/cmd", "deviceidle", "whitelist", "-com.google.android.gms"),
            GmsManualBrokerUserService.fixedCommand(remove))
    }

    @Test fun onlyExactMatchingSuccessfulLineIsAccepted() {
        assertTrue(GmsManualBrokerUserService.validCommandOutput(add, complete))
        assertTrue(GmsManualBrokerUserService.validCommandOutput(add, complete.copy(stdout = "Added: com.google.android.gms")))
        assertTrue(GmsManualBrokerUserService.validCommandOutput(remove,
            complete.copy(stdout = "Removed: com.google.android.gms\r\n")))
        listOf("", "Added: com.google.android.gms.extra\n", " Added: com.google.android.gms\n",
            "Added: com.google.android.gms\nextra\n", "Added: com.google.android.gms\n\n",
            "Unknown package: com.google.android.gms\n", "Removed: com.google.android.gms\n").forEach {
            assertFalse(it, GmsManualBrokerUserService.validCommandOutput(add, complete.copy(stdout = it)))
        }
    }

    @Test fun incompleteOrFailedCommandCannotBecomeSuccess() {
        listOf(complete.copy(childExited = false), complete.copy(exitCode = null), complete.copy(exitCode = 1),
            complete.copy(stdoutEofAndDrained = false), complete.copy(stderrEofAndDrained = false),
            complete.copy(outputTruncated = true), complete.copy(stderr = "error\n")).forEach {
            assertFalse(GmsManualBrokerUserService.validCommandOutput(add, it))
        }
        // Resource close is checked separately by V2 cleanup/release, never inferred from output.
        assertTrue(GmsManualBrokerUserService.validCommandOutput(add, complete.copy(handlesClosed = false)))
    }
}
