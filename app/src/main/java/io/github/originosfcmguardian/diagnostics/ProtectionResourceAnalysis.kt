package io.github.originosfcmguardian.diagnostics

import io.github.originosfcmguardian.autoprotection.*

internal enum class ResourceExitCorrelation {
    PID_AND_RETAINED_LIFETIME_MATCH, NO_PRE_EXIT_SAMPLE, AMBIGUOUS_PROCESS_LIFETIME,
    CLOCK_DISCONTINUITY, STALE_PRE_EXIT_SAMPLE, RESOURCE_UNAVAILABLE,
}

/** Export-only correlation, never an authorization, leak diagnosis or precise kill attribution. */
internal data class ResourceExitAnalysis(
    val exit: ProtectionResourceExit,
    val correlation: ResourceExitCorrelation,
    val nearestSample: ProtectionResourceSample? = null,
    val nearestSampleAgeMillis: Long? = null,
    val retainedSameProcessSamples: Int = 0,
    val retainedTrendElapsedMillis: Long? = null,
    val rssDeltaKiB: Long? = null,
    val heapDeltaBytes: Long? = null,
    val threadDelta: Long? = null,
    val cpuDeltaMillis: Long? = null,
    val readerTimeoutDelta: Long? = null,
    val readerRejectionDelta: Long? = null,
    val sampledSystemLowMemory: Boolean? = null,
)

private const val PRE_EXIT_FRESHNESS_MILLIS = 600_000L
private const val CLOCK_TOLERANCE_MILLIS = 2_000L
internal const val RESOURCE_PREVIEW_LIMIT = 16

private fun clockConsistent(sample: ProtectionResourceSample): Boolean = runCatching {
    val wall = Math.subtractExact(sample.atMillis, sample.processStartedAtMillis)
    val monotonic = Math.subtractExact(sample.elapsedMillis, sample.processStartedElapsedMillis)
    val difference = Math.subtractExact(wall, monotonic)
    wall >= 0 && difference in -CLOCK_TOLERANCE_MILLIS..CLOCK_TOLERANCE_MILLIS
}.getOrDefault(false)

internal fun analyzeResourceExit(snapshot: ProtectionResourceSnapshot, exit: ProtectionResourceExit): ResourceExitAnalysis {
    fun unknown(reason: ResourceExitCorrelation) = ResourceExitAnalysis(exit, reason)
    if (snapshot.availability != ProtectionResourceAvailability.AVAILABLE) return unknown(ResourceExitCorrelation.RESOURCE_UNAVAILABLE)
    val groups = snapshot.samples.filter { it.processId == exit.processId }.groupBy { it.processInstance }
    val possible = groups.values.filter { group ->
        val start = group.first().processStartedAtMillis
        val nextStart = groups.values.map { it.first().processStartedAtMillis }.filter { it > start }.minOrNull()
        start <= exit.atMillis && (nextStart == null || exit.atMillis < nextStart) && group.any { it.atMillis <= exit.atMillis }
    }
    if (possible.isEmpty()) return unknown(ResourceExitCorrelation.NO_PRE_EXIT_SAMPLE)
    if (possible.size != 1) return unknown(ResourceExitCorrelation.AMBIGUOUS_PROCESS_LIFETIME)
    val group = possible.single()
    if (group.any { it.atMillis > exit.atMillis }) return unknown(ResourceExitCorrelation.AMBIGUOUS_PROCESS_LIFETIME)
    val first = group.first()
    if (group.any { !clockConsistent(it) || it.processStartedAtMillis != first.processStartedAtMillis ||
            it.processStartedElapsedMillis != first.processStartedElapsedMillis || it.versionCode != first.versionCode })
        return unknown(ResourceExitCorrelation.CLOCK_DISCONTINUITY)
    val retained = group.filter { it.atMillis <= exit.atMillis }.sortedBy { it.elapsedMillis }
    if (retained.zipWithNext().any { (a, b) -> b.atMillis < a.atMillis }) return unknown(ResourceExitCorrelation.CLOCK_DISCONTINUITY)
    val oldest = retained.first()
    val nearest = retained.last()
    val age = exit.atMillis - nearest.atMillis
    if (age > PRE_EXIT_FRESHNESS_MILLIS) return ResourceExitAnalysis(exit, ResourceExitCorrelation.STALE_PRE_EXIT_SAMPLE, nearest, age)
    fun delta(a: Long?, b: Long?) = if (a != null && b != null && retained.size >= 2) b - a else null
    val pressure = retained.mapNotNull { it.systemLowMemory }.let { flags ->
        if (flags.isEmpty()) null else flags.any { it }
    }
    return ResourceExitAnalysis(exit, ResourceExitCorrelation.PID_AND_RETAINED_LIFETIME_MATCH,
        nearest, age, retained.size, nearest.elapsedMillis - oldest.elapsedMillis,
        delta(oldest.rssKiB, nearest.rssKiB), delta(oldest.heapUsedBytes, nearest.heapUsedBytes),
        delta(oldest.threads, nearest.threads), delta(oldest.cpuTimeMillis, nearest.cpuTimeMillis)?.takeIf { it >= 0 },
        delta(oldest.readerTimeouts, nearest.readerTimeouts)?.takeIf { it >= 0 },
        delta(oldest.readerRejections, nearest.readerRejections)?.takeIf { it >= 0 }, pressure)
}

internal fun actualFcmObservationAge(sample: ProtectionResourceSample): Long? =
    sample.fcmObservationElapsedMillis?.let {
        if (it in sample.processStartedElapsedMillis..sample.elapsedMillis) sample.elapsedMillis - it else null
    }

/** Independent bounded section; complete raw history stays in the local sidecar, not the UI. */
internal fun StringBuilder.appendProtectionResourceJson(snapshot: ProtectionResourceSnapshot,
    daemonAvailability: String = "NOT_COLLECTED") {
    append('{')
    resourceField("availability", snapshot.availability.name)
    resourceField("collectionScope", "GUARDIAN_APP_PROCESS_HISTORY")
    resourceField("daemonResourceAvailability", daemonAvailability)
    resourceField("retainedSampleCount", snapshot.samples.size)
    resourceField("retainedExitCount", snapshot.exits.size)
    resourceField("evictedSamples", snapshot.evictedSamples)
    resourceField("evictedExits", snapshot.evictedExits)
    resourceField("skippedOffers", snapshot.skipped)
    resourceField("failedOperations", snapshot.failed)
    resourceField("exitHistoryAvailable", snapshot.exitHistoryAvailable)
    resourceField("oldestSampleAtEpochMillis", snapshot.samples.minOfOrNull { it.atMillis })
    resourceField("newestSampleAtEpochMillis", snapshot.samples.maxOfOrNull { it.atMillis })
    resourceField("previewLimit", RESOURCE_PREVIEW_LIMIT)
    resourceField("previewTruncated", snapshot.samples.size > RESOURCE_PREVIEW_LIMIT)
    resourceField("sampleMinimumIntervalMillis", 300_000)
    resourceField("sampleMinimumIntervalScope", "PER_PROCESS_FIRST_EXISTING_OPPORTUNITY_THEN_INTERVAL")
    resourceField("exitMemoryScope", "LAST_SYSTEM_MEMORY_SAMPLE_NOT_PRECISE_AT_DEATH")
    resourceField("correlationScope", "PID_AND_RETAINED_LIFETIME_COMPATIBILITY_NOT_CAUSE_OR_DELIVERY_PROOF")
    append("\"recentSamples\":[")
    snapshot.samples.takeLast(RESOURCE_PREVIEW_LIMIT).forEachIndexed { index, sample ->
        if (index > 0) append(',')
        appendResourceSample(sample)
    }
    append("],\"exitAnalysis\":[")
    snapshot.exits.forEachIndexed { index, exit ->
        if (index > 0) append(',')
        val analysis = analyzeResourceExit(snapshot, exit)
        append('{')
        resourceField("exitAtEpochMillis", exit.atMillis)
        resourceField("processId", exit.processId)
        resourceField("systemExitReason", exit.reason)
        resourceField("systemExitStatus", exit.status)
        resourceField("systemLastImportance", exit.importance)
        resourceField("systemLastRssKiB", exit.rssKiB)
        resourceField("systemLastPssKiB", exit.pssKiB)
        resourceField("category", when (exit.reason) {
            3 -> "LOW_MEMORY"; 4, 5, 6 -> "CRASH_OR_ANR"; 16 -> "PACKAGE_UPDATED"
            10, 11 -> "USER_ACTION_REPORTED"; 2 -> "UNATTRIBUTED_SIGNAL"; else -> "OTHER_OR_UNKNOWN"
        })
        resourceField("correlation", analysis.correlation.name)
        resourceField("nearestSampleAgeMillis", analysis.nearestSampleAgeMillis)
        resourceField("retainedSameProcessSamples", analysis.retainedSameProcessSamples)
        resourceField("retainedTrendElapsedMillis", analysis.retainedTrendElapsedMillis)
        resourceField("rssDeltaKiB", analysis.rssDeltaKiB)
        resourceField("heapDeltaBytes", analysis.heapDeltaBytes)
        resourceField("threadDelta", analysis.threadDelta)
        resourceField("cpuDeltaMillis", analysis.cpuDeltaMillis)
        resourceField("readerTimeoutDelta", analysis.readerTimeoutDelta)
        resourceField("readerRejectionDelta", analysis.readerRejectionDelta)
        resourceField("sampledSystemLowMemory", analysis.sampledSystemLowMemory)
        append("\"nearestSample\":")
        analysis.nearestSample?.let { s ->
            append('{')
            resourceField("processInstance", s.processInstance)
            resourceField("versionCode", s.versionCode)
            resourceField("atEpochMillis", s.atMillis)
            resourceField("elapsedMillis", s.elapsedMillis)
            resourceField("rssKiB", s.rssKiB)
            resourceField("heapUsedBytes", s.heapUsedBytes)
            resourceField("threads", s.threads)
            resourceField("systemAvailableBytes", s.systemAvailableBytes)
            resourceField("systemThresholdBytes", s.systemThresholdBytes)
            resourceField("systemLowMemory", s.systemLowMemory)
            resourceField("readerTimeouts", s.readerTimeouts)
            resourceField("readerRejections", s.readerRejections)
            resourceField("actualFcmObservationAgeMillis", actualFcmObservationAge(s))
            resourceField("fcmService", s.fcmService)
            resourceField("maintenance", s.maintenance)
            resourceField("recoveryReady", s.recoveryReady, false)
            append('}')
        } ?: append("null")
        append('}')
    }
    append("]}")
}

private fun StringBuilder.appendResourceSample(sample: ProtectionResourceSample) {
    append('{')
    resourceField("processInstance", sample.processInstance)
    resourceField("processId", sample.processId)
    resourceField("processStartedAtEpochMillis", sample.processStartedAtMillis)
    resourceField("processStartedElapsedMillis", sample.processStartedElapsedMillis)
    resourceField("versionCode", sample.versionCode)
    resourceField("atEpochMillis", sample.atMillis)
    resourceField("elapsedMillis", sample.elapsedMillis)
    resourceField("rssKiB", sample.rssKiB)
    resourceField("threads", sample.threads)
    resourceField("heapUsedBytes", sample.heapUsedBytes)
    resourceField("heapMaxBytes", sample.heapMaxBytes)
    resourceField("nativeAllocatedBytes", sample.nativeAllocatedBytes)
    resourceField("cpuTimeMillis", sample.cpuTimeMillis)
    resourceField("systemAvailableBytes", sample.systemAvailableBytes)
    resourceField("systemThresholdBytes", sample.systemThresholdBytes)
    resourceField("systemLowMemory", sample.systemLowMemory)
    resourceField("interactive", sample.interactive)
    resourceField("selectedCount", sample.selectedCount)
    resourceField("automaticBusy", sample.automaticBusy)
    resourceField("automaticLastCheckedAtEpochMillis", sample.automaticLastCheckedAtMillis)
    resourceField("pollIterations", sample.pollIterations)
    resourceField("readerActive", sample.readerActive)
    resourceField("readerPoolSize", sample.readerPoolSize)
    resourceField("readerTimeouts", sample.readerTimeouts)
    resourceField("readerRejections", sample.readerRejections)
    resourceField("fcmConfigured", sample.fcmConfigured)
    resourceField("maintenance", sample.maintenance)
    resourceField("recoveryReady", sample.recoveryReady)
    resourceField("actualFcmObservationAtEpochMillis", sample.fcmObservationAtMillis)
    resourceField("actualFcmObservationAgeMillis", actualFcmObservationAge(sample))
    resourceField("fcmService", sample.fcmService)
    resourceField("fcmCompleteness", sample.fcmCompleteness)
    resourceField("fcmSocket", sample.fcmSocket)
    resourceField("heartbeatConfigured", sample.heartbeatConfigured)
    resourceField("heartbeatActive", sample.heartbeatActive)
    resourceField("heartbeatPaused", sample.heartbeatPaused)
    resourceField("heartbeatStopPending", sample.heartbeatStopPending)
    resourceField("heartbeatResumeBlocked", sample.heartbeatResumeBlocked)
    resourceField("heartbeatRequiresConfirmation", sample.heartbeatRequiresConfirmation)
    resourceField("heartbeatLastStatus", sample.heartbeatLastStatus)
    resourceField("lastRetainedRecoveryAction", sample.recoveryAction)
    resourceField("lastRetainedRecoveryActionAtEpochMillis", sample.recoveryActionAtMillis, false)
    append('}')
}

private fun StringBuilder.resourceField(name: String, value: Any?, comma: Boolean = true) {
    append('"').append(name).append("\":")
    when (value) {
        null -> append("null")
        is String -> { require(value.matches(Regex("[a-zA-Z0-9_-]{1,128}"))); append('"').append(value).append('"') }
        is Number, is Boolean -> append(value)
        else -> error("Unsupported resource field")
    }
    if (comma) append(',')
}

internal fun StringBuilder.appendProtectionResourceMarkdown(snapshot: ProtectionResourceSnapshot) {
    append("\n## 后台资源与中断分析\n\n")
    append("范围：Guardian App进程历史，不是独立核心的当前资源或连续耗电证据；独立核心采集状态见对应章节。\n\n")
    append("可用性：").append(snapshot.availability.name).append("；保留采样：").append(snapshot.samples.size)
        .append("；淘汰采样：").append(snapshot.evictedSamples).append("；跳过：").append(snapshot.skipped)
        .append("；失败：").append(snapshot.failed).append("。\n\n")
    append("复用已有检测，每个进程首次机会采样后至少间隔五分钟；没有独立唤醒定时器。退出RSS/PSS是系统最后一次采样，非死亡瞬间值。")
    append("关联仅表示PID与保留寿命相容，不证明泄漏、OEM杀进程或通知送达；未采到压力不排除采样间的瞬时压力。\n\n")
    snapshot.samples.takeLast(RESOURCE_PREVIEW_LIMIT).forEach { s ->
        append("- 采样 ").append(s.atMillis).append(" / PID ").append(s.processId)
            .append(" / RSS KiB ").append(s.rssKiB ?: "未知").append(" / heap bytes ").append(s.heapUsedBytes ?: "未知")
            .append(" / threads ").append(s.threads ?: "未知").append(" / systemLowMemory ").append(s.systemLowMemory ?: "未知")
            .append(" / FCM读取年龄ms ").append(actualFcmObservationAge(s) ?: "未知")
            .append(" / maintenance ").append(s.maintenance ?: "未知").append(" / recoveryReady ").append(s.recoveryReady ?: "未知").append('\n')
    }
    if (snapshot.samples.size > RESOURCE_PREVIEW_LIMIT) append("\n仅展示最近16条原始快照；退出分析使用全部保留样本。\n")
    snapshot.exits.forEach { e ->
        val a = analyzeResourceExit(snapshot, e)
        append("- 退出 ").append(e.atMillis).append(" / reason ").append(e.reason).append(" / ").append(a.correlation.name)
            .append(" / 近邻年龄ms ").append(a.nearestSampleAgeMillis ?: "未知")
            .append(" / RSS变化KiB ").append(a.rssDeltaKiB ?: "未知")
            .append(" / heap变化bytes ").append(a.heapDeltaBytes ?: "未知")
            .append(" / 采样中出现系统压力 ").append(a.sampledSystemLowMemory ?: "未知").append('\n')
    }
}
