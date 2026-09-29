package io.github.originosfcmguardian.doze

import android.os.Build
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit parent-run device check. It exercises the production service/client while refusing every
 * journal CAS, so even a changed GMS state cannot reach a DeviceIdle addition.
 */
@LargeTest
class GmsDozeNoWriteDeviceTest {
    @Test(timeout = 60_000L)
    fun healthyGmsIsNoWriteThroughProductionBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Explicit guardianRunGmsDozeNoWrite=true is required",
            arguments.getString("guardianRunGmsDozeNoWrite")?.toBooleanStrictOrNull() == true,
        )
        assumeTrue(
            "Exact approved fingerprint is required",
            arguments.getString("guardianExpectedFingerprint") == KNOWN_FINGERPRINT &&
                Build.FINGERPRINT == KNOWN_FINGERPRINT,
        )
        val context = instrumentation.targetContext
        val watched = listOf("first-read-only-baseline.bin", DozeAtomicJournalStore.FILE_NAME)
            .flatMap { name -> listOf(name, "$name.bak", "$name.new") }
            .associateWith { name -> File(context.noBackupFilesDir, name).takeIf(File::exists)?.readBytes() }
        val casCalls = AtomicInteger()
        val realJournal = DozeAtomicJournalStore(context)
        val denyingJournal = object : DozeJournalPort {
            override fun load(): ByteArray? = realJournal.load()
            override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
                casCalls.incrementAndGet()
                return false
            }
        }

        val outcome = DozeTransactionClient(context, denyingJournal, testOnly = true).apply()
        assertEquals("Only healthy GMS can pass this opt-in check: ${outcome.detail.take(192)}",
            DozeOutcomeCode.NO_WRITE, outcome.code)
        assertEquals("No journal commit may be attempted", 0, casCalls.get())
        watched.forEach { (name, before) ->
            val after = File(context.noBackupFilesDir, name).takeIf(File::exists)?.readBytes()
            assertTrue("Journal or immutable baseline changed: $name", sameBytes(before, after))
        }
    }

    private fun sameBytes(first: ByteArray?, second: ByteArray?): Boolean = when {
        first == null || second == null -> first == null && second == null
        else -> first.contentEquals(second)
    }

    private companion object {
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}
