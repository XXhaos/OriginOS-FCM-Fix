package io.github.originosfcmguardian.daemon

import android.os.Bundle
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.autostart.TargetSelectionDecodeResult
import io.github.originosfcmguardian.core.gms.GmsObservationCodec

/** Platform primitives only. Native grants, arbitrary plans, paths and commands are not wire input. */
internal object DaemonWire {
    const val PREFLIGHT = 0
    const val BOOTSTRAP = 1; const val STATUS = 2; const val BEGIN_IMPORT = 3; const val APPEND_IMPORT = 4
    const val FINISH_IMPORT = 5; const val STAGE = 6; const val CURRENT_POINTER = 7; const val RESUME_STAGED = 8
    const val ATTEST_APPLY = 9; const val RECONCILE = 10; const val QUERY = 11
    const val BEGIN_SNAPSHOT = 12; const val SNAPSHOT_CHUNK = 13; const val END_SNAPSHOT = 14
    const val READ_GMS = 15; const val IMPORT_OFFSET = 16; const val RECONCILE_INITIAL_ARM = 17
    const val READ_DIAGNOSTICS = 18
    const val ACTIVATE = 1; const val MASTER_ON = 2; const val MASTER_OFF = 3
    const val TARGET_ON = 4; const val TARGET_OFF = 5; const val FCM_ON = 6; const val FCM_OFF = 7

    fun success(action: Bundle.() -> Unit = {}) = Bundle().apply { putBoolean("ok", true); action() }
    fun failure(reason: String) = Bundle().apply { putBoolean("ok", false); putString("error", reason) }
    fun checkReply(value: Bundle): Bundle { check(value.getBoolean("ok")) { value.getString("error") ?: "UNAVAILABLE" }; return value }
    fun diagnostics(value: DaemonDiagnosticsSnapshot): Bundle = success { putByteArray("diagnostics", DaemonDiagnosticsCodec.encode(value)) }
    @Suppress("DEPRECATION") fun diagnostics(value: Bundle): DaemonDiagnosticsSnapshot {
        checkReply(value); keys(value, "ok", "diagnostics")
        val bytes = value.get("diagnostics") as? ByteArray ?: error("Missing diagnostics")
        require(bytes.size <= DaemonDiagnosticsCodec.MAX_BYTES)
        return DaemonDiagnosticsCodec.decode(bytes)
    }
    fun keys(value: Bundle, vararg allowed: String) {
        value.classLoader = null
        require(value.keySet().all { it in allowed }) { "Unexpected wire field" }
    }
    @Suppress("DEPRECATION") fun text(value: Bundle, key: String, max: Int = 128): String =
        (value.get(key) as? String ?: error("Missing text")).also { require(it.isNotBlank() && it.length <= max && '\u0000' !in it) }
    @Suppress("DEPRECATION") fun long(value: Bundle, key: String): Long = value.get(key) as? Long ?: error("Missing long")
    @Suppress("DEPRECATION") fun int(value: Bundle, key: String): Int = value.get(key) as? Int ?: error("Missing int")
    @Suppress("DEPRECATION") fun nested(value: Bundle, key: String): Bundle = value.get(key) as? Bundle ?: error("Missing bundle")
    @Suppress("DEPRECATION") fun chunk(value: Bundle, key: String): ByteArray =
        (value.get(key) as? ByteArray ?: error("Missing bytes")).also { require(it.size in 1..DaemonImportProtocol.MAX_CHUNK_BYTES) }

    fun record(value: DaemonControlRecord) = Bundle().apply {
        putString("instance", value.owner.installInstance); putLong("epoch", value.owner.ownerEpoch)
        putLong("revision", value.revision); putString("digest", value.authorityDigest)
        putString("mode", value.mode.name); putString("migration", value.migrationId)
    }
    fun record(value: Bundle): DaemonControlRecord {
        keys(value, "instance", "epoch", "revision", "digest", "mode", "migration")
        return DaemonControlRecord(DaemonOwnerKey(text(value, "instance"), long(value, "epoch")), long(value, "revision"),
            text(value, "digest"), DaemonControlMode.valueOf(text(value, "mode")), text(value, "migration"))
    }
    fun pointer(value: DaemonPointerCommitProof) = Bundle().apply { putString("commit", value.commitId); putBundle("record", record(value.record)) }
    fun pointer(value: Bundle): DaemonPointerCommitProof {
        keys(value, "commit", "record"); return DaemonPointerCommitProof(text(value, "commit"), record(nested(value, "record")))
    }
    fun challenge(value: DaemonPointerChallenge) = Bundle().apply {
        putString("nonce", value.nonce); putString("purpose", value.purpose.name)
        putBundle("pointer", pointer(value.pointer)); putString("patch", value.patchDigest)
    }
    fun challenge(value: Bundle): DaemonPointerChallenge {
        keys(value, "nonce", "purpose", "pointer", "patch")
        return DaemonPointerChallenge(text(value, "nonce"), DaemonPointerPurpose.valueOf(text(value, "purpose")),
            pointer(nested(value, "pointer")), text(value, "patch"))
    }
    fun request(value: DaemonControlRequest) = Bundle().apply {
        putString("id", value.requestId); putString("instance", value.owner.installInstance); putLong("epoch", value.owner.ownerEpoch)
        putLong("expected", value.expectedRevision)
        when (val intent = value.intent) {
            is DaemonControlIntent.Activate -> { putInt("kind", ACTIVATE); putBundle("pointer", pointer(intent.pointer)) }
            is DaemonControlIntent.SetAuthority -> { putInt("kind", 0); putBundle("pointer", pointer(intent.pointer)) }
            is DaemonControlIntent.Prepare -> error("Prepare is not an App wire operation")
        }
    }
    fun request(value: Bundle): DaemonControlRequest {
        keys(value, "id", "instance", "epoch", "expected", "kind", "pointer")
        val proof = pointer(nested(value, "pointer"))
        val intent = when (int(value, "kind")) { ACTIVATE -> DaemonControlIntent.Activate(proof)
            0 -> DaemonControlIntent.SetAuthority(proof); else -> error("Unknown request") }
        return DaemonControlRequest(text(value, "id"), DaemonOwnerKey(text(value, "instance"), long(value, "epoch")), long(value, "expected"), intent)
    }
    fun target(value: SelectedTargetIdentity): String = TargetSelectionCodec.encode(listOf(value))
    fun target(value: String): SelectedTargetIdentity =
        ((TargetSelectionCodec.decode(value) as? TargetSelectionDecodeResult.Valid)?.values ?: error("Invalid target"))
            .single()
    fun status(value: DaemonHostStatus) = success {
        putString("status", value.status.name); value.record?.let { putBundle("record", record(it)) }
        value.dataRevision?.let { putLong("dataRevision", it) }; putBoolean("closing", value.closing)
        putBoolean("released", value.leaseReleased); value.sessionStartedElapsed?.let { putLong("sessionElapsed", it) }
        putBoolean("needsReconciliation", value.needsReconciliation)
        value.sessionStartedAtMillis?.let { putLong("sessionWall", it) }; value.lastCheckedAtMillis?.let { putLong("checked", it) }
        value.latestGmsObservation?.let { putString("gms", GmsObservationCodec.encode(it)); putLong("gmsRequestId", it.requestId) }
        value.recoveryReadiness?.let { evidence -> putBundle("recoveryReadiness", Bundle().apply {
            putBundle("record", record(evidence.record)); putLong("requestId", evidence.sampleRequestId)
            putLong("sampleElapsed", evidence.sampleElapsedMillis); putLong("checkedElapsed", evidence.checkedAtElapsedMillis)
            putBoolean("admitted", evidence.admitted)
        }) }
    }
    fun appStatus(value: Bundle): DaemonAppStatus {
        checkReply(value)
        keys(value, "ok", "status", "record", "dataRevision", "closing", "released", "sessionElapsed",
            "sessionWall", "checked", "gms", "gmsRequestId", "needsReconciliation", "recoveryReadiness")
        return DaemonAppStatus(value.getBundle("record")?.let(::record),
            DaemonOwnerStatus.valueOf(text(value, "status")),
            longOrNull(value, "dataRevision"), boolean(value, "closing"), boolean(value, "released"),
            longOrNull(value, "sessionElapsed"), longOrNull(value, "sessionWall"), longOrNull(value, "checked"),
            if (value.containsKey("gms")) GmsObservationCodec.decode(text(value, "gms", 12_000), long(value, "gmsRequestId")) else null,
            boolean(value, "needsReconciliation"),
            if (value.containsKey("recoveryReadiness")) readiness(nested(value, "recoveryReadiness")) else null)
    }
    private fun readiness(value: Bundle): DaemonRecoveryReadinessEvidence {
        keys(value, "record", "requestId", "sampleElapsed", "checkedElapsed", "admitted")
        return DaemonRecoveryReadinessEvidence(record(nested(value, "record")), long(value, "requestId"),
            long(value, "sampleElapsed"), long(value, "checkedElapsed"), boolean(value, "admitted"))
    }
    @Suppress("DEPRECATION") private fun boolean(value: Bundle, key: String): Boolean = value.get(key) as? Boolean ?: error("Missing boolean")
    private fun longOrNull(value: Bundle, key: String): Long? = if (value.containsKey(key)) long(value, key) else null
    fun reply(value: DaemonControlReply) = when (value) {
        is DaemonControlReply.Rejected -> failure(value.reason.name)
        is DaemonControlReply.Acknowledged -> success {
            putBundle("record", record(value.receipt.committed)); putLong("currentRevision", value.currentRevision)
            putBundle("request", request(value.receipt.request))
        }
    }
}
