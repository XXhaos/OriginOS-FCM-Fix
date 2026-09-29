package io.github.originosfcmguardian.monitoring

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.core.trace.ProcessTraceEvent
import io.github.originosfcmguardian.core.trace.ProcessTraceKind
import io.github.originosfcmguardian.core.trace.ProcessTraceTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSessionStoreTest {
    @Test
    fun roundTripPreservesRawRunningStateAndExactIdentity() {
        val access = FakeMonitorSessionFileAccess()
        val store = store(access)
        val expected = session()

        assertTrue(store.save(expected))

        val actual = (store.load() as MonitorSessionLoadResult.Available).session
        assertEquals(expected, actual)
        assertEquals(MonitorSessionStatus.RUNNING, actual.status)
        assertEquals(identity.firstInstallTime, actual.identity.firstInstallTime)
        assertEquals(identity.signerSha256, actual.identity.signerSha256)
    }

    @Test
    fun corruptArtifactRefusesOverwriteUntilExplicitClear() {
        val corrupt = "not-a-monitor-session".toByteArray()
        val access = FakeMonitorSessionFileAccess().apply {
            exists = true
            bytes = corrupt.copyOf()
        }
        val store = store(access)

        assertEquals(MonitorSessionLoadResult.Corrupt, store.load())
        assertFalse(store.save(session()))
        assertEquals(0, access.startCount)
        assertArrayEquals(corrupt, access.bytes)

        assertTrue(store.clear())
        assertEquals(MonitorSessionLoadResult.Empty, store.load())
        assertTrue(store.save(session()))
    }

    @Test
    fun validCommittedBaseRecoversAnInterruptedPendingWrite() {
        val committed = MonitorSessionCodec.encode(session())
        val access = FakeMonitorSessionFileAccess().apply {
            baseBytes = committed.copyOf()
            pendingBytes = "partial-next-session".toByteArray()
        }
        val store = store(access)

        assertEquals(session(), (store.load() as MonitorSessionLoadResult.Available).session)
        assertArrayEquals(committed, access.baseBytes)
        assertNull(access.pendingBytes)
        assertEquals(1, access.confirmCount)
    }

    @Test
    fun validLegacyBackupIsAuthoritativeAndRecoveredBeforeUse() {
        val committed = MonitorSessionCodec.encode(session())
        val access = FakeMonitorSessionFileAccess().apply {
            baseBytes = "unfinished-replacement".toByteArray()
            backupBytes = committed.copyOf()
            pendingBytes = "partial-new".toByteArray()
        }
        val store = store(access)

        assertEquals(session(), (store.load() as MonitorSessionLoadResult.Available).session)
        assertArrayEquals(committed, access.baseBytes)
        assertNull(access.backupBytes)
        assertNull(access.pendingBytes)
        assertEquals(1, access.confirmCount)
    }

    @Test
    fun malformedCommittedCandidateAndOrphanPendingBytesRemainFenced() {
        val malformed = "malformed-committed".toByteArray()
        val pending = MonitorSessionCodec.encode(session())
        val access = FakeMonitorSessionFileAccess().apply {
            baseBytes = malformed.copyOf()
            pendingBytes = pending.copyOf()
        }
        val store = store(access)

        assertEquals(MonitorSessionLoadResult.Corrupt, store.load())
        assertFalse(store.save(session()))
        assertArrayEquals(malformed, access.baseBytes)
        assertArrayEquals(pending, access.pendingBytes)
        assertEquals(0, access.confirmCount)
        assertEquals(0, access.startCount)

        val orphan = FakeMonitorSessionFileAccess().apply { pendingBytes = pending.copyOf() }
        val orphanStore = store(orphan)
        assertEquals(MonitorSessionLoadResult.Corrupt, orphanStore.load())
        assertFalse(orphanStore.save(session()))
        assertArrayEquals(pending, orphan.pendingBytes)
        assertEquals(0, orphan.confirmCount)
    }

    @Test
    fun malformedLegacyBackupRemainsAuthoritativeAndPreserved() {
        val validBase = MonitorSessionCodec.encode(session())
        val malformedBackup = "bad-legacy-backup".toByteArray()
        val access = FakeMonitorSessionFileAccess().apply {
            baseBytes = validBase.copyOf()
            backupBytes = malformedBackup.copyOf()
        }
        val store = store(access)

        assertEquals(MonitorSessionLoadResult.Corrupt, store.load())
        assertFalse(store.save(session()))
        assertArrayEquals(validBase, access.baseBytes)
        assertArrayEquals(malformedBackup, access.backupBytes)
        assertEquals(0, access.confirmCount)
        assertEquals(0, access.startCount)
    }

    @Test
    fun malformedTruncatedOversizedAndCrossTargetPayloadsAreRejected() {
        val encoded = MonitorSessionCodec.encode(session())
        assertNull(MonitorSessionCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(MonitorSessionCodec.decode(ByteArray(MonitorSessionStore.MAX_FILE_BYTES + 1)))

        val crossTargetBytes = encoded.copyOf()
        val packageBytes = identity.packageName.toByteArray()
        val replacement = "org.example.otherx".toByteArray()
        check(packageBytes.size == replacement.size)
        var occurrence = 0
        for (index in 0..crossTargetBytes.size - packageBytes.size) {
            if (crossTargetBytes.copyOfRange(index, index + packageBytes.size).contentEquals(packageBytes)) {
                occurrence += 1
                if (occurrence == 2) {
                    replacement.copyInto(crossTargetBytes, index)
                    break
                }
            }
        }
        assertEquals(2, occurrence)
        assertNull(MonitorSessionCodec.decode(crossTargetBytes))
    }

    @Test
    fun failedAtomicWriteLeavesNoClaimOfSavedState() {
        val access = FakeMonitorSessionFileAccess().apply { failStart = true }
        val store = store(access)

        assertFalse(store.save(session()))
        assertEquals(1, access.failCount)
        assertEquals(MonitorSessionLoadResult.Empty, store.load())
    }

    @Test
    fun codecRejectsDuplicateAndOutOfWindowEventsThroughModelValidation() {
        val valid = session()
        val encoded = MonitorSessionCodec.encode(valid)
        assertEquals(valid, MonitorSessionCodec.decode(encoded))

        val duplicate = valid.events.single()
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(events = listOf(duplicate, duplicate))
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(events = listOf(event(START - 1)))
        }
    }

    private fun session() = MonitorSession(
        id = "session_20260928",
        identity = identity,
        startedAtEpochMillis = START,
        deadlineEpochMillis = START + MonitorSession.MAX_DURATION_MILLIS,
        status = MonitorSessionStatus.RUNNING,
        events = listOf(event(START + 1_000)),
        pollCount = 7,
    )

    private fun event(timestamp: Long) = ProcessTraceEvent(
        timestampEpochMillis = timestamp,
        target = ProcessTraceTarget(identity.packageName, identity.uid),
        kind = ProcessTraceKind.PROCESS_START,
        pid = 77,
    )

    private fun store(access: FakeMonitorSessionFileAccess): MonitorSessionStore =
        MonitorSessionStore(File(access.canonicalPath), access)

    private class FakeMonitorSessionFileAccess : MonitorSessionFileAccess {
        private val directory = Files.createTempDirectory("guardian-monitor-session").toFile()
        private val pending = File(directory, "pending")
        override val canonicalPath: String = File(directory, "session").canonicalPath
        var baseBytes: ByteArray? = null
        var backupBytes: ByteArray? = null
        var pendingBytes: ByteArray? = null
        var startCount = 0
        var failCount = 0
        var confirmCount = 0
        var failStart = false

        var exists: Boolean
            get() = baseBytes != null || backupBytes != null || pendingBytes != null
            set(value) {
                if (!value) {
                    baseBytes = null
                    backupBytes = null
                    pendingBytes = null
                } else if (!exists) {
                    baseBytes = ByteArray(0)
                }
            }

        var bytes: ByteArray
            get() = baseBytes ?: ByteArray(0)
            set(value) {
                baseBytes = value
            }

        override fun hasAnyArtifact(): Boolean = exists

        override fun startWrite(): FileOutputStream {
            startCount += 1
            if (failStart) throw IOException("injected start failure")
            return FileOutputStream(pending)
        }

        override fun finishWrite(output: FileOutputStream) {
            output.close()
            baseBytes = pending.readBytes()
        }

        override fun failWrite(output: FileOutputStream?) {
            failCount += 1
            runCatching { output?.close() }
            pending.delete()
        }

        override fun readBounded(maxBytes: Int): ByteArray = when {
            backupBytes != null -> backupBytes!!.copyOf()
            baseBytes != null -> baseBytes!!.copyOf()
            else -> throw IOException("no committed candidate")
        }

        override fun confirmValidatedRead(expected: ByteArray, maxBytes: Int): Boolean {
            confirmCount += 1
            backupBytes?.let { baseBytes = it.copyOf() }
            backupBytes = null
            pendingBytes = null
            return baseBytes?.contentEquals(expected) == true
        }

        override fun clearArtifacts() {
            baseBytes = null
            backupBytes = null
            pendingBytes = null
            pending.delete()
        }
    }

    private companion object {
        const val START = 1_800_000_000_000L
        val identity = SelectedTargetIdentity(
            packageName = "org.example.target",
            uid = 10_321,
            firstInstallTime = 1_700_000_000_000L,
            signerSha256 = listOf("a".repeat(64), "b".repeat(64)),
        )
    }
}
