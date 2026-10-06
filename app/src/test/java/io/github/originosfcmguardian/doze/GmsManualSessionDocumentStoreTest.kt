package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GmsManualSessionDocumentStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val installation = GmsGlobalInstallation(10208, 123, 100, 200, listOf("a".repeat(64)))
    private val guardian = GmsGlobalGuardianBinding(0, installation.copy(appId = 10493))
    private val consent = GmsGlobalDozeConsent(true, 1, 1, 1000, installation, guardian, "fingerprint")
    private val initial = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(consent))
    private val baseline = "system,android,1000\nuser,com.other.example,11111\n"
    private val transaction = GmsGlobalDozeTransaction("00000000-0000-0000-0000-000000000001", 1,
        installation, 1, baseline, null, DozePhase.ADD_INTENT)
    private val intent = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(consent.copy(generation = 2), transaction))
    private val identity = GmsGlobalDozeIdentity(installation, guardian,
        GmsGlobalEnvironment("fingerprint", 2000, 0), GmsGlobalOwnerEvidence.Qualified(1, "b".repeat(64), 10208))
    private val emptyBroker = GmsDozeBrokerV2Codec.encode(GmsDozeBrokerV2Codec.initialState())
    private val add = GmsDozeBrokerV2Attempt(GmsDozeBrokerV2Grant(
        "00000000-0000-0000-0000-000000000002", GmsGlobalDozeOperation.ADD, transaction.id, 1,
        identity, GmsDozeBrokerV2Codec.rawSha256(baseline), null, GmsDozeBrokerV2Codec.sha256(intent)))
    private val issued = GmsDozeBrokerV2State(1, add, null)
    private val issuedBytes = GmsDozeBrokerV2Codec.encode(issued)
    private val key = GmsDozeBrokerKey("manual-broker", 1)
    private val pinned = issued.copy(revision = 2, add = add.copy(pin = key))

    @Test fun cyclePendingBeforeLedgerAndItsResidueNeverBecomeAnEmptyManualSession() {
        for ((index, suffix) in listOf("", ".new", ".bak").withIndex()) {
            val access = Access(folder.newFolder("cycle-pending-$index"))
            val marker = File(access.directory, "gms-global-doze-cycle.commit-fence$suffix")
            val retained = byteArrayOf(7, 8, 9)
            marker.writeBytes(retained)
            val store = access.store()
            assertThrows(IOException::class.java) { store.withStorage { it.global.compareAndSet(null, initial) } }
            assertFalse(store.control.addsLocallyAllowed())
            assertArrayEquals(retained, marker.readBytes())
            assertFalse(access.fence.exists() || access.base(GmsManualDocument.GLOBAL).exists() ||
                access.base(GmsManualDocument.BROKER).exists())
        }
    }

    /** Real host files, exclusive creation, fd.sync, atomic rename and OS FileLock. Dir fsync is injected. */
    private class Access(val directory: File) : GmsManualSessionFileAccess {
        override val canonicalDirectory = directory.canonicalPath
        val fence = File(directory, GmsManualSessionDocumentStore.SESSION_FENCE_NAME)
        val events = mutableListOf<String>()
        var failAt: String? = null
        var writes = 0
        private var writing: GmsManualDocument? = null
        fun base(document: GmsManualDocument) = File(directory, if (document == GmsManualDocument.GLOBAL)
            GmsManualSessionDocumentStore.GLOBAL_FILE_NAME else GmsManualSessionDocumentStore.BROKER_FILE_NAME)
        fun pending(document: GmsManualDocument) = File(base(document).path + ".new")
        fun store(registry: GmsManualSessionProcessRegistry = GmsManualSessionProcessRegistry()) =
            GmsManualSessionDocumentStore(this, directory, registry)
        private fun event(name: String) {
            events += name
            if (failAt == name) throw IOException("Injected $name failure")
        }
        override fun createAndSyncFence(expected: ByteArray) {
            event("fence-create")
            check(fence.createNewFile())
            FileOutputStream(fence).use {
                event("fence-write"); it.write(expected)
                event("fence-sync"); it.fd.sync()
            }
        }
        override fun validateFence(expected: ByteArray) {
            event("fence-read")
            if (!FileInputStream(fence).use { it.readGlobalGmsDocumentBounded(32) }.contentEquals(expected))
                throw IOException("Fence differs")
        }
        override fun startWrite(document: GmsManualDocument): FileOutputStream {
            assertTrue(fence.isFile && events.contains("directory-sync"))
            writing = document
            event("$document-start")
            check(pending(document).createNewFile())
            writes++
            return FileOutputStream(pending(document))
        }
        override fun sync(output: FileOutputStream) { event("$writing-sync"); output.fd.sync() }
        override fun finishWrite(document: GmsManualDocument, output: FileOutputStream) {
            output.close()
            event("$document-rename")
            Files.move(pending(document).toPath(), base(document).toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        }
        override fun syncDirectory() { event("directory-sync"); event("$writing-directory") }
        override fun readStrictlyBounded(document: GmsManualDocument, maxBytes: Int): ByteArray {
            event("$document-read")
            return FileInputStream(base(document)).use { it.readGlobalGmsDocumentBounded(maxBytes) }
        }
    }

    @Test fun neutralInitialReadsShareControlAndBothFacadesExpire() {
        val access = Access(folder.newFolder("neutral"))
        val registry = GmsManualSessionProcessRegistry()
        val store = access.store(registry)
        val second = access.store(registry)
        assertSame(store.control, second.control)
        File(access.directory, GmsGlobalAtomicDocumentStore.STORAGE_LOCK_NAME).writeBytes(byteArrayOf())
        File(access.directory, GmsDozeBrokerRecordStore.STORAGE_LOCK_NAME).writeBytes(byteArrayOf())
        val legacy = File(access.directory, "doze-transaction-v2.bin").apply { writeBytes(byteArrayOf(7, 8)) }
        lateinit var escaped: GmsManualLockedDocuments
        store.withStorage {
            escaped = it
            assertNull(it.global.load())
            assertArrayEquals(emptyBroker, it.broker.readExact().exactBytes())
            val failure = AtomicReference<Throwable?>()
            val outsider = thread { try { it.global.load() } catch (caught: Throwable) { failure.set(caught) } }
            outsider.join(1000)
            assertFalse(outsider.isAlive)
            assertTrue(failure.get() is IllegalStateException)
        }
        assertThrows(IllegalStateException::class.java) { escaped.global.load() }
        assertThrows(IllegalStateException::class.java) { escaped.global.compareAndSet(null, initial) }
        assertThrows(IllegalStateException::class.java) { escaped.broker.readExact() }
        assertThrows(IllegalStateException::class.java) { escaped.broker.fenceUncertainCommit() }
        second.withStorage { assertNull(it.global.load()) }
        assertTrue(store.control.addsLocallyAllowed())
        assertFalse(access.fence.exists())
        assertEquals(0, access.writes)
        assertArrayEquals(byteArrayOf(7, 8), legacy.readBytes())
    }

    @Test fun permanentFencePrecedesEitherFirstAuthorityAndIndependentProcessCannotRecover() {
        for (first in GmsManualDocument.entries) {
            val access = Access(folder.newFolder("first-$first"))
            val registry = GmsManualSessionProcessRegistry()
            val store = access.store(registry)
            val otherProcess = access.store()
            otherProcess.withStorage { assertNull(it.global.load()) }
            store.withStorage {
                if (first == GmsManualDocument.GLOBAL) assertTrue(it.global.compareAndSet(null, initial))
                else assertArrayEquals(issuedBytes, it.broker.compareAndSet(emptyBroker, issued))
            }
            assertEquals(32, access.fence.length().toInt())
            val prefix = access.events.take(5)
            assertEquals(listOf("fence-create", "fence-write", "fence-sync", "directory-sync", "null-directory"), prefix)
            assertTrue(access.events.indexOf("fence-read") < access.events.indexOf("$first-start"))
            assertThrows(IOException::class.java) { otherProcess.withStorage { it.broker.readExact() } }
            assertThrows(IOException::class.java) { access.store().withStorage { it.global.load() } }
            assertTrue(store.control.addsLocallyAllowed())
            store.withStorage { assertArrayEquals(if (first == GmsManualDocument.GLOBAL) initial else null, it.global.load()) }
        }
        val access = Access(folder.newFolder("fence-failure"))
        val store = access.store()
        access.failAt = "fence-sync"
        assertThrows(IOException::class.java) { store.withStorage { it.global.compareAndSet(null, initial) } }
        assertTrue(access.fence.exists())
        assertEquals(0, access.writes)
        assertFalse(store.control.addsLocallyAllowed())
        access.failAt = null
        assertThrows(IOException::class.java) { access.store().withStorage { it.global.load() } }
    }

    @Test fun eitherDocumentFailureClosesBothEvenIfCaughtAndTwoCasGapStaysRetained() {
        for (failed in GmsManualDocument.entries) {
            val access = Access(folder.newFolder("failure-$failed"))
            val registry = GmsManualSessionProcessRegistry()
            val store = access.store(registry)
            store.withStorage {
                assertTrue(it.global.compareAndSet(null, initial))
                assertArrayEquals(issuedBytes, it.broker.compareAndSet(emptyBroker, issued))
            }
            access.failAt = if (failed == GmsManualDocument.GLOBAL) "GLOBAL-sync" else "BROKER-directory"
            store.withStorage {
                assertThrows(IOException::class.java) {
                    if (failed == GmsManualDocument.GLOBAL) it.global.compareAndSet(initial, intent)
                    else it.broker.compareAndSet(issuedBytes, pinned)
                }
                assertThrows(IOException::class.java) { it.global.load() }
                assertThrows(IOException::class.java) { it.broker.readExact() }
            }
            assertFalse(store.control.addsLocallyAllowed())
            access.failAt = null
            assertThrows(IOException::class.java) { access.store(registry).withStorage { it.global.load() } }
            assertThrows(IOException::class.java) { access.store().withStorage { it.broker.readExact() } }
            if (failed == GmsManualDocument.GLOBAL) {
                assertArrayEquals(intent, access.pending(failed).readBytes())
                assertArrayEquals(initial, access.base(failed).readBytes())
            } else assertArrayEquals(GmsDozeBrokerV2Codec.encode(pinned), access.base(failed).readBytes())
            assertTrue(access.fence.exists())
        }
        val gap = Access(folder.newFolder("two-cas-gap"))
        val store = gap.store()
        assertThrows(IOException::class.java) {
            store.withStorage {
                assertTrue(it.global.compareAndSet(null, initial))
                gap.failAt = "BROKER-sync"
                it.broker.compareAndSet(emptyBroker, issued)
            }
        }
        assertArrayEquals(initial, gap.base(GmsManualDocument.GLOBAL).readBytes())
        assertArrayEquals(issuedBytes, gap.pending(GmsManualDocument.BROKER).readBytes())
        assertFalse(gap.base(GmsManualDocument.BROKER).exists())
        assertFalse(store.control.addsLocallyAllowed())
    }

    @Test fun oldPendingMissingOrUnexpectedMaterialNeverResetsOrRecreatesAuthority() {
        val names = GmsManualSessionDocumentStore.RETIRED_AUTHORITY_NAMES + listOf(
            GmsManualSessionDocumentStore.GLOBAL_FILE_NAME,
            GmsManualSessionDocumentStore.GLOBAL_FILE_NAME + ".new",
            GmsManualSessionDocumentStore.GLOBAL_FILE_NAME + ".bak",
            GmsManualSessionDocumentStore.BROKER_FILE_NAME,
            GmsManualSessionDocumentStore.BROKER_FILE_NAME + ".new",
            GmsManualSessionDocumentStore.BROKER_FILE_NAME + ".bak",
            GmsManualSessionDocumentStore.SESSION_FENCE_NAME,
        )
        names.forEachIndexed { index, name ->
            val access = Access(folder.newFolder("old-$index"))
            val evidence = File(access.directory, name).apply { writeBytes(byteArrayOf(9, 8, 7)) }
            val store = access.store()
            assertThrows(IOException::class.java) { store.withStorage { it.global.compareAndSet(null, initial) } }
            assertArrayEquals(byteArrayOf(9, 8, 7), evidence.readBytes())
            assertEquals(0, access.writes)
            assertFalse(store.control.addsLocallyAllowed())
        }
        for (drift in listOf("missing", "fence", "legacy", "oversized")) {
            val access = Access(folder.newFolder("drift-$drift"))
            val store = access.store()
            store.withStorage { assertTrue(it.global.compareAndSet(null, initial)) }
            when (drift) {
                "missing" -> assertTrue(access.base(GmsManualDocument.GLOBAL).delete())
                "fence" -> access.fence.writeBytes(ByteArray(32))
                "legacy" -> File(access.directory, "gms-doze-broker-v1.bin").writeBytes(byteArrayOf(3))
                else -> access.base(GmsManualDocument.GLOBAL).writeBytes(ByteArray(GmsGlobalDozeCodec.MAX_BYTES + 1))
            }
            assertThrows(Exception::class.java) { store.withStorage { it.broker.readExact() } }
            assertTrue(access.fence.exists())
            assertFalse(store.control.addsLocallyAllowed())
        }
    }

    @Test fun exactCasClosedAddControlAndShortStorageLocksRemainIndependent() {
        val access = Access(folder.newFolder("cas"))
        val registry = GmsManualSessionProcessRegistry()
        val store = access.store(registry)
        store.withStorage {
            assertFalse(it.global.compareAndSet(byteArrayOf(1), initial))
            assertNull(it.broker.compareAndSet(byteArrayOf(1), issued))
        }
        assertFalse(access.fence.exists())
        store.withStorage {
            assertTrue(it.global.compareAndSet(null, initial))
            assertTrue(it.global.compareAndSet(initial, intent))
            assertArrayEquals(issuedBytes, it.broker.compareAndSet(emptyBroker, issued))
            assertArrayEquals(GmsDozeBrokerV2Codec.encode(pinned), it.broker.compareAndSet(issuedBytes, pinned))
            it.global.load()!!.fill(0)
            assertArrayEquals(intent, it.global.load())
        }
        store.control.closeAdds()
        val prepared = pinned.copy(revision = 3, add = pinned.add!!.copy(preparation =
            GmsDozeBrokerV2Preparation(key, add.grant.grantId, 3, 0)))
        val applied = GmsGlobalDozeCodec.encode(GmsGlobalDozeDocument(consent.copy(generation = 3),
            transaction.copy(phase = DozePhase.APPLIED, applied = baseline + "user,com.google.android.gms,10208\n")))
        store.withStorage {
            assertNull(it.broker.compareAndSet(GmsDozeBrokerV2Codec.encode(pinned), prepared))
            val revoked = pinned.copy(revision = 3, add = pinned.add!!.copy(revokeVersion = 1))
            assertArrayEquals(GmsDozeBrokerV2Codec.encode(revoked),
                it.broker.compareAndSet(GmsDozeBrokerV2Codec.encode(pinned), revoked))
            assertTrue(it.global.compareAndSet(intent, applied)) // Retained in-flight result, despite local OFF.
            val off = GmsGlobalDozeCodec.decode(applied).let {
                GmsGlobalDozeCodec.encode(it.copy(consent = it.consent.copy(enabled = false, epoch = 2, generation = 4)))
            }
            assertTrue(it.global.compareAndSet(applied, off))
        }
        val closedAccess = Access(folder.newFolder("closed-fresh"))
        val closed = closedAccess.store()
        closed.control.closeAdds()
        closed.withStorage {
            assertFalse(it.global.compareAndSet(null, initial))
            assertNull(it.broker.compareAndSet(emptyBroker, issued))
        }
        assertFalse(closedAccess.fence.exists())

        val concurrent = Access(folder.newFolder("concurrent"))
        val shared = GmsManualSessionProcessRegistry()
        val first = concurrent.store(shared)
        val second = concurrent.store(shared)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startedSecond = CountDownLatch(1)
        val enteredSecond = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val owner = thread { try { first.withStorage { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
            catch (caught: Throwable) { failure.set(caught) } }
        var contender: Thread? = null
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            contender = thread { try { startedSecond.countDown(); second.withStorage { enteredSecond.countDown() } }
                catch (caught: Throwable) { failure.set(caught) } }
            assertTrue(startedSecond.await(1, TimeUnit.SECONDS))
            assertFalse(enteredSecond.await(30, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); owner.join(1000); contender?.join(1000) }
        assertNull(failure.get())
        assertEquals(0L, enteredSecond.count)
        RandomAccessFile(File(concurrent.directory, GmsManualSessionDocumentStore.STORAGE_LOCK_NAME), "rw").use {
            it.channel.lock().use {
                assertThrows(IOException::class.java) { first.withStorage { error("OS lock must prevent callback") } }
            }
        }
        assertFalse(first.control.addsLocallyAllowed())
        assertSame(first.control, second.control)
    }
}
