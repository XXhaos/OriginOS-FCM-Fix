package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GmsDozeCycleBinding
import io.github.originosfcmguardian.core.doze.GmsDozeCycleAutomaticConsent
import io.github.originosfcmguardian.core.doze.GmsDozeCycleConsent
import io.github.originosfcmguardian.core.doze.GmsDozeCycleCoordinator
import io.github.originosfcmguardian.core.doze.GmsDozeCycleLedger
import io.github.originosfcmguardian.core.doze.GmsDozeCycleLedgerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeCycleReenableConfirmation
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeCodec
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeConsent
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeDocument
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeIdentity
import io.github.originosfcmguardian.core.doze.GmsGlobalDozeObservation
import io.github.originosfcmguardian.core.doze.GmsGlobalEnvironment
import io.github.originosfcmguardian.core.doze.GmsGlobalGuardianBinding
import io.github.originosfcmguardian.core.doze.GmsGlobalInstallation
import io.github.originosfcmguardian.core.doze.GmsGlobalOwnerEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class GmsDozeCycleLedgerStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val gms = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val guardian = GmsGlobalGuardianBinding(0, gms.copy(appId = 10493))
    private val first = GmsDozeCycleLedger("00000000-0000-0000-0000-000000000001", 1, true, 1,
        GmsDozeCycleConsent("00000000-0000-0000-0000-000000000002", 1000),
        GmsDozeCycleBinding(gms, guardian, GmsGlobalEnvironment("fingerprint", 2000, 0)), emptyList())
    private val initial get() = GmsDozeCycleLedgerCodec.encode(first)
    private val off get() = GmsDozeCycleLedgerCodec.encode(first.copy(revision = 2, runningIntent = false, runningGeneration = 2))

    /** Real host file fsync/atomic rename/FileLock. Directory fsync is injected, not Android evidence. */
    private class Access(val directory: File) : GmsDozeCycleLedgerFileAccess {
        override val canonicalDirectory = directory.canonicalPath
        val ledger = File(directory, GmsDozeCycleLedgerStore.FILE_NAME)
        val marker = File(directory, GmsDozeCycleLedgerStore.MARKER_NAME)
        val events = mutableListOf<String>()
        var failAt: String? = null
        var hook: ((String) -> Unit)? = null
        private var lastRenamed: GmsDozeCycleLedgerWrite? = null
        fun reset() { events.clear(); failAt = null; hook = null; lastRenamed = null }
        fun store(registry: GmsDozeCycleLedgerProcessRegistry = GmsDozeCycleLedgerProcessRegistry()) =
            GmsDozeCycleLedgerStore(this, directory, registry)
        private fun base(stage: GmsDozeCycleLedgerWrite) = if (stage == GmsDozeCycleLedgerWrite.LEDGER) ledger else marker
        private fun event(name: String) {
            events += name
            hook?.invoke(name)
            if (failAt == name) throw IOException("Injected $name failure")
        }
        override fun startWrite(stage: GmsDozeCycleLedgerWrite): FileOutputStream {
            if (stage == GmsDozeCycleLedgerWrite.LEDGER) {
                assertTrue(GmsDozeCycleLedgerMarkerCodec.decode(marker.readBytes()).pending)
                assertTrue(events.contains("PENDING-directory") && events.contains("PENDING-readback"))
            }
            event("$stage-start")
            val pending = File(base(stage).path + ".new")
            check(pending.createNewFile())
            return FileOutputStream(pending)
        }
        override fun sync(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream) {
            event("$stage-sync"); output.fd.sync()
        }
        override fun finishWrite(stage: GmsDozeCycleLedgerWrite, output: FileOutputStream) {
            output.close()
            event("$stage-rename")
            Files.move(File(base(stage).path + ".new").toPath(), base(stage).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            lastRenamed = stage
        }
        override fun syncDirectory(stage: GmsDozeCycleLedgerWrite) { event("$stage-directory") }
        override fun readStrictlyBounded(stage: GmsDozeCycleLedgerWrite, maxBytes: Int): ByteArray {
            event(if (lastRenamed == stage) "$stage-readback" else "$stage-read")
            return FileInputStream(base(stage)).use { it.readGlobalGmsDocumentBounded(maxBytes) }
        }
    }

    @Test fun initializationCleanCasAndReconstructedSnapshotUseSamePermanentPaths() {
        val access = Access(folder.newFolder("clean"))
        val registry = GmsDozeCycleLedgerProcessRegistry()
        val store = access.store(registry)
        val sibling = access.store(registry)
        assertSame(store.control, sibling.control)
        assertNotSame(store.control, access.store().control)
        assertNull(store.load())
        assertFalse(access.ledger.exists())
        assertFalse(access.marker.exists())
        // Empty coordination locks are neither consent nor migration artifacts.
        File(access.directory, GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME).createNewFile()
        File(access.directory, GmsDozeBrokerRecordStore.STORAGE_LOCK_NAME).createNewFile()
        assertTrue(store.compareAndSet(null, initial))
        assertArrayEquals(initial, sibling.load())
        val firstMarker = access.marker.readBytes()
        val marker = GmsDozeCycleLedgerMarkerCodec.decode(firstMarker)
        assertFalse(marker.pending)
        assertEquals(first.ledgerId, marker.candidate.ledgerId)
        assertEquals(1L, marker.candidate.revision)
        assertEquals(GmsDozeCycleLedgerCodec.sha256(initial), marker.candidate.sha256)
        assertTrue(access.events.indexOf("PENDING-readback") < access.events.indexOf("LEDGER-start"))
        assertTrue(access.events.indexOf("LEDGER-readback") < access.events.indexOf("CLEAN-start"))
        assertArrayEquals(initial, access.store().load())
        access.reset()
        assertTrue(store.compareAndSet(initial, off))
        assertArrayEquals(off, access.store().load())
        assertEquals(2L, GmsDozeCycleLedgerMarkerCodec.decode(access.marker.readBytes()).candidate.revision)
        assertFalse(firstMarker.contentEquals(access.marker.readBytes()))
        assertTrue(access.marker.isFile)
        GmsDozeCycleLedgerStore.TEMP_NAMES.forEach { assertFalse(File(access.directory, it).exists()) }
        // Same-path explicit re-enable updates the permanent marker; no initialization/reset.
        val coordinator = GmsDozeCycleCoordinator(store, "fingerprint")
        val target = requireNotNull(coordinator.captureOffTarget())
        assertEquals(2L, target.revision)
        assertEquals(GmsDozeCycleLedgerCodec.sha256(off), target.sha256)
        val fresh = GmsGlobalDozeObservation(GmsGlobalDozeIdentity(gms, guardian,
            first.binding.environment, GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), gms.appId)),
            "system,android,1000\nuser,com.other.example,11111\n")
        val enabled = requireNotNull(coordinator.reenableExplicit(GmsDozeCycleReenableConfirmation(
            "00000000-0000-0000-0000-000000000003", 2000, target, 3), fresh, coordinator.captureLocalConsentSequence()))
        assertEquals(first.ledgerId, enabled.ledgerId)
        assertEquals(first.automaticConsent, enabled.automaticConsent)
        assertEquals(first.binding, enabled.binding)
        assertEquals(first.cycles, enabled.cycles)
        val enabledBytes = GmsDozeCycleLedgerCodec.encode(enabled)
        assertArrayEquals(enabledBytes, access.store().load())
        val reenabledMarker = GmsDozeCycleLedgerMarkerCodec.decode(access.marker.readBytes())
        assertFalse(reenabledMarker.pending)
        assertEquals(3L, reenabledMarker.candidate.revision)
        assertEquals(first.ledgerId, reenabledMarker.candidate.ledgerId)
        assertEquals(GmsDozeCycleLedgerCodec.sha256(enabledBytes), reenabledMarker.candidate.sha256)
        assertTrue(access.marker.length() <= GmsDozeCycleLedgerMarkerCodec.MAX_BYTES)
        GmsDozeCycleLedgerStore.TEMP_NAMES.forEach { assertFalse(File(access.directory, it).exists()) }
        // Independent automatic confirmation uses the same schema3 CAS barrier and fixed paths.
        val automaticTarget = requireNotNull(coordinator.captureAutomaticTarget())
        val automatic = GmsDozeCycleAutomaticConsent("00000000-0000-0000-0000-000000000004", 3000, automaticTarget)
        val confirmed = requireNotNull(coordinator.confirmAutomaticExplicit(automatic, fresh,
            requireNotNull(coordinator.captureLocalConsentSequence())))
        assertEquals(first.automaticConsent, confirmed.automaticConsent)
        assertEquals(enabled.latestReenable, confirmed.latestReenable)
        assertEquals(enabled.cycles, confirmed.cycles)
        assertEquals(automatic, confirmed.automaticSessionConsent)
        val automaticBytes = GmsDozeCycleLedgerCodec.encode(confirmed)
        assertArrayEquals(automaticBytes, access.store().load())
        val automaticMarker = GmsDozeCycleLedgerMarkerCodec.decode(access.marker.readBytes())
        assertFalse(automaticMarker.pending)
        assertEquals(4L, automaticMarker.candidate.revision)
        assertEquals(first.ledgerId, automaticMarker.candidate.ledgerId)
        assertEquals(GmsDozeCycleLedgerCodec.sha256(automaticBytes), automaticMarker.candidate.sha256)
        assertTrue(access.marker.length() <= GmsDozeCycleLedgerMarkerCodec.MAX_BYTES)
        GmsDozeCycleLedgerStore.TEMP_NAMES.forEach { assertFalse(File(access.directory, it).exists()) }
    }

    @Test fun everyFixedOldAuthorityAndOldGlobalSchemaBlocksWithoutMigrationOrWrites() {
        for ((index, name) in GmsDozeCycleLedgerStore.RETIRED_AUTHORITY_NAMES.withIndex()) {
            val access = Access(folder.newFolder("retained-$index"))
            val retained = File(access.directory, name)
            val bytes = byteArrayOf(7, 8, 9)
            retained.writeBytes(bytes)
            val registry = GmsDozeCycleLedgerProcessRegistry()
            val store = access.store(registry)
            assertThrows(IOException::class.java) { store.load() }
            assertThrows(IOException::class.java) { access.store(registry).compareAndSet(null, initial) }
            assertArrayEquals(bytes, retained.readBytes())
            assertFalse(access.ledger.exists())
            assertFalse(access.marker.exists())
            assertFalse(access.events.any { it.endsWith("-start") })
        }
        assertTrue(DozeAtomicJournalStore.FILE_NAME in GmsDozeCycleLedgerStore.RETIRED_AUTHORITY_NAMES)
        val access = Access(folder.newFolder("old-global"))
        val old = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(
            GmsGlobalDozeConsent(true, 1, 1, 1000, gms, guardian, "fingerprint")))
        access.ledger.writeBytes(old)
        assertThrows(IOException::class.java) { access.store().load() }
        assertArrayEquals(old, access.ledger.readBytes())
        assertFalse(access.marker.exists())
        // Even a forged CLEAN hash around legacy bytes cannot turn their schema into a ledger.
        access.marker.writeBytes(GmsDozeCycleLedgerMarkerCodec.encode(GmsDozeCycleLedgerMarker(false, null,
            GmsDozeCycleLedgerSnapshot(first.ledgerId, 1, GmsDozeCycleLedgerCodec.sha256(old)))))
        assertThrows(Exception::class.java) { access.store().load() }
        assertArrayEquals(old, access.ledger.readBytes())
    }

    @Test fun pendingTempsMissingMarkerAndExactIdentityRevisionHashMismatchRemainBlocked() {
        val changes: List<(Access) -> Unit> = listOf<(Access) -> Unit>(
            { it.marker.writeBytes(GmsDozeCycleLedgerMarkerCodec.encode(
                GmsDozeCycleLedgerMarker(true, null, GmsDozeCycleLedgerMarkerCodec.decode(it.marker.readBytes()).candidate))) },
            { it.marker.writeBytes(it.marker.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() }) },
            { it.marker.writeBytes(ByteArray(GmsDozeCycleLedgerMarkerCodec.MAX_BYTES + 1)) },
            { assertTrue(it.marker.delete()) },
            { assertTrue(it.ledger.delete()) },
            { a -> val marker = GmsDozeCycleLedgerMarkerCodec.decode(a.marker.readBytes())
                a.marker.writeBytes(GmsDozeCycleLedgerMarkerCodec.encode(marker.copy(candidate = marker.candidate.copy(sha256 = "b".repeat(64))))) },
            { a -> val marker = GmsDozeCycleLedgerMarkerCodec.decode(a.marker.readBytes())
                a.marker.writeBytes(GmsDozeCycleLedgerMarkerCodec.encode(marker.copy(candidate = marker.candidate.copy(ledgerId = "00000000-0000-0000-0000-000000000099")))) },
            { a -> val marker = GmsDozeCycleLedgerMarkerCodec.decode(a.marker.readBytes())
                a.marker.writeBytes(GmsDozeCycleLedgerMarkerCodec.encode(marker.copy(candidate = marker.candidate.copy(revision = 2)))) },
        ) + GmsDozeCycleLedgerStore.TEMP_NAMES.map { name -> { a: Access -> File(a.directory, name).writeBytes(byteArrayOf(4)) } }
        for ((index, change) in changes.withIndex()) {
            val access = Access(folder.newFolder("corrupt-$index"))
            assertTrue(access.store().compareAndSet(null, initial))
            change(access)
            access.reset()
            val retained = access.directory.listFiles()!!.filter { it.name != GmsDozeCycleLedgerStore.STORAGE_LOCK_NAME }
                .associate { it.name to it.readBytes() }
            assertThrows(Exception::class.java) { access.store().load() }
            assertThrows(Exception::class.java) { access.store().compareAndSet(initial, off) }
            retained.forEach { (name, bytes) -> assertArrayEquals(bytes, File(access.directory, name).readBytes()) }
            assertFalse(access.events.any { it.endsWith("-start") })
        }
    }

    @Test fun threeStageFaultsCloseSharedOwnerAndOnlyCompleteCleanAllowsDifferentProcessSnapshot() {
        for (stage in GmsDozeCycleLedgerWrite.entries) for (point in listOf("start", "sync", "rename", "directory", "readback")) {
            val access = Access(folder.newFolder("fault-$stage-$point"))
            val registry = GmsDozeCycleLedgerProcessRegistry()
            val store = access.store(registry)
            val sibling = access.store(registry)
            assertTrue(store.compareAndSet(null, initial))
            access.reset()
            access.failAt = "$stage-$point"
            assertThrows(IOException::class.java) { store.compareAndSet(initial, off) }
            assertSame(store.control, sibling.control)
            assertTrue(sibling.control.isTerminal())
            access.reset()
            assertThrows(IOException::class.java) { store.load() }
            assertThrows(IOException::class.java) { sibling.compareAndSet(initial, off) }
            val coherentOld = stage == GmsDozeCycleLedgerWrite.PENDING && point == "start"
            val coherentNew = stage == GmsDozeCycleLedgerWrite.CLEAN && point in listOf("directory", "readback")
            if (coherentOld) assertArrayEquals(initial, access.store().load())
            else if (coherentNew) assertArrayEquals(off, access.store().load())
            else assertThrows(Exception::class.java) { access.store().load() }
            if (stage == GmsDozeCycleLedgerWrite.PENDING) assertArrayEquals(initial, access.ledger.readBytes())
            // OFF may have left old CLEAN when its first barrier failed; no post-hoc repair claim.
            if (coherentOld) assertFalse(GmsDozeCycleLedgerMarkerCodec.decode(access.marker.readBytes()).pending)
        }
        // A direct store failure must poison a token prepared by another same-path coordinator,
        // even when that coordinator never performs another storage read before its start gate.
        val access = Access(folder.newFolder("prepared-token-poison"))
        val registry = GmsDozeCycleLedgerProcessRegistry()
        val store = access.store(registry)
        val sibling = access.store(registry)
        assertTrue(store.compareAndSet(null, initial))
        val coordinator = GmsDozeCycleCoordinator(store, "fingerprint")
        val fresh = GmsGlobalDozeObservation(GmsGlobalDozeIdentity(gms, guardian,
            GmsGlobalEnvironment("fingerprint", 2000, 0), GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), gms.appId)),
            "system,android,1000\nuser,com.other.example,11111\n")
        val grant = requireNotNull(coordinator.prepareAdd(fresh, "prepared-store-poison"))
        assertNotNull(coordinator.recordStartIntent(grant.ref))
        access.reset()
        access.failAt = "LEDGER-read"
        assertThrows(IOException::class.java) { sibling.load() }
        assertTrue(store.control.isTerminal())
        var starts = 0
        assertFalse(coordinator.admitActualStart(grant.ref) { starts++ })
        assertEquals(0, starts)
        access.reset()
    }

    @Test fun failedInitializationNeverTouchesLedgerBeforeSuccessfulPendingReadback() {
        for (point in listOf("start", "sync", "rename", "directory", "readback")) {
            val access = Access(folder.newFolder("initial-$point"))
            val registry = GmsDozeCycleLedgerProcessRegistry()
            val store = access.store(registry)
            access.failAt = "PENDING-$point"
            assertThrows(IOException::class.java) { store.compareAndSet(null, initial) }
            assertFalse(access.ledger.exists())
            assertFalse(access.events.contains("LEDGER-start"))
            access.reset()
            assertThrows(IOException::class.java) { access.store(registry).load() }
            if (point == "start") assertNull(access.store().load())
            else assertThrows(Exception::class.java) { access.store().load() }
        }
        val access = Access(folder.newFolder("invalid-initial"))
        val invalid = GmsDozeCycleLedgerCodec.encode(first.copy(revision = 2))
        assertThrows(Exception::class.java) { access.store().compareAndSet(null, invalid) }
        assertFalse(access.ledger.exists())
        assertFalse(access.marker.exists())
        assertFalse(access.events.any { it.endsWith("-start") })
    }

    @Test fun staleCasExternalOsLockAndArrayOwnershipAreFiniteAndExact() {
        val access = Access(folder.newFolder("exact"))
        val registry = GmsDozeCycleLedgerProcessRegistry()
        val store = access.store(registry)
        assertFalse(store.compareAndSet(initial, off))
        assertFalse(access.marker.exists())
        assertTrue(store.compareAndSet(null, initial))
        access.reset()
        assertFalse(store.compareAndSet(null, off))
        assertFalse(store.compareAndSet(off, off))
        assertFalse(access.events.any { it.endsWith("-start") })
        val read = requireNotNull(store.load())
        read.fill(0)
        assertArrayEquals(initial, store.load())
        val expected = initial
        val replacement = off
        val exactReplacement = replacement.clone()
        access.hook = { event -> if (event == "PENDING-start") { expected.fill(0); replacement.fill(0) } }
        assertTrue(store.compareAndSet(expected, replacement))
        access.reset()
        assertArrayEquals(exactReplacement, store.load())
        val lockedRegistry = GmsDozeCycleLedgerProcessRegistry()
        val blocked = access.store(lockedRegistry)
        RandomAccessFile(File(access.directory, GmsDozeCycleLedgerStore.STORAGE_LOCK_NAME), "rw").use { file ->
            requireNotNull(file.channel.tryLock()).use {
                assertThrows(IOException::class.java) { blocked.load() }
                assertThrows(IOException::class.java) { blocked.compareAndSet(exactReplacement, exactReplacement) }
            }
        }
        assertThrows(IOException::class.java) { access.store(lockedRegistry).load() }
        assertArrayEquals(exactReplacement, access.store().load())
    }
}
