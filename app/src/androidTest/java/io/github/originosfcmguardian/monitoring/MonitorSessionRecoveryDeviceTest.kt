package io.github.originosfcmguardian.monitoring

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Simulates interrupted disk states, not an actual process kill; never uses the production path. */
@RunWith(AndroidJUnit4::class)
class MonitorSessionRecoveryDeviceTest {
    private lateinit var directory: File
    private lateinit var base: File
    private val expected = MonitorSession(
        id = "isolated-recovery",
        identity = SelectedTargetIdentity("example.recovery", 12345, 1000L, listOf("a".repeat(64))),
        startedAtEpochMillis = 2000L,
        deadlineEpochMillis = 62000L,
        status = MonitorSessionStatus.STOPPED,
        events = emptyList(),
        pollCount = 3,
    )

    @Before fun setUp() {
        // Instrumentation runs as the target UID. Use a unique target cache directory,
        // never Guardian's real no_backup store or the other APK's inaccessible sandbox.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "monitor-recovery-${UUID.randomUUID()}")
        check(directory.mkdir())
        base = File(directory, "session.bin")
    }

    @After fun tearDown() {
        if (!::base.isInitialized) return
        // Only the three known test artifacts; no recursive deletion.
        listOf(base, File(base.path + ".bak"), File(base.path + ".new")).forEach {
            check(!it.exists() || it.delete())
        }
        check(directory.delete())
    }

    @Test fun unfinishedReplacementPreservesCommittedSession() {
        val store = MonitorSessionStore(base)
        assertTrue(store.save(expected))
        val committed = base.readBytes()
        // Close without finishWrite/failWrite to reproduce interruption after a partial write.
        AtomicFile(base).startWrite().use { output ->
            output.write(byteArrayOf(1, 2, 3))
            output.flush()
            output.fd.sync()
        }
        assertTrue(File(base.path + ".new").exists())
        assertEquals(expected, (MonitorSessionStore(base).load() as MonitorSessionLoadResult.Available).session)
        assertArrayEquals(committed, base.readBytes())
        assertFalse(File(base.path + ".new").exists())
    }

    @Test fun validLegacyBackupRecoversBeforeUse() {
        val bytes = MonitorSessionCodec.encode(expected)
        File(base.path + ".bak").writeBytes(bytes)
        base.writeBytes(byteArrayOf(1))
        File(base.path + ".new").writeBytes(byteArrayOf(2))
        assertEquals(expected, (MonitorSessionStore(base).load() as MonitorSessionLoadResult.Available).session)
        assertArrayEquals(bytes, base.readBytes())
        assertFalse(File(base.path + ".bak").exists())
        assertFalse(File(base.path + ".new").exists())
    }

    @Test fun orphanPendingIsPreservedAndRefusesOverwrite() {
        val pending = File(base.path + ".new")
        val bytes = MonitorSessionCodec.encode(expected)
        pending.writeBytes(bytes)
        val store = MonitorSessionStore(base)
        assertEquals(MonitorSessionLoadResult.Corrupt, store.load())
        assertFalse(store.save(expected))
        assertArrayEquals(bytes, pending.readBytes())
        assertFalse(base.exists())
    }

    @Test fun corruptBackupCannotBeSilentlyReplacedByValidBase() {
        val bytes = MonitorSessionCodec.encode(expected)
        base.writeBytes(bytes)
        val backup = File(base.path + ".bak")
        val corrupt = byteArrayOf(9, 8, 7)
        backup.writeBytes(corrupt)
        val store = MonitorSessionStore(base)
        assertEquals(MonitorSessionLoadResult.Corrupt, store.load())
        assertFalse(store.save(expected))
        assertArrayEquals(bytes, base.readBytes())
        assertArrayEquals(corrupt, backup.readBytes())
    }
}
