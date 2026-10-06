package io.github.originosfcmguardian.autoprotection

internal data class BudgetClock(val boot: Long, val elapsed: Long, val epoch: Long)
internal data class BudgetSnapshot(val clock: BudgetClock, val attempts: List<Long>, val quarantineUntil: Long?)
internal interface AttemptBudgetStorage {
    fun <T> exclusive(action: () -> T): T
    /** null only means an absent file. Malformed/unreadable data must throw. */
    fun read(): BudgetSnapshot?
    /** Commit atomically before an attempt can receive permission. */
    fun write(value: BudgetSnapshot)
}

/** Historical budget algorithm retained for stored-record compatibility tests, unused by production recovery. */
internal class PersistentAttemptBudget(private val storage: AttemptBudgetStorage) {
    @Synchronized fun take(now: BudgetClock): AutomaticCheckDecision = evaluate(now, reserve = true)

    /** Requires existing state; normalization/pruning never creates or consumes a reservation. */
    @Synchronized fun check(now: BudgetClock): AutomaticCheckDecision = evaluate(now, reserve = false)

    private fun evaluate(now: BudgetClock, reserve: Boolean): AutomaticCheckDecision {
        if (now.boot < 0 || now.elapsed < 0 || now.epoch < 0) return failure()
        return try {
            storage.exclusive {
            val prior = storage.read()
            if (prior == null && !reserve) return@exclusive failure()
            if (prior != null && !valid(prior)) return@exclusive failure()
            val discontinuity = prior != null && (now.boot != prior.clock.boot ||
                now.elapsed < prior.clock.elapsed || now.epoch < prior.clock.epoch ||
                Math.subtractExact(Math.subtractExact(now.epoch, prior.clock.epoch),
                    Math.subtractExact(now.elapsed, prior.clock.elapsed)) !in -CLOCK_TOLERANCE..CLOCK_TOLERANCE)
            if (discontinuity && (requireNotNull(prior).attempts.isNotEmpty() || prior.quarantineUntil != null)) {
                // On a forward boot all old debt predates this boot. elapsedRealtime includes
                // suspend, so uptime alone can prove expiry; never age debt using wall time.
                // Counter/epoch regression and same-boot clock drift retain the old conservative wait.
                val forwardBoot = now.boot > prior.clock.boot && now.epoch >= prior.clock.epoch
                val until = if (forwardBoot) HOUR else Math.addExact(now.elapsed, HOUR)
                if (until > now.elapsed) {
                    storage.write(BudgetSnapshot(now, emptyList(), until))
                    return@exclusive AutomaticCheckDecision.Cooldown(until - now.elapsed)
                }
            }
            // A validated empty budget or proven-expired preboot debt may rebase. check() does
            // not reserve; take() must durably commit a new reservation below before returning.
            val retained = if (discontinuity) BudgetSnapshot(now, emptyList(), null) else prior
            val quarantine = retained?.quarantineUntil
            if (quarantine != null && quarantine > now.elapsed) {
                storage.write(requireNotNull(retained).copy(clock = now))
                return@exclusive AutomaticCheckDecision.Cooldown(quarantine - now.elapsed)
            }
            val attempts = retained?.attempts.orEmpty().filter { now.elapsed - it < HOUR }
            val result = when {
                attempts.size >= 3 -> AutomaticCheckDecision.Cooldown(HOUR - (now.elapsed - attempts.first()))
                attempts.lastOrNull()?.let { now.elapsed - it < INTERVAL } == true ->
                    AutomaticCheckDecision.Cooldown(INTERVAL - (now.elapsed - attempts.last()))
                else -> AutomaticCheckDecision.Recover
            }
            val next = if (reserve && result == AutomaticCheckDecision.Recover) attempts + now.elapsed else attempts
            storage.write(BudgetSnapshot(now, next, null))
            result
            }
        } catch (_: Exception) { failure() }
    }

    private fun valid(value: BudgetSnapshot): Boolean = value.clock.boot >= 0 && value.clock.elapsed >= 0 &&
        value.clock.epoch >= 0 && value.attempts.size <= 3 && value.attempts.all { it in 0..value.clock.elapsed } &&
        value.attempts.zipWithNext().all { (a, b) -> b - a >= INTERVAL } &&
        (value.quarantineUntil == null || (value.attempts.isEmpty() && value.quarantineUntil >= value.clock.elapsed &&
            value.quarantineUntil - value.clock.elapsed <= HOUR))

    private fun failure() = AutomaticCheckDecision.Pause("保护不可用：持久限频记录无法确认")
    private companion object {
        const val INTERVAL = 300_000L
        const val HOUR = 3_600_000L
        const val CLOCK_TOLERANCE = 5_000L
    }
}
