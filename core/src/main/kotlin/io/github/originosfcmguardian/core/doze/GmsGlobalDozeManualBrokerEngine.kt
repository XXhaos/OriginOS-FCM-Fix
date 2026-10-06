package io.github.originosfcmguardian.core.doze

import java.util.UUID

/** Actual Android transport obligations include authenticated caller, fresh shell nonce and Binder lifetime. */
data class GmsManualBrokerHello(val nonce: String, val operation: GmsGlobalDozeOperation)

/** Exit, output and cleanup are distinct facts obtained from the actual fixed child. */
data class GmsManualBrokerCommandResult(
    val preparation: GmsDozeBrokerV2Preparation,
    val identity: GmsGlobalDozeIdentity,
    val cleanup: GmsDozeBrokerV2Cleanup,
    val exitCode: Int?,
    val commandOutputValid: Boolean,
)

interface GmsManualBrokerConnection {
    fun hello(): GmsManualBrokerHello
    fun probe(): GmsGlobalDozeObservation
    fun activate(proof: GmsDozeBrokerV2Proof): Boolean
    /** Actual fixed ProcessBuilder.start admission, never merely queued RPC work. */
    fun start(proof: GmsDozeBrokerV2Proof): Boolean
    fun awaitCleanup(preparation: GmsDozeBrokerV2Preparation): GmsManualBrokerCommandResult
    fun revoke(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerRevokeAck
    fun release(proof: GmsDozeBrokerV2Proof): GmsDozeBrokerV2ReleaseAck
    /** Remove only this own service and observe death of its previously linked actual Binder. */
    fun destroyOwnAndObserveDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath
}

interface GmsManualBrokerTransport {
    /** A distinct INERT instance restricted to the given fixed operation; no retry/replacement. */
    fun bindInert(operation: GmsGlobalDozeOperation): GmsManualBrokerConnection
    /** Exact existing own lifetime only; this must never bind/create a replacement. */
    fun findOwn(key: GmsDozeBrokerKey): GmsManualBrokerConnection?
    /** Only a recorded actual death callback of the exact previously linked Binder, or null. */
    fun observedDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath?
}

/**
 * Finite manual business adapter. It deliberately does not implement or wrap the old admission.
 * Confirmation timestamps are trusted UI assertions of a fresh explicit action, with restore
 * bound to its named transaction. They are not authentication or proof that Android performed it.
 * Qualified probes must be complete, fresh and caller-checked by the actual transport. No remote
 * call executes under withStorage, and no timeout enables replacement, inverse or automatic retry.
 */
class GmsGlobalDozeManualBrokerEngine(
    private val session: GmsManualSessionPort,
    private val transport: GmsManualBrokerTransport,
    private val legacyJournal: DozeJournalPort,
    private val expectedFingerprint: String,
) {
    init { require(expectedFingerprint.isNotBlank()) }

    fun applyOnce(explicitAddConfirmation: Long): DozeOutcome = business(GmsGlobalDozeOperation.ADD) { markAuthority, markTransport ->
        if (!session.control.addsLocallyAllowed()) return@business DozeOutcome(DozeOutcomeCode.BLOCKED,
            detail = "This finite session is OFF; no ADD prepared or dispatched")
        val legacy = settledLegacy()
        require(session.withStorage { docs -> load(docs)?.first?.transaction == null &&
            docs.broker.readExact().state == GmsDozeBrokerV2Codec.initialState() })
        markTransport()
        val broker = transport.bindInert(GmsGlobalDozeOperation.ADD)
        val hello = broker.hello()
        require(hello.operation == GmsGlobalDozeOperation.ADD)
        val first = broker.probe()
        val rows = inspect(first)
        // Existing membership cannot become an owned addition, including except-idle membership.
        if (rows.any { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }) {
            val inertKey = GmsDozeBrokerKey(hello.nonce, 1)
            require(broker.destroyOwnAndObserveDeath(inertKey).key == inertKey)
            return@business DozeOutcome(DozeOutcomeCode.NO_WRITE, detail = "Existing GMS membership; no ownership claimed")
        }
        require(broker.probe() == first) { "Complete initial observation drifted" }
        val pinned = requireNotNull(session.withStorage { docs ->
            if (!session.control.addsLocallyAllowed() || !legacyJournal.load().sameBytes(legacy)) return@withStorage null
            val previous = load(docs)
            if (previous?.first?.transaction != null || docs.broker.readExact().state != GmsDozeBrokerV2Codec.initialState())
                return@withStorage null
            val old = previous?.first?.consent
            if (explicitAddConfirmation <= (old?.confirmedAtMillis ?: 0)) return@withStorage null
            val consent = GmsGlobalDozeConsent(true, next(old?.epoch ?: 0), next(old?.generation ?: 0),
                explicitAddConfirmation, first.identity.gms, first.identity.guardian, expectedFingerprint)
            markAuthority()
            val consentBytes = persist(docs, previous?.second, GmsGlobalDozeDocument(consent)) ?: return@withStorage null
            val tx = GmsGlobalDozeTransaction(UUID.randomUUID().toString(), consent.epoch, first.identity.gms,
                GMS_GLOBAL_OWNER_PROOF_VERSION, first.rawWhitelist, null, DozePhase.ADD_INTENT)
            val intentBytes = persist(docs, consentBytes, revised(GmsGlobalDozeDocument(consent), tx)) ?: return@withStorage null
            val coordinator = GmsDozeBrokerV2Coordinator(docs.broker)
            val issued = coordinator.issueAddGrant(first.identity, intentBytes) ?: return@withStorage null
            coordinator.pin(requireNotNull(issued.state.add).grant.grantId, hello.nonce)
        })
        execute(broker, pinned, first, legacy, GmsGlobalDozeOperation.ADD)
    }

    /** Local closure and short durable OFF precede exact revoke RPC; business busy is never awaited. */
    fun off(): DozeOutcome {
        session.control.closeAdds()
        return try {
            val pending = session.withStorage { docs ->
                val current = load(docs)
                if (current?.first?.consent?.enabled == true) {
                    val value = current.first
                    if (persist(docs, current.second, value.copy(consent = value.consent.copy(enabled = false,
                        epoch = next(value.consent.epoch), generation = next(value.consent.generation)))) == null)
                        return@withStorage OffPending(false, null)
                }
                val state = docs.broker.readExact().state
                val add = state.add
                if (add?.pin == null || add.actualDeathObserved) OffPending(true, null)
                else if (add.revokeVersion > 0 && add.acknowledgedRevoke == add.revokeVersion)
                    OffPending(true, docs.broker.readExact())
                else GmsDozeBrokerV2Coordinator(docs.broker).requestRevoke(add.pin)?.let { OffPending(true, it) }
                    ?: OffPending(false, null)
            }
            require(pending.saved)
            val revokeProof = pending.proof
            if (revokeProof != null) {
                val add = requireNotNull(revokeProof.state.add)
                val key = requireNotNull(add.pin)
                val broker = transport.findOwn(key)
                if (add.acknowledgedRevoke != add.revokeVersion) {
                    try {
                        val ack = requireNotNull(broker).revoke(revokeProof)
                        requireNotNull(session.withStorage { docs ->
                            GmsDozeBrokerV2Coordinator(docs.broker).acceptRevokeAck(ack)
                        })
                    } catch (failure: Exception) {
                        val death = transport.observedDeath(key) ?: throw failure
                        require(death.key == key)
                        requireNotNull(session.withStorage { docs ->
                            GmsDozeBrokerV2Coordinator(docs.broker).acceptDeath(death)
                        })
                    }
                }
                if (broker != null) retireReleasedAdd(broker, key)
            }
            DozeOutcome(DozeOutcomeCode.NO_WRITE, detail = "OFF persisted; future ADD start closed")
        } catch (_: Exception) {
            DozeOutcome(DozeOutcomeCode.UNCERTAIN, detail = "OFF locally closed; durable OFF or exact revoke/death remains UNKNOWN")
        }
    }

    fun restoreOnce(transactionId: String, explicitRestoreConfirmation: Long): DozeOutcome = business(GmsGlobalDozeOperation.REMOVE) { markAuthority, markTransport ->
        val legacy = settledLegacy()
        val original = requireNotNull(session.withStorage { docs ->
            val current = load(docs) ?: return@withStorage null
            val tx = current.first.transaction ?: return@withStorage null
            val state = docs.broker.readExact().state
            val add = state.add ?: return@withStorage null
            if (current.first.consent.enabled || tx.id != transactionId || tx.phase != DozePhase.APPLIED ||
                explicitRestoreConfirmation <= current.first.consent.confirmedAtMillis ||
                !GmsDozeBrokerV2Codec.retiredAdd(add) || state.restore != null) return@withStorage null
            current
        })
        markTransport()
        val broker = transport.bindInert(GmsGlobalDozeOperation.REMOVE)
        val hello = broker.hello()
        require(hello.operation == GmsGlobalDozeOperation.REMOVE)
        val first = broker.probe()
        inspect(first)
        requireBound(first.identity, original.first.consent)
        require(broker.probe() == first)
        val tx = requireNotNull(original.first.transaction)
        if (first.rawWhitelist == tx.baseline) {
            requireNotNull(session.withStorage { docs ->
                val current = load(docs) ?: return@withStorage null
                if (!legacyJournal.load().sameBytes(legacy) || !current.second.contentEquals(original.second)) return@withStorage null
                markAuthority()
                persist(docs, current.second, revised(current.first, tx.copy(phase = DozePhase.OBSERVED_RESTORED)))
            })
            val inertKey = GmsDozeBrokerKey(hello.nonce, 2)
            require(broker.destroyOwnAndObserveDeath(inertKey).key == inertKey)
            return@business DozeOutcome(DozeOutcomeCode.OBSERVED_RESTORED, DozePhase.OBSERVED_RESTORED,
                "Complete original baseline observed; no REMOVE dispatched")
        }
        require(first.rawWhitelist == tx.applied) { "Full APPLIED state drifted" }
        val pinned = requireNotNull(session.withStorage { docs ->
            val current = load(docs) ?: return@withStorage null
            if (!legacyJournal.load().sameBytes(legacy) || !current.second.contentEquals(original.second) || current.first.consent.enabled)
                return@withStorage null
            markAuthority()
            val intent = persist(docs, current.second, revised(current.first, tx.copy(phase = DozePhase.RESTORE_INTENT))) ?: return@withStorage null
            val coordinator = GmsDozeBrokerV2Coordinator(docs.broker)
            val issued = coordinator.issueRestoreGrant(first.identity, intent) ?: return@withStorage null
            coordinator.pin(requireNotNull(issued.state.restore).grant.grantId, hello.nonce)
        })
        execute(broker, pinned, first, legacy, GmsGlobalDozeOperation.REMOVE)
    }

    private fun execute(broker: GmsManualBrokerConnection, pinned: GmsDozeBrokerV2Proof,
        first: GmsGlobalDozeObservation, legacy: ByteArray?, operation: GmsGlobalDozeOperation): DozeOutcome {
        val attempt = requireNotNull(if (operation == GmsGlobalDozeOperation.ADD) pinned.state.add else pinned.state.restore)
        val key = requireNotNull(attempt.pin)
        require(broker.activate(pinned))
        require(broker.probe() == first) { "Fresh preflight drifted" }
        val prepared = requireNotNull(session.withStorage { docs ->
            if (!legacyJournal.load().sameBytes(legacy)) return@withStorage null
            val current = load(docs)?.first ?: return@withStorage null
            requireBound(first.identity, current.consent)
            val tx = current.transaction ?: return@withStorage null
            if (tx.id != attempt.grant.globalTransactionId || tx.epoch != attempt.grant.transactionEpoch ||
                GmsDozeBrokerV2Codec.sha256(GmsGlobalDozeCodec.encode(current)) != attempt.grant.businessIntentSha256)
                return@withStorage null
            if (!(if (operation == GmsGlobalDozeOperation.ADD)
                current.consent.enabled && session.control.addsLocallyAllowed() && tx.phase == DozePhase.ADD_INTENT
                else !current.consent.enabled && tx.phase == DozePhase.RESTORE_INTENT)) return@withStorage null
            GmsDozeBrokerV2Coordinator(docs.broker).prepare(key, attempt.grant.grantId)
        })
        val preparation = requireNotNull(prepared.state.attempt(attempt.grant.grantId)?.preparation)
        require(broker.start(prepared))
        val receipt = broker.awaitCleanup(preparation)
        require(receipt.preparation == preparation && receipt.cleanup.preparation == preparation &&
            receipt.cleanup.complete() && receipt.exitCode == 0 && receipt.commandOutputValid && receipt.identity == first.identity)
        val after = broker.probe()
        require(after.identity == first.identity)
        val rows = inspect(after)
        if (operation == GmsGlobalDozeOperation.ADD) require(rows == inspect(first) + Membership(MembershipSource.USER,
            GMS_GLOBAL_DOZE_PACKAGE, first.identity.gms.appId)) { "ADD is not exact GMS-only delta" }
        else require(GmsDozeBrokerV2Codec.rawSha256(after.rawWhitelist) == attempt.grant.baselineSha256)
        val resultProof = requireNotNull(session.withStorage { docs ->
            val current = load(docs) ?: return@withStorage null
            val tx = current.first.transaction ?: return@withStorage null
            if (tx.id != attempt.grant.globalTransactionId || tx.epoch != attempt.grant.transactionEpoch)
                return@withStorage null
            requireBound(first.identity, current.first.consent)
            if (GmsDozeBrokerV2Codec.rawSha256(tx.baseline) != attempt.grant.baselineSha256) return@withStorage null
            val finished = if (operation == GmsGlobalDozeOperation.ADD) {
                if (tx.phase != DozePhase.ADD_INTENT) return@withStorage null
                tx.copy(applied = after.rawWhitelist, phase = DozePhase.APPLIED)
            } else {
                if (current.first.consent.enabled || tx.phase != DozePhase.RESTORE_INTENT ||
                    after.rawWhitelist != tx.baseline || rows != StrictWhitelist.parse(tx.baseline)) return@withStorage null
                tx.copy(phase = DozePhase.RESTORED)
            }
            // Merge into CURRENT authority, so a concurrent OFF is preserved.
            val bytes = persist(docs, current.second, revised(current.first, finished)) ?: return@withStorage null
            val result = GmsDozeBrokerV2Result(preparation, GmsDozeBrokerV2Codec.sha256(bytes),
                GmsDozeBrokerV2Codec.rawSha256(after.rawWhitelist))
            GmsDozeBrokerV2Coordinator(docs.broker).commitResult(receipt.cleanup, result, bytes)
        })
        val release = broker.release(resultProof)
        requireNotNull(session.withStorage { docs -> GmsDozeBrokerV2Coordinator(docs.broker).acceptReleaseAck(release) })
        if (operation == GmsGlobalDozeOperation.REMOVE) {
            val death = broker.destroyOwnAndObserveDeath(key)
            require(death.key == key)
            requireNotNull(session.withStorage { docs -> GmsDozeBrokerV2Coordinator(docs.broker).acceptDeath(death) })
        } else retireReleasedAdd(broker, key)
        return DozeOutcome(if (operation == GmsGlobalDozeOperation.ADD) DozeOutcomeCode.APPLIED else DozeOutcomeCode.RESTORED,
            if (operation == GmsGlobalDozeOperation.ADD) DozePhase.APPLIED else DozePhase.RESTORED,
            "Exact fixed operation, complete child cleanup and broker release verified")
    }

    private fun retireReleasedAdd(broker: GmsManualBrokerConnection, key: GmsDozeBrokerKey) {
        val retire = session.withStorage { docs ->
            val current = load(docs)
            val add = requireNotNull(docs.broker.readExact().state.add)
            add.pin == key && current?.first?.consent?.enabled == false && add.result != null &&
                add.releaseAcknowledged && !add.actualDeathObserved && add.revokeVersion > 0 &&
                add.acknowledgedRevoke == add.revokeVersion
        }
        if (retire) {
            val death = broker.destroyOwnAndObserveDeath(key)
            require(death.key == key)
            session.withStorage { docs ->
                val add = requireNotNull(docs.broker.readExact().state.add)
                if (!add.actualDeathObserved) requireNotNull(GmsDozeBrokerV2Coordinator(docs.broker).acceptDeath(death))
            }
        }
    }

    private fun business(operation: GmsGlobalDozeOperation, action: (() -> Unit, () -> Unit) -> DozeOutcome): DozeOutcome {
        if (!session.control.enterBusinessOperation()) return DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Business operation busy")
        var authorityAttempted = false
        var transportTouched = false
        return try { action({ authorityAttempted = true }, { transportTouched = true }) } catch (_: Exception) {
            if (authorityAttempted) session.control.closeAdds()
            val phase = try { session.withStorage { load(it)?.first?.transaction?.phase } } catch (_: Exception) { null }
            // No retained intent means disk alone would allow a replacement after a failed finite
            // bind/authority attempt. Close the session, including confirmed CAS rejection, without
            // claiming that a rejected CAS committed or that a remote child was cleaned up.
            if (transportTouched && (phase == null || operation == GmsGlobalDozeOperation.REMOVE && phase == DozePhase.APPLIED))
                session.fenceUncertainSession()
            DozeOutcome(if (phase == null && !authorityAttempted && !transportTouched) DozeOutcomeCode.BLOCKED
                else DozeOutcomeCode.UNCERTAIN, phase, "Finite attempt stopped; no retry, inverse or replacement")
        } finally { session.control.leaveBusinessOperation() }
    }

    private fun settledLegacy(): ByteArray? = legacyJournal.load()?.also {
        val record = DozeJournalCodec.decode(it)
        require(record.phase == DozePhase.RESTORED || record.phase == DozePhase.OBSERVED_RESTORED)
    }

    private fun load(docs: GmsManualLockedDocuments): Pair<GmsGlobalDozeDocument, ByteArray>? = docs.global.load()?.let {
        val value = GmsGlobalDozeCodec.decode(it)
        require(value.consent.pinnedFingerprint == expectedFingerprint)
        value to it
    }

    private fun persist(docs: GmsManualLockedDocuments, expected: ByteArray?, value: GmsGlobalDozeDocument): ByteArray? = try {
        val bytes = GmsGlobalDozeCodec.encode(value)
        require(docs.global.load().sameBytes(expected))
        GmsGlobalDozeCodec.validateTransition(expected, bytes)
        if (!docs.global.compareAndSet(expected, bytes)) null else {
            require(docs.global.load().sameBytes(bytes))
            bytes
        }
    } catch (failure: Exception) {
        session.fenceUncertainSession()
        throw failure
    }

    private fun inspect(observation: GmsGlobalDozeObservation): Set<Membership> {
        GmsGlobalDozeCodec.validateIdentity(observation.identity, expectedFingerprint)
        return StrictWhitelist.parse(observation.rawWhitelist).also { rows ->
            require(rows.filter { it.packageName == GMS_GLOBAL_DOZE_PACKAGE }.all { it.appId == observation.identity.gms.appId })
        }
    }

    private fun requireBound(identity: GmsGlobalDozeIdentity, consent: GmsGlobalDozeConsent) {
        require(identity.gms == consent.gms && identity.guardian == consent.guardian &&
            identity.environment.fingerprint == consent.pinnedFingerprint)
    }

    private fun revised(document: GmsGlobalDozeDocument, tx: GmsGlobalDozeTransaction) =
        document.copy(consent = document.consent.copy(generation = next(document.consent.generation)), transaction = tx)
    private fun next(value: Long): Long { require(value < Long.MAX_VALUE); return value + 1 }
    private data class OffPending(val saved: Boolean, val proof: GmsDozeBrokerV2Proof?)
    private fun ByteArray?.sameBytes(other: ByteArray?): Boolean = if (this == null) other == null else other != null && contentEquals(other)
}
