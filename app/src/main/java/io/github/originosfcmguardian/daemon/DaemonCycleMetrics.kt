package io.github.originosfcmguardian.daemon

/** Awaited host-cycle wall latency, including dispatcher/queue waits; never CPU or child runtime. */
enum class DaemonCycleStage { STOPPED_CYCLE, FCM_CYCLE }
enum class DaemonCycleOutcome { CHECKED, RECONCILED, WAITING, BUSY, THROTTLED, PENDING, UNAVAILABLE }

data class DaemonCycleOutcomes(
    val checked: Long = 0, val reconciled: Long = 0, val waiting: Long = 0,
    val busy: Long = 0, val throttled: Long = 0, val pending: Long = 0, val unavailable: Long = 0,
) {
    internal fun record(outcome: DaemonCycleOutcome) = when (outcome) {
        DaemonCycleOutcome.CHECKED -> copy(checked = saturatedAdd(checked, 1))
        DaemonCycleOutcome.RECONCILED -> copy(reconciled = saturatedAdd(reconciled, 1))
        DaemonCycleOutcome.WAITING -> copy(waiting = saturatedAdd(waiting, 1))
        DaemonCycleOutcome.BUSY -> copy(busy = saturatedAdd(busy, 1))
        DaemonCycleOutcome.THROTTLED -> copy(throttled = saturatedAdd(throttled, 1))
        DaemonCycleOutcome.PENDING -> copy(pending = saturatedAdd(pending, 1))
        DaemonCycleOutcome.UNAVAILABLE -> copy(unavailable = saturatedAdd(unavailable, 1))
    }
    internal fun counts() = listOf(checked, reconciled, waiting, busy, throttled, pending, unavailable)
}

data class DaemonCycleStageMetrics(
    val completedCount: Long = 0, val totalElapsedMillis: Long = 0, val maxElapsedMillis: Long = 0,
    val outcomes: DaemonCycleOutcomes = DaemonCycleOutcomes(),
) {
    internal fun record(duration: Long, outcome: DaemonCycleOutcome) = copy(
        completedCount = saturatedAdd(completedCount, 1), totalElapsedMillis = saturatedAdd(totalElapsedMillis, duration),
        maxElapsedMillis = maxOf(maxElapsedMillis, duration), outcomes = outcomes.record(outcome))
}

/** Null on a legacy session means unmeasured, whereas explicit zero means measured with no completions. */
data class DaemonCycleMetrics(
    val version: Int = 1,
    val startedAtMillis: Long, val startedElapsedMillis: Long,
    val lastCheckpointAtMillis: Long, val lastCheckpointElapsedMillis: Long,
    val stopped: DaemonCycleStageMetrics = DaemonCycleStageMetrics(),
    val fcm: DaemonCycleStageMetrics = DaemonCycleStageMetrics(),
) {
    internal fun record(stage: DaemonCycleStage, duration: Long, outcome: DaemonCycleOutcome, wall: Long, elapsed: Long) =
        copy(lastCheckpointAtMillis = wall, lastCheckpointElapsedMillis = elapsed,
            stopped = if (stage == DaemonCycleStage.STOPPED_CYCLE) stopped.record(duration, outcome) else stopped,
            fcm = if (stage == DaemonCycleStage.FCM_CYCLE) fcm.record(duration, outcome) else fcm)

    internal fun validate() {
        require(version == 1 && startedAtMillis > 0 && startedElapsedMillis >= 0 && lastCheckpointAtMillis > 0 &&
            lastCheckpointElapsedMillis >= startedElapsedMillis)
        listOf(stopped, fcm).forEach { stage ->
            val counts = stage.outcomes.counts()
            require(counts.all { it >= 0 } && stage.completedCount >= 0 && stage.totalElapsedMillis >= 0 &&
                stage.maxElapsedMillis in 0..stage.totalElapsedMillis)
            require(counts.fold(0L, ::saturatedAdd) == stage.completedCount)
            require(stage.completedCount != 0L || stage.totalElapsedMillis == 0L)
        }
    }
}

internal fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
