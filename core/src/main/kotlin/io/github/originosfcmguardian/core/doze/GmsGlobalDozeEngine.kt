package io.github.originosfcmguardian.core.doze

import java.util.UUID

/**
 * Optimistic host protocol for fixed by-name GMS commands. PM binding and shell dispatch are not
 * atomic; fresh pre/post mapping checks limit that gap but do not eliminate it. No automatic retry,
 * inverse or new cycle is available, including after process loss or an incomplete callee result.
 */
class GmsGlobalDozeEngine(
    private val device: GmsGlobalDozeDevicePort,
    private val document: GmsGlobalDozeDocumentPort,
    private val legacyJournal: DozeJournalPort,
    private val admission: GmsGlobalDozeAdmissionPort,
    private val expectedFingerprint: String,
    /** Independent explicit restore authority; never derived from the protection master switch. */
    private val mayRestore: (String) -> Boolean,
) {
    init { require(expectedFingerprint.isNotBlank()) }

    /** Explicit confirmation stores the fresh fixed installations, never caller-provided identities. */
    fun enable(confirmedAtMillis: Long): DozeOutcome {
        val revocation = admission.revocationVersion()
        return admission.serialized {
            guarded {
                val legacy = settledLegacy()
                val stored = load()
                require(stored?.first?.transaction == null) { "Retained transaction cannot be replaced by enable" }
                val observation = device.probe()
                val rows = inspectObservation(observation)
                require(!exceptIdleOnly(rows)) { "Except-idle-only scope is ambiguous" }
                require(confirmedAtMillis > (stored?.first?.consent?.confirmedAtMillis ?: 0L)) { "New confirmation required" }
                require(admission.revocationVersion() == revocation) { "Enable revoked while pending" }
                require(legacyJournal.load().sameBytes(legacy)) { "Legacy journal changed" }
                val previous = stored?.first?.consent
                val consent = GmsGlobalDozeConsent(true, increment(previous?.epoch ?: 0),
                    increment(previous?.generation ?: 0), confirmedAtMillis, observation.identity.gms,
                    observation.identity.guardian, expectedFingerprint)
                val bytes = persist(stored?.second, GmsGlobalDozeDocument(consent))
                if (!admission.allowAdds(revocation)) {
                    persist(bytes, GmsGlobalDozeDocument(consent.copy(enabled = false,
                        epoch = increment(consent.epoch), generation = increment(consent.generation))))
                    return@guarded blocked("Enable was synchronously revoked")
                }
                DozeOutcome(DozeOutcomeCode.NO_WRITE, detail = "Explicit global GMS consent stored; no device write")
            }
        }
    }

    /** Synchronously closes the shared ADD gate before waiting for operation/storage admission. */
    fun disable(): DozeOutcome {
        admission.revokeAdds()
        return admission.serialized {
            guarded {
                admission.revokeAdds()
                val stored = load() ?: return@guarded DozeOutcome(DozeOutcomeCode.NO_WRITE,
                    detail = "ADD admission revoked; no consent document exists")
                if (stored.first.consent.enabled) {
                    val consent = stored.first.consent
                    persist(stored.second, stored.first.copy(consent = consent.copy(enabled = false,
                        epoch = increment(consent.epoch), generation = increment(consent.generation))))
                }
                DozeOutcome(DozeOutcomeCode.NO_WRITE, stored.first.transaction?.phase,
                    "Future ADD revoked; retained transaction preserved")
            }
        }
    }

    fun inspect(): DozeOutcome = admission.serialized {
        guarded {
            settledLegacy()
            val stored = load()
            val observation = device.probe()
            val rows = inspectObservation(observation)
            val transaction = stored?.first?.transaction ?: return@guarded classify(rows)
            requireBound(observation.identity, stored.first.consent)
            when (transaction.phase) {
                DozePhase.ADD_INTENT, DozePhase.RESTORE_INTENT -> DozeOutcome(DozeOutcomeCode.UNCERTAIN,
                    transaction.phase, "Retained interrupted intent; no automatic action")
                DozePhase.APPLIED -> {
                    require(observation.identity.gms == transaction.gms && observation.rawWhitelist == transaction.applied) {
                        "Applied state drifted"
                    }
                    DozeOutcome(DozeOutcomeCode.APPLIED, transaction.phase, "Retained exact addition is visible")
                }
                DozePhase.RESTORED, DozePhase.OBSERVED_RESTORED -> {
                    require(observation.rawWhitelist == transaction.baseline) { "Closed baseline drifted" }
                    DozeOutcome(if (transaction.phase == DozePhase.RESTORED) DozeOutcomeCode.RESTORED
                        else DozeOutcomeCode.OBSERVED_RESTORED, transaction.phase, "Retained exact baseline is visible")
                }
            }
        }
    }

    fun apply(): DozeOutcome = admission.serialized {
        var phase: DozePhase? = null
        try {
            val revocation = admission.revocationVersion()
            val legacy = settledLegacy()
            val stored = load() ?: return@serialized blocked("Explicit global GMS consent required")
            val authority = stored.first.consent
            require(authority.enabled && admission.addsAllowed(revocation)) { "ADD authority is OFF" }
            require(stored.first.transaction == null) { "Retained transaction blocks every new cycle" }
            val first = device.probe()
            val rows = inspectObservation(first)
            requireBound(first.identity, authority)
            if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }) return@serialized classify(rows)
            require(device.probe() == first) { "Full pre-intent observation drifted" }
            requireCurrentAddAuthority(stored.second, authority, legacy, revocation)
            val transaction = GmsGlobalDozeTransaction(UUID.randomUUID().toString(), authority.epoch,
                first.identity.gms, GMS_GLOBAL_OWNER_PROOF_VERSION, first.rawWhitelist, null, DozePhase.ADD_INTENT)
            phase = DozePhase.ADD_INTENT
            val bytes = persist(stored.second, nextRevision(stored.first, transaction))

            require(device.probe() == first) { "Full state drifted after add intent" }
            requireCurrentAddAuthority(bytes, authority, legacy, revocation, allowRevisionChange = true)
            // Admission remains held through callee and cleanup, even if the transport times out.
            val dispatched = admission.dispatchAdd(revocation, device::dispatchAddFixedGms)
                ?: error("ADD atomically revoked before actual dispatch")
            val receipt = dispatched.awaitTerminationAndCleanup()
            requireReceipt(receipt, GmsGlobalDozeOperation.ADD, first.identity)
            val after = device.probe()
            require(after.identity == first.identity) { "Identity or complete owner mapping drifted after ADD" }
            val added = Membership(MembershipSource.USER, GMS_GLOBAL_DOZE_PACKAGE, first.identity.gms.appId)
            require(inspectObservation(after) == rows + added) { "ADD is not the exact GMS-only delta" }
            persistResult(transaction, transaction.copy(applied = after.rawWhitelist, phase = DozePhase.APPLIED))
            DozeOutcome(DozeOutcomeCode.APPLIED, DozePhase.APPLIED, "Exact global GMS user membership verified")
        } catch (_: Exception) {
            if (phase == null) blocked("ADD preflight or consent failed") else uncertain(phase,
                "ADD intent retained or commit uncertain; no inverse, retry or new cycle")
        }
    }

    /** Explicit independent restoration names the durable transaction; no implicit removal on OFF. */
    fun restore(transactionId: String): DozeOutcome = admission.serialized {
        var phase: DozePhase? = null
        try {
            val legacy = settledLegacy()
            val stored = load() ?: return@serialized blocked("No global GMS transaction")
            val transaction = stored.first.transaction ?: return@serialized blocked("No owned GMS addition")
            require(transaction.id == transactionId && transaction.phase == DozePhase.APPLIED) { "Only named durable APPLIED may restore" }
            require(mayRestore(transactionId)) { "Independent restore authority required" }
            val first = device.probe()
            val rows = inspectObservation(first)
            requireBound(first.identity, stored.first.consent)
            require(first.identity.gms == transaction.gms) { "Original installation changed" }
            require(legacyJournal.load().sameBytes(legacy) && document.load().sameBytes(stored.second)) { "Journal drifted" }
            if (first.rawWhitelist == transaction.baseline) {
                require(rows == StrictWhitelist.parse(transaction.baseline)) { "Baseline parse differs" }
                require(mayRestore(transactionId)) { "Restore authority revoked" }
                persist(stored.second, nextRevision(stored.first, transaction.copy(phase = DozePhase.OBSERVED_RESTORED)))
                return@serialized DozeOutcome(DozeOutcomeCode.OBSERVED_RESTORED, DozePhase.OBSERVED_RESTORED,
                    "Complete original baseline observed; no REMOVE dispatched")
            }
            require(first.rawWhitelist == transaction.applied) { "Complete applied whitelist changed" }
            require(device.probe() == first) { "Full restore pre-intent observation drifted" }
            require(document.load().sameBytes(stored.second) && legacyJournal.load().sameBytes(legacy) &&
                mayRestore(transactionId)) { "Restore admission drifted" }
            val intent = transaction.copy(phase = DozePhase.RESTORE_INTENT)
            phase = DozePhase.RESTORE_INTENT
            val bytes = persist(stored.second, nextRevision(stored.first, intent))
            require(device.probe() == first) { "Full state drifted after restore intent" }
            require(document.load().sameBytes(bytes) && legacyJournal.load().sameBytes(legacy) &&
                mayRestore(transactionId)) { "Restore dispatch admission drifted" }
            val receipt = device.removeFixedGms()
            requireReceipt(receipt, GmsGlobalDozeOperation.REMOVE, first.identity)
            val after = device.probe()
            require(after.identity == first.identity && after.rawWhitelist == transaction.baseline) { "Complete original baseline not restored" }
            require(inspectObservation(after) == StrictWhitelist.parse(transaction.baseline)) { "Restore baseline parse differs" }
            persistResult(intent, intent.copy(phase = DozePhase.RESTORED))
            DozeOutcome(DozeOutcomeCode.RESTORED, DozePhase.RESTORED, "Complete original global whitelist restored")
        } catch (_: Exception) {
            if (phase == null) blocked("Restore preflight failed; no REMOVE") else uncertain(phase,
                "Restore intent retained or commit uncertain; no retry or new cycle")
        }
    }

    private fun requireCurrentAddAuthority(bytes: ByteArray, authority: GmsGlobalDozeConsent,
        legacy: ByteArray?, revocation: Long, allowRevisionChange: Boolean = false) {
        val current = load() ?: error("Consent disappeared")
        require(current.second.contentEquals(bytes)) { "Exact current document differs" }
        require(current.first.consent.enabled && if (allowRevisionChange)
            current.first.consent.copy(generation = authority.generation) == authority
            else current.first.consent == authority) { "Exact ADD authority changed" }
        require(legacyJournal.load().sameBytes(legacy) && admission.addsAllowed(revocation)) { "ADD revoked or legacy changed" }
    }

    /** Results may follow OFF; merge only the matching retained intent into the current document. */
    private fun persistResult(before: GmsGlobalDozeTransaction, after: GmsGlobalDozeTransaction) {
        val current = load() ?: error("Document disappeared after dispatch")
        require(current.first.transaction == before) { "Retained intent changed after dispatch" }
        persist(current.second, nextRevision(current.first, after))
    }

    private fun nextRevision(value: GmsGlobalDozeDocument, transaction: GmsGlobalDozeTransaction) =
        value.copy(consent = value.consent.copy(generation = increment(value.consent.generation)), transaction = transaction)

    private fun load(): Pair<GmsGlobalDozeDocument, ByteArray>? {
        val bytes = document.load() ?: return null
        val value = GmsGlobalDozeCodec.decode(bytes)
        require(value.consent.pinnedFingerprint == expectedFingerprint) { "Document belongs to another build" }
        return value to bytes
    }

    private fun settledLegacy(): ByteArray? {
        val bytes = legacyJournal.load() ?: return null
        val record = DozeJournalCodec.decode(bytes)
        require(record.phase == DozePhase.RESTORED || record.phase == DozePhase.OBSERVED_RESTORED) { "Unsettled schema2 blocks global GMS" }
        return bytes
    }

    private fun persist(expected: ByteArray?, value: GmsGlobalDozeDocument): ByteArray {
        val replacement = GmsGlobalDozeCodec.encode(value)
        require(document.load().sameBytes(expected)) { "Document changed before commit" }
        GmsGlobalDozeCodec.validateTransition(expected, replacement)
        require(document.compareAndSet(expected, replacement)) { "Global GMS CAS rejected" }
        require(document.load().sameBytes(replacement)) { "Global GMS exact commit readback differs" }
        return replacement
    }

    private fun requireBound(identity: GmsGlobalDozeIdentity, consent: GmsGlobalDozeConsent) {
        require(identity.gms == consent.gms && identity.guardian == consent.guardian &&
            identity.environment.fingerprint == consent.pinnedFingerprint) { "Confirmed exact binding drifted" }
    }

    private fun inspectObservation(value: GmsGlobalDozeObservation): Set<Membership> {
        GmsGlobalDozeCodec.validateIdentity(value.identity, expectedFingerprint)
        return StrictWhitelist.parse(value.rawWhitelist).also { rows ->
            require(rows.filter { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }.all { it.appId == value.identity.gms.appId }) {
                "GMS whitelist identity conflicts"
            }
        }
    }

    private fun exceptIdleOnly(rows: Set<Membership>): Boolean = rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE } &&
        rows.none { it.packageName == GMS_GLOBAL_DOZE_PACKAGE && (it.source == MembershipSource.SYSTEM || it.source == MembershipSource.USER) }

    private fun classify(rows: Set<Membership>): DozeOutcome = if (rows.any {
        it.packageName == GMS_GLOBAL_DOZE_PACKAGE && (it.source == MembershipSource.SYSTEM || it.source == MembershipSource.USER)
    }) DozeOutcome(DozeOutcomeCode.NO_WRITE, detail = "Existing system/user GMS membership; no ownership claimed")
    else blocked(if (exceptIdleOnly(rows)) "Except-idle-only scope is ambiguous" else "GMS membership absent")

    private fun requireReceipt(receipt: GmsGlobalDozeDispatchReceipt, operation: GmsGlobalDozeOperation,
        identity: GmsGlobalDozeIdentity) {
        require(receipt.completed && receipt.operation == operation && receipt.identity == identity) { "Incomplete or mismatched fixed dispatch" }
        GmsGlobalDozeCodec.validateIdentity(receipt.identity, expectedFingerprint)
    }

    private inline fun guarded(operation: () -> DozeOutcome): DozeOutcome = try { operation() }
        catch (_: Exception) { blocked("Probe, authority or exact document could not be validated") }
    private fun blocked(detail: String) = DozeOutcome(DozeOutcomeCode.BLOCKED, detail = detail)
    private fun uncertain(phase: DozePhase, detail: String) = DozeOutcome(DozeOutcomeCode.UNCERTAIN, phase, detail)
    private fun increment(value: Long): Long { require(value < Long.MAX_VALUE); return value + 1 }
    private fun ByteArray?.sameBytes(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
}
