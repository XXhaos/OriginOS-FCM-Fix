package io.github.originosfcmguardian.core.doze

enum class GmsDozeRecoveryKind { PREEXISTING_FULL_EXEMPTION, APPLIED_RETAINED, BLOCKED }

/** A read-only description, never consent, a storage permit, a grant or restore authorization. */
data class GmsDozeRecoveryDecision(val kind: GmsDozeRecoveryKind, val reason: String) {
    /** Existing codecs retain the transaction and fixed slots; no result opens another cycle. */
    val nextCycleAllowed: Boolean get() = false
}

/**
 * Pure inspection of one trusted, stable storage snapshot and one fresh complete observation.
 * null means confirmed absence, never a failed read. The caller establishes storageCertain only
 * after checking errors, interrupted files, old authority, canonical paths and snapshot stability.
 * This object cannot authenticate those facts, acquire a restart permit or prove old child cleanup.
 */
object GmsDozeRecoveryDecider {
    fun decide(
        expectedFingerprint: String,
        storageCertain: Boolean,
        globalBytes: ByteArray?,
        brokerBytes: ByteArray?,
        sessionFenceBytes: ByteArray?,
        legacyJournalBytes: ByteArray?,
        currentObservation: GmsGlobalDozeObservation?,
    ): GmsDozeRecoveryDecision {
        // Snapshot every byte input immediately; decoding and comparisons never use caller arrays.
        val global = globalBytes?.clone()
        val broker = brokerBytes?.clone()
        val fence = sessionFenceBytes?.clone()
        val legacy = legacyJournalBytes?.clone()
        if (!storageCertain) return blocked("Uncertain storage snapshot")
        if (expectedFingerprint.isBlank()) return blocked("Expected fingerprint missing")
        if (fence != null && fence.size != 32) return blocked("Session fence size invalid")
        return try {
            val document = global?.let(GmsGlobalDozeCodec::decode)
            val brokerState = broker?.let(GmsDozeBrokerV2Codec::decode)
            legacy?.let {
                val phase = DozeJournalCodec.decode(it).phase
                if (phase != DozePhase.RESTORED && phase != DozePhase.OBSERVED_RESTORED)
                    return blocked("Legacy transaction unsettled")
            }
            val observed = currentObservation ?: return blocked("Fresh observation missing")
            val identity = observed.identity.copy(
                gms = observed.identity.gms.copy(signerSha256 = observed.identity.gms.signerSha256.toList()),
                guardian = observed.identity.guardian.copy(installation = observed.identity.guardian.installation.copy(
                    signerSha256 = observed.identity.guardian.installation.signerSha256.toList())),
            )
            GmsGlobalDozeCodec.validateIdentity(identity, expectedFingerprint)
            val rows = StrictWhitelist.parse(observed.rawWhitelist)
            if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE && it.appId != identity.gms.appId })
                return blocked("Fresh GMS membership identity differs")

            if (global == null && broker == null && fence == null) {
                return if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE &&
                        (it.source == MembershipSource.SYSTEM || it.source == MembershipSource.USER) })
                    GmsDozeRecoveryDecision(GmsDozeRecoveryKind.PREEXISTING_FULL_EXEMPTION,
                        "Existing full GMS exemption; no ownership")
                else blocked("No existing full GMS exemption")
            }

            if (document == null || brokerState == null || fence == null)
                return blocked("Retained storage material incomplete")
            val tx = document.transaction ?: return blocked("Retained transaction missing")
            if (tx.phase != DozePhase.APPLIED) return blocked("Transaction is not retained APPLIED")
            val add = brokerState.add ?: return blocked("Matching ADD attempt missing")
            val result = add.result ?: return blocked("ADD result missing")
            if (!add.releaseAcknowledged || brokerState.restore != null)
                return blocked("ADD release missing or REMOVE attempt retained")
            val grant = add.grant
            if (document.consent.pinnedFingerprint != expectedFingerprint ||
                document.consent.gms != identity.gms || document.consent.guardian != identity.guardian ||
                grant.identity.gms != identity.gms || grant.identity.guardian != identity.guardian ||
                grant.identity.environment != identity.environment ||
                grant.globalTransactionId != tx.id || grant.transactionEpoch != tx.epoch)
                return blocked("Retained transaction or installation binding differs")
            val applied = tx.applied ?: return blocked("APPLIED raw evidence missing")
            if (grant.baselineSha256 != GmsDozeBrokerV2Codec.rawSha256(tx.baseline) ||
                result.appliedSha256 != GmsDozeBrokerV2Codec.rawSha256(applied) ||
                observed.rawWhitelist != applied)
                return blocked("Retained raw whitelist evidence differs")

            // Intent/result hashes describe historical exact CAS/readback snapshots. A later OFF
            // changes current document bytes, epoch and generation without changing ownership.
            // Do not rewrite those hashes or claim current bytes revalidate that historical CAS.
            // Fresh qualified owner mapping may differ; installation/environment binding may not.
            return GmsDozeRecoveryDecision(GmsDozeRecoveryKind.APPLIED_RETAINED,
                "Matching APPLIED retained; read-only")
        } catch (_: Exception) {
            blocked("Invalid storage or observation evidence")
        }
    }

    private fun blocked(reason: String) = GmsDozeRecoveryDecision(GmsDozeRecoveryKind.BLOCKED, reason)
}
