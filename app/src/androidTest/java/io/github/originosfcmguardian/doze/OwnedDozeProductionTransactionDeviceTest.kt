package io.github.originosfcmguardian.doze

import android.app.Instrumentation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.doze.DozeJournalCodec
import io.github.originosfcmguardian.core.doze.DozeOutcomeCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two explicitly invoked parent-controlled stages for the fixed owned test package. Each stage
 * uses the production client/service/engine and real isolated AtomicFile journal. No test cleanup
 * removes a journal, package, or whitelist entry; an uncertain result requires parent review.
 */
@LargeTest
class OwnedDozeProductionTransactionDeviceTest {
    @Test(timeout = 90_000L)
    fun applyAndRecord() {
        val args = approvedArguments("apply") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = OwnedDozeTransactionClient(context, phaseRecorder(context, args, APPLY_PHASES))
        val evidence = Evidence(owned.journalDirectory)
        evidence.requireFreshApplySlot()
        val production = productionArtifacts(context.noBackupFilesDir)
        val before = readWhitelist()
        val baselineRows = parseFullWhitelist(before)
        check(baselineRows.none { it.packageName == OWNED_PACKAGE }) { "Owned target already whitelisted" }
        requireExactOwnedUid(args.uid)
        evidence.writeJson(BASELINE_FILE, JSONObject()
            .put("nonce", args.nonce).put("fingerprint", args.fingerprint).put("uid", args.uid)
            .put("productionArtifacts", production.toJson()).put("beforeRaw", before))

        val result = owned.apply()
        assertEquals("Apply result: ${result.detail}", DozeOutcomeCode.APPLIED, result.code)
        assertEquals("APPLIED", result.phase?.name)
        val appliedRecord = requireNotNull(owned.journalBytes()) { "Applied journal missing" }
        assertTrue(appliedRecord.contentEquals(evidence.readBytes(PHASE_APPLIED)))
        val after = readWhitelist()
        val addedRow = WhitelistRow("user", OWNED_PACKAGE, args.uid)
        assertEquals("Addition must be the sole visible delta", baselineRows + addedRow, parseFullWhitelist(after))
        checkProductionArtifacts(production, context.noBackupFilesDir)
        evidence.writeJson(APPLIED_FILE, JSONObject()
            .put("nonce", args.nonce).put("fingerprint", args.fingerprint).put("uid", args.uid)
            .put("beforeRaw", before).put("appliedRaw", after)
            .put("journalSha256", sha256(appliedRecord)))
    }

    @Test(timeout = 90_000L)
    fun restoreAndRecord() {
        val args = approvedArguments("restore") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = OwnedDozeTransactionClient(context, phaseRecorder(context, args, RESTORE_PHASES))
        val evidence = Evidence(owned.journalDirectory)
        val baseline = evidence.readJson(BASELINE_FILE)
        val applied = evidence.readJson(APPLIED_FILE)
        evidence.requireNoRestoreEvidence()
        verifyStageIdentity(baseline, args)
        verifyStageIdentity(applied, args)
        val production = ArtifactHashes.fromJson(baseline.getJSONObject("productionArtifacts"))
        checkProductionArtifacts(production, context.noBackupFilesDir)
        val appliedRecord = requireNotNull(owned.journalBytes()) { "Applied journal missing" }
        assertTrue(appliedRecord.contentEquals(evidence.readBytes(PHASE_APPLIED)))
        val metadata = journalMetadata(appliedRecord)
        check(metadata.phase == "APPLIED" && metadata.appId == args.uid) { "Applied journal identity changed" }
        val before = baseline.getString("beforeRaw")
        check(applied.getString("beforeRaw") == before) { "Stage baseline evidence changed" }
        val appliedRaw = applied.getString("appliedRaw")
        assertEquals("Applied list drifted before explicit restore", appliedRaw, readWhitelist())
        assertEquals(parseFullWhitelist(before) + WhitelistRow("user", OWNED_PACKAGE, args.uid),
            parseFullWhitelist(appliedRaw))

        val result = owned.restore()
        assertEquals("Restore result: ${result.detail}", DozeOutcomeCode.RESTORED, result.code)
        assertEquals("RESTORED", result.phase?.name)
        val restoredRecord = requireNotNull(owned.journalBytes()) { "Restored journal missing" }
        assertTrue(restoredRecord.contentEquals(evidence.readBytes(PHASE_RESTORED)))
        val after = readWhitelist()
        assertEquals("Complete raw whitelist baseline must be restored", before, after)
        checkProductionArtifacts(production, context.noBackupFilesDir)
        evidence.writeJson(RESTORED_FILE, JSONObject()
            .put("nonce", args.nonce).put("fingerprint", args.fingerprint).put("uid", args.uid)
            .put("beforeRaw", before).put("restoredRaw", after)
            .put("journalSha256", sha256(restoredRecord)))
    }

    private fun approvedArguments(stage: String): StageArgs? {
        val input = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit guardianRunOwnedProductionDoze=true required",
            input.getString("guardianRunOwnedProductionDoze")?.toBooleanStrictOrNull() == true)
        assumeTrue("Exact parent-approved mutation stage required",
            input.getString("guardianOwnedDozeStage") == stage)
        val method = if (stage == "apply") "applyAndRecord" else "restoreAndRecord"
        assumeTrue("Exact class#method invocation required",
            input.getString("class") ==
                "io.github.originosfcmguardian.doze.OwnedDozeProductionTransactionDeviceTest#$method")
        val fingerprint = input.getString("guardianExpectedFingerprint")
        assumeTrue("Exact approved fingerprint required",
            fingerprint == KNOWN_FINGERPRINT && Build.FINGERPRINT == KNOWN_FINGERPRINT)
        val uid = input.getString("guardianExpectedOwnedUid")?.toIntOrNull()
        assumeTrue("Fresh parent-supplied owned UID required", uid != null && uid in 10_000..99_999)
        val nonce = input.getString("guardianOwnedDozeNonce")
        assumeTrue("Caller-chosen nonce of 12-64 safe characters required",
            nonce != null && NONCE.matches(nonce))
        return StageArgs(requireNotNull(fingerprint), requireNotNull(uid), requireNotNull(nonce))
    }

    private fun phaseRecorder(
        context: android.content.Context,
        args: StageArgs,
        expected: List<Pair<String, String>>,
    ): (ByteArray) -> Unit {
        val evidence = Evidence(OwnedDozeStorageContext(context).noBackupFilesDir)
        var index = 0
        return { bytes ->
            check(index < expected.size) { "Unexpected extra durable journal transition" }
            val (phase, file) = expected[index]
            val metadata = journalMetadata(bytes)
            check(metadata.target == "OWNED_DEBUG_TEST" && metadata.phase == phase &&
                metadata.appId == args.uid && metadata.fingerprint == args.fingerprint &&
                metadata.packageName == OWNED_PACKAGE
            ) { "Durable journal identity or phase differs from approved owned stage" }
            evidence.writeBytes(file, bytes)
            index++
        }
    }

    private fun verifyStageIdentity(stage: JSONObject, args: StageArgs) {
        check(stage.getString("nonce") == args.nonce &&
            stage.getString("fingerprint") == args.fingerprint && stage.getInt("uid") == args.uid
        ) { "Stage identity or nonce changed" }
    }

    private fun requireExactOwnedUid(uid: Int) {
        val raw = readShell("cmd package list packages --user 0 --uid $uid -U")
        assertEquals("Fresh user-0 UID must belong only to owned target",
            "package:$OWNED_PACKAGE uid:$uid", raw.trim())
    }

    private fun readWhitelist(): String = readShell("cmd deviceidle whitelist")

    private fun readShell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "owned-doze-read-only-evidence").apply { isDaemon = true }
        }
        try {
            return executor.submit(Callable {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val bytes = input.readNBytes(MAX_SHELL_OUTPUT_BYTES + 1)
                    check(bytes.isNotEmpty() && bytes.size <= MAX_SHELL_OUTPUT_BYTES) {
                        "Read-only shell output missing or oversized"
                    }
                    String(bytes, Charsets.UTF_8)
                }
            }).get(6, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private data class StageArgs(val fingerprint: String, val uid: Int, val nonce: String)
    private data class WhitelistRow(val source: String, val packageName: String, val appId: Int)

    private fun parseFullWhitelist(raw: String): Set<WhitelistRow> {
        check(raw.isNotEmpty() && raw.toByteArray().size <= 128 * 1024) { "Whitelist evidence size invalid" }
        val rows = raw.lineSequence().filter(String::isNotEmpty).map { line ->
            val match = requireNotNull(WHITELIST_ROW.matchEntire(line)) { "Malformed whitelist evidence row" }
            WhitelistRow(match.groupValues[1], match.groupValues[2],
                requireNotNull(match.groupValues[3].toIntOrNull()))
        }.toList()
        check(rows.isNotEmpty() && rows.size == rows.toSet().size) { "Whitelist evidence is empty or duplicated" }
        return rows.toSet()
    }

    private data class JournalMetadata(
        val target: String,
        val phase: String,
        val fingerprint: String,
        val packageName: String,
        val appId: Int,
    )

    private fun journalMetadata(bytes: ByteArray): JournalMetadata {
        DozeJournalCodec.validate(bytes)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 0x444F5A45 && input.readInt() == 2) { "Unexpected journal version" }
            val target = input.readJournalString()
            val phase = input.readJournalString()
            val fingerprint = input.readJournalString()
            check(input.readInt() == 0 && input.readInt() == 2000) { "Wrong journal scope" }
            val packageName = input.readJournalString()
            return JournalMetadata(target, phase, fingerprint, packageName, input.readInt())
        }
    }

    private fun DataInputStream.readJournalString(): String {
        val length = readInt()
        check(length in 0..128 * 1024) { "Journal evidence field oversized" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private data class ArtifactHashes(val values: Map<String, String?>) {
        fun toJson() = JSONObject().also { json ->
            values.forEach { (name, hash) -> json.put(name, hash ?: JSONObject.NULL) }
        }

        companion object {
            fun fromJson(json: JSONObject): ArtifactHashes = ArtifactHashes(
                PRODUCTION_ARTIFACT_NAMES.associateWith { name ->
                    check(json.has(name)) { "Production artifact baseline incomplete" }
                    if (json.isNull(name)) null else json.getString(name)
                },
            )
        }
    }

    private fun productionArtifacts(directory: File): ArtifactHashes = ArtifactHashes(
        PRODUCTION_ARTIFACT_NAMES.associateWith { name ->
            val file = File(directory, name)
            if (!file.exists()) null else {
                check(file.isFile && file.length() in 0..1024 * 1024) { "Production artifact size invalid" }
                sha256(file.readBytes())
            }
        },
    )

    private fun checkProductionArtifacts(before: ArtifactHashes, directory: File) {
        assertEquals("One of six production artifacts changed", before, productionArtifacts(directory))
    }

    private class Evidence(private val directory: File) {
        fun requireFreshApplySlot() {
            check(directory.isDirectory) { "Owned evidence directory missing" }
            check(directory.listFiles().orEmpty().isEmpty()) { "Prior owned transaction evidence exists" }
        }

        fun requireNoRestoreEvidence() {
            check(!hasAnyArtifact(PHASE_RESTORE_INTENT) &&
                !hasAnyArtifact(PHASE_RESTORED) && !hasAnyArtifact(RESTORED_FILE)
            ) { "Prior owned restore evidence exists" }
        }

        fun readBytes(name: String): ByteArray {
            val file = File(directory, name)
            check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                "Interrupted evidence write requires parent review: $name"
            }
            check(file.isFile && file.length() in 1..MAX_EVIDENCE_BYTES.toLong()) {
                "Evidence file missing or oversized: $name"
            }
            return file.readBytes()
        }

        fun readJson(name: String) = JSONObject(String(readBytes(name), Charsets.UTF_8))

        fun writeJson(name: String, json: JSONObject) = writeBytes(name, json.toString().toByteArray(Charsets.UTF_8))

        fun writeBytes(name: String, bytes: ByteArray) {
            check(name in FIXED_EVIDENCE_NAMES && bytes.isNotEmpty() && bytes.size <= MAX_EVIDENCE_BYTES) {
                "Evidence name or size invalid"
            }
            val base = File(directory, name)
            check(!hasAnyArtifact(name)) { "Existing evidence must be retained: $name" }
            val atomic = AtomicFile(base)
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(bytes)
                output.flush()
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                if (output != null) runCatching { atomic.failWrite(output) }
                throw failure
            }
            check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                "Evidence fsync/read-back failed: $name"
            }
        }

        private fun hasAnyArtifact(name: String): Boolean {
            val base = File(directory, name)
            return base.exists() || File(base.path + ".bak").exists() ||
                File(base.path + ".new").exists()
        }
    }

    private companion object {
        const val OWNED_PACKAGE = "io.github.originosfcmguardian.test"
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
        const val BASELINE_FILE = "owned-baseline.json"
        const val APPLIED_FILE = "owned-applied.json"
        const val RESTORED_FILE = "owned-restored.json"
        const val PHASE_ADD_INTENT = "phase-add-intent.bin"
        const val PHASE_APPLIED = "phase-applied.bin"
        const val PHASE_RESTORE_INTENT = "phase-restore-intent.bin"
        const val PHASE_RESTORED = "phase-restored.bin"
        const val MAX_EVIDENCE_BYTES = 512 * 1024
        const val MAX_SHELL_OUTPUT_BYTES = 128 * 1024
        val NONCE = Regex("^[A-Za-z0-9_-]{12,64}$")
        val WHITELIST_ROW = Regex("^(system-excidle|system|user),([A-Za-z_][A-Za-z0-9_.]*),(0|[1-9][0-9]*)$")
        val PRODUCTION_ARTIFACT_NAMES = listOf(
            "first-read-only-baseline.bin", "first-read-only-baseline.bin.bak", "first-read-only-baseline.bin.new",
            DozeAtomicJournalStore.FILE_NAME, "${DozeAtomicJournalStore.FILE_NAME}.bak", "${DozeAtomicJournalStore.FILE_NAME}.new",
        )
        val FIXED_EVIDENCE_NAMES = setOf(
            BASELINE_FILE, APPLIED_FILE, RESTORED_FILE,
            PHASE_ADD_INTENT, PHASE_APPLIED, PHASE_RESTORE_INTENT, PHASE_RESTORED,
        )
        val APPLY_PHASES = listOf("ADD_INTENT" to PHASE_ADD_INTENT, "APPLIED" to PHASE_APPLIED)
        val RESTORE_PHASES = listOf("RESTORE_INTENT" to PHASE_RESTORE_INTENT, "RESTORED" to PHASE_RESTORED)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
