package io.github.originosfcmguardian.core.doze

/** A one-transaction, service-boundary Doze operation. No automatic retry or rollback. */
class DozeTransactionEngine(
    private val device: DozeDevicePort,
    private val journal: DozeJournalPort,
    private val expectedFingerprint: String,
    private val target: DozeTarget = DozeTarget.GMS,
    private val mayContinue: () -> Boolean = { true },
) {
    init { require(expectedFingerprint.isNotBlank()) }

    @Synchronized
    fun inspect(): DozeOutcome = try {
        val stored = loadRecord()
        val observation = device.probe()
        val rows = inspectObservation(observation)
        if (stored == null) classify(observation, rows)
        else when (stored.first.phase) {
            DozePhase.APPLIED -> {
                val expected = StrictWhitelist.parse(stored.first.applied!!)
                if (observation.identity == stored.first.identity && rows == expected)
                    DozeOutcome(DozeOutcomeCode.APPLIED, DozePhase.APPLIED, "Verified addition is visible")
                else DozeOutcome(DozeOutcomeCode.BLOCKED, DozePhase.APPLIED, "Device state drifted; manual review required")
            }
            DozePhase.RESTORED, DozePhase.OBSERVED_RESTORED -> {
                if (observation.identity != stored.first.identity ||
                    observation.rawWhitelist != stored.first.baseline) {
                    DozeOutcome(DozeOutcomeCode.BLOCKED, stored.first.phase, "Closed transaction is historical; live baseline drifted")
                } else if (stored.first.phase == DozePhase.RESTORED) {
                    DozeOutcome(DozeOutcomeCode.RESTORED, DozePhase.RESTORED, "Retained closed; live baseline observed")
                } else {
                    DozeOutcome(DozeOutcomeCode.OBSERVED_RESTORED, DozePhase.OBSERVED_RESTORED,
                        "Baseline observed without a tool removal; transaction retained closed")
                }
            }
            else -> DozeOutcome(DozeOutcomeCode.UNCERTAIN, stored.first.phase, "Interrupted intent; no automatic action")
        }
    } catch (_: Exception) {
        DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Probe or journal could not be validated")
    }

    @Synchronized
    fun apply(): DozeOutcome {
        var phase: DozePhase? = null
        try {
            if (loadRecord() != null) return DozeOutcome(
                DozeOutcomeCode.BLOCKED, detail = "Existing transaction is retained; no new cycle",
            )
            if (!mayContinue()) return DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Request stopped before intent")
            val first = device.probe()
            val rows = inspectObservation(first)
            if (eligibility(first, rows) != AddEligibility.ABSENT) return classify(first, rows)
            requireWriteEligible(first.identity)
            val second = device.probe()
            require(second == first && inspectObservation(second) == rows) { "Pre-intent state drifted" }
            if (!mayContinue()) return DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Request stopped before intent")
            val intent = DozeRecord(target, first.identity, first.rawWhitelist, null, DozePhase.ADD_INTENT)
            phase = DozePhase.ADD_INTENT
            var bytes = persist(null, intent)

            val beforeDispatch = device.probe()
            require(beforeDispatch == first && inspectObservation(beforeDispatch) == rows) {
                "State drifted after intent"
            }
            require(journal.load().sameBytes(bytes)) { "Intent readback changed" }
            require(mayContinue()) { "Request stopped before add" }
            device.add()
            val after = device.probe()
            require(after.identity == first.identity) { "Identity drifted after add" }
            val added = Membership(MembershipSource.USER, target.packageName, first.identity.appId)
            require(inspectObservation(after) == rows + added) { "Addition readback is not exact" }
            val applied = intent.copy(applied = after.rawWhitelist, phase = DozePhase.APPLIED)
            bytes = persist(bytes, applied)
            phase = DozePhase.APPLIED
            return DozeOutcome(DozeOutcomeCode.APPLIED, phase, "Exact user membership addition verified")
        } catch (_: Exception) {
            return if (phase == null) DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Preflight or journal commit failed")
            else DozeOutcome(DozeOutcomeCode.UNCERTAIN, phase,
                "Add intent commit or dispatch uncertain; inspect journal; no automatic retry or inverse")
        }
    }

    @Synchronized
    fun restore(): DozeOutcome {
        var phase: DozePhase? = null
        try {
            val stored = loadRecord()
                ?: return DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "No owned transaction exists")
            val record = stored.first
            if (record.phase != DozePhase.APPLIED) return DozeOutcome(
                DozeOutcomeCode.BLOCKED, record.phase, "Only durable APPLIED may be restored",
            )
            val first = device.probe()
            require(first.identity == record.identity) { "Identity drifted since addition" }
            val current = inspectObservation(first)
            val baseline = StrictWhitelist.parse(record.baseline)
            if (current == baseline && first.rawWhitelist == record.baseline) {
                persist(stored.second, record.copy(phase = DozePhase.OBSERVED_RESTORED))
                return DozeOutcome(
                    DozeOutcomeCode.OBSERVED_RESTORED, DozePhase.OBSERVED_RESTORED,
                    "Baseline observed; no removal dispatched",
                )
            }
            val expected = StrictWhitelist.parse(record.applied!!)
            require(current == expected) { "Visible addition is not exact" }
            val second = device.probe()
            require(second == first && inspectObservation(second) == expected) { "Pre-intent state drifted" }
            require(mayContinue()) { "Request stopped before restore intent" }
            val intent = record.copy(phase = DozePhase.RESTORE_INTENT)
            phase = DozePhase.RESTORE_INTENT
            val bytes = persist(stored.second, intent)

            val beforeDispatch = device.probe()
            require(beforeDispatch == first && inspectObservation(beforeDispatch) == expected) {
                "State drifted after restore intent"
            }
            require(journal.load().sameBytes(bytes)) { "Restore intent readback changed" }
            require(mayContinue()) { "Request stopped before remove" }
            device.remove()
            val after = device.probe()
            require(after.identity == record.identity && after.rawWhitelist == record.baseline) {
                "Complete baseline was not restored"
            }
            require(inspectObservation(after) == baseline) { "Baseline parse drifted" }
            persist(bytes, intent.copy(phase = DozePhase.RESTORED))
            phase = DozePhase.RESTORED
            return DozeOutcome(DozeOutcomeCode.RESTORED, phase, "Complete baseline verified after removal")
        } catch (_: Exception) {
            return if (phase == null) DozeOutcome(DozeOutcomeCode.BLOCKED, detail = "Restore preflight failed; no removal")
            else DozeOutcome(DozeOutcomeCode.UNCERTAIN, phase,
                "Restore intent commit or removal uncertain; inspect journal; no automatic retry")
        }
    }

    private fun loadRecord(): Pair<DozeRecord, ByteArray>? {
        val bytes = journal.load() ?: return null
        val record = DozeJournalCodec.decode(bytes)
        require(record.target == target && record.identity.fingerprint == expectedFingerprint) {
            "Journal belongs to a different target or build"
        }
        return record to bytes
    }

    private fun persist(expected: ByteArray?, record: DozeRecord): ByteArray {
        val replacement = DozeJournalCodec.encode(record)
        require(journal.load().sameBytes(expected)) { "Journal changed before commit" }
        DozeJournalCodec.validateTransition(expected, replacement)
        require(journal.compareAndSet(expected, replacement)) { "Journal CAS rejected" }
        require(journal.load().sameBytes(replacement)) { "Journal commit readback differs" }
        return replacement
    }

    private fun inspectObservation(observation: DozeObservation): Set<Membership> {
        val id = observation.identity
        require(id.fingerprint == expectedFingerprint && id.userId == 0 && id.executionUid == 2000) {
            "Unknown build or execution scope"
        }
        require(id.packageName == target.packageName && id.appId in 10000..99999 && id.enabled) {
            "Target package identity invalid"
        }
        val rows = StrictWhitelist.parse(observation.rawWhitelist)
        require(rows.filter { it.packageName == target.packageName }.all { it.appId == id.appId }) {
            "Target appId conflicts with whitelist"
        }
        return rows
    }

    private enum class AddEligibility { NO_WRITE, EXCEPT_IDLE_ONLY, MULTI_PROFILE, ABSENT }

    private fun eligibility(observation: DozeObservation, rows: Set<Membership>): AddEligibility {
        val targetRows = rows.filter { it.packageName == target.packageName }
        if (targetRows.any { it.source == MembershipSource.SYSTEM || it.source == MembershipSource.USER }) {
            return AddEligibility.NO_WRITE
        }
        if (targetRows.isNotEmpty()) return AddEligibility.EXCEPT_IDLE_ONLY
        if (observation.identity.installedUserIds != setOf(0)) return AddEligibility.MULTI_PROFILE
        return AddEligibility.ABSENT
    }

    private fun classify(observation: DozeObservation, rows: Set<Membership>): DozeOutcome =
        when (eligibility(observation, rows)) {
            AddEligibility.NO_WRITE -> DozeOutcome(DozeOutcomeCode.NO_WRITE,
                detail = "Target already has system or user membership")
            AddEligibility.EXCEPT_IDLE_ONLY -> DozeOutcome(DozeOutcomeCode.BLOCKED,
                detail = "Except-idle-only membership has ambiguous scope")
            AddEligibility.MULTI_PROFILE -> DozeOutcome(DozeOutcomeCode.BLOCKED,
                detail = "Target absent but installed outside user 0")
            AddEligibility.ABSENT -> DozeOutcome(DozeOutcomeCode.BLOCKED,
                detail = "Target absent from all sources")
        }

    private fun requireWriteEligible(id: DozeIdentity) {
        require(id.installedUserIds == setOf(0) && id.pccId == -1 &&
            id.uidOwnerPackages == setOf(target.packageName)) { "Target write scope is ambiguous" }
    }

    private fun ByteArray?.sameBytes(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
}
