package io.github.originosfcmguardian.core.doze

/** These are the only targets this transaction protocol can represent. */
enum class DozeTarget(val packageName: String) {
    GMS("com.google.android.gms"),
    OWNED_DEBUG_TEST("io.github.originosfcmguardian.test"),
}

data class DozeIdentity(
    val fingerprint: String,
    val userId: Int,
    val executionUid: Int,
    val packageName: String,
    val appId: Int,
    val enabled: Boolean,
    val installedUserIds: Set<Int>,
    val pccId: Int,
    val uidOwnerPackages: Set<String>,
)

data class DozeObservation(val identity: DozeIdentity, val rawWhitelist: String)

interface DozeDevicePort {
    /** Complete fresh identity and full whitelist, or throw on any incomplete probe. */
    fun probe(): DozeObservation

    /** Fixed target only; throw on any command failure or uncertain response. */
    fun add()

    /** Fixed target only; throw on any command failure or uncertain response. */
    fun remove()
}

interface DozeJournalPort {
    /** Exact bytes; null only for confirmed absence. */
    fun load(): ByteArray?

    /** Atomic, durable exact-byte CAS. Never delete or replace an unknown record. */
    fun compareAndSet(expected: ByteArray?, replacement: ByteArray): Boolean
}

enum class DozePhase { ADD_INTENT, APPLIED, RESTORE_INTENT, RESTORED, OBSERVED_RESTORED }

enum class DozeOutcomeCode { NO_WRITE, APPLIED, RESTORED, OBSERVED_RESTORED, BLOCKED, UNCERTAIN }

data class DozeOutcome(
    val code: DozeOutcomeCode,
    val phase: DozePhase? = null,
    val detail: String,
)
