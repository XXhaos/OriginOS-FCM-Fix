package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.daemon.*
import java.security.MessageDigest

internal const val DAEMON_SAMPLE_PREVIEW = 16
internal const val DAEMON_TIME_BUCKET_LIMIT = 168
private const val HOUR_MILLIS = 3_600_000L
private const val SAMPLE_GAP_MILLIS = 600_000L

/** Export-time arithmetic only. Samples are not proof of uninterrupted protection or energy use. */
internal data class DaemonResourceSummary(
    val sampleCount: Int, val firstSampleAtEpochMillis: Long?, val lastSampleAtEpochMillis: Long?,
    val cpuDeltaMillis: Long?, val cpuMeasuredElapsedMillis: Long?, val cpuSingleCorePercent: Double?,
    val rssMinKiB: Long?, val rssMaxKiB: Long?, val rssDeltaKiB: Long?, val threadsMax: Long?,
    val invalidClockIntervals: Int, val gapIntervals: Int,
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
    return DaemonResourceSummary(retained.size, retained.firstOrNull()?.atMillis, retained.lastOrNull()?.atMillis,
        cpu.takeIf { measured > 0 }, measured.takeIf { it > 0 },
        if (measured > 0) cpu.toDouble() / measured * 100.0 else null,
        rss.minOrNull(), rss.maxOrNull(), delta, retained.mapNotNull { it.threads }.maxOrNull(), invalid, gaps)
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
    item("gapIntervals", s.gapIntervals, false); append('}')
}

internal fun StringBuilder.appendDaemonDiagnosticsMarkdown(snapshot: DaemonDiagnosticsSnapshot) {
    append("\n## 独立保护核心运行记录\n\n")
    append("采集状态：").append(snapshot.availability.name).append("\n\n")
    append("独立快照时间：").append(snapshot.generatedAtMillis.takeIf { it > 0 } ?: "未知").append("\n\n")
    append("最多2016条五分钟机会样本（约7天容量，非固定留存期限），32个会话。BEGIN不等于保护运行；READY为离散观察，不能证明持续运行。\n\n")
    append("CPU仅为该核心进程，不包含子进程、GMS或无线电；电量/充电是整机参考。诊断与授权快照不是同一事务。未正常结束不是已确认被杀，原因未知。\n\n")
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
    }
    val all = buckets(snapshot)
    append("### 按小时的留存资源趋势\n\n")
    append("仅汇总实际留存样本；小时边界为UTC，未展示小时段：").append((all.size - DAEMON_TIME_BUCKET_LIMIT).coerceAtLeast(0)).append("。\n\n")
    all.takeLast(DAEMON_TIME_BUCKET_LIMIT).forEach { bucket ->
        val s = summarizeDaemonSamples(bucket.session, bucket.samples)
        append("- ").append(bucket.hour).append(" / ").append(reference(bucket.session.sessionId)).append("：样本=").append(s.sampleCount)
            .append("，CPUms=").append(s.cpuDeltaMillis ?: "未知").append("，区间ms=").append(s.cpuMeasuredElapsedMillis ?: "未知")
            .append("，RSS范围KiB=").append(s.rssMinKiB ?: "未知").append("–").append(s.rssMaxKiB ?: "未知")
            .append("，空档=").append(s.gapIntervals).append('\n')
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
