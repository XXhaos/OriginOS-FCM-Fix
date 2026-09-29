package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.DozeDevicePort
import io.github.originosfcmguardian.core.doze.DozeIdentity
import io.github.originosfcmguardian.core.doze.DozeObservation
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import io.github.originosfcmguardian.core.doze.DozeTransactionEngine
import io.github.originosfcmguardian.core.doze.DozeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DozeAdapterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun targetProjectionRequiresCompleteDistinctProfileRows() {
        val projection = parseGmsPackageProjection(PROJECTION)
        assertEquals(setOf(0, 666, 999), projection.profileUserIds)
        assertEquals(setOf(0, 999), projection.installedUserIds)
        assertEquals(10_208, projection.appId)
        assertEquals(-1, projection.pccId)
        // Target dumps also contain bare permission-section headings; projection drops exactly those.
        val runner = DozeCommandRunner({ true }, { 1_000 }, {})
        val full = PROJECTION + "User 0:\nUser 666:\nUser 999:\n"
        val selected = runner.projectPackageLines(ByteArrayInputStream(full.toByteArray()))
        assertEquals(projection, parseGmsPackageProjection(selected))
        assertThrows(IllegalStateException::class.java) {
            val missing = full.replace("User 999: installed=true hidden=false enabled=1\n", "")
            val result = parseGmsPackageProjection(runner.projectPackageLines(ByteArrayInputStream(missing.toByteArray())))
            check(result.profileUserIds == setOf(0, 666, 999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseGmsPackageProjection(PROJECTION.replace("installed=false", "present=false"))
        }
        assertThrows(IllegalStateException::class.java) {
            parseGmsPackageProjection(PROJECTION + "User 0: installed=true enabled=1\n")
        }
    }

    @Test fun completeUserEnumerationAndExactUidOwners() {
        assertEquals(setOf(0, 666, 999), parseAndroidUsers(USERS))
        assertThrows(IllegalStateException::class.java) {
            parseAndroidUsers(USERS + "UserInfo{999:Duplicate:30} running\n")
        }
        assertEquals(setOf("com.google.android.gms"), parseUidOwners("package:com.google.android.gms uid:10208\n", 10_208))
        assertTrue(verifiedEnabledList(
            "package:com.google.android.gms uid:10208\n" +
                "package:com.google.android.gms.policy_sidecar_aps uid:10181\n", 10_208,
        ))
        assertFalse(verifiedEnabledList("package:com.google.android.gms.policy_sidecar_aps uid:10181\n", 10_208))
    }

    @Test fun fixedOwnedPackageProjectionAcceptsDefaultEnabledOnlyWithIndependentEnabledQuery() {
        val owned = PROJECTION
            .replace("com.google.android.gms", DozeTarget.OWNED_DEBUG_TEST.packageName)
            .replace("User 0: installed=true hidden=false enabled=1",
                "User 0: installed=true hidden=false enabled=0")
        val parsed = parsePackageProjection(owned, DozeTarget.OWNED_DEBUG_TEST)
        assertTrue(parsed.userZeroEnabled) // 0 means DEFAULT; the separate -e query must also pass.
        assertTrue(verifiedEnabledList(
            "package:${DozeTarget.OWNED_DEBUG_TEST.packageName} uid:10208\n", 10_208,
            DozeTarget.OWNED_DEBUG_TEST.packageName,
        ))
        assertFalse(verifiedEnabledList("", 10_208, DozeTarget.OWNED_DEBUG_TEST.packageName))
        assertThrows(IllegalStateException::class.java) {
            parsePackageProjection(PROJECTION, DozeTarget.OWNED_DEBUG_TEST)
        }
    }

    @Test fun packageProjectionStreamsLargeIrrelevantDumpAndEnforcesRawBound() {
        val runner = DozeCommandRunner({ true }, { 1_000 }, {})
        val irrelevant = "irrelevant-permission-value=" + "x".repeat(20_000) + "\n"
        val dump = PROJECTION + "User 0:\nUser 666:\nUser 999:\n" + irrelevant.repeat(110)
        assertEquals(PROJECTION, runner.projectPackageLines(ByteArrayInputStream(dump.toByteArray())))
        assertThrows(IllegalStateException::class.java) {
            runner.projectPackageLines(ByteArrayInputStream((dump + irrelevant.repeat(110)).toByteArray()))
        }
    }

    @Test fun committedRecordUsesExactByteCasAndReadback() {
        val access = FakeAccess(folder.newFolder("cas"))
        val store = DozeAtomicJournalStore(access, access.canonicalPath)
        val device = FakeDevice()
        assertEquals(DozeOutcomeCode.APPLIED, DozeTransactionEngine(device, store, FP).apply().code)
        val committed = requireNotNull(store.load())
        assertFalse(store.compareAndSet(null, committed))
        assertTrue(store.load()!!.contentEquals(committed))
        assertEquals(1, device.addCount)
    }

    @Test fun corruptionAndOrphanArtifactsBlockAndPreserveEvidence() {
        val access = FakeAccess(folder.newFolder("corrupt"))
        val store = DozeAtomicJournalStore(access, access.canonicalPath)
        access.base.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(Exception::class.java) { store.load() }
        assertThrows(Exception::class.java) { store.compareAndSet(null, byteArrayOf(4)) }
        assertEquals(listOf<Byte>(1, 2, 3), access.base.readBytes().toList())
        access.base.delete()
        access.pending.writeText("orphan")
        assertThrows(IOException::class.java) { store.load() }
        assertTrue(access.pending.exists())
        access.pending.delete()
        access.backup.writeText("backup")
        assertThrows(IOException::class.java) { store.load() }
        assertTrue(access.backup.exists())
    }

    @Test fun syncFailureNeverDispatchesDeviceWriteAndRetainsArtifact() {
        val access = FakeAccess(folder.newFolder("sync"))
        access.failSync = true
        val store = DozeAtomicJournalStore(access, access.canonicalPath)
        val device = FakeDevice()
        assertEquals(DozeOutcomeCode.UNCERTAIN, DozeTransactionEngine(device, store, FP).apply().code)
        assertEquals(0, device.addCount)
        assertTrue(access.pending.exists())
        assertThrows(IOException::class.java) { store.load() }
    }

    @Test fun hungCallbackCancelsSessionAndLateResultCannotDispatchWrite() {
        val cancelled = AtomicBoolean(false)
        val release = AtomicBoolean(false)
        val callback = BoundedDozeCallback(cancelled, { 40L }, {})
        val device = FakeDevice()
        val journal = object : io.github.originosfcmguardian.core.doze.DozeJournalPort {
            override fun load(): ByteArray? = callback.call {
                while (!release.get()) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { /* Simulate stuck Binder. */ }
                }
                null
            }
            override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean = false
        }
        try {
            assertEquals(DozeOutcomeCode.BLOCKED,
                DozeTransactionEngine(device, journal, FP, mayContinue = { !cancelled.get() }).apply().code)
            assertTrue(cancelled.get())
            assertEquals(0, device.addCount)
        } finally {
            release.set(true)
        }
        Thread.sleep(20)
        assertEquals(0, device.addCount)
    }

    @Test fun lateBindCompletionIsRemovedAfterClientTimeout() {
        val unbindCalls = AtomicInteger()
        val lifecycle = DozeBindLifecycle { unbindCalls.incrementAndGet() }
        lifecycle.beforeDispatch()
        lifecycle.close() // The timed-out public call closes before Shizuku bind returns.
        lifecycle.afterReturn()
        assertEquals(2, unbindCalls.get())
    }

    @Test fun shellServiceExpiryIsOneShotAndExplicitDestroyCancelsTimer() {
        val scheduler = FakeExpiryScheduler()
        val exits = AtomicInteger()
        val expiry = DozeServiceExpiry(2_000, { exits.incrementAndGet() }, scheduler)
        assertEquals(1, scheduler.scheduled)
        assertEquals(65_000L, scheduler.delay)
        scheduler.fire()
        scheduler.fire()
        assertEquals(1, exits.get())
        assertEquals(1, scheduler.cancelled)
        assertFalse(expiry.terminateNow())

        val second = FakeExpiryScheduler()
        val secondExits = AtomicInteger()
        val explicit = DozeServiceExpiry(2_000, { secondExits.incrementAndGet() }, second)
        assertTrue(explicit.terminateNow())
        second.fire()
        assertEquals(1, secondExits.get())
        assertEquals(1, second.cancelled)

        val nonShell = FakeExpiryScheduler()
        val rejected = DozeServiceExpiry(10_000, { error("App process must never exit") }, nonShell)
        assertFalse(rejected.terminateNow())
        assertEquals(0, nonShell.scheduled)
    }

    private class FakeExpiryScheduler : DozeExpiryScheduler {
        var scheduled = 0
        var cancelled = 0
        var delay = 0L
        private var action: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, action: () -> Unit): DozeExpiryCancellation {
            scheduled++
            delay = delayMillis
            this.action = action
            return DozeExpiryCancellation { cancelled++ }
        }
        fun fire() { requireNotNull(action).invoke() }
    }

    private class FakeAccess(directory: File) : DozeJournalFileAccess {
        val base = File(directory, "doze-transaction-v2.bin")
        val backup = File(base.path + ".bak")
        val pending = File(base.path + ".new")
        var failSync = false
        override val canonicalPath: String = base.canonicalPath
        override fun hasAnyArtifact(): Boolean = base.exists() || backup.exists() || pending.exists()
        override fun readStrictlyBounded(maxBytes: Int): ByteArray {
            if (backup.exists() || pending.exists()) throw IOException("Orphaned artifact")
            val bytes = base.readBytes()
            if (bytes.size > maxBytes) throw IOException("Oversized")
            return bytes
        }
        override fun startWrite(): FileOutputStream = FileOutputStream(pending)
        override fun sync(output: FileOutputStream) {
            if (failSync) throw IOException("Injected fsync failure")
            output.fd.sync()
        }
        override fun finishWrite(output: FileOutputStream) {
            output.close()
            check(pending.renameTo(base))
        }
        override fun failWrite(output: FileOutputStream?) { output?.close() }
    }

    private class FakeDevice : DozeDevicePort {
        var addCount = 0
        private var whitelist = "system,android,1000\n"
        override fun probe() = DozeObservation(
            DozeIdentity(FP, 0, 2000, "com.google.android.gms", 10_208, true,
                setOf(0), -1, setOf("com.google.android.gms")), whitelist,
        )
        override fun add() {
            addCount++
            whitelist += "user,com.google.android.gms,10208\n"
        }
        override fun remove() { whitelist = "system,android,1000\n" }
    }

    private companion object {
        const val FP = "test/fingerprint"
        const val PROJECTION = "Package [com.google.android.gms] (a9bfb3e):\n" +
            "appId=10208\npccId=-1\n" +
            "User 0: installed=true hidden=false enabled=1\n" +
            "User 666: installed=false hidden=false enabled=0\n" +
            "User 999: installed=true hidden=false enabled=1\n"
        const val USERS = "Users:\n" +
            "UserInfo{0:Owner:13} running\n" +
            "UserInfo{666:Private:30}\n" +
            "UserInfo{999:Clone:30}\n"
    }
}
