package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.daemon.*
import java.security.MessageDigest

internal const val DAEMON_SAMPLE_PREVIEW = 16
internal const val DAEMON_TIME_BUCKET_LIMIT = 168
private const val HOUR_MILLIS = 3_600_000L
private const val SAMPLE_GAP_MILLIS = 600_000L
private const val SAMPLED_CONTEXT_SCOPE = "RETAINED_SAMPLE_POINTS_NOT_CONTINUOUS_STATE_OR_GUARDIAN_ENERGY"
private val CHARGING_STATES = listOf("CHARGING", "FULL", "DISCHARGING", "NOT_CHARGING", "UNKNOWN")

internal data class DaemonSampledStates(val trueCount: Int, val falseCount: Int, val unknownCount: Int)
internal data class DaemonSampledContext(
    val batteryKnownCount: Int, val batteryUnknownCount: Int,
    val batteryFirstPercent: Int?, val batteryLastPercent: Int?, val batteryMinPercent: Int?, val batteryMaxPercent: Int?,
    val chargingStatusCounts: Map<String, Int>, val interactive: DaemonSampledStates, val deviceIdle: DaemonSampledStates,
)

/** Export-time arithmetic only. Samples are not proof of uninterrupted protection or energy use. */
internal data class DaemonResourceSummary(
    val sampleCount: Int, val firstSampleAtEpochMillis: Long?, val lastSampleAtEpochMillis: Long?,
    val cpuDeltaMillis: Long?, val cpuMeasuredElapsedMillis: Long?, val cpuSingleCorePercent: Double?,
    val rssMinKiB: Long?, val rssMaxKiB: Long?, val rssDeltaKiB: Long?, val threadsMax: Long?,
    val invalidClockIntervals: Int, val gapIntervals: Int,
    val sampledContext: DaemonSampledContext,
)

internal fun summarizeDaemonSamples(session: DaemonDiagnosticsSession, samples: List<DaemonDiagnosticsSample>): DaemonResourceSummary {
    val retained = samples.filter { it.sessionId == session.sessionId }.sortedBy { it.elapsedMillis }
    fun clockValid(s: DaemonDiagnosticsSample) = runCatching {
        val monotonic = Math.subtractExact(s.elapsedMillis, session.startedElapsedMillis)
        val wall = Math.subtractExact(s.atMillis, session.startedAtMillis)
        monotonic >= 0 && Math.subtractExact(wall, monotonic) in -2_000L..2_000L
    }.getOrDefault(false)
    var cpu = 0L; var measured = 0L; var invalid = 0; var gaps = 0
    for ((first, next) in retained.zipWithNext()) {
        val duration = next.elapsedMillis - first.elapsedMillis
        if (!clockValid(first) || !clockValid(next) || duration <= 0) { invalid++; continue }
        if (duration > SAMPLE_GAP_MILLIS) gaps++
        val a = first.cpuTimeMillis; val b = next.cpuTimeMillis
        if (a == null || b == null || b < a) continue
        val sums = runCatching { Math.addExact(cpu, b - a) to Math.addExact(measured, duration) }.getOrNull() ?: continue
        cpu = sums.first; measured = sums.second
    }
    val rss = retained.mapNotNull { it.rssKiB }
    val delta = if (retained.size >= 2 && retained.all(::clockValid) && invalid == 0)
        retained.first().rssKiB?.let { a -> retained.last().rssKiB?.let { b -> b - a } } else null
    fun states(read: (DaemonDiagnosticsSample) -> Boolean?) = DaemonSampledStates(
        retained.count { read(it) == true }, retained.count { read(it) == false }, retained.count { read(it) == null })
    val battery = retained.mapNotNull { it.batteryPercent }
    // These are sampled points, including unknown endpoints, never interpolated state durations or discharge.
    val context = DaemonSampledContext(battery.size, retained.size - battery.size,
        retained.firstOrNull()?.batteryPercent, retained.lastOrNull()?.batteryPercent, battery.minOrNull(), battery.maxOrNull(),
        CHARGING_STATES.associateWith { state -> retained.count { (it.chargingStatus ?: "UNKNOWN") == state } },
        states { it.interactive }, states { it.deviceIdle })
    return DaemonResourceSummary(retained.size, retained.firstOrNull()?.atMillis, retained.lastOrNull()?.atMillis,
        cpu.takeIf { measured > 0 }, measured.takeIf { it > 0 },
        if (measured > 0) cpu.toDouble() / measured * 100.0 else null,
        rss.minOrNull(), rss.maxOrNull(), delta, retained.mapNotNull { it.threads }.maxOrNull(), invalid, gaps, context)
}

// Stable opaque diagnostic references allow cross-report matching without exporting raw boot/session UUIDs.
private fun reference(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
private data class Bucket(val session: DaemonDiagnosticsSession, val hour: Long, val samples: List<DaemonDiagnosticsSample>)
private fun buckets(snapshot: DaemonDiagnosticsSnapshot): List<Bucket> = snapshot.sessions.flatMap { session ->
    snapshot.samples.filter { it.sessionId == session.sessionId }.groupBy { it.atMillis / HOUR_MILLIS * HOUR_MILLIS }
        .map { (hour, samples) -> Bucket(session, hour, samples) }
}.sortedWith(compareBy<Bucket> { it.hour }.thenBy { it.session.startedElapsedMillis })

internal fun StringBuilder.appendDaemonDiagnosticsJson(snapshot: DaemonDiagnosticsSnapshot) {
    val available = snapshot.availability == DaemonDiagnosticsAvailability.AVAILABLE
    val sessions = if (available) snapshot.sessions else emptyList()
    val samples = if (available) snapshot.samples else emptyList()
    val allBuckets = if (available) buckets(snapshot) else emptyList()
    append('{')
    item("availability", snapshot.availability.name)
    item("collectionScope", "GUARDIAN_PRIVILEGED_DAEMON_HISTORY")
    item("generatedAtEpochMillis", snapshot.generatedAtMillis.takeIf { it > 0 })
    item("generatedElapsedMillis", snapshot.generatedElapsedMillis.takeIf { snapshot.generatedAtMillis > 0 })
    item("currentSession", snapshot.currentSessionId?.let(::reference))
    item("generation", snapshot.generation)
    item("retainedSessionCount", sessions.size.takeIf { available })
    item("retainedSampleCount", samples.size.takeIf { available })
    item("evictedSessions", snapshot.evictedSessions); item("evictedSamples", snapshot.evictedSamples)
    item("evictedEvents", snapshot.evictedEvents); item("skippedSamples", snapshot.skippedSamples)
    item("failedOperations", snapshot.failedOperations); item("unknownIntervals", snapshot.unknownIntervals)
    item("sampleMinimumIntervalMillis", 300_000)
    item("sampleCapacity", DaemonDiagnosticsCodec.MAX_SAMPLES)
    item("capacityScope", "COUNT_BOUND_NOT_SEVEN_DAY_TTL")
    item("snapshotScope", "INDEPENDENT_DIAGNOSTIC_SNAPSHOT_NOT_CONTROL_TRANSACTION")
    item("cpuScope", "SAME_HOST_SESSION_PROCESS_CPU_EXCLUDES_CHILDREN_GMS_AND_RADIO")
    item("batteryScope", "WHOLE_PHONE_CONTEXT_NOT_GUARDIAN_ENERGY")
    item("sampledContextScope", SAMPLED_CONTEXT_SCOPE)
    item("cycleMetricsScope", "AWAITED_POLL_WALL_LATENCY_INCLUDES_DISPATCH_WAIT_NOT_CPU_OR_CHILD_RUNTIME")
    item("cycleOutcomeScope", "CYCLE_RESULT_NOT_TARGET_CHECK_WRITE_OR_FCM_DELIVERY_COUNTS")
    item("cycleMetricsPersistenceScope", "LIVE_COUNTERS_PERIODIC_PERSISTENCE_UNCOMMITTED_TAIL_MAY_BE_LOST")
    item("unknownIntervalScope", "UNSETTLED_SESSIONS_OR_POLL_GAPS_NOT_CONFIRMED_KILLS")
    item("continuousProtectionProven", false)
    append("\"sessions\":[")
    sessions.forEachIndexed { index, session ->
        if (index > 0) append(',')
        append('{'); item("session", reference(session.sessionId)); item("boot", reference(session.bootId))
        item("processId", session.processId); item("versionCode", session.versionCode)
        item("startedAtEpochMillis", session.startedAtMillis); item("startedElapsedMillis", session.startedElapsedMillis)
        item("lastCheckpointAtEpochMillis", session.lastCheckpointAtMillis)
        item("lastCheckpointElapsedMillis", session.lastCheckpointElapsedMillis)
        item("state", session.state.name); item("normalEndedAtEpochMillis", session.endedAtMillis)
        item("normalEndedElapsedMillis", session.endedElapsedMillis)
        item("stoppedCheckedAtEpochMillis", session.stoppedCheckedAtMillis)
        item("fcmCheckedAtEpochMillis", session.fcmCheckedAtMillis); item("maxPollGapMillis", session.maxPollGapMillis)
        // No exact death time or cause is fabricated from a missing clean-end record.
        item("exitReason", if (session.state == DaemonDiagnosticsState.UNKNOWN_END) "UNKNOWN" else null)
        append("\"cycleMetrics\":"); cycleMetrics(session.cycleMetrics); append(',')
        append("\"summary\":"); summary(summarizeDaemonSamples(session, samples)); append('}')
    }
    append("],"); item("omittedTimeBuckets", (allBuckets.size - DAEMON_TIME_BUCKET_LIMIT).coerceAtLeast(0))
    append("\"timeBuckets\":[")
    allBuckets.takeLast(DAEMON_TIME_BUCKET_LIMIT).forEachIndexed { index, bucket ->
        if (index > 0) append(',')
        append('{'); item("session", reference(bucket.session.sessionId)); item("hourEpochMillis", bucket.hour)
        append("\"summary\":"); summary(summarizeDaemonSamples(bucket.session, bucket.samples)); append('}')
    }
    append("],\"events\":[")
    (if (available) snapshot.events else emptyList()).forEachIndexed { index, event ->
        if (index > 0) append(',')
        append('{'); item("session", reference(event.sessionId)); item("atEpochMillis", event.atMillis)
        item("elapsedMillis", event.elapsedMillis); item("state", event.state.name, false); append('}')
    }
    append("],"); item("omittedRawSamples", (samples.size - DAEMON_SAMPLE_PREVIEW).coerceAtLeast(0))
    append("\"recentSamples\":[")
    samples.takeLast(DAEMON_SAMPLE_PREVIEW).forEachIndexed { index, s ->
        if (index > 0) append(',')
        append('{'); item("session", reference(s.sessionId)); item("atEpochMillis", s.atMillis)
        item("elapsedMillis", s.elapsedMillis); item("cpuTimeMillis", s.cpuTimeMillis); item("rssKiB", s.rssKiB)
        item("heapUsedBytes", s.heapUsedBytes); item("heapMaxBytes", s.heapMaxBytes)
        item("nativeAllocatedBytes", s.nativeAllocatedBytes); item("threads", s.threads)
        item("batteryPercent", s.batteryPercent); item("chargingStatus", s.chargingStatus)
        item("interactive", s.interactive); item("deviceIdle", s.deviceIdle, false); append('}')
    }
    append("]}")
}

private fun StringBuilder.summary(s: DaemonResourceSummary) {
    append('{'); item("sampleCount", s.sampleCount); item("firstSampleAtEpochMillis", s.firstSampleAtEpochMillis)
    item("lastSampleAtEpochMillis", s.lastSampleAtEpochMillis); item("cpuDeltaMillis", s.cpuDeltaMillis)
    item("cpuMeasuredElapsedMillis", s.cpuMeasuredElapsedMillis); item("cpuSingleCorePercent", s.cpuSingleCorePercent)
    item("rssMinKiB", s.rssMinKiB); item("rssMaxKiB", s.rssMaxKiB); item("rssDeltaKiB", s.rssDeltaKiB)
    item("threadsMax", s.threadsMax); item("invalidClockIntervals", s.invalidClockIntervals)
    item("gapIntervals", s.gapIntervals)
    append("\"sampledContext\":"); sampledContext(s.sampledContext); append('}')
}

private fun StringBuilder.cycleMetrics(m: DaemonCycleMetrics?) {
    if (m == null) { append("null"); return }
    append('{'); item("version", m.version); item("startedAtEpochMillis", m.startedAtMillis)
    item("startedElapsedMillis", m.startedElapsedMillis); item("lastCheckpointAtEpochMillis", m.lastCheckpointAtMillis)
    item("lastCheckpointElapsedMillis", m.lastCheckpointElapsedMillis)
    append("\"stopped\":"); cycleStage(m.stopped); append(",\"fcm\":"); cycleStage(m.fcm); append('}')
}
private fun StringBuilder.cycleStage(s: DaemonCycleStageMetrics) {
    append('{'); item("completedCount", s.completedCount); item("totalElapsedMillis", s.totalElapsedMillis)
    item("maxElapsedMillis", s.maxElapsedMillis)
    append("\"outcomes\":{"); item("checked", s.outcomes.checked); item("reconciled", s.outcomes.reconciled)
    item("waiting", s.outcomes.waiting); item("busy", s.outcomes.busy); item("throttled", s.outcomes.throttled)
    item("pending", s.outcomes.pending); item("unavailable", s.outcomes.unavailable, false); append("}}")
}
private fun StringBuilder.cycleStageMarkdown(label: String, s: DaemonCycleStageMetrics) {
    append(label).append("周期完成/总等待ms/最长等待ms=").append(s.completedCount).append('/')
        .append(s.totalElapsedMillis).append('/').append(s.maxElapsedMillis)
    append("；结果[checked/reconciled/waiting/busy/throttled/pending/unavailable]=")
        .append(listOf(s.outcomes.checked, s.outcomes.reconciled, s.outcomes.waiting, s.outcomes.busy,
            s.outcomes.throttled, s.outcomes.pending, s.outcomes.unavailable).joinToString("/")).append("\n\n")
}

private fun StringBuilder.sampledContext(c: DaemonSampledContext) {
    append('{'); item("batteryKnownCount", c.batteryKnownCount); item("batteryUnknownCount", c.batteryUnknownCount)
    item("batteryFirstPercent", c.batteryFirstPercent); item("batteryLastPercent", c.batteryLastPercent)
    item("batteryMinPercent", c.batteryMinPercent); item("batteryMaxPercent", c.batteryMaxPercent)
    append("\"chargingStatusCounts\":{")
    CHARGING_STATES.forEachIndexed { index, state -> item(state, c.chargingStatusCounts.getValue(state), index < CHARGING_STATES.lastIndex) }
    append("},\"interactive\":"); sampledStates(c.interactive)
    append(",\"deviceIdle\":"); sampledStates(c.deviceIdle); append('}')
}
private fun StringBuilder.sampledStates(s: DaemonSampledStates) {
    append('{'); item("trueCount", s.trueCount); item("falseCount", s.falseCount); item("unknownCount", s.unknownCount, false); append('}')
}

private fun StringBuilder.sampledContextMarkdown(c: DaemonSampledContext) {
    append("电量首/末/最小/最大%=").append(c.batteryFirstPercent ?: "未知").append('/')
        .append(c.batteryLastPercent ?: "未知").append('/').append(c.batteryMinPercent ?: "未知").append('/')
        .append(c.batteryMaxPercent ?: "未知").append("，电量已知/未知样本=").append(c.batteryKnownCount).append('/').append(c.batteryUnknownCount)
    append("，充电状态样本[CHARGING/FULL/DISCHARGING/NOT_CHARGING/UNKNOWN]=")
        .append(CHARGING_STATES.joinToString("/") { c.chargingStatusCounts.getValue(it).toString() })
    append("，交互是/否/未知样本=").append(c.interactive.trueCount).append('/').append(c.interactive.falseCount).append('/').append(c.interactive.unknownCount)
    append("，idle是/否/未知样本=").append(c.deviceIdle.trueCount).append('/').append(c.deviceIdle.falseCount).append('/').append(c.deviceIdle.unknownCount)
}

internal fun StringBuilder.appendDaemonDiagnosticsMarkdown(snapshot: DaemonDiagnosticsSnapshot) {
    append("\n## 独立保护核心运行记录\n\n")
    append("采集状态：").append(snapshot.availability.name).append("\n\n")
    append("独立快照时间：").append(snapshot.generatedAtMillis.takeIf { it > 0 } ?: "未知").append("\n\n")
    append("最多2016条五分钟机会样本（约7天容量，非固定留存期限），32个会话。BEGIN不等于保护运行；READY为离散观察，不能证明持续运行。\n\n")
    append("CPU仅为该核心进程，不包含子进程、GMS或无线电；电量/充电是整机参考。诊断与授权快照不是同一事务。未正常结束不是已确认被杀，原因未知。\n\n")
    append("电量首末值保留实际端点的未知；充电、交互和idle仅计留存采样点，不推断空档内连续状态、持续时长、耗电率或Guardian能耗。\n\n")
    append("周期统计仅计awaited poll的墙钟等待（含调度/等待），不是CPU或子进程耗时；周期结果不是目标检查数、写入数或FCM送达数。计数/耗时达到Long上限后饱和。\n\n")
    append("周期计数包含内存实时值，仅随既有采样/状态提交持久化；突然终止可能丢失尚未提交的尾段。周期检查点是最近完成时间，不是保护覆盖或持久化提交证明。\n\n")
    append("淘汰：会话=").append(snapshot.evictedSessions).append("，样本=").append(snapshot.evictedSamples)
        .append("，事件=").append(snapshot.evictedEvents).append("；跳过=").append(snapshot.skippedSamples)
        .append("，失败=").append(snapshot.failedOperations).append("，未结算会话/采样空档=").append(snapshot.unknownIntervals).append("\n\n")
    if (snapshot.availability != DaemonDiagnosticsAvailability.AVAILABLE) return
    snapshot.sessions.forEach { session ->
        val s = summarizeDaemonSamples(session, snapshot.samples)
        append("### 核心会话 ").append(reference(session.sessionId)).append("\n\n")
        append("版本：").append(session.versionCode).append("；进程：").append(session.processId)
            .append("；状态：").append(session.state.name).append("\n\n")
        append("开始/最后检查点：").append(session.startedAtMillis).append(" / ").append(session.lastCheckpointAtMillis)
            .append("；正常结束：").append(session.endedAtMillis ?: "未知/尚无记录").append("\n\n")
        append("stopped/FCM最近检查：").append(session.stoppedCheckedAtMillis ?: "未知").append(" / ")
            .append(session.fcmCheckedAtMillis ?: "未知").append("；最大机会空档ms：").append(session.maxPollGapMillis).append("\n\n")
        append("样本：").append(s.sampleCount).append("；CPU累计增量ms：").append(s.cpuDeltaMillis ?: "未知")
            .append("；可比较区间ms：").append(s.cpuMeasuredElapsedMillis ?: "未知")
            .append("；RSS最小/最大KiB：").append(s.rssMinKiB ?: "未知").append(" / ").append(s.rssMaxKiB ?: "未知")
            .append("；时钟无效/样本空档：").append(s.invalidClockIntervals).append(" / ").append(s.gapIntervals).append("\n\n")
        sampledContextMarkdown(s.sampledContext); append("\n\n")
        val metrics = session.cycleMetrics
        if (metrics == null) append("周期统计：未知（旧会话无记录）。\n\n") else {
            append("周期统计开始/最近完成检查点：").append(metrics.startedAtMillis).append('/').append(metrics.lastCheckpointAtMillis)
                .append("；elapsed：").append(metrics.startedElapsedMillis).append('/').append(metrics.lastCheckpointElapsedMillis).append("\n\n")
            cycleStageMarkdown("stopped", metrics.stopped); cycleStageMarkdown("FCM", metrics.fcm)
        }
    }
    val all = buckets(snapshot)
    append("### 按小时的留存资源趋势\n\n")
    append("仅汇总实际留存样本；小时边界为UTC，未展示小时段：").append((all.size - DAEMON_TIME_BUCKET_LIMIT).coerceAtLeast(0)).append("。\n\n")
    all.takeLast(DAEMON_TIME_BUCKET_LIMIT).forEach { bucket ->
        val s = summarizeDaemonSamples(bucket.session, bucket.samples)
        append("- ").append(bucket.hour).append(" / ").append(reference(bucket.session.sessionId)).append("：样本=").append(s.sampleCount)
            .append("，CPUms=").append(s.cpuDeltaMillis ?: "未知").append("，区间ms=").append(s.cpuMeasuredElapsedMillis ?: "未知")
            .append("，RSS范围KiB=").append(s.rssMinKiB ?: "未知").append("–").append(s.rssMaxKiB ?: "未知")
            .append("，空档=").append(s.gapIntervals).append("；")
        sampledContextMarkdown(s.sampledContext); append('\n')
    }
}

private fun StringBuilder.item(key: String, value: Any?, comma: Boolean = true) {
    quote(key); append(':')
    when (value) {
        null -> append("null")
        is String -> quote(value)
        is Double -> if (value.isFinite()) append(value) else append("null")
        is Number, is Boolean -> append(value)
        else -> error("Unsupported diagnostic value")
    }
    if (comma) append(',')
}
private fun StringBuilder.quote(value: String) {
    append('"')
    value.forEach { when (it) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (it.code < 32) append("\\u%04x".format(it.code)) else append(it)
    } }
    append('"')
}
