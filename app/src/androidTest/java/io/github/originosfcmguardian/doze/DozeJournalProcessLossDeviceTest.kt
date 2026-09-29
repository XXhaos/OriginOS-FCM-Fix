package io.github.originosfcmguardian.doze

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.doze.DozeDevicePort
import io.github.originosfcmguardian.core.doze.DozeIdentity
import io.github.originosfcmguardian.core.doze.DozeJournalCodec
import io.github.originosfcmguardian.core.doze.DozeJournalPort
import io.github.originosfcmguardian.core.doze.DozeObservation
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import io.github.originosfcmguardian.core.doze.DozePhase
import io.github.originosfcmguardian.core.doze.DozeTarget
import io.github.originosfcmguardian.core.doze.DozeTransactionEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/**
 * Explicit, parent-run storage checks. These only use app-private files and a synthetic port.
 * A kill stage intentionally fails its instrumentation invocation when its own process dies.
 * Run each named method separately, in order, with the same nonce and exact device fingerprint.
 */
@LargeTest
class DozeJournalProcessLossDeviceTest {
    @Test(timeout = 30_000L)
    fun stageCommittedIntentAndKill() {
        val env = optIn("stageCommittedIntentAndKill")
        assertFalse("Existing isolated evidence must be reviewed before a new run", env.root.exists())
        val watched = snapshotProduction(env)
        assertTrue(env.root.mkdir())
        val store = isolatedStore(env)
        assertEquals(null, store.load())

        val port = FakePort(BASELINE)
        var checks = 0
        val outcome = engine(port, store) { ++checks < 3 }.apply()
        assertEquals(DozeOutcomeCode.UNCERTAIN, outcome.code)
        assertEquals(DozePhase.ADD_INTENT, outcome.phase)
        assertEquals(0, port.adds)
        assertEquals(0, port.removes)
        val intent = requireNotNull(store.load())
        DozeJournalCodec.validate(intent)
        assertFalse("Stale CAS must refuse the retained record", store.compareAndSet(null, intent))
        assertArrayEquals(intent, isolatedStore(env).load())

        assertProductionUnchanged(env, watched)

        writeMarker(env, Marker("ADD_INTENT", Process.myPid(), sha256(intent), watched))
        killSelf()
    }

    @Test(timeout = 30_000L)
    fun verifyIntentThenStagePendingNewAndKill() {
        val env = optIn("verifyIntentThenStagePendingNewAndKill")
        val previous = readMarker(env, "ADD_INTENT")
        assertTrue("A different Android process must reopen the journal", previous.pid != Process.myPid())
        assertProductionUnchanged(env, previous.watched)
        val store = isolatedStore(env)
        val intent = requireNotNull(store.load())
        assertEquals(previous.journalHash, sha256(intent))
        assertIntentRefusal(store, BASELINE, DozePhase.ADD_INTENT)
        assertArrayEquals(intent, store.load())

        // Generate the legal next record through the core engine with a synthetic port.
        // Its simulated add changes only this in-memory observation, never system policy.
        val fixture = MemoryJournal()
        val fixturePort = FakePort(BASELINE)
        assertEquals(DozeOutcomeCode.APPLIED, engine(fixturePort, fixture).apply().code)
        assertEquals(1, fixturePort.adds)
        assertArrayEquals(intent, fixture.firstCommit)
        val applied = requireNotNull(fixture.load())
        assertTrue(store.compareAndSet(intent, applied))
        assertArrayEquals(applied, isolatedStore(env).load())
        assertFalse(store.compareAndSet(intent, applied))

        val restorePort = FakePort(APPLIED)
        var checks = 0
        val restore = engine(restorePort, store) { ++checks < 2 }.restore()
        assertEquals(DozeOutcomeCode.UNCERTAIN, restore.code)
        assertEquals(DozePhase.RESTORE_INTENT, restore.phase)
        assertEquals(0, restorePort.removes)
        val restoreIntent = requireNotNull(isolatedStore(env).load())
        assertIntentRefusal(store, APPLIED, DozePhase.RESTORE_INTENT)
        assertArrayEquals(restoreIntent, store.load())

        // Leave AtomicFile's real pending artifact open after bytes and fd have reached disk.
        val pendingOutput = AtomicFile(env.journal).startWrite()
        pendingOutput.write(byteArrayOf(0x4f, 0x52, 0x50, 0x48, 0x41, 0x4e))
        pendingOutput.fd.sync()
        assertTrue(env.pending.exists())
        assertProductionUnchanged(env, previous.watched)
        writeMarker(env, Marker("PENDING_NEW", Process.myPid(), sha256(restoreIntent),
            previous.watched, sha256(env.pending.readBytes())))
        // Deliberately do not call finishWrite/failWrite or close the stream before process loss.
        killSelf()
    }

    @Test(timeout = 30_000L)
    fun verifyPendingNewAndWriteReport() {
        val env = optIn("verifyPendingNewAndWriteReport")
        val previous = readMarker(env, "PENDING_NEW")
        assertTrue("A different Android process must reopen the pending file", previous.pid != Process.myPid())
        assertProductionUnchanged(env, previous.watched)
        val baseBefore = env.journal.readBytes()
        val pendingBefore = env.pending.readBytes()
        assertEquals(previous.journalHash, sha256(baseBefore))
        assertEquals(previous.pendingHash, sha256(pendingBefore))
        assertThrows(IOException::class.java) { isolatedStore(env).load() }
        val port = FakePort(APPLIED)
        val engine = engine(port, isolatedStore(env))
        assertEquals(DozeOutcomeCode.BLOCKED, engine.inspect().code)
        assertEquals(DozeOutcomeCode.BLOCKED, engine.apply().code)
        assertEquals(DozeOutcomeCode.BLOCKED, engine.restore().code)
        assertEquals(0, port.adds)
        assertEquals(0, port.removes)
        assertArrayEquals(baseBefore, env.journal.readBytes())
        assertArrayEquals(pendingBefore, env.pending.readBytes())
        assertProductionUnchanged(env, previous.watched)
        val report = """{"schema":1,"result":"PASS","nonce":"${env.nonce}","fingerprintMatch":true,"committedIntentReopened":true,"restoreIntentRefused":true,"pendingNewPreserved":true,"recoveryFakeWrites":0,"fixtureSyntheticAdds":1,"systemPolicyCalls":0,"baseSha256":"${previous.journalHash}","pendingSha256":"${previous.pendingHash}"}"""
        writeSynced(env.report, report.toByteArray(Charsets.UTF_8))
        writeMarker(env, previous.copy(stage = "COMPLETE", pid = Process.myPid()))
    }

    @Test(timeout = 30_000L)
    fun cleanupOnlyAfterEvidenceArchive() {
        val env = optIn("cleanupOnlyAfterEvidenceArchive")
        val marker = readMarker(env, "COMPLETE")
        assertProductionUnchanged(env, marker.watched)
        assertTrue("Successful report must exist before cleanup", env.report.isFile)
        val allowed = setOf(env.journal.name, env.backup.name, env.pending.name,
            env.marker.name, env.report.name)
        val actual = requireNotNull(env.root.listFiles()).map(File::getName).toSet()
        assertTrue("Unexpected isolated evidence must be retained", allowed.containsAll(actual))
        assertTrue(actual.containsAll(setOf(env.journal.name, env.pending.name,
            env.marker.name, env.report.name)))
        listOf(env.journal, env.backup, env.pending, env.marker, env.report).forEach {
            if (it.exists()) assertTrue("Could not remove known isolated artifact: ${it.name}", it.delete())
        }
        assertTrue(env.root.delete())
        assertProductionUnchanged(env, marker.watched)
    }

    private fun assertIntentRefusal(journal: DozeJournalPort, raw: String, phase: DozePhase) {
        val port = FakePort(raw)
        val engine = engine(port, journal)
        val inspection = engine.inspect()
        assertEquals(DozeOutcomeCode.UNCERTAIN, inspection.code)
        assertEquals(phase, inspection.phase)
        assertEquals(DozeOutcomeCode.BLOCKED, engine.apply().code)
        assertEquals(DozeOutcomeCode.BLOCKED, engine.restore().code)
        assertEquals(0, port.adds)
        assertEquals(0, port.removes)
    }

    private fun engine(port: DozeDevicePort, journal: DozeJournalPort,
        mayContinue: () -> Boolean = { true }) =
        DozeTransactionEngine(port, journal, FINGERPRINT, DozeTarget.OWNED_DEBUG_TEST, mayContinue)

    private class FakePort(raw: String) : DozeDevicePort {
        var observation = DozeObservation(DozeIdentity(FINGERPRINT, 0, 2000,
            DozeTarget.OWNED_DEBUG_TEST.packageName, 10493, true, setOf(0), -1,
            setOf(DozeTarget.OWNED_DEBUG_TEST.packageName)), raw)
        var adds = 0
        var removes = 0
        override fun probe() = observation
        override fun add() {
            adds++
            observation = observation.copy(rawWhitelist = APPLIED)
        }
        override fun remove() {
            removes++
            error("Synthetic port must never remove a device membership")
        }
    }

    private class MemoryJournal : DozeJournalPort {
        private var bytes: ByteArray? = null
        var firstCommit: ByteArray? = null
            private set
        override fun load() = bytes?.clone()
        override fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean {
            if (bytes == null && expected != null || bytes != null && !bytes!!.contentEquals(expected))
                return false
            DozeJournalCodec.validateTransition(bytes, replacement)
            if (firstCommit == null) firstCommit = replacement.clone()
            bytes = replacement.clone()
            return true
        }
    }

    private data class Env(val context: Context, val root: File, val nonce: String) {
        val journal = File(root, DozeAtomicJournalStore.FILE_NAME)
        val backup = File(journal.path + ".bak")
        val pending = File(journal.path + ".new")
        val marker = File(root, "stage.properties")
        val report = File(root, "report.json")
    }

    private data class Marker(val stage: String, val pid: Int, val journalHash: String,
        val watched: Map<String, String>, val pendingHash: String = "")

    private fun optIn(method: String): Env {
        val args = InstrumentationRegistry.getArguments()
        val exactMethod = "${javaClass.name}#$method"
        assumeTrue("Exact class#method stage opt-in is required",
            args.getString("guardianJournalStage") == exactMethod &&
                args.getString("class") == exactMethod)
        assumeTrue("Exact approved fingerprint is required",
            args.getString("guardianExpectedFingerprint") == FINGERPRINT && Build.FINGERPRINT == FINGERPRINT)
        val nonce = args.getString("guardianJournalNonce").orEmpty()
        assumeTrue("A caller-chosen 16-64 character nonce is required", nonce.matches(Regex("[A-Za-z0-9]{16,64}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "doze-journal-process-loss-validation-v1")
        assertEquals(context.noBackupFilesDir.canonicalFile, root.canonicalFile.parentFile)
        return Env(context, root, nonce)
    }

    private fun isolatedStore(env: Env): DozeAtomicJournalStore {
        val isolated = object : ContextWrapper(env.context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = env.root
        }
        val expected = File(env.root, DozeAtomicJournalStore.FILE_NAME).canonicalFile
        assertEquals(env.root.canonicalFile, expected.parentFile)
        assertFalse(expected == File(env.context.noBackupFilesDir, DozeAtomicJournalStore.FILE_NAME).canonicalFile)
        return DozeAtomicJournalStore(isolated)
    }

    private fun snapshotProduction(env: Env): Map<String, String> = WATCHED.associateWith { name ->
        val file = File(env.context.noBackupFilesDir, name)
        if (!file.exists()) "ABSENT" else {
            require(file.isFile && file.length() in 1..DozeJournalCodec.MAX_BYTES.toLong())
            "${file.length()}:${sha256(file.readBytes())}"
        }
    }

    private fun assertProductionUnchanged(env: Env, expected: Map<String, String>) {
        assertEquals("Production journal or immutable baseline changed", expected, snapshotProduction(env))
    }

    private fun writeMarker(env: Env, marker: Marker) {
        val values = Properties().apply {
            setProperty("nonce", env.nonce)
            setProperty("stage", marker.stage)
            setProperty("pid", marker.pid.toString())
            setProperty("journalSha256", marker.journalHash)
            setProperty("pendingSha256", marker.pendingHash)
            marker.watched.forEach { (name, value) -> setProperty("watched.$name", value) }
        }
        FileOutputStream(env.marker).use { output ->
            values.store(output, "Isolated Doze journal validation; no system policy commands")
            output.fd.sync()
        }
    }

    private fun readMarker(env: Env, requiredStage: String): Marker {
        assertTrue("Missing isolated marker; retain evidence", env.marker.isFile)
        require(env.marker.length() in 1..16_384)
        val values = Properties().apply { env.marker.inputStream().use(::load) }
        assertEquals(env.nonce, values.getProperty("nonce"))
        assertEquals(requiredStage, values.getProperty("stage"))
        val watched = WATCHED.associateWith { name ->
            requireNotNull(values.getProperty("watched.$name"))
        }
        return Marker(requiredStage, requireNotNull(values.getProperty("pid")).toInt(),
            requireNotNull(values.getProperty("journalSha256")), watched,
            values.getProperty("pendingSha256").orEmpty())
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun killSelf(): Nothing {
        Process.killProcess(Process.myPid())
        error("killProcess unexpectedly returned without terminating this process")
    }

    private companion object {
        const val FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
        const val BASELINE = "system,android,1000\nuser,com.example.other,10123\n"
        const val APPLIED = BASELINE + "user,io.github.originosfcmguardian.test,10493\n"
        val WATCHED = listOf("first-read-only-baseline.bin", DozeAtomicJournalStore.FILE_NAME)
            .flatMap { listOf(it, "$it.bak", "$it.new") }
    }
}
